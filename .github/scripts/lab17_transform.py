from pathlib import Path
import re

ROOT = Path('.')

def read(path):
    return (ROOT / path).read_text(encoding='utf-8')

def write(path, text):
    (ROOT / path).write_text(text, encoding='utf-8')

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected 1 occurrence, found {count}')
    return text.replace(old, new, 1)

def replace_last(text, old, new, label):
    idx = text.rfind(old)
    if idx < 0:
        raise RuntimeError(f'{label}: marker not found')
    return text[:idx] + new + text[idx + len(old):]

def replace_between(text, start, end, replacement, label):
    a = text.find(start)
    if a < 0:
        raise RuntimeError(f'{label}: start marker not found')
    b = text.find(end, a)
    if b < 0:
        raise RuntimeError(f'{label}: end marker not found')
    return text[:a] + replacement + text[b:]

# -----------------------------------------------------------------------------
# 1) Global player behaviour: every real navigation change collapses the player.
# -----------------------------------------------------------------------------
path = 'app/src/main/kotlin/com/metrolist/music/MainActivity.kt'
text = read(path)
old = '''                    // Collapse player when navigating to equalizer
                    if (navBackStackEntry?.destination?.route == "equalizer" &&
                        playerBottomSheetState.isExpanded
                    ) {
                        playerBottomSheetState.collapseSoft()
                    }
'''
new = '''                    // MusicLab LAB17: the expanded player is never persistent over another page.
                    // The full player is an overlay, not a Nav destination, so a back-stack change
                    // means the user selected app content and the player must become the mini-player.
                    if (navBackStackEntry?.destination?.route != null && playerBottomSheetState.isExpanded) {
                        playerBottomSheetState.collapseSoft()
                    }
'''
text = replace_once(text, old, new, 'MainActivity global collapse')
write(path, text)

# -----------------------------------------------------------------------------
# 2) Cover AI: Cloudflare + Gemini union, stepwise recovery, lightweight prompts.
# -----------------------------------------------------------------------------
path = 'app/src/main/kotlin/com/metrolist/music/ui/component/GeminiAiCoverDiscovery.kt'
text = read(path)
start = '    /** Prima corsia: fino a dieci cover studio, poi il resto continua in background. */\n'
end = '    /** Compatibilità con LAB10: raccoglie tutti i batch progressivi in un unico risultato. */\n'
replacement = '''    /**
     * STEP 1: stabilisce la composizione canonica e raccoglie un primo gruppo leggero.
     * Cloudflare e Gemini non sono più alternativi: le due risposte vengono unite.
     * In questa fase chiediamo solo i metadati essenziali; i crediti completi sono on-demand.
     */
    suspend fun discoverInitial(
        originalTitle: String,
        originalArtist: String,
        config: GeminiCoverVerificationConfig,
    ): AiCoverDiscoveryResult = withContext(Dispatchers.IO) {
        if (originalTitle.isBlank() || (config.apiKey.isBlank() && config.cloudEndpoint.isBlank())) {
            return@withContext AiCoverDiscoveryResult(null, emptyList())
        }
        val cacheKey = "initial17|${config.model}|${canonical(originalTitle)}|${canonical(originalArtist)}"
        initialCache[cacheKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let {
            return@withContext it.value
        }

        val cloud = if (config.cloudEndpoint.isNotBlank()) {
            CloudMusicDiscovery.discoverCover(
                title = originalTitle,
                artist = originalArtist,
                config = config,
                phase = "initial",
                focus = "STEP 1: identifica con precisione la composizione canonica e il vero interprete originale; poi restituisci prime cover studio reali con soli titolo, artista, anno e album essenziali.",
            )
        } else null

        val direct = if (config.apiKey.isNotBlank()) {
            val prompt = """Sei il motore musicale AI-first di MusicLab. Lavora a STEP e non confondere l'interprete della traccia corrente con l'interprete originale.

STEP 1 — IDENTITÀ CANONICA.
Traccia di partenza:
Titolo/video: ${originalTitle.trim()}
Interprete/canale: ${originalArtist.trim().ifBlank { "sconosciuto" }}

Prima stabilisci qual è la COMPOSIZIONE e chi l'ha INCISA/INTERPRETATA ORIGINARIAMENTE. Se la traccia di partenza è una cover, live, duetto o video TV, NON usare automaticamente quel cantante come originale. Distingui interprete originale da autore/compositore. Usa la ricerca Google quando serve a evitare un'identità sbagliata.

STEP 2 — PRIMO GRUPPO.
Dopo l'identità, restituisci fino a $INITIAL_LIMIT cover IN STUDIO reali della stessa composizione, eseguite da altri artisti. In questa fase servono solo titolo, artista, categoria, lingua, anno e album se noto. NON spendere spazio sui crediti completi: verranno richiesti solo quando l'utente apre i dettagli.
Escludi karaoke, reaction, tutorial, backing track, mashup e medley.

Rispondi SOLO JSON:
{
 "original":{"title":"","artist":"","year":null,"album":null,"language":null},
 "versions":[{"title":"","artist":"","category":"cover","language":null,"year":null,"album":null}]
}"""
            executeWithFallbackModels(config, prompt, 2400, true)?.let { parse(it, originalArtist) }
        } else null

        val result = mergeDiscoveries(cloud, direct, originalArtist, AiCoverCategory.COVER, INITIAL_LIMIT)
        initialCache[cacheKey] = CachedDiscovery(result, System.currentTimeMillis() + CACHE_TTL_MS)
        result
    }

'''
text = replace_between(text, start, end, replacement, 'Gemini cover initial')

research_start = '    private fun discoverResearchRound(\n'
research_end = '    private fun sanitize(\n'
new_research = '''    /**
     * STEP di recupero richiesto dalla UI quando i candidati trovati non diventano
     * abbastanza risultati riproducibili. Ogni round usa una strategia diversa e
     * riceve l'elenco già noto, quindi l'AI si auto-interroga senza ripetere gli stessi nomi.
     */
    suspend fun discoverRecoveryBatch(
        originalTitle: String,
        originalArtist: String,
        existing: List<AiCoverCandidate>,
        config: GeminiCoverVerificationConfig,
        round: Int,
    ): List<AiCoverCandidate> = withContext(Dispatchers.IO) {
        val focus = COVER_PLAYABLE_RECOVERY_FOCI[round.coerceAtLeast(0) % COVER_PLAYABLE_RECOVERY_FOCI.size]
        discoverResearchRound(originalTitle, originalArtist, existing, focus, config)
    }

    private fun discoverResearchRound(
        originalTitle: String,
        originalArtist: String,
        existing: List<AiCoverCandidate>,
        focus: ResearchFocus,
        config: GeminiCoverVerificationConfig,
    ): List<AiCoverCandidate> {
        val cloudVersions = if (config.cloudEndpoint.isNotBlank()) {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    CloudMusicDiscovery.discoverCover(
                        title = originalTitle,
                        artist = originalArtist,
                        config = config,
                        phase = "expand",
                        existing = existing,
                        focus = "STEP DI RICERCA: ${focus.instructions} Restituisci soprattutto NUOVI nomi/versioni; crediti completi non necessari ora.",
                    )
                }
            }.getOrNull()?.let { sanitize(it, originalArtist, focus.category, focus.limit).versions }.orEmpty()
        } else emptyList()

        if (config.apiKey.isBlank()) return cloudVersions.distinctBy { it.stableKey }.take(focus.limit)

        val excluded = existing.take(260).joinToString("\\n") {
            "- ${it.artist} — ${it.title} [${it.category.name.lowercase()}${it.language?.let { l -> ", $l" }.orEmpty()}]"
        }

        val prompt = """Sei il motore musicale AI-first centrale di MusicLab. Devi lavorare A STEP e massimizzare i risultati REALI, non fermarti ai nomi più famosi.

Composizione canonica:
Titolo: ${originalTitle.trim()}
Interprete originale: ${originalArtist.trim().ifBlank { "sconosciuto" }}

STEP CORRENTE:
${focus.instructions}

Obiettivo di questo step: trovare fino a ${focus.limit} elementi NUOVI della stessa composizione. Se la prima memoria mentale produce pochi nomi, riesamina per decenni, album, singoli, paesi, programmi TV, festival o pubblicazioni digitali coerenti con il focus. Non ripetere gli elementi già noti.

Classificazione:
- cover = incisione IN STUDIO da un interprete diverso dall'originale nella lingua originale;
- live = concerto, TV, radio, sessione, festival o performance non da studio;
- remix = remix/rework/mix attribuito;
- straniera = incisione IN STUDIO in altra lingua, anche con titolo tradotto.
Precedenza: remix > live > straniera > cover.
Escludi karaoke, reaction, tutorial, backing track, mashup, medley e tribute anonimi.
Non inventare per raggiungere il numero.

IMPORTANTE: in questa fase restituisci SOLO metadati essenziali per la localizzazione audio: titolo, artista, categoria, lingua, anno e album se noto. I crediti completi saranno richiesti soltanto quando l'utente apre Dettagli.

Già note, da NON ripetere:
${excluded.ifBlank { "(nessuna)" }}

Rispondi SOLO JSON:
{"original":null,"versions":[{"title":"","artist":"","category":"cover|live|remix|straniera","language":null,"year":null,"album":null}]}
"""

        val directVersions = executeWithFallbackModels(config, prompt, 4300, focus.deepResearch)
            ?.let { sanitize(parse(it, originalArtist), originalArtist, focus.category, focus.limit).versions }
            .orEmpty()

        return (cloudVersions + directVersions)
            .distinctBy { it.stableKey }
            .take(focus.limit)
    }

    private fun mergeDiscoveries(
        cloud: AiCoverDiscoveryResult?,
        direct: AiCoverDiscoveryResult?,
        originalArtist: String,
        forcedCategory: AiCoverCategory?,
        limit: Int,
    ): AiCoverDiscoveryResult {
        val original = direct?.original ?: cloud?.original
        val merged = AiCoverDiscoveryResult(
            original = original,
            versions = buildList {
                cloud?.versions?.let(::addAll)
                direct?.versions?.let(::addAll)
            }.distinctBy { it.stableKey },
        )
        return sanitize(merged, originalArtist, forcedCategory, limit)
    }

'''
text = replace_between(text, research_start, research_end, new_research, 'Gemini cover research')

marker = '    private val DISALLOWED = Regex("\\\\b(karaoke|reaction|tutorial|lesson|backing track|instrumental backing|mashup|medley)\\\\b")\n'
if marker not in text:
    raise RuntimeError('Gemini cover constants marker not found')
insert = '''    private val COVER_PLAYABLE_RECOVERY_FOCI = listOf(
        ResearchFocus(AiCoverCategory.COVER, "Nuovo giro: cerca cover studio reali non ancora elencate, artista per artista e decennio per decennio. Privilegia incisioni ufficiali/localizzabili.", 36, true),
        ResearchFocus(AiCoverCategory.COVER, "Nuovo giro: esplora cataloghi, compilation, singoli, talent/show e reinterpretazioni ufficiali meno note della stessa composizione.", 36, true),
        ResearchFocus(AiCoverCategory.COVER, "Nuovo giro: cerca reinterpretazioni studio internazionali nella stessa lingua originale e pubblicazioni digitali attribuite.", 36, true),
        ResearchFocus(AiCoverCategory.COVER, "Nuovo giro: verifica quali interpreti e incisioni reali della composizione sono ancora assenti dalla lista già nota.", 40, true),
        ResearchFocus(AiCoverCategory.COVER, "Recupero profondo: usa ricerca web per trovare cover studio documentate che i passaggi precedenti non hanno nominato.", 40, true),
        ResearchFocus(AiCoverCategory.COVER, "Ultimo giro: trova soltanto nuove cover studio reali ancora assenti; se non esistono altri risultati affidabili restituisci lista vuota.", 40, true),
    )

'''
text = text.replace(marker, insert + marker, 1)
text = text.replace('    private const val MAX_TOTAL_CANDIDATES = 240\n', '    private const val MAX_TOTAL_CANDIDATES = 320\n', 1)
write(path, text)

# -----------------------------------------------------------------------------
# 3) Cover resolver: exhaust YouTube Music first, then normal YouTube.
# -----------------------------------------------------------------------------
path = 'app/src/main/kotlin/com/metrolist/music/ui/component/AiCoverSearchEngine.kt'
text = read(path)
start = '    private suspend fun resolveOne(\n'
end = '    /**\n     * Se Gemini non conosce l\'anno, prova il metadato reale dell\'album YouTube Music.\n'
new_func = '''    private suspend fun resolveOne(
        candidate: AiCoverCandidate,
        currentYouTubeId: String?,
    ): AiCoverPlayable? {
        val languageSuffix = candidate.language?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        val queries = when (candidate.category) {
            AiCoverCategory.COVER -> listOf(
                "${candidate.title} ${candidate.artist}",
                "${candidate.artist} ${candidate.title}",
                "${candidate.title} ${candidate.artist} official audio",
                "${candidate.title} ${candidate.artist} cover",
                "${candidate.title} ${candidate.artist} lyrics",
            )
            AiCoverCategory.FOREIGN -> listOf(
                "${candidate.title} ${candidate.artist}$languageSuffix",
                "${candidate.artist} ${candidate.title}$languageSuffix",
                "${candidate.title} ${candidate.artist} official audio$languageSuffix",
                "${candidate.title} ${candidate.artist} lyrics$languageSuffix",
            )
            AiCoverCategory.REMIX -> listOf(
                "${candidate.title} ${candidate.artist} remix",
                "${candidate.artist} ${candidate.title} remix",
                "${candidate.title} ${candidate.artist} rework",
                "${candidate.title} ${candidate.artist} mix",
            )
            AiCoverCategory.LIVE -> listOf(
                "${candidate.title} ${candidate.artist} live",
                "${candidate.artist} ${candidate.title} live",
                "${candidate.title} ${candidate.artist} performance",
                "${candidate.title} ${candidate.artist} session",
            )
        }.distinct()

        // STEP playback 1: YouTube Music su tutte le query, perché offre album/cover più puliti.
        for (query in queries) {
            searchAndPick(query, YouTube.SearchFilter.FILTER_SONG, candidate, currentYouTubeId)?.let {
                return datedPlayable(candidate, it, "YouTube Music")
            }
        }

        // STEP playback 2: se YTM non localizza la versione, usa il catalogo video YouTube.
        for (query in queries) {
            searchAndPick(query, YouTube.SearchFilter.FILTER_VIDEO, candidate, currentYouTubeId)?.let {
                return datedPlayable(candidate, it, "YouTube")
            }
        }
        return null
    }

'''
text = replace_between(text, start, end, new_func, 'Cover YTM then YouTube')
write(path, text)

# -----------------------------------------------------------------------------
# 4) Cover UI: light list, thumbnail=play, side=details, details AI on demand,
#    playable-target recovery rather than candidate-target completion.
# -----------------------------------------------------------------------------
path = 'app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt'
text = read(path)
text = replace_once(text, 'import androidx.compose.foundation.ExperimentalFoundationApi\n', 'import androidx.compose.foundation.ExperimentalFoundationApi\nimport androidx.compose.foundation.clickable\n', 'Cover clickable import')
text = replace_once(text, 'private const val COVER_PAGE_SIZE = 10\n', 'private const val COVER_PAGE_SIZE = 10\nprivate const val MIN_COVER_PLAYABLE_TARGET = 50\nprivate const val MAX_COVER_RECOVERY_ROUNDS = 6\n', 'Cover constants')
state_marker = '    var keyDraft by remember { mutableStateOf("") }\n'
state_insert = '''    var keyDraft by remember { mutableStateOf("") }
    var detailResult by remember(sessionKey) { mutableStateOf<AiCoverPlayable?>(null) }
    var detailCredits by remember(sessionKey) { mutableStateOf<GeminiVersionCredits?>(null) }
    var detailLoading by remember(sessionKey) { mutableStateOf(false) }
'''
text = replace_once(text, state_marker, state_insert, 'Cover detail state')

# Remove full credits from the main identity block: keep essential year/language/album.
old_credits = '''                            AiCoverCredits(
                                year = info.year,
                                album = info.album,
                                songwriters = info.songwriters,
                                composers = info.composers,
                                lyricists = info.lyricists,
                                producers = info.producers,
                                label = info.label,
                            )
'''
new_credits = '''                            info.album?.let { album ->
                                Text("Album: $album", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
'''
text = replace_once(text, old_credits, new_credits, 'Cover identity lightweight')

# Insert target-driven playable recovery after the normal expanded discovery pass.
needle = '        }.onFailure { failed = true }\n\n        session.backgroundComplete = true\n'
recovery = '''        }.onFailure { failed = true }

        // STEP 4: il target riguarda risultati realmente riproducibili, non semplici nomi AI.
        // Se molti candidati non vengono localizzati, chiediamo nuovi nomi con strategie
        // differenti e riproviamo YTM -> YouTube. Due passate senza progresso fermano il ciclo.
        var noProgressPasses = 0
        for (round in 0 until MAX_COVER_RECOVERY_ROUNDS) {
            val playableCovers = playables.count { it.candidate.category == AiCoverCategory.COVER }
            if (playableCovers >= MIN_COVER_PLAYABLE_TARGET || noProgressPasses >= 2) break

            val beforeCandidates = knownCandidates.size
            val beforePlayables = playables.size
            val recovered = runCatching {
                GeminiAiCoverDiscovery.discoverRecoveryBatch(
                    originalTitle = originalInfo?.title?.ifBlank { title } ?: title,
                    originalArtist = originalInfo?.artist?.ifBlank { originalArtist } ?: originalArtist,
                    existing = knownCandidates,
                    config = config,
                    round = round,
                )
            }.getOrDefault(emptyList())
                .filter { categoryEnabled(it.category) }
                .filter { candidate -> knownCandidates.none { it.stableKey == candidate.stableKey } }

            if (recovered.isNotEmpty()) {
                knownCandidates = (knownCandidates + recovered).distinctBy { it.stableKey }
                expandedCandidateCount += recovered.size
                session.knownCandidates = knownCandidates
                session.expandedCandidateCount = expandedCandidateCount

                val resolved = runCatching {
                    AiCoverSearchEngine.resolveCandidates(
                        candidates = recovered,
                        currentYouTubeId = currentYouTubeId,
                        pauseBetweenBatches = false,
                    )
                }.getOrDefault(AiCoverResolveResult(emptyList(), AiCoverResolveStats()))

                playables = mergePlayables(playables, resolved.playables)
                youtubeMusicHits += resolved.stats.youtubeMusicHits
                youtubeHits += resolved.stats.youtubeHits
                session.playables = playables
                session.youtubeMusicHits = youtubeMusicHits
                session.youtubeHits = youtubeHits
            }

            val madeProgress = knownCandidates.size > beforeCandidates || playables.size > beforePlayables
            noProgressPasses = if (madeProgress) 0 else noProgressPasses + 1
        }

        session.backgroundComplete = true
'''
text = replace_last(text, needle, recovery, 'Cover playable recovery')

# On-demand detail enrichment.
detail_effect_marker = '    if (showDiagnosticsDialog) {\n'
detail_effect = '''    LaunchedEffect(detailResult?.song?.id, geminiConfig, originalInfo?.title, originalInfo?.artist) {
        val result = detailResult ?: return@LaunchedEffect
        detailCredits = null
        detailLoading = true
        val info = originalInfo
        val identity = GeminiOriginalIdentity(
            title = info?.title?.ifBlank { title } ?: title,
            originalArtists = listOf(info?.artist?.ifBlank { originalArtist } ?: originalArtist).filter { it.isNotBlank() },
            year = info?.year,
            songwriters = info?.songwriters.orEmpty(),
            composers = info?.composers.orEmpty(),
            lyricists = info?.lyricists.orEmpty(),
            producers = info?.producers.orEmpty(),
            label = info?.label,
            album = info?.album,
            mode = GeminiOriginalMode.MODEL_KNOWLEDGE,
            webSourceCount = 0,
        )
        detailCredits = runCatching {
            GeminiOriginalVersionCredits.enrich(identity, listOf(result.song), geminiConfig)[result.song.id]
        }.getOrNull()
        detailLoading = false
    }

'''
text = replace_once(text, detail_effect_marker, detail_effect + detail_effect_marker, 'Cover detail effect')

# Detail dialog before the main Surface.
surface_marker = '    Surface(modifier = Modifier.fillMaxSize()) {\n'
dialog = '''    detailResult?.let { selected ->
        CoverDetailDialog(
            result = selected,
            credits = detailCredits,
            loading = detailLoading,
            onDismiss = { detailResult = null },
            onReplace = {
                replaceWith(selected)
                detailResult = null
            },
        )
    }

'''
text = replace_once(text, surface_marker, dialog + surface_marker, 'Cover detail dialog insertion')

# Add onDetails to the result row invocation.
old_call = '''                                AiCoverResultRow(
                                    result = result,
                                    onPlay = { play(result.song) },
                                    onReplace = { replaceWith(result) },
                                    onLongClick = {
'''
new_call = '''                                AiCoverResultRow(
                                    result = result,
                                    onPlay = { play(result.song) },
                                    onReplace = { replaceWith(result) },
                                    onDetails = { detailResult = result },
                                    onLongClick = {
'''
text = replace_once(text, old_call, new_call, 'Cover result details callback')

# Replace the two row composables with lightweight UI + details side action.
row_start = '@OptIn(ExperimentalFoundationApi::class)\n@Composable\nprivate fun CoverStartRow(\n'
row_end = '@Composable\nprivate fun AiCoverCredits(\n'
new_rows = '''@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverStartRow(
    info: AiCoverOriginalInfo?,
    fallbackYear: Int?,
    song: SongItem,
    onPlay: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        AsyncImage(
            model = song.thumbnail,
            contentDescription = "Riproduci",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPlay),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(info?.title?.ifBlank { song.title } ?: song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                info?.artist?.ifBlank { song.artists.joinToString(", ") { it.name } }
                    ?: song.artists.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = (info?.year ?: fallbackYear)?.let { "Data: $it" } ?: "Data non disponibile",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            info?.album?.let { album -> Text("Album: $album", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiCoverResultRow(
    result: AiCoverPlayable,
    onPlay: () -> Unit,
    onReplace: () -> Unit,
    onDetails: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onDetails, onLongClick = onLongClick).padding(vertical = 6.dp),
    ) {
        AsyncImage(
            model = result.song.thumbnail,
            contentDescription = "Riproduci",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPlay),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(result.candidate.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(result.candidate.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = result.candidate.year?.let { "Data: $it" } ?: "Data non disponibile",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            result.candidate.album?.let { album ->
                Text("Album: $album", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${coverCategoryLabel(result.candidate.category)} · ${result.playbackSource}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onDetails,
            modifier = Modifier.padding(start = 8.dp).height(36.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        ) { Text("Dettagli", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun CoverDetailDialog(
    result: AiCoverPlayable,
    credits: GeminiVersionCredits?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onReplace: () -> Unit,
) {
    val candidate = result.candidate
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(candidate.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(candidate.artist, style = MaterialTheme.typography.titleSmall)
                Text("Tipo: ${coverCategoryLabel(candidate.category)}")
                Text((credits?.year ?: candidate.year)?.let { "Data: $it" } ?: "Data: non disponibile")
                (credits?.album ?: candidate.album)?.let { Text("Album: $it") }
                candidate.language?.let { Text("Lingua: $it") }
                Text("Riproduzione: ${result.playbackSource}", style = MaterialTheme.typography.bodySmall)
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Recupero informazioni complete…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                val songwriters = credits?.songwriters?.takeIf { it.isNotEmpty() } ?: candidate.songwriters
                val composers = credits?.composers?.takeIf { it.isNotEmpty() } ?: candidate.composers
                val lyricists = credits?.lyricists?.takeIf { it.isNotEmpty() } ?: candidate.lyricists
                val producers = credits?.producers?.takeIf { it.isNotEmpty() } ?: candidate.producers
                val label = credits?.label ?: candidate.label
                if (songwriters.isNotEmpty()) Text("Autori: ${songwriters.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                if (composers.isNotEmpty()) Text("Compositori: ${composers.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                if (lyricists.isNotEmpty()) Text("Parolieri: ${lyricists.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                if (producers.isNotEmpty()) Text("Produttori: ${producers.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                label?.let { Text("Etichetta: $it", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onReplace) { Text("Sostituisci") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
}

private fun coverCategoryLabel(category: AiCoverCategory): String = when (category) {
    AiCoverCategory.COVER -> "Studio"
    AiCoverCategory.LIVE -> "Live"
    AiCoverCategory.REMIX -> "Remix"
    AiCoverCategory.FOREIGN -> "Studio · straniera"
}

'''
text = replace_between(text, row_start, row_end, new_rows, 'Cover lightweight rows')
write(path, text)

# -----------------------------------------------------------------------------
# 5) Original identity: Cloudflare + Gemini, lightweight identity, AI query planning.
# -----------------------------------------------------------------------------
path = 'app/src/main/kotlin/com/metrolist/music/ui/component/GeminiOriginalDiscovery.kt'
text = read(path)
start = '    suspend fun identify(\n'
end = '    private fun GeminiCoverVerificationConfig.isUsable(): Boolean =\n'
new_identify = '''    suspend fun identify(
        currentTitle: String,
        currentArtist: String,
        config: GeminiCoverVerificationConfig,
    ): GeminiOriginalIdentity? = withContext(Dispatchers.IO) {
        if (currentTitle.isBlank()) return@withContext null

        val cacheKey = "v3|${config.model}|${currentTitle.trim().lowercase()}|${currentArtist.trim().lowercase()}"
        cache[cacheKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let { return@withContext it.identity }

        val cloudIdentity = if (config.cloudEndpoint.isNotBlank()) {
            CloudMusicDiscovery.identifyOriginal(currentTitle, currentArtist, config)
        } else null

        val directIdentity = if (config.isUsable()) {
            val prompt = """Sei il motore Originali di MusicLab. Lavora per STEP.

STEP 1 — IDENTITÀ CANONICA.
La traccia corrente può essere cover, live, duetto, TV o avere un titolo descrittivo.
Titolo/video: ${currentTitle.trim()}
Interprete/canale: ${currentArtist.trim().ifBlank { "sconosciuto" }}

Stabilisci la COMPOSIZIONE canonica e chi è il vero INTERPRETE ORIGINALE della prima incisione/pubblicazione. Non confondere autore o compositore con interprete, e non assumere che l'artista corrente sia l'originale. Usa la ricerca Google per verificare i casi ambigui.

In questo step NON servono crediti completi. Restituisci solo titolo canonico, interprete originale, anno della prima pubblicazione e album/singolo se noto. I crediti saranno caricati soltanto quando l'utente apre Dettagli.

Rispondi SOLO JSON valido:
{
  "title":"titolo canonico",
  "original_artists":["interprete originale"],
  "year":1965,
  "album":"album o singolo oppure null"
}"""
            executeWithFallbackModels(prompt, config, useGoogleSearch = true, maxOutputTokens = 900)?.let(::parseIdentity)
        } else null

        val identity = mergeIdentities(cloudIdentity, directIdentity) ?: return@withContext null
        cache[cacheKey] = CachedIdentity(identity, System.currentTimeMillis() + CACHE_TTL_MS)
        identity
    }

    /**
     * STEP 2/3 di Originali: Gemini suggerisce nuove interrogazioni quando le ricerche
     * standard non hanno ancora prodotto almeno il target di versioni riproducibili.
     */
    suspend fun planVersionQueries(
        identity: GeminiOriginalIdentity,
        existingQueries: List<String>,
        round: Int,
        config: GeminiCoverVerificationConfig?,
    ): List<String> = withContext(Dispatchers.IO) {
        if (config == null || !config.isUsable()) return@withContext emptyList()
        val excluded = existingQueries.take(80).joinToString("\\n") { "- $it" }
        val prompt = """Sei il pianificatore di ricerca della funzione Originali di MusicLab.
Composizione: ${identity.title}
Interprete originale: ${identity.originalArtists.joinToString(", ")}
Anno originale: ${identity.year ?: "sconosciuto"}

Siamo al giro ${round + 1}. Dobbiamo localizzare almeno 10 registrazioni/performance REALI della stessa composizione in cui compare l'interprete originale. Non devi inventare risultati: devi produrre QUERY DI RICERCA utili per YouTube Music e YouTube.

Cerca strategie diverse: incisioni studio/remaster, album e singoli, live, TV/radio, sessioni, duetti/collaborazioni, acoustic/unplugged, remix ufficiali, anni/eventi noti. Se i giri precedenti hanno fallito, cambia strategia.

Query già usate, da NON ripetere:
${excluded.ifBlank { "(nessuna)" }}

Restituisci fino a 18 query complete e concise. Nessun credito.
Rispondi SOLO JSON: {"queries":["query 1","query 2"]}
"""
        val answer = executeWithFallbackModels(
            prompt = prompt,
            config = config,
            useGoogleSearch = round > 0,
            maxOutputTokens = 1400,
        ) ?: return@withContext emptyList()
        parseQueries(answer.text)
            .filter { it.isNotBlank() && it !in existingQueries }
            .distinct()
            .take(18)
    }

    private fun mergeIdentities(
        cloud: GeminiOriginalIdentity?,
        direct: GeminiOriginalIdentity?,
    ): GeminiOriginalIdentity? {
        if (direct == null) return cloud
        if (cloud == null) return direct
        return direct.copy(
            year = direct.year ?: cloud.year,
            album = direct.album ?: cloud.album,
            songwriters = direct.songwriters.ifEmpty { cloud.songwriters },
            composers = direct.composers.ifEmpty { cloud.composers },
            lyricists = direct.lyricists.ifEmpty { cloud.lyricists },
            producers = direct.producers.ifEmpty { cloud.producers },
            label = direct.label ?: cloud.label,
        )
    }

    private fun parseQueries(text: String): List<String> {
        val root = extractJsonObject(text) ?: return emptyList()
        return root["queries"]?.runCatching { jsonArray }?.getOrNull()
            ?.mapNotNull { it.runCatching { jsonPrimitive }.getOrNull()?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
            ?.distinct()
            .orEmpty()
    }

'''
text = replace_between(text, start, end, new_identify, 'Original identity stepwise')
write(path, text)

# -----------------------------------------------------------------------------
# 6) Original search: YTM first, YouTube fallback, then AI-planned recovery rounds.
# -----------------------------------------------------------------------------
path = 'app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionSearchEngine.kt'
text = read(path)
start = '    suspend fun findInitialVersions(\n'
end = '    suspend fun findExpandedVersions(\n'
new_initial = '''    suspend fun findInitialVersions(
        identity: GeminiOriginalIdentity,
        currentYouTubeId: String,
    ): OriginalVersionSearchResult = coroutineScope {
        val targetTitle = exactBaseTitle(identity.title)
        val originalArtists = canonicalOriginalArtists(identity)
        val leadArtist = identity.originalArtists.firstOrNull().orEmpty()
        if (targetTitle.isBlank() || originalArtists.isEmpty() || leadArtist.isBlank()) {
            return@coroutineScope OriginalVersionSearchResult(
                original = null,
                versions = emptyList(),
                aiIdentity = identity,
                diagnostics = diagnosticsForIdentity(identity).copy(aiStatus = OriginalAiStatus.NO_ANSWER),
            )
        }

        val query = "${identity.title} $leadArtist"
        val music = searchFirstPage(query, targetTitle, originalArtists, YouTube.SearchFilter.FILTER_SONG, "YouTube Music")
        val merged = linkedMapOf<String, CoverHubResult>()
        music.results.forEach { mergeInto(merged, it) }
        includeCurrentIfOriginal(currentYouTubeId, targetTitle, originalArtists, merged)

        // YTM viene esaurito per primo; YouTube normale entra solo se il primo step è corto.
        val video = if (merged.size < INITIAL_RESULTS_LIMIT) {
            searchFirstPage(query, targetTitle, originalArtists, YouTube.SearchFilter.FILTER_VIDEO, "YouTube")
                .also { outcome -> outcome.results.forEach { mergeInto(merged, it) } }
        } else QueryOutcome(emptyList(), 0, false, false)

        val pool = resolveMissingYears(merged.values.toList())
        val chosen = chooseAiOriginal(pool, identity)
        val visible = linkedMapOf<String, CoverHubResult>()
        chosen?.let { visible[it.song.id] = it }
        pool.forEach { candidate -> if (visible.size < INITIAL_RESULTS_LIMIT) visible[candidate.song.id] = candidate }

        val original = chosen?.copy(year = identity.year ?: chosen.year, source = aiSource(chosen.source), confirmed = true)
        val versions = visible.values.asSequence().filter { it.song.id != original?.song?.id }.sortedWith(versionOrder).toList()

        buildCategorizedResult(
            identity = identity,
            original = original,
            versions = versions,
            diagnostics = diagnosticsForIdentity(identity).copy(
                youtubeMusicStatus = statusFor(music),
                youtubeMusicFound = music.results.size,
                youtubeMusicPages = music.pages,
                youtubeStatus = statusFor(video),
                youtubeFound = video.results.size,
                youtubePages = video.pages,
                finalVersions = versions.size + if (original != null) 1 else 0,
                initialVisible = versions.size + if (original != null) 1 else 0,
                backgroundComplete = false,
            ),
        )
    }

'''
text = replace_between(text, start, end, new_initial, 'Original initial YTM first')

start = '    suspend fun findExpandedVersions(\n'
end = '    suspend fun findVersions(\n'
new_expanded = '''    suspend fun findExpandedVersions(
        identity: GeminiOriginalIdentity,
        currentYouTubeId: String,
        seed: OriginalVersionSearchResult,
        geminiConfig: GeminiCoverVerificationConfig?,
    ): OriginalVersionSearchResult = coroutineScope {
        val targetTitle = exactBaseTitle(identity.title)
        val originalArtists = canonicalOriginalArtists(identity)
        if (targetTitle.isBlank() || originalArtists.isEmpty()) return@coroutineScope seed

        val merged = linkedMapOf<String, CoverHubResult>()
        seed.original?.let { mergeInto(merged, it) }
        seed.versions.forEach { mergeInto(merged, it) }

        val usedQueries = defaultVersionQueries(identity).toMutableList()
        var musicOutcome = searchAiArtistVersions(
            identity, targetTitle, originalArtists, YouTube.SearchFilter.FILTER_SONG, "YouTube Music", usedQueries,
        )
        musicOutcome.results.forEach { mergeInto(merged, it) }

        var videoOutcome = OriginalArtistSearchOutcome(emptyList(), 0, OriginalSearchStageStatus.NOT_RUN)
        if (merged.size < MIN_ORIGINAL_PLAYABLE_TARGET) {
            videoOutcome = searchAiArtistVersions(
                identity, targetTitle, originalArtists, YouTube.SearchFilter.FILTER_VIDEO, "YouTube", usedQueries,
            )
            videoOutcome.results.forEach { mergeInto(merged, it) }
        }

        // AI self-interrogation: if the real playable count is still short, ask Gemini for
        // a different search plan and execute it step by step, always YTM before YouTube.
        var noProgressPasses = 0
        for (round in 0 until MAX_AI_SEARCH_ROUNDS) {
            if (merged.size >= MIN_ORIGINAL_PLAYABLE_TARGET || noProgressPasses >= 2) break
            val before = merged.size
            val planned = GeminiOriginalDiscovery.planVersionQueries(identity, usedQueries, round, geminiConfig)
            if (planned.isEmpty()) {
                noProgressPasses++
                continue
            }
            usedQueries += planned

            val musicExtra = searchAiArtistVersions(
                identity, targetTitle, originalArtists, YouTube.SearchFilter.FILTER_SONG, "YouTube Music", planned,
            )
            musicExtra.results.forEach { mergeInto(merged, it) }
            musicOutcome = mergeOutcomes(musicOutcome, musicExtra)

            if (merged.size < MIN_ORIGINAL_PLAYABLE_TARGET) {
                val videoExtra = searchAiArtistVersions(
                    identity, targetTitle, originalArtists, YouTube.SearchFilter.FILTER_VIDEO, "YouTube", planned,
                )
                videoExtra.results.forEach { mergeInto(merged, it) }
                videoOutcome = mergeOutcomes(videoOutcome, videoExtra)
            }
            noProgressPasses = if (merged.size > before) 0 else noProgressPasses + 1
        }

        includeCurrentIfOriginal(currentYouTubeId, targetTitle, originalArtists, merged)
        val raw = resolveMissingYears(merged.values.toList())
        val chosen = chooseAiOriginal(raw, identity)
        val original = chosen?.copy(year = identity.year ?: chosen.year, source = aiSource(chosen.source), confirmed = true)
        val alternatives = raw.asSequence()
            .filter { it.song.id != original?.song?.id }
            .distinctBy { it.song.id }
            .sortedWith(versionOrder)
            .toList()

        buildCategorizedResult(
            identity = identity,
            original = original,
            versions = alternatives,
            diagnostics = diagnosticsForIdentity(identity).copy(
                youtubeMusicStatus = musicOutcome.status,
                youtubeMusicFound = musicOutcome.results.size,
                youtubeMusicPages = musicOutcome.pages,
                youtubeStatus = videoOutcome.status,
                youtubeFound = videoOutcome.results.size,
                youtubePages = videoOutcome.pages,
                finalVersions = alternatives.size + if (original != null) 1 else 0,
                initialVisible = seed.diagnostics.initialVisible,
                backgroundComplete = true,
            ),
        )
    }

'''
text = replace_between(text, start, end, new_expanded, 'Original expanded stepwise')

# Update compatibility wrapper.
old = '        return findExpandedVersions(identity, currentYouTubeId, initial)\n'
new = '        return findExpandedVersions(identity, currentYouTubeId, initial, geminiConfig)\n'
text = replace_once(text, old, new, 'Original findVersions config')

# Replace query worker with query-list driven version.
start = '    private suspend fun searchAiArtistVersions(\n'
end = '    private suspend fun searchFirstPage(\n'
new_search = '''    private fun defaultVersionQueries(identity: GeminiOriginalIdentity): List<String> {
        val leadArtist = identity.originalArtists.firstOrNull().orEmpty()
        return listOf(
            "${identity.title} $leadArtist",
            "${identity.title} $leadArtist studio",
            "${identity.title} $leadArtist remastered",
            "${identity.title} $leadArtist album",
            "${identity.title} $leadArtist live",
            "${identity.title} $leadArtist concert",
            "${identity.title} $leadArtist session",
            "${identity.title} $leadArtist tv",
            "${identity.title} $leadArtist radio",
            "${identity.title} $leadArtist duet",
            "${identity.title} $leadArtist feat",
            "${identity.title} $leadArtist acoustic",
            "${identity.title} $leadArtist unplugged",
            "${identity.title} $leadArtist remix",
            "${identity.title} $leadArtist official",
            "${identity.title} $leadArtist performance",
        ).filter { leadArtist.isNotBlank() }.distinct()
    }

    private suspend fun searchAiArtistVersions(
        identity: GeminiOriginalIdentity,
        targetTitle: String,
        originalArtists: Set<String>,
        filter: YouTube.SearchFilter,
        source: String,
        queries: List<String>,
    ): OriginalArtistSearchOutcome = coroutineScope {
        if (identity.title.isBlank() || identity.originalArtists.firstOrNull().isNullOrBlank() || queries.isEmpty()) {
            return@coroutineScope OriginalArtistSearchOutcome(emptyList(), 0, OriginalSearchStageStatus.NOT_RUN)
        }

        val queryOutcomes = mutableListOf<QueryOutcome>()
        val batches = queries.distinct().chunked(2)
        for ((batchIndex, batch) in batches.withIndex()) {
            val outcomes = batch.map { query ->
                async(Dispatchers.IO) { searchQueryPages(query, targetTitle, originalArtists, filter, source) }
            }.awaitAll()
            queryOutcomes += outcomes
            if (batchIndex < batches.lastIndex) delay(BACKGROUND_BATCH_PAUSE_MS)
        }

        val merged = linkedMapOf<String, CoverHubResult>()
        queryOutcomes.flatMap { it.results }.forEach { mergeInto(merged, it) }
        val pages = queryOutcomes.sumOf { it.pages }
        val anyFailure = queryOutcomes.any { it.failed }
        val anySuccess = queryOutcomes.any { it.succeeded }
        val status = when {
            merged.isNotEmpty() -> OriginalSearchStageStatus.OK
            anyFailure -> OriginalSearchStageStatus.ERROR
            anySuccess -> OriginalSearchStageStatus.NO_RESULTS
            else -> OriginalSearchStageStatus.NOT_RUN
        }
        OriginalArtistSearchOutcome(merged.values.take(MAX_RESULTS_PER_SOURCE), pages, status)
    }

    private fun mergeOutcomes(
        first: OriginalArtistSearchOutcome,
        second: OriginalArtistSearchOutcome,
    ): OriginalArtistSearchOutcome {
        val merged = linkedMapOf<String, CoverHubResult>()
        first.results.forEach { mergeInto(merged, it) }
        second.results.forEach { mergeInto(merged, it) }
        val status = when {
            merged.isNotEmpty() -> OriginalSearchStageStatus.OK
            first.status == OriginalSearchStageStatus.ERROR || second.status == OriginalSearchStageStatus.ERROR -> OriginalSearchStageStatus.ERROR
            first.status == OriginalSearchStageStatus.NO_RESULTS || second.status == OriginalSearchStageStatus.NO_RESULTS -> OriginalSearchStageStatus.NO_RESULTS
            else -> OriginalSearchStageStatus.NOT_RUN
        }
        return OriginalArtistSearchOutcome(merged.values.toList(), first.pages + second.pages, status)
    }

'''
text = replace_between(text, start, end, new_search, 'Original query planner worker')
text = text.replace('    private const val INITIAL_RESULTS_LIMIT = 10\n', '    private const val INITIAL_RESULTS_LIMIT = 10\n    private const val MIN_ORIGINAL_PLAYABLE_TARGET = 10\n    private const val MAX_AI_SEARCH_ROUNDS = 3\n', 1)
write(path, text)

# -----------------------------------------------------------------------------
# 7) Original UI: Studio/Live split, no eager credits, thumbnail plays, details on demand.
# -----------------------------------------------------------------------------
path = 'app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionScreen.kt'
text = read(path)
text = text.replace('    VERSIONS,\n', '    STUDIO,\n', 1)
text = text.replace('OriginalResultsTab.VERSIONS', 'OriginalResultsTab.STUDIO')

state_marker = '    var startingVersion by remember(request.currentYouTubeId) { mutableStateOf<CoverHubResult?>(null) }\n'
state_new = '''    var startingVersion by remember(request.currentYouTubeId) { mutableStateOf<CoverHubResult?>(null) }
    var detailResult by remember(request.currentYouTubeId) { mutableStateOf<CoverHubResult?>(null) }
    var detailLoading by remember(request.currentYouTubeId) { mutableStateOf(false) }
'''
text = replace_once(text, state_marker, state_new, 'Original detail state')

# Replace eager credit pipeline with expansion only.
start = '        val initialSongs = buildList<SongItem> {\n'
end = '        creditsLoading = false\n'
new_pipeline = '''        val expanded = runCatching {
            OriginalVersionSearchEngine.findExpandedVersions(
                identity = identity,
                currentYouTubeId = request.currentYouTubeId,
                seed = initial,
                geminiConfig = geminiConfig,
            )
        }.onFailure { failed = true }
            .getOrNull()

        if (expanded != null) searchResult = expanded
        backgroundLoading = false
        creditsLoading = false
'''
text = replace_between(text, start, end, new_pipeline, 'Original remove eager credits')
# replace_between leaves the end marker in place, remove the duplicate retained marker once.
text = text.replace('        creditsLoading = false\n\n    }\n', '\n    }\n', 1)

# Add on-demand detail enrichment before link handling.
marker = '    LaunchedEffect(link) {\n'
detail_effect = '''    LaunchedEffect(detailResult?.song?.id, searchResult.aiIdentity?.title, geminiConfig) {
        val selected = detailResult ?: return@LaunchedEffect
        val identity = searchResult.aiIdentity ?: return@LaunchedEffect
        if (versionCredits[selected.song.id] != null) return@LaunchedEffect
        detailLoading = true
        val loaded = runCatching {
            GeminiOriginalVersionCredits.enrich(identity, listOf(selected.song), geminiConfig)
        }.getOrDefault(emptyMap())
        if (loaded.isNotEmpty()) versionCredits = versionCredits + loaded
        detailLoading = false
    }

'''
text = replace_once(text, marker, detail_effect + marker, 'Original detail effect')

# Studio is versions excluding Live/Remix; keep other specialist tabs as secondary views.
old_selected = '''    val selectedVersions = when (selectedTab) {
        OriginalResultsTab.STUDIO -> searchResult.versions
        OriginalResultsTab.LIVE -> searchResult.liveVersions
        OriginalResultsTab.WITH_OTHERS -> searchResult.withOthersVersions
        OriginalResultsTab.REMIX -> searchResult.remixVersions
    }
'''
new_selected = '''    val nonStudioIds = (searchResult.liveVersions + searchResult.remixVersions).map { it.song.id }.toSet()
    val studioVersions = searchResult.versions.filter { it.song.id !in nonStudioIds }
    val selectedVersions = when (selectedTab) {
        OriginalResultsTab.STUDIO -> studioVersions
        OriginalResultsTab.LIVE -> searchResult.liveVersions
        OriginalResultsTab.WITH_OTHERS -> searchResult.withOthersVersions
        OriginalResultsTab.REMIX -> searchResult.remixVersions
    }
'''
text = replace_once(text, old_selected, new_selected, 'Original studio split')

# Diagnostics reflects on-demand details rather than eager credits.
old_diag = '                        "Crediti AI caricati: ${versionCredits.size}${if (creditsLoading) " · altri in background" else ""}",\n'
new_diag = '                        "Dettagli AI caricati su richiesta: ${versionCredits.size}",\n'
text = replace_once(text, old_diag, new_diag, 'Original diagnostics details')

# Identity summary is lightweight.
text = replace_once(text, '                        AiCreditsBlock(identity)\n', '                        identity.album?.let { Text("Album / pubblicazione: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }\n', 'Original identity lightweight')
text = text.replace('L\'AI decide direttamente originale, anno, album e crediti. YouTube e YouTube Music servono esclusivamente a trovare le versioni riproducibili.', 'L\'AI identifica originale, anno e pubblicazione. I crediti completi vengono caricati solo aprendo Dettagli; YouTube Music e YouTube servono a localizzare la riproduzione.')

# Counts and labels for Studio tab.
text = text.replace('                                versionsCount = searchResult.versions.size,\n', '                                versionsCount = studioVersions.size,\n', 1)
text = text.replace('            label = "Versioni",\n', '            label = "Studio",\n', 1)

# Add detail callbacks to each row invocation.
text = replace_once(text,
'''                            onPreview = { preview(candidate) },
                            onReplace = { replaceWith(candidate) },
''',
'''                            onPreview = { preview(candidate) },
                            onReplace = { replaceWith(candidate) },
                            onDetails = { detailResult = CoverHubResult(song = candidate, year = manualYear, source = "Link manuale") },
''', 'Original manual details')
text = replace_once(text,
'''                                onPreview = { preview(original.song) },
                                onReplace = { replaceWith(original.song) },
''',
'''                                onPreview = { preview(original.song) },
                                onReplace = { replaceWith(original.song) },
                                onDetails = { detailResult = original },
''', 'Original original details')
text = replace_once(text,
'''                                onPreview = { preview(version.song) },
                                onReplace = { replaceWith(version.song) },
''',
'''                                onPreview = { preview(version.song) },
                                onReplace = { replaceWith(version.song) },
                                onDetails = { detailResult = version },
''', 'Original version details')
text = replace_once(text,
'''                            onPreview = { preview(starting.song) },
                            onReplace = { replaceWith(starting.song) },
''',
'''                            onPreview = { preview(starting.song) },
                            onReplace = { replaceWith(starting.song) },
                            onDetails = { detailResult = starting },
''', 'Original starting details')

# Insert detail dialog before Surface.
surface = '    Surface(modifier = Modifier.fillMaxSize()) {\n'
dialog = '''    detailResult?.let { selected ->
        OriginalDetailDialog(
            result = selected,
            credits = versionCredits[selected.song.id],
            loading = detailLoading,
            onDismiss = { detailResult = null },
            onReplace = {
                replaceWith(selected.song)
                detailResult = null
            },
        )
    }

'''
text = replace_once(text, surface, dialog + surface, 'Original detail dialog insertion')

# Replace row function with lightweight list behaviour, then add dialog.
start = '@Composable\nprivate fun OriginalVersionRow(\n'
end = '@Composable\nprivate fun VersionCreditsBlock(credits: GeminiVersionCredits?) {\n'
new_row = '''@Composable
private fun OriginalVersionRow(
    result: CoverHubResult,
    credits: GeminiVersionCredits?,
    creditsLoading: Boolean,
    onPreview: () -> Unit,
    onReplace: () -> Unit,
    onDetails: () -> Unit,
) {
    val displayYear = credits?.year ?: result.year
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDetails).padding(vertical = 6.dp),
    ) {
        AsyncImage(
            model = result.song.thumbnail,
            contentDescription = "Riproduci",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPreview),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(result.song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                result.song.artists.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = displayYear?.let { "Data: $it" } ?: "Data non disponibile",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            credits?.album?.let { album -> Text("Album: $album", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (result.source.isNotBlank()) Text("Riproduzione: ${result.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(
            onClick = onDetails,
            modifier = Modifier.padding(start = 8.dp).height(36.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        ) { Text("Dettagli", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun OriginalDetailDialog(
    result: CoverHubResult,
    credits: GeminiVersionCredits?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onReplace: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(result.song.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(result.song.artists.joinToString(", ") { it.name }, style = MaterialTheme.typography.titleSmall)
                Text((credits?.year ?: result.year)?.let { "Data: $it" } ?: "Data: non disponibile")
                credits?.album?.let { Text("Album: $it") }
                if (result.source.isNotBlank()) Text("Riproduzione: ${result.source}", style = MaterialTheme.typography.bodySmall)
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Recupero informazioni complete…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                credits?.let { VersionCreditsBlock(it) }
            }
        },
        confirmButton = { TextButton(onClick = onReplace) { Text("Sostituisci") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
}

'''
text = replace_between(text, start, end, new_row, 'Original lightweight rows')
write(path, text)

print('LAB17 transform completed successfully')
