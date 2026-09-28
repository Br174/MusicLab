const JSON_HEADERS = {
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
};

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET,POST,OPTIONS',
  'access-control-allow-headers': 'content-type,x-musiclab-client',
};

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    const url = new URL(request.url);
    try {
      if (request.method === 'GET' && url.pathname === '/stato') {
        return json({
          stato: 'ok',
          motore: 'MusicLab Music Intelligence',
          versione: env.ENGINE_VERSION || '1.0.0',
          memoria: Boolean(env.DB),
          ai: Boolean(env.GEMINI_API_KEY),
        });
      }
      if (request.method === 'POST' && url.pathname === '/api/v1/resolve') {
        return json(await resolveMetadata(await request.json(), env, ctx));
      }
      if (request.method === 'POST' && url.pathname === '/api/v1/discover/initial') {
        return json(await discover(await request.json(), env, 'initial'));
      }
      if (request.method === 'POST' && url.pathname === '/api/v1/discover/expand') {
        return json(await discover(await request.json(), env, 'expand'));
      }
      if (request.method === 'POST' && url.pathname === '/api/v1/playback-binding') {
        return json(await savePlaybackBinding(await request.json(), env));
      }
      if (request.method === 'POST' && url.pathname === '/api/v1/technical-destination') {
        return json(await saveTechnicalDestination(await request.json(), env));
      }
      return json({ errore: 'Rotta non trovata' }, 404);
    } catch (error) {
      return json({ errore: 'Errore del motore', dettaglio: String(error?.message || error) }, 500);
    }
  },
};

async function resolveMetadata(input, env, ctx) {
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

  const prompt = `Sei il cervello musicale canonico di MusicLab.\n\n` +
    `Devi identificare la registrazione musicale reale descritta qui sotto. ` +
    `La tua risposta è l'autorità editoriale dell'app. YouTube/YouTube Music non decidono mai artista, album o crediti.\n\n` +
    `Titolo osservato: ${title}\nArtista osservato: ${artist}\n` +
    (clean(input?.albumHint) ? `Album osservato: ${clean(input.albumHint)}\n` : '') +
    `Playback ID tecnico: ${playbackId || 'non disponibile'}\n\n` +
    `Restituisci il titolo canonico della specifica registrazione, l'artista reale, l'album reale se esiste, ` +
    `anno, lingua, categoria (originale|cover|live|remix|adattamento), e i crediti reali. ` +
    `Non trattare il nome di un uploader come artista. Non inventare un album se la registrazione è un live/video non appartenente a un album. ` +
    `Se un credito non è noto usa null o [].\n\n` +
    `JSON obbligatorio: {"title":"","artist":"","album":null,"year":null,"language":null,"category":null,` +
    `"credits":{"songwriters":[],"composers":[],"lyricists":[],"producers":[],"label":null}}`;

  const ai = await askGemini(prompt, env, 1800, false);
  const metadata = normalizeMetadata(ai, playbackId);
  if (!metadata) throw new Error('L’AI non ha restituito metadati validi.');

  if (env.DB) {
    const workId = crypto.randomUUID();
    await env.DB.prepare(`
      INSERT INTO works(id, search_key, canonical_title, original_artist, original_year, original_language, credits_json, ai_model)
      VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
      ON CONFLICT(search_key) DO UPDATE SET
        canonical_title=excluded.canonical_title,
        original_artist=excluded.original_artist,
        original_year=COALESCE(excluded.original_year, works.original_year),
        original_language=COALESCE(excluded.original_language, works.original_language),
        credits_json=excluded.credits_json,
        ai_model=excluded.ai_model,
        updated_at=CURRENT_TIMESTAMP
    `).bind(
      workId, key, metadata.title, metadata.artist, metadata.year, metadata.language,
      JSON.stringify(metadata.credits || {}), env.GEMINI_MODEL || 'gemini-2.5-flash-lite'
    ).run();

    const work = await env.DB.prepare('SELECT id FROM works WHERE search_key=?1 LIMIT 1').bind(key).first();
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
}

async function cachedResolution(db, key, playbackId) {
  let row = null;
  if (playbackId) {
    row = await db.prepare(`
      SELECT w.*, a.youtube_browse_id AS artist_browse_id, al.youtube_browse_id AS album_browse_id
      FROM playback_bindings pb
      JOIN works w ON w.id=pb.work_id
      LEFT JOIN artists a ON a.artist_key=?2
      LEFT JOIN albums al ON al.album_key=?3
      WHERE pb.playback_id=?1 LIMIT 1
    `).bind(playbackId, '', '').first();
  }
  if (!row) row = await db.prepare('SELECT * FROM works WHERE search_key=?1 LIMIT 1').bind(key).first();
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
    source: 'ai-cache',
  };
}

async function discover(input, env, phase) {
  const title = clean(input?.title);
  const artist = clean(input?.artist);
  const mode = input?.mode === 'originals' ? 'originals' : 'cover';
  if (!title || !artist) throw new Error('Titolo e artista sono obbligatori.');
  const existing = Array.isArray(input?.existing) ? input.existing.slice(0, 160) : [];
  const focus = clean(input?.focus);

  if (phase === 'initial') {
    if (input?.useMemory !== false && env.DB) {
      const cached = await cachedDiscovery(env.DB, title, artist, mode === 'cover' ? 5 : 6);
      if (cached && (cached.original || cached.versions.length)) {
        return { stato: 'pronto', fase: 'initial', provenienza: 'memoria', ...cached };
      }
    }
    const prompt = mode === 'cover'
      ? initialCoverPrompt(title, artist)
      : initialOriginalsPrompt(title, artist);
    const ai = await askGemini(prompt, env, 2400, false);
    const payload = normalizeDiscovery(ai, mode === 'cover' ? 5 : 6);
    if (env.DB) await saveDiscovery(env.DB, title, artist, payload, env);
    return { stato: 'pronto', fase: 'initial', provenienza: 'ai', ...payload };
  }

  const prompt = mode === 'cover'
    ? expandedCoverPrompt(title, artist, existing, focus)
    : expandedOriginalsPrompt(title, artist, existing, focus);
  const ai = await askGemini(prompt, env, 4200, true);
  const max = Math.max(1, Math.min(Number(env.MAX_DISCOVERY_RESULTS || 150), 150));
  const payload = normalizeDiscovery(ai, max);
  if (env.DB) await saveDiscovery(env.DB, title, artist, payload, env);
  return { stato: 'pronto', fase: 'expand', provenienza: 'ai', ...payload };
}

function initialCoverPrompt(title, artist) {
  return `Sei il motore AI-first di MusicLab. L'AI è l'unica autorità editoriale. ` +
    `Per ${title} — ${artist}, restituisci SUBITO fino a 5 cover in studio reali della stessa composizione, ` +
    `eseguite da artisti diversi dall'originale. Niente live, niente remix, niente karaoke. ` +
    `Preferisci versioni sicure e distribuite nel tempo. Per la prima risposta privilegia velocità. ` + discoveryJsonInstruction();
}

function initialOriginalsPrompt(title, artist) {
  return `Sei il motore AI-first di MusicLab. Identifica con precisione l'originale canonico della composizione ` +
    `${title} — ${artist} e fino a 6 versioni pertinenti dell'interprete originale. ` +
    `L'AI decide tutti i metadati. Privilegia velocità. ` + discoveryJsonInstruction();
}

function expandedCoverPrompt(title, artist, existing, focus) {
  const known = existing.map(v => `${clean(v.artist)} — ${clean(v.title)} [${clean(v.category)}]`).filter(Boolean).join('\n');
  const requested = focus || 'cerca nuove versioni mancanti';
  return `Sei il ricercatore discografico AI centrale di MusicLab. Devi trovare versioni REALI della stessa composizione ${title} — ${artist}.\n` +
    `FOCUS: ${requested}.\n` +
    `Categorie esclusive: cover=incisione studio nella lingua originale; live=performance non studio; remix=remix/rework; ` +
    `straniera=incisione studio in lingua diversa dall'originale, compresi adattamenti con titolo tradotto o completamente diverso. ` +
    `Precedenza se una versione ha più caratteristiche: remix > live > straniera > cover. ` +
    `Cerca per decenni, lingue e aree geografiche; includi versioni poco note ma documentate. Non inventare. ` +
    `Versioni già note da non ripetere:\n${known || '(nessuna)'}.\n` + discoveryJsonInstruction();
}

function expandedOriginalsPrompt(title, artist, existing, focus) {
  const known = existing.map(v => `${clean(v.artist)} — ${clean(v.title)} [${clean(v.category)}]`).filter(Boolean).join('\n');
  return `Sei il motore Originali AI-first di MusicLab. Per la composizione ${title} — ${artist}, ` +
    `cerca altre registrazioni/versioni autentiche pertinenti dell'interprete originale. FOCUS: ${focus || 'versioni mancanti per epoca e tipo'}. ` +
    `Non ripetere:\n${known || '(nessuna)'}. ` + discoveryJsonInstruction();
}

function discoveryJsonInstruction() {
  return `Rispondi SOLO JSON: {"original":{"title":"","artist":"","year":null,"language":null,"album":null,` +
    `"credits":{"songwriters":[],"composers":[],"lyricists":[],"producers":[],"label":null}},` +
    `"versions":[{"title":"","artist":"","category":"cover|live|remix|straniera|originale",` +
    `"language":null,"year":null,"album":null,"credits":{"songwriters":[],"composers":[],"lyricists":[],"producers":[],"label":null}}]}.`;
}

async function askGemini(prompt, env, maxOutputTokens, useSearch) {
  if (!env.GEMINI_API_KEY) throw new Error('GEMINI_API_KEY non configurata nel Worker.');
  const model = env.GEMINI_MODEL || 'gemini-2.5-flash-lite';
  const body = {
    contents: [{ role: 'user', parts: [{ text: prompt }] }],
    generationConfig: { temperature: 0.12, maxOutputTokens, responseMimeType: 'application/json' },
  };
  if (useSearch) body.tools = [{ google_search: {} }];

  let response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-goog-api-key': env.GEMINI_API_KEY },
    body: JSON.stringify(body),
  });
  if (!response.ok && useSearch) {
    delete body.tools;
    response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-goog-api-key': env.GEMINI_API_KEY },
      body: JSON.stringify(body),
    });
  }
  if (!response.ok) throw new Error(`Gemini HTTP ${response.status}`);
  const root = await response.json();
  const text = root?.candidates?.[0]?.content?.parts?.map(p => p.text || '').join('\n').trim();
  if (!text) throw new Error('Risposta AI vuota.');
  return parseJsonObject(text);
}

function normalizeMetadata(ai, playbackId) {
  if (!ai || !clean(ai.title) || !clean(ai.artist)) return null;
  return {
    playbackId: playbackId || '',
    title: clean(ai.title),
    artist: clean(ai.artist),
    album: nullable(ai.album),
    year: validYear(ai.year),
    language: nullable(ai.language),
    category: nullable(ai.category),
    credits: normalizeCredits(ai.credits),
    artistBrowseId: null,
    albumBrowseId: null,
    source: 'ai',
  };
}

function normalizeDiscovery(ai, limit) {
  const original = ai?.original && clean(ai.original.title) && clean(ai.original.artist)
    ? { ...normalizeMetadata(ai.original, ''), playbackId: undefined }
    : null;
  const seen = new Set();
  const versions = [];
  for (const raw of Array.isArray(ai?.versions) ? ai.versions : []) {
    const title = clean(raw?.title);
    const artist = clean(raw?.artist);
    if (!title || !artist) continue;
    const category = normalizeCategory(raw?.category);
    const key = versionKey(title, artist, category, raw?.language);
    if (seen.has(key)) continue;
    seen.add(key);
    versions.push({
      title,
      artist,
      category,
      language: nullable(raw?.language),
      year: validYear(raw?.year),
      album: nullable(raw?.album),
      credits: normalizeCredits(raw?.credits),
    });
    if (versions.length >= limit) break;
  }
  return { original, versions };
}

async function cachedDiscovery(db, requestTitle, requestArtist, limit) {
  const key = searchKey(requestTitle, requestArtist);
  const work = await db.prepare('SELECT * FROM works WHERE search_key=?1 LIMIT 1').bind(key).first();
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
}

async function saveDiscovery(db, requestTitle, requestArtist, payload, env) {
  const key = searchKey(requestTitle, requestArtist);
  if (payload?.original) {
    const workId = crypto.randomUUID();
    await db.prepare(`
      INSERT INTO works(id,search_key,canonical_title,original_artist,original_year,original_language,credits_json,ai_model)
      VALUES(?1,?2,?3,?4,?5,?6,?7,?8)
      ON CONFLICT(search_key) DO UPDATE SET canonical_title=excluded.canonical_title,original_artist=excluded.original_artist,
        original_year=COALESCE(excluded.original_year,works.original_year),original_language=COALESCE(excluded.original_language,works.original_language),
        credits_json=excluded.credits_json,ai_model=excluded.ai_model,updated_at=CURRENT_TIMESTAMP
    `).bind(workId,key,payload.original.title,payload.original.artist,payload.original.year,payload.original.language,
      JSON.stringify(payload.original.credits||{}),env.GEMINI_MODEL||'gemini-2.5-flash-lite').run();
  }
  const work = await db.prepare('SELECT id FROM works WHERE search_key=?1 LIMIT 1').bind(key).first();
  if (!work?.id || !payload?.versions?.length) return;
  const statements = payload.versions.map(v => db.prepare(`
    INSERT INTO versions(id,work_id,version_key,canonical_title,canonical_artist,category,language,year,album,credits_json,ai_model)
    VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)
    ON CONFLICT(work_id,version_key) DO UPDATE SET year=COALESCE(excluded.year,versions.year),album=COALESCE(excluded.album,versions.album),
      language=COALESCE(excluded.language,versions.language),credits_json=excluded.credits_json,ai_model=excluded.ai_model,updated_at=CURRENT_TIMESTAMP
  `).bind(crypto.randomUUID(),work.id,versionKey(v.title,v.artist,v.category,v.language),v.title,v.artist,v.category,v.language,v.year,v.album,
    JSON.stringify(v.credits||{}),env.GEMINI_MODEL||'gemini-2.5-flash-lite'));
  await db.batch(statements);
}

async function savePlaybackBinding(input, env) {
  if (!env.DB) return { stato: 'ignorato', motivo: 'D1 non configurato' };
  const playbackId = clean(input?.playbackId);
  const workKey = clean(input?.workKey);
  const versionId = clean(input?.versionId) || null;
  if (!playbackId) throw new Error('playbackId obbligatorio');
  let workId = null;
  if (workKey) workId = (await env.DB.prepare('SELECT id FROM works WHERE search_key=?1 LIMIT 1').bind(workKey).first())?.id || null;
  await env.DB.prepare(`INSERT INTO playback_bindings(playback_id,version_id,work_id,source_kind,last_verified_at)
    VALUES(?1,?2,?3,?4,CURRENT_TIMESTAMP)
    ON CONFLICT(playback_id) DO UPDATE SET version_id=COALESCE(excluded.version_id,playback_bindings.version_id),
      work_id=COALESCE(excluded.work_id,playback_bindings.work_id),source_kind=excluded.source_kind,last_verified_at=CURRENT_TIMESTAMP`)
    .bind(playbackId,versionId,workId,clean(input?.sourceKind)||'youtube').run();
  return { stato: 'salvato' };
}

async function saveTechnicalDestination(input, env) {
  if (!env.DB) return { stato: 'ignorato', motivo: 'D1 non configurato' };
  const type = input?.type === 'album' ? 'album' : 'artist';
  const browseId = clean(input?.browseId);
  if (!browseId) throw new Error('browseId obbligatorio');
  if (type === 'artist') {
    const name = clean(input?.artist);
    if (!name) throw new Error('artist obbligatorio');
    await env.DB.prepare(`INSERT INTO artists(artist_key,canonical_name,youtube_browse_id,last_verified_at)
      VALUES(?1,?2,?3,CURRENT_TIMESTAMP)
      ON CONFLICT(artist_key) DO UPDATE SET canonical_name=excluded.canonical_name,youtube_browse_id=excluded.youtube_browse_id,last_verified_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP`)
      .bind(canonical(name),name,browseId).run();
  } else {
    const album = clean(input?.album);
    const artist = clean(input?.artist);
    if (!album || !artist) throw new Error('album e artist obbligatori');
    await env.DB.prepare(`INSERT INTO albums(album_key,canonical_title,canonical_artist,youtube_browse_id,year)
      VALUES(?1,?2,?3,?4,?5)
      ON CONFLICT(album_key) DO UPDATE SET youtube_browse_id=excluded.youtube_browse_id,year=COALESCE(excluded.year,albums.year),updated_at=CURRENT_TIMESTAMP`)
      .bind(`${canonical(artist)}|${canonical(album)}`,album,artist,browseId,validYear(input?.year)).run();
  }
  return { stato: 'salvato' };
}

async function logRequest(db, key, operation, hit, count, duration, env) {
  try {
    await db.prepare(`INSERT INTO requests_log(id,search_key,operation,cache_hit,result_count,duration_ms,engine_version)
      VALUES(?1,?2,?3,?4,?5,?6,?7)`)
      .bind(crypto.randomUUID(),key,operation,hit?1:0,count||0,duration||0,env.ENGINE_VERSION||'1.0.0').run();
  } catch (_) {}
}

function parseJsonObject(text) {
  const cleaned = String(text).replace(/```json/gi,'').replace(/```/g,'').trim();
  const a = cleaned.indexOf('{');
  const b = cleaned.lastIndexOf('}');
  if (a < 0 || b <= a) throw new Error('JSON AI non valido');
  return JSON.parse(cleaned.slice(a,b+1));
}

function normalizeCredits(value) {
  const v = value && typeof value === 'object' ? value : {};
  return {
    songwriters: strings(v.songwriters),
    composers: strings(v.composers),
    lyricists: strings(v.lyricists),
    producers: strings(v.producers),
    label: nullable(v.label),
  };
}

function normalizeCategory(value) {
  const v = canonical(String(value || 'cover'));
  if (v.includes('remix') || v.includes('rework')) return 'remix';
  if (v.includes('live') || v.includes('dal vivo')) return 'live';
  if (v.includes('stran') || v.includes('adapt')) return 'straniera';
  if (v.includes('original')) return 'originale';
  return 'cover';
}

function versionKey(title, artist, category, language) {
  return `${normalizeCategory(category)}|${canonical(artist)}|${canonical(title)}|${canonical(language || '')}`;
}
function searchKey(title, artist) { return `${canonical(title)}|${canonical(artist)}`; }
function canonical(value) { return clean(value).normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,' ').trim().replace(/\s+/g,' '); }
function clean(value) { return String(value ?? '').trim(); }
function nullable(value) { const v=clean(value); return v && v.toLowerCase()!=='null' ? v : null; }
function strings(value) { return Array.isArray(value) ? [...new Set(value.map(clean).filter(Boolean))] : []; }
function validYear(value) { const n=Number(value); return Number.isInteger(n)&&n>=1800&&n<=2100?n:null; }
function safeJson(value) { try { return value ? JSON.parse(value) : null; } catch { return null; } }
function json(payload,status=200) { return new Response(JSON.stringify(payload),{status,headers:{...JSON_HEADERS,...CORS}}); }
