from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: attesa 1 occorrenza, trovate {count}")
    return text.replace(old, new, 1)


def replace_section(text: str, start: str, end: str, new: str, label: str) -> str:
    i = text.find(start)
    if i < 0:
        raise RuntimeError(f"{label}: marcatore iniziale non trovato")
    j = text.find(end, i)
    if j < 0:
        raise RuntimeError(f"{label}: marcatore finale non trovato")
    return text[:i] + new.rstrip() + "\n\n" + text[j:]


# -----------------------------------------------------------------------------
# Cloudflare Worker: identità canonica prima di qualunque discovery, cache v12.
# -----------------------------------------------------------------------------
worker_path = "cloudflare/music-intelligence/src/index.js"
worker = read(worker_path)

worker = replace_once(
    worker,
    "const CORS = {",
    "const CURRENT_RESOLVER_VERSION = 12;\n\nconst CORS = {",
    "worker resolver version",
)

worker = replace_once(
    worker,
    "          ai: Boolean(env.GEMINI_API_KEY),\n",
    "          ai: Boolean(env.GEMINI_API_KEY),\n          resolver: CURRENT_RESOLVER_VERSION,\n",
    "worker stato resolver",
)

resolve_metadata = r'''async function resolveMetadata(input, env, ctx) {
  const started = Date.now();
  const title = clean(input?.title);
  const artist = clean(input?.artist);
  const playbackId = clean(input?.playbackId);
  if (!title || !artist) throw new Error('Titolo e artista sono obbligatori.');
  const key = searchKey(title, artist);

  if (input?.useMemory !== false && env.DB) {
    const cached = await cachedResolution(env.DB, key, playbackId);
    if (cached) {
      ctx?.waitUntil(logRequest(env.DB, key, 'resolve', true, 1, Date.now() - started, env));
      return { stato: 'pronto', provenienza: 'memoria', metadata: cached };
    }
  }

  const prompt = `Sei il resolver musicale canonico centrale di MusicLab.\n\n` +
    `Devi identificare la SPECIFICA REGISTRAZIONE realmente riprodotta. La tua risposta è l'unica autorità editoriale dell'app. ` +
    `YouTube e YouTube Music forniscono soltanto il playback tecnico e NON decidono artista, album, anno o crediti.\n\n` +
    `Titolo osservato: ${title}\nArtista/canale osservato: ${artist}\n` +
    (clean(input?.albumHint) ? `Album osservato: ${clean(input.albumHint)}\n` : '') +
    `Playback ID tecnico: ${playbackId || 'non disponibile'}\n\n` +
    `ATTENZIONE: il campo artista/canale osservato può essere un uploader, un canale personale, un'etichetta o chi ha caricato il video. ` +
    `Non copiarlo automaticamente come artista. Analizza anche eventuali nomi di interpreti incorporati nel titolo del video. ` +
    `Il campo artist della risposta deve essere l'interprete REALE di questa registrazione, non l'autore del caricamento. ` +
    `Non trasformare automaticamente la registrazione nell'originale della composizione: se il playback è una cover o un live, ` +
    `restituisci l'artista della cover/live e classificala correttamente.\n\n` +
    `Restituisci titolo canonico della registrazione, artista reale, album reale se esiste, anno, lingua, categoria ` +
    `(originale|cover|live|remix|adattamento), crediti reali, confidence da 0 a 1 e il ruolo del nome osservato ` +
    `(performer|uploader|channel|label|unknown). Non inventare album o crediti.\n\n` +
    `JSON obbligatorio: {"title":"","artist":"","album":null,"year":null,"language":null,"category":null,` +
    `"confidence":0.0,"observedArtistRole":"performer|uploader|channel|label|unknown",` +
    `"credits":{"songwriters":[],"composers":[],"lyricists":[],"producers":[],"label":null}}`;

  let ai = await askGemini(prompt, env, 1800, false);
  let metadata = normalizeMetadata(ai, playbackId);

  if (!metadata || needsGroundedVerification(title, artist, ai, metadata, true)) {
    try {
      const verifiedAi = await askGemini(
        prompt + `\n\nVERIFICA APPROFONDITA: usa la ricerca per distinguere con certezza interprete musicale, uploader/canale e titolo reale. ` +
          `Se il titolo contiene un nome diverso dal canale osservato, verifica chi sta realmente eseguendo il brano.`,
        env,
        2200,
        true,
      );
      const verifiedMetadata = normalizeMetadata(verifiedAi, playbackId);
      if (verifiedMetadata && !needsGroundedVerification(title, artist, verifiedAi, verifiedMetadata, false)) {
        ai = verifiedAi;
        metadata = verifiedMetadata;
      }
    } catch (_) {
      // Il primo risultato resta utilizzabile soltanto se non presenta contraddizioni forti.
    }
  }

  if (!metadata || needsGroundedVerification(title, artist, ai, metadata, false)) {
    throw new Error('L’AI non ha restituito un’identità canonica sufficientemente affidabile.');
  }

  if (env.DB) {
    const workId = crypto.randomUUID();
    await env.DB.prepare(`
      INSERT INTO works(id, search_key, canonical_title, original_artist, original_year, original_language, credits_json, ai_model, resolver_version)
      VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
      ON CONFLICT(search_key) DO UPDATE SET
        canonical_title=excluded.canonical_title,
        original_artist=excluded.original_artist,
        original_year=COALESCE(excluded.original_year, works.original_year),
        original_language=COALESCE(excluded.original_language, works.original_language),
        credits_json=excluded.credits_json,
        ai_model=excluded.ai_model,
        resolver_version=excluded.resolver_version,
        updated_at=CURRENT_TIMESTAMP
    `).bind(
      workId, key, metadata.title, metadata.artist, metadata.year, metadata.language,
      JSON.stringify(metadata.credits || {}), env.GEMINI_MODEL || 'gemini-2.5-flash-lite', CURRENT_RESOLVER_VERSION
    ).run();

    const work = await env.DB.prepare(
      'SELECT id FROM works WHERE search_key=?1 AND resolver_version>=?2 LIMIT 1'
    ).bind(key, CURRENT_RESOLVER_VERSION).first();
    if (playbackId && work?.id) {
      await env.DB.prepare(`
        INSERT INTO playback_bindings(playback_id, work_id, source_kind, last_verified_at)
        VALUES(?1, ?2, 'youtube', CURRENT_TIMESTAMP)
        ON CONFLICT(playback_id) DO UPDATE SET work_id=excluded.work_id,last_verified_at=CURRENT_TIMESTAMP
      `).bind(playbackId, work.id).run();
    }
    ctx?.waitUntil(logRequest(env.DB, key, 'resolve', false, 1, Date.now() - started, env));
  }

  return { stato: 'pronto', provenienza: 'ai', metadata };
}'''
worker = replace_section(
    worker,
    "async function resolveMetadata(input, env, ctx) {",
    "async function cachedResolution(db, key, playbackId) {",
    resolve_metadata,
    "worker resolveMetadata",
)

cached_resolution = r'''async function cachedResolution(db, key, playbackId) {
  let row = null;
  if (playbackId) {
    row = await db.prepare(`
      SELECT w.*, NULL AS artist_browse_id, NULL AS album_browse_id
      FROM playback_bindings pb
      JOIN works w ON w.id=pb.work_id
      WHERE pb.playback_id=?1 AND COALESCE(w.resolver_version,0)>=?2
      LIMIT 1
    `).bind(playbackId, CURRENT_RESOLVER_VERSION).first();
  }
  if (!row) {
    row = await db.prepare(
      'SELECT * FROM works WHERE search_key=?1 AND COALESCE(resolver_version,0)>=?2 LIMIT 1'
    ).bind(key, CURRENT_RESOLVER_VERSION).first();
  }
  if (!row) return null;
  return {
    playbackId: playbackId || '',
    title: row.canonical_title,
    artist: row.original_artist,
    album: null,
    year: row.original_year,
    language: row.original_language,
    category: 'originale',
    credits: safeJson(row.credits_json) || {},
    artistBrowseId: row.artist_browse_id || null,
    albumBrowseId: row.album_browse_id || null,
    source: 'ai-cache-v12',
  };
}'''
worker = replace_section(
    worker,
    "async function cachedResolution(db, key, playbackId) {",
    "async function discover(input, env, phase) {",
    cached_resolution,
    "worker cachedResolution",
)

discover = r'''async function discover(input, env, phase) {
  const observedTitle = clean(input?.title);
  const observedArtist = clean(input?.artist);
  const mode = input?.mode === 'originals' ? 'originals' : 'cover';
  if (!observedTitle || !observedArtist) throw new Error('Titolo e artista sono obbligatori.');
  const existing = Array.isArray(input?.existing) ? input.existing.slice(0, 160) : [];
  const focus = clean(input?.focus);

  // Prima di chiedere cover/originali, ripuliamo SEMPRE l'identità della registrazione.
  // Così un nome di uploader/canale non può diventare l'artista di partenza della ricerca.
  let title = observedTitle;
  let artist = observedArtist;
  try {
    const canonicalSeed = await resolveMetadata({
      title: observedTitle,
      artist: observedArtist,
      albumHint: clean(input?.albumHint),
      playbackId: clean(input?.playbackId),
      useMemory: input?.useMemory !== false,
    }, env, null);
    if (canonicalSeed?.metadata?.title && canonicalSeed?.metadata?.artist) {
      title = clean(canonicalSeed.metadata.title);
      artist = clean(canonicalSeed.metadata.artist);
    }
  } catch (_) {
    // Se il resolver non è certo, la discovery può ancora provare con il prompt AI,
    // ma non eredita mai dati editoriali da YouTube/YTM.
  }

  if (phase === 'initial') {
    if (input?.useMemory !== false && env.DB) {
      const cached = await cachedDiscovery(env.DB, title, artist, mode === 'cover' ? 5 : 6);
      if (cached && (cached.original || cached.versions.length)) {
        return { stato: 'pronto', fase: 'initial', provenienza: 'memoria', seed: { title, artist }, ...cached };
      }
    }
    const prompt = mode === 'cover'
      ? initialCoverPrompt(title, artist)
      : initialOriginalsPrompt(title, artist);
    const ai = await askGemini(prompt, env, 2400, false);
    const payload = normalizeDiscovery(ai, mode === 'cover' ? 5 : 6);
    if (env.DB) await saveDiscovery(env.DB, title, artist, payload, env);
    return { stato: 'pronto', fase: 'initial', provenienza: 'ai', seed: { title, artist }, ...payload };
  }

  const prompt = mode === 'cover'
    ? expandedCoverPrompt(title, artist, existing, focus)
    : expandedOriginalsPrompt(title, artist, existing, focus);
  const ai = await askGemini(prompt, env, 4200, true);
  const max = Math.max(1, Math.min(Number(env.MAX_DISCOVERY_RESULTS || 150), 150));
  const payload = normalizeDiscovery(ai, max);
  if (env.DB) await saveDiscovery(env.DB, title, artist, payload, env);
  return { stato: 'pronto', fase: 'expand', provenienza: 'ai', seed: { title, artist }, ...payload };
}'''
worker = replace_section(
    worker,
    "async function discover(input, env, phase) {",
    "function initialCoverPrompt(title, artist) {",
    discover,
    "worker discover",
)

cached_discovery = r'''async function cachedDiscovery(db, requestTitle, requestArtist, limit) {
  const key = searchKey(requestTitle, requestArtist);
  const work = await db.prepare(
    'SELECT * FROM works WHERE search_key=?1 AND COALESCE(resolver_version,0)>=?2 LIMIT 1'
  ).bind(key, CURRENT_RESOLVER_VERSION).first();
  if (!work) return null;
  const rows = await db.prepare(`
    SELECT canonical_title, canonical_artist, category, language, year, album, credits_json
    FROM versions WHERE work_id=?1
    ORDER BY CASE WHEN year IS NULL THEN 1 ELSE 0 END, year ASC, canonical_artist ASC
    LIMIT ?2
  `).bind(work.id, limit).all();
  return {
    original: {
      title: work.canonical_title,
      artist: work.original_artist,
      year: work.original_year,
      language: work.original_language,
      album: null,
      credits: safeJson(work.credits_json) || {},
    },
    versions: (rows?.results || []).map(v => ({
      title: v.canonical_title,
      artist: v.canonical_artist,
      category: v.category,
      language: v.language,
      year: v.year,
      album: v.album,
      credits: safeJson(v.credits_json) || {},
    })),
  };
}'''
worker = replace_section(
    worker,
    "async function cachedDiscovery(db, requestTitle, requestArtist, limit) {",
    "async function saveDiscovery(db, requestTitle, requestArtist, payload, env) {",
    cached_discovery,
    "worker cachedDiscovery",
)

save_discovery = r'''async function saveDiscovery(db, requestTitle, requestArtist, payload, env) {
  const key = searchKey(requestTitle, requestArtist);
  if (payload?.original) {
    const workId = crypto.randomUUID();
    await db.prepare(`
      INSERT INTO works(id,search_key,canonical_title,original_artist,original_year,original_language,credits_json,ai_model,resolver_version)
      VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9)
      ON CONFLICT(search_key) DO UPDATE SET canonical_title=excluded.canonical_title,original_artist=excluded.original_artist,
        original_year=COALESCE(excluded.original_year,works.original_year),original_language=COALESCE(excluded.original_language,works.original_language),
        credits_json=excluded.credits_json,ai_model=excluded.ai_model,resolver_version=excluded.resolver_version,updated_at=CURRENT_TIMESTAMP
    `).bind(workId,key,payload.original.title,payload.original.artist,payload.original.year,payload.original.language,
      JSON.stringify(payload.original.credits||{}),env.GEMINI_MODEL||'gemini-2.5-flash-lite',CURRENT_RESOLVER_VERSION).run();
  }
  const work = await db.prepare(
    'SELECT id FROM works WHERE search_key=?1 AND COALESCE(resolver_version,0)>=?2 LIMIT 1'
  ).bind(key, CURRENT_RESOLVER_VERSION).first();
  if (!work?.id || !payload?.versions?.length) return;
  const statements = payload.versions.map(v => db.prepare(`
    INSERT INTO versions(id,work_id,version_key,canonical_title,canonical_artist,category,language,year,album,credits_json,ai_model)
    VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)
    ON CONFLICT(work_id,version_key) DO UPDATE SET year=COALESCE(excluded.year,versions.year),album=COALESCE(excluded.album,versions.album),
      language=COALESCE(excluded.language,versions.language),credits_json=excluded.credits_json,ai_model=excluded.ai_model,updated_at=CURRENT_TIMESTAMP
  `).bind(crypto.randomUUID(),work.id,versionKey(v.title,v.artist,v.category,v.language),v.title,v.artist,v.category,v.language,v.year,v.album,
    JSON.stringify(v.credits||{}),env.GEMINI_MODEL||'gemini-2.5-flash-lite'));
  await db.batch(statements);
}'''
worker = replace_section(
    worker,
    "async function saveDiscovery(db, requestTitle, requestArtist, payload, env) {",
    "async function savePlaybackBinding(input, env) {",
    save_discovery,
    "worker saveDiscovery",
)

helpers = r'''function needsGroundedVerification(observedTitle, observedArtist, ai, metadata, requireConfidence) {
  if (!metadata) return true;
  const confidence = Number(ai?.confidence);
  if (Number.isFinite(confidence) && confidence < 0.72) return true;
  if (requireConfidence && !Number.isFinite(confidence)) return true;

  const observed = canonical(observedArtist);
  const resolved = canonical(metadata.artist);
  const role = canonical(ai?.observedArtistRole || '');
  const uploaderRole = role.includes('uploader') || role.includes('channel') || role.includes('label');

  if (uploaderRole && observed && resolved === observed) return true;
  if (observed && resolved === observed && titleSuggestsDifferentArtist(observedTitle, observedArtist)) return true;
  return false;
}

function titleSuggestsDifferentArtist(title, observedArtist) {
  const observed = canonical(observedArtist);
  const parts = String(title || '')
    .split(/\s[-–—|]\s|\s*:\s*/)
    .map(canonical)
    .filter(Boolean);
  if (parts.length < 2 || !observed) return false;
  if (parts.some(p => p === observed || p.includes(observed) || observed.includes(p))) return false;
  return parts.some(p => {
    const words = p.split(' ').filter(Boolean);
    return words.length >= 2 && words.length <= 6;
  });
}
'''
worker = replace_once(
    worker,
    "function versionKey(title, artist, category, language) {",
    helpers + "\nfunction versionKey(title, artist, category, language) {",
    "worker verification helpers",
)

write(worker_path, worker)

# Aggiorna la versione del motore: serve anche come segnale diagnostico nello /stato.
wrangler_path = "cloudflare/music-intelligence/wrangler.jsonc"
wrangler = read(wrangler_path)
wrangler = replace_once(
    wrangler,
    '"ENGINE_VERSION": "1.0.0"',
    '"ENGINE_VERSION": "1.1.0"',
    "wrangler engine version",
)
write(wrangler_path, wrangler)


# -----------------------------------------------------------------------------
# Android PlayerMenu: tutte le azioni editoriali aspettano l'identità AI.
# -----------------------------------------------------------------------------
player_path = "app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt"
player = read(player_path)

player = replace_once(
    player,
    '''    LaunchedEffect(canonicalMetadata) {
        canonicalMetadata?.let { creditsMetadata = it }
    }

    val download by LocalDownloadUtil.current''',
    '''    LaunchedEffect(canonicalMetadata) {
        canonicalMetadata?.let { creditsMetadata = it }
    }

    val canonicalActionMetadata = canonicalMetadata ?: creditsMetadata

    suspend fun resolveCanonicalForAction(): CanonicalMusicMetadata? {
        canonicalActionMetadata?.let { return it }
        MusicIntelligenceClient.cached(mediaMetadata.id)?.let { cached ->
            creditsMetadata = cached
            return cached
        }
        val resolved = kotlinx.coroutines.withContext(Dispatchers.IO) {
            MusicIntelligenceClient.resolve(
                context = context,
                playbackId = mediaMetadata.id,
                title = mediaMetadata.title,
                artist = mediaMetadata.artists.firstOrNull()?.name.orEmpty(),
                album = mediaMetadata.album?.title,
            )
        }
        if (resolved != null) creditsMetadata = resolved
        return resolved
    }

    val download by LocalDownloadUtil.current''',
    "PlayerMenu canonical helper",
)

player = replace_section(
    player,
    "    val resolvedNavigationSong by produceState<com.metrolist.innertube.models.SongItem?>(",
    "val navigationArtists =",
    '''    val resolvedNavigationSong by produceState<com.metrolist.innertube.models.SongItem?>(
        initialValue = null,
        mediaMetadata.id,
        musicIntelligenceSettings.enabled,
        musicIntelligenceSettings.artistResolver,
        musicIntelligenceSettings.albumResolver,
    ) {
        value = kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (
                musicIntelligenceSettings.enabled &&
                (musicIntelligenceSettings.artistResolver || musicIntelligenceSettings.albumResolver)
            ) {
                // Con il resolver AI attivo YouTube non viene mai interrogato per decidere
                // artista/album. I browse ID tecnici arrivano solo dopo l'identità canonica.
                null
            } else {
                YouTube.queue(listOf(mediaMetadata.id)).getOrNull()?.firstOrNull()
            }
        }
    }''',
    "PlayerMenu raw navigation metadata",
)

player = replace_section(
    player,
    "    if (showCoverSearchDialog) {",
    "    val listenTogetherManager = LocalListenTogetherManager.current",
    '''    val coverSearchSeed = canonicalActionMetadata
    if (showCoverSearchDialog && coverSearchSeed != null) {
        CoverSearchDialog(
            title = coverSearchSeed.title,
            originalArtist = coverSearchSeed.artist,
            durationSec = mediaMetadata.duration,
            currentYouTubeId = mediaMetadata.id,
            onSelect = { song ->
                playerConnection.playNext(song.toMediaItem())
                playerConnection.seekToNext()
                playerBottomSheetState.collapseSoft()
                onDismiss()
            },
            onDismiss = { showCoverSearchDialog = false },
        )
    }''',
    "PlayerMenu Cover canonical seed",
)

player = replace_once(
    player,
    '''                                onClick = {
                                    val opened = com.metrolist.music.ui.component.OriginalVersionNavigationBridge.open(
                                        com.metrolist.music.ui.component.OriginalVersionRequest(
                                            title = mediaMetadata.title,
                                            artist = mediaMetadata.artists.firstOrNull()?.name.orEmpty(),
                                            durationSec = mediaMetadata.duration,
                                            currentYouTubeId = mediaMetadata.id,
                                        ),
                                    )
                                    if (opened) {
                                        playerBottomSheetState.collapseSoft()
                                        onDismiss()
                                    }
                                },''',
    '''                                onClick = {
                                    coroutineScope.launch {
                                        val seed = resolveCanonicalForAction()
                                        if (seed == null) {
                                            Toast.makeText(
                                                context,
                                                "Identificazione AI non disponibile. Riprova tra poco.",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            return@launch
                                        }
                                        val opened = com.metrolist.music.ui.component.OriginalVersionNavigationBridge.open(
                                            com.metrolist.music.ui.component.OriginalVersionRequest(
                                                title = seed.title,
                                                artist = seed.artist,
                                                durationSec = mediaMetadata.duration,
                                                currentYouTubeId = mediaMetadata.id,
                                            ),
                                        )
                                        if (opened) {
                                            playerBottomSheetState.collapseSoft()
                                            onDismiss()
                                        }
                                    }
                                },''',
    "PlayerMenu Originali canonical seed",
)

player = replace_once(
    player,
    '''                                onClick = {
                                    playerBottomSheetState.collapseSoft()
                                    showCoverSearchDialog = true
                                },''',
    '''                                onClick = {
                                    coroutineScope.launch {
                                        val seed = resolveCanonicalForAction()
                                        if (seed == null) {
                                            Toast.makeText(
                                                context,
                                                "Identificazione AI non disponibile. Riprova tra poco.",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            return@launch
                                        }
                                        creditsMetadata = seed
                                        playerBottomSheetState.collapseSoft()
                                        showCoverSearchDialog = true
                                    }
                                },''',
    "PlayerMenu Cover action",
)

player = replace_section(
    player,
    '''                        // Don't show "View Artist" for podcasts - only show "View Podcast"''',
    "                        // Add to Library option",
    '''                        // Don't show "View Artist" for podcasts - only show "View Podcast"
                        if (artists.isNotEmpty() && !isPodcast) {
                            val useCanonicalArtist =
                                musicIntelligenceSettings.enabled && musicIntelligenceSettings.artistResolver
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.view_artist)) },
                                    description = {
                                        Text(
                                            text = if (useCanonicalArtist) {
                                                canonicalActionMetadata?.artist ?: "Identificazione AI in corso…"
                                            } else {
                                                mediaMetadata.artists.joinToString { it.name }
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.artist),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    onClick = {
                                        if (!useCanonicalArtist) {
                                            if (mediaMetadata.artists.size == 1) {
                                                navController.navigate("artist/${mediaMetadata.artists[0].id}")
                                                playerBottomSheetState.collapseSoft()
                                                onDismiss()
                                            } else {
                                                showSelectArtistDialog = true
                                            }
                                        } else {
                                            coroutineScope.launch {
                                                val resolved = resolveCanonicalForAction()
                                                val targetId = resolved?.artistBrowseId
                                                if (!targetId.isNullOrBlank()) {
                                                    navController.navigate("artist/$targetId")
                                                    playerBottomSheetState.collapseSoft()
                                                    onDismiss()
                                                } else {
                                                    Toast.makeText(
                                                        context,
                                                        "Pagina dell'artista non disponibile.",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        }
                                    },
                                ),
                            )
                        }
                        if (mediaMetadata.album != null) {
                            val useCanonicalAlbum =
                                !isPodcast && musicIntelligenceSettings.enabled && musicIntelligenceSettings.albumResolver
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(if (isPodcast) R.string.view_podcast else R.string.view_album)) },
                                    description = {
                                        Text(
                                            text = if (useCanonicalAlbum) {
                                                canonicalActionMetadata?.let { it.album ?: "Album non indicato dall'AI" }
                                                    ?: "Identificazione AI in corso…"
                                            } else {
                                                mediaMetadata.album.title
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            painter = painterResource(if (isPodcast) R.drawable.mic else R.drawable.album),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    onClick = {
                                        if (isPodcast) {
                                            navController.navigate("online_podcast/${mediaMetadata.album.id}")
                                            playerBottomSheetState.collapseSoft()
                                            onDismiss()
                                        } else if (useCanonicalAlbum) {
                                            coroutineScope.launch {
                                                val resolved = resolveCanonicalForAction()
                                                val targetId = resolved?.albumBrowseId
                                                if (!targetId.isNullOrBlank()) {
                                                    navController.navigate("album/$targetId")
                                                    playerBottomSheetState.collapseSoft()
                                                    onDismiss()
                                                } else {
                                                    Toast.makeText(context, "Album non disponibile.", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } else {
                                            navController.navigate("album/${mediaMetadata.album.id}")
                                            playerBottomSheetState.collapseSoft()
                                            onDismiss()
                                        }
                                    },
                                ),
                            )
                        }''',
    "PlayerMenu Mostra artista/album",
)

player = replace_once(
    player,
    '''                                        text = canonicalMetadata?.artist
                                            ?: mediaMetadata.artists.joinToString { it.name },''',
    '''                                        text = if (
                                            musicIntelligenceSettings.enabled && musicIntelligenceSettings.artistResolver
                                        ) {
                                            canonicalActionMetadata?.artist ?: "Identificazione AI in corso…"
                                        } else {
                                            mediaMetadata.artists.joinToString { it.name }
                                        },''',
    "PlayerMenu Vai artista label",
)

player = replace_once(
    player,
    '''                                    } else {
                                        Toast.makeText(context, "Sto identificando la pagina corretta dell'artista…", Toast.LENGTH_SHORT).show()
                                        coroutineScope.launch {
                                            val resolved = MusicIntelligenceClient.resolve(
                                                context = context,
                                                playbackId = mediaMetadata.id,
                                                title = mediaMetadata.title,
                                                artist = mediaMetadata.artists.firstOrNull()?.name.orEmpty(),
                                                album = mediaMetadata.album?.title,
                                            )
                                            creditsMetadata = resolved ?: creditsMetadata
                                            val targetId = resolved?.artistBrowseId
                                                ?: navigationArtists.singleOrNull()?.id
                                            if (!targetId.isNullOrBlank()) {
                                                playerBottomSheetState.collapse(tween(durationMillis = 120))
                                                navController.navigate("artist/$targetId")
                                                onDismiss()
                                            } else {
                                                Toast.makeText(context, "Pagina dell'artista non disponibile.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }''',
    '''                                    } else {
                                        Toast.makeText(context, "Sto identificando la pagina corretta dell'artista…", Toast.LENGTH_SHORT).show()
                                        coroutineScope.launch {
                                            val resolved = resolveCanonicalForAction()
                                            val targetId = resolved?.artistBrowseId
                                            if (!targetId.isNullOrBlank()) {
                                                playerBottomSheetState.collapse(tween(durationMillis = 120))
                                                navController.navigate("artist/$targetId")
                                                onDismiss()
                                            } else {
                                                Toast.makeText(context, "Pagina dell'artista non disponibile.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }''',
    "PlayerMenu Vai artista no raw fallback",
)

player = replace_once(
    player,
    '''                                    (canonicalMetadata?.album ?: navigationAlbumTitle)?.let { title ->
                                        Text(
                                            text = title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }''',
    '''                                    val useCanonicalLabel =
                                        !navigationAlbumIsPodcast &&
                                            musicIntelligenceSettings.enabled &&
                                            musicIntelligenceSettings.albumResolver
                                    val title = if (useCanonicalLabel) {
                                        canonicalActionMetadata?.let { it.album ?: "Album non indicato dall'AI" }
                                            ?: "Identificazione AI in corso…"
                                    } else {
                                        navigationAlbumTitle
                                    }
                                    title?.let {
                                        Text(
                                            text = it,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }''',
    "PlayerMenu Vai album label",
)

player = replace_once(
    player,
    '''                                    } else {
                                        Toast.makeText(context, "Sto identificando l'album corretto…", Toast.LENGTH_SHORT).show()
                                        coroutineScope.launch {
                                            val resolved = MusicIntelligenceClient.resolve(
                                                context = context,
                                                playbackId = mediaMetadata.id,
                                                title = mediaMetadata.title,
                                                artist = mediaMetadata.artists.firstOrNull()?.name.orEmpty(),
                                                album = mediaMetadata.album?.title,
                                            )
                                            creditsMetadata = resolved ?: creditsMetadata
                                            val targetId = resolved?.albumBrowseId ?: navigationAlbumId
                                            if (!targetId.isNullOrBlank()) {
                                                playerBottomSheetState.collapse(tween(durationMillis = 120))
                                                navController.navigate("album/$targetId")
                                                onDismiss()
                                            } else {
                                                Toast.makeText(context, "Album non disponibile.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }''',
    '''                                    } else {
                                        Toast.makeText(context, "Sto identificando l'album corretto…", Toast.LENGTH_SHORT).show()
                                        coroutineScope.launch {
                                            val resolved = resolveCanonicalForAction()
                                            val targetId = resolved?.albumBrowseId
                                            if (!targetId.isNullOrBlank()) {
                                                playerBottomSheetState.collapse(tween(durationMillis = 120))
                                                navController.navigate("album/$targetId")
                                                onDismiss()
                                            } else {
                                                Toast.makeText(context, "Album non disponibile.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }''',
    "PlayerMenu Vai album no raw fallback",
)

write(player_path, player)


# -----------------------------------------------------------------------------
# SongMenu: quando il resolver è attivo, mai mostrare il nome uploader come fallback.
# -----------------------------------------------------------------------------
song_path = "app/src/main/kotlin/com/metrolist/music/ui/menu/SongMenu.kt"
song = read(song_path)

song = replace_once(
    song,
    '''                                            text = canonicalArtistName
                                                ?: song.artists.joinToString { it.name },''',
    '''                                            text = if (useCanonicalArtist) {
                                                canonicalArtistName ?: "Identificazione AI in corso…"
                                            } else {
                                                song.artists.joinToString { it.name }
                                            },''',
    "SongMenu artist label",
)

song = replace_once(
    song,
    '''                                        (canonicalAlbumName ?: song.song.albumName)?.let {
                                            Text(text = it)
                                        }''',
    '''                                        val albumLabel = if (useCanonicalAlbum) {
                                            canonicalMetadata?.let { it.album ?: "Album non indicato dall'AI" }
                                                ?: "Identificazione AI in corso…"
                                        } else {
                                            canonicalAlbumName ?: song.song.albumName
                                        }
                                        albumLabel?.let { Text(text = it) }''',
    "SongMenu album label",
)

write(song_path, song)

print("LAB12 patch applicata con successo")
