/**
 * MusicLab AI-first cover search screen.
 * Gemini decides cover/remix/live versions and all editorial credits.
 * YouTube/YouTube Music are used only to locate playable media.
 */
package com.metrolist.music.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.YouTubeMatchOverride
import com.metrolist.music.ui.menu.YouTubeSongMenu
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val CoverAiFirstGeminiApiKey = stringPreferencesKey("coverGeminiApiKey")

private enum class AiCoverTab {
    COVER,
    REMIX,
    LIVE,
}

private class AiCoverSession {
    var initialLoaded: Boolean = false
    var backgroundComplete: Boolean = false
    var originalInfo: AiCoverOriginalInfo? = null
    var knownCandidates: List<AiCoverCandidate> = emptyList()
    var playables: List<AiCoverPlayable> = emptyList()
    var initialCandidateCount: Int = 0
    var expandedCandidateCount: Int = 0
    var initialPlayableCount: Int = 0
    var youtubeMusicHits: Int = 0
    var youtubeHits: Int = 0

    fun invalidate() {
        initialLoaded = false
        backgroundComplete = false
        originalInfo = null
        knownCandidates = emptyList()
        playables = emptyList()
        initialCandidateCount = 0
        expandedCandidateCount = 0
        initialPlayableCount = 0
        youtubeMusicHits = 0
        youtubeHits = 0
    }
}

private object AiCoverSessionStore {
    private val sessions = ConcurrentHashMap<String, AiCoverSession>()

    fun get(key: String): AiCoverSession {
        if (sessions.size > 12 && !sessions.containsKey(key)) {
            sessions.keys.firstOrNull()?.let(sessions::remove)
        }
        return sessions.getOrPut(key) { AiCoverSession() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoverSearchScreen(
    request: CoverSearchRequest,
    navController: NavHostController,
) {
    val title = request.title
    val originalArtist = request.originalArtist
    val currentYouTubeId = request.currentYouTubeId
    val sessionKey = currentYouTubeId?.takeIf { it.isNotBlank() }
        ?: "${title.trim()}|${originalArtist.trim()}"
    val session = remember(sessionKey) { AiCoverSessionStore.get(sessionKey) }

    val playerConnection = LocalPlayerConnection.current
    val service = playerConnection?.service
    val menuState = LocalMenuState.current

    val aiProvider by rememberPreference(AiProviderKey, "OpenRouter")
    val sharedApiKey by rememberPreference(OpenRouterApiKey, "")
    val sharedModel by rememberPreference(OpenRouterModelKey, "")
    var dedicatedGeminiKey by rememberPreference(CoverAiFirstGeminiApiKey, "")

    val sharedGoogleKey = sharedApiKey.takeIf {
        it.isNotBlank() && (aiProvider == "Gemini" || it.trim().startsWith("AIza"))
    }.orEmpty()
    val effectiveKey = dedicatedGeminiKey.ifBlank { sharedGoogleKey }
    val effectiveModel = if (
        aiProvider == "Gemini" && sharedModel.isNotBlank() && !sharedModel.contains('/')
    ) {
        sharedModel
    } else {
        "gemini-3.5-flash-lite"
    }
    val geminiConfig = effectiveKey.takeIf { it.isNotBlank() }?.let {
        GeminiCoverVerificationConfig(apiKey = it, model = effectiveModel)
    }

    var selectedTab by remember(sessionKey) { mutableStateOf(AiCoverTab.COVER) }
    var initialLoading by remember(sessionKey) { mutableStateOf(!session.initialLoaded) }
    var backgroundLoading by remember(sessionKey) { mutableStateOf(session.initialLoaded && !session.backgroundComplete) }
    var failed by remember(sessionKey) { mutableStateOf(false) }
    var originalInfo by remember(sessionKey) { mutableStateOf(session.originalInfo) }
    var knownCandidates by remember(sessionKey) { mutableStateOf(session.knownCandidates) }
    var playables by remember(sessionKey) { mutableStateOf(session.playables) }

    var initialCandidateCount by remember(sessionKey) { mutableStateOf(session.initialCandidateCount) }
    var expandedCandidateCount by remember(sessionKey) { mutableStateOf(session.expandedCandidateCount) }
    var initialPlayableCount by remember(sessionKey) { mutableStateOf(session.initialPlayableCount) }
    var youtubeMusicHits by remember(sessionKey) { mutableStateOf(session.youtubeMusicHits) }
    var youtubeHits by remember(sessionKey) { mutableStateOf(session.youtubeHits) }

    var startingSong by remember(sessionKey) { mutableStateOf<SongItem?>(null) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }

    LaunchedEffect(currentYouTubeId) {
        val id = currentYouTubeId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        startingSong = withContext(Dispatchers.IO) {
            YouTube.queue(listOf(id)).getOrNull()?.firstOrNull()
        }
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("Gemini per Cerca cover") },
            text = {
                Column {
                    Text(
                        "L'AI decide direttamente cover, remix, live, anno, album e crediti. YouTube e YouTube Music servono soltanto alla riproduzione.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        label = { Text("Chiave API Google AI") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dedicatedGeminiKey = keyDraft.trim()
                    session.invalidate()
                    initialLoading = true
                    backgroundLoading = false
                    originalInfo = null
                    knownCandidates = emptyList()
                    playables = emptyList()
                    showKeyDialog = false
                }) { Text("Salva") }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("Annulla") }
            },
        )
    }

    LaunchedEffect(
        sessionKey,
        title,
        originalArtist,
        currentYouTubeId,
        effectiveKey,
        effectiveModel,
    ) {
        val config = geminiConfig
        if (config == null) {
            initialLoading = false
            backgroundLoading = false
            return@LaunchedEffect
        }

        failed = false

        if (!session.initialLoaded) {
            initialLoading = true
            val discovery = runCatching {
                GeminiAiCoverDiscovery.discoverInitial(
                    originalTitle = title,
                    originalArtist = originalArtist,
                    config = config,
                )
            }.onFailure { failed = true }
                .getOrDefault(AiCoverDiscoveryResult(null, emptyList()))

            originalInfo = discovery.original ?: AiCoverOriginalInfo(
                title = title,
                artist = originalArtist,
            )
            knownCandidates = discovery.versions
            initialCandidateCount = discovery.versions.size

            val resolved = runCatching {
                AiCoverSearchEngine.resolveCandidates(
                    candidates = discovery.versions,
                    currentYouTubeId = currentYouTubeId,
                    pauseBetweenBatches = false,
                )
            }.onFailure { failed = true }
                .getOrDefault(AiCoverResolveResult(emptyList(), AiCoverResolveStats()))

            playables = mergePlayables(emptyList(), resolved.playables)
            initialPlayableCount = playables.size
            youtubeMusicHits += resolved.stats.youtubeMusicHits
            youtubeHits += resolved.stats.youtubeHits

            session.initialLoaded = true
            session.originalInfo = originalInfo
            session.knownCandidates = knownCandidates
            session.playables = playables
            session.initialCandidateCount = initialCandidateCount
            session.initialPlayableCount = initialPlayableCount
            session.youtubeMusicHits = youtubeMusicHits
            session.youtubeHits = youtubeHits
            initialLoading = false
        } else {
            originalInfo = session.originalInfo
            knownCandidates = session.knownCandidates
            playables = session.playables
            initialCandidateCount = session.initialCandidateCount
            expandedCandidateCount = session.expandedCandidateCount
            initialPlayableCount = session.initialPlayableCount
            youtubeMusicHits = session.youtubeMusicHits
            youtubeHits = session.youtubeHits
        }

        if (session.backgroundComplete) {
            backgroundLoading = false
            return@LaunchedEffect
        }

        backgroundLoading = true
        val expanded = runCatching {
            GeminiAiCoverDiscovery.discoverExpanded(
                originalTitle = originalInfo?.title?.ifBlank { title } ?: title,
                originalArtist = originalInfo?.artist?.ifBlank { originalArtist } ?: originalArtist,
                existing = knownCandidates,
                config = config,
            )
        }.onFailure { failed = true }
            .getOrDefault(AiCoverDiscoveryResult(null, emptyList()))

        val newCandidates = expanded.versions
            .filter { candidate -> knownCandidates.none { it.stableKey == candidate.stableKey } }
        expandedCandidateCount = newCandidates.size
        knownCandidates = (knownCandidates + newCandidates).distinctBy { it.stableKey }
        session.knownCandidates = knownCandidates
        session.expandedCandidateCount = expandedCandidateCount

        for (batch in newCandidates.chunked(8)) {
            val resolved = runCatching {
                AiCoverSearchEngine.resolveCandidates(
                    candidates = batch,
                    currentYouTubeId = currentYouTubeId,
                    pauseBetweenBatches = true,
                )
            }.onFailure { failed = true }
                .getOrDefault(AiCoverResolveResult(emptyList(), AiCoverResolveStats()))

            playables = mergePlayables(playables, resolved.playables)
            youtubeMusicHits += resolved.stats.youtubeMusicHits
            youtubeHits += resolved.stats.youtubeHits

            session.playables = playables
            session.youtubeMusicHits = youtubeMusicHits
            session.youtubeHits = youtubeHits
        }

        session.backgroundComplete = true
        backgroundLoading = false
    }

    fun play(song: SongItem) {
        val connection = playerConnection ?: return
        connection.playNext(song.toMediaItem())
        connection.seekToNext()
        PlayerBottomSheetBridge.expandSoft()
    }

    fun replaceWith(song: SongItem) {
        val sourceId = currentYouTubeId?.takeIf { it.isNotBlank() } ?: return
        service?.setYouTubeMatchOverride(
            sourceId,
            YouTubeMatchOverride(
                videoId = song.id,
                title = song.title,
                artist = song.artists.joinToString(", ") { it.name },
                thumbnail = song.thumbnail,
            ),
        )
        navController.popBackStack()
    }

    val coverResults = playables.filter { it.candidate.category == AiCoverCategory.COVER }
    val remixResults = playables.filter { it.candidate.category == AiCoverCategory.REMIX }
    val liveResults = playables.filter { it.candidate.category == AiCoverCategory.LIVE }

    if (showDiagnosticsDialog) {
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("Verifica ricerca Cover") },
            text = {
                Column {
                    Text(
                        if (geminiConfig != null) "Gemini AI: ok · decisione diretta" else "Gemini AI: non configurata",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(7.dp))
                    Text("Candidati AI iniziali: $initialCandidateCount", style = MaterialTheme.typography.bodySmall)
                    Text("Riproducibili mostrati subito: $initialPlayableCount", style = MaterialTheme.typography.bodySmall)
                    Text("Candidati AI aggiuntivi: $expandedCandidateCount", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        if (backgroundLoading) "Ricerca estesa: in background" else "Ricerca estesa: completata",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(7.dp))
                    Text("Cover: ${coverResults.size} · Remix: ${remixResults.size} · Live: ${liveResults.size}")
                    Text("Riproduzione YouTube Music: $youtubeMusicHits", style = MaterialTheme.typography.bodySmall)
                    Text("Riproduzione YouTube: $youtubeHits", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "Anno, album e crediti arrivano esclusivamente dall'AI. YouTube e YouTube Music servono solo a localizzare la riproduzione.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnosticsDialog = false }) { Text("Chiudi") }
            },
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Cover",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showDiagnosticsDialog = true }) { Text("ⓘ") }
                TextButton(onClick = { navController.popBackStack() }) { Text("Chiudi") }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                originalInfo?.let { info ->
                    item {
                        CoverSectionTitle("Identificato dall'AI")
                        Text(
                            text = "${info.title.ifBlank { title }} · ${info.artist.ifBlank { originalArtist }}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = info.year?.let { "Prima pubblicazione: $it" }
                                ?: "Prima pubblicazione: anno non indicato dall'AI",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        AiCoverCredits(
                            year = info.year,
                            album = info.album,
                            songwriters = info.songwriters,
                            composers = info.composers,
                            lyricists = info.lyricists,
                            producers = info.producers,
                            label = info.label,
                        )
                        Text(
                            text = "L'AI decide direttamente cover, remix, live, anno, album e crediti. YouTube e YouTube Music servono esclusivamente a trovare le versioni riproducibili.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp, bottom = 12.dp),
                        )
                    }
                }

                startingSong?.let { originalSong ->
                    item {
                        CoverSectionTitle("Originale di partenza")
                        CoverStartRow(
                            info = originalInfo,
                            song = originalSong,
                            onPlay = { play(originalSong) },
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }

                if (initialLoading) {
                    item {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("L'AI cerca le prime cover da studio…")
                        }
                    }
                } else if (geminiConfig == null) {
                    item {
                        Text(
                            "Gemini AI non è configurata. Cerca cover funziona esclusivamente con l'intelligenza artificiale.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            keyDraft = dedicatedGeminiKey.ifBlank { sharedGoogleKey }
                            showKeyDialog = true
                        }) { Text("Chiave AI") }
                    }
                } else {
                    item {
                        CoverSectionTitle("Versioni della stessa canzone")
                        CoverResultsTabs(
                            selected = selectedTab,
                            coverCount = coverResults.size,
                            remixCount = remixResults.size,
                            liveCount = liveResults.size,
                            onSelected = { selectedTab = it },
                        )

                        if (backgroundLoading) {
                            Row(
                                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Altre versioni stanno arrivando in background…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Text(
                                text = "Cover = incisioni in studio · Remix = remix/rework · Live = esecuzioni dal vivo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 7.dp, bottom = 6.dp),
                            )
                        }
                    }

                    val visible = when (selectedTab) {
                        AiCoverTab.COVER -> coverResults
                        AiCoverTab.REMIX -> remixResults
                        AiCoverTab.LIVE -> liveResults
                    }

                    if (visible.isNotEmpty()) {
                        items(
                            items = visible,
                            key = { "${selectedTab.name}-${it.candidate.stableKey}-${it.song.id}" },
                        ) { result ->
                            AiCoverResultRow(
                                result = result,
                                onPlay = { play(result.song) },
                                onReplace = { replaceWith(result.song) },
                                onLongClick = {
                                    menuState.show {
                                        YouTubeSongMenu(
                                            song = result.song,
                                            navController = navController,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    } else if (backgroundLoading) {
                        item {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Cerco altre versioni in background…")
                            }
                        }
                    } else if (failed) {
                        item {
                            Text("La ricerca AI non è riuscita. Apri ⓘ per il feedback.")
                        }
                    } else {
                        item {
                            Text(
                                when (selectedTab) {
                                    AiCoverTab.COVER -> "L'AI non ha trovato altre cover da studio riproducibili."
                                    AiCoverTab.REMIX -> "Nessun remix riproducibile indicato dall'AI."
                                    AiCoverTab.LIVE -> "Nessuna versione live riproducibile indicata dall'AI."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun CoverResultsTabs(
    selected: AiCoverTab,
    coverCount: Int,
    remixCount: Int,
    liveCount: Int,
    onSelected: (AiCoverTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CoverResultChip(
            label = "Cover",
            count = coverCount,
            selected = selected == AiCoverTab.COVER,
            onClick = { onSelected(AiCoverTab.COVER) },
        )
        if (remixCount > 0) {
            CoverResultChip(
                label = "Remix",
                count = remixCount,
                selected = selected == AiCoverTab.REMIX,
                onClick = { onSelected(AiCoverTab.REMIX) },
            )
        }
        if (liveCount > 0) {
            CoverResultChip(
                label = "Live",
                count = liveCount,
                selected = selected == AiCoverTab.LIVE,
                onClick = { onSelected(AiCoverTab.LIVE) },
            )
        }
    }
}

@Composable
private fun CoverResultChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = {}),
    ) {
        Text(
            text = "$label · $count",
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CoverSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverStartRow(
    info: AiCoverOriginalInfo?,
    song: SongItem,
    onPlay: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPlay, onLongClick = {})
            .padding(vertical = 6.dp),
    ) {
        AsyncImage(
            model = song.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                info?.title?.ifBlank { song.title } ?: song.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                info?.artist?.ifBlank { song.artists.joinToString(", ") { it.name } }
                    ?: song.artists.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            AiCoverCredits(
                year = info?.year,
                album = info?.album,
                songwriters = info?.songwriters.orEmpty(),
                composers = info?.composers.orEmpty(),
                lyricists = info?.lyricists.orEmpty(),
                producers = info?.producers.orEmpty(),
                label = info?.label,
            )
            Text(
                "Tocca la locandina per ascoltare",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiCoverResultRow(
    result: AiCoverPlayable,
    onPlay: () -> Unit,
    onReplace: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPlay, onLongClick = onLongClick)
            .padding(vertical = 6.dp),
    ) {
        AsyncImage(
            model = result.song.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                result.candidate.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                result.candidate.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = result.candidate.year?.let { "Anno AI: $it" } ?: "Anno AI non disponibile",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            result.candidate.album?.let { album ->
                Text(
                    text = "Album: $album",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AiCoverCreditsWithoutYearAlbum(
                songwriters = result.candidate.songwriters,
                composers = result.candidate.composers,
                lyricists = result.candidate.lyricists,
                producers = result.candidate.producers,
                label = result.candidate.label,
            )
            Text(
                "Riproduzione: ${result.playbackSource}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Tocca la locandina per ascoltare",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onReplace,
            modifier = Modifier
                .padding(start = 8.dp)
                .height(36.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        ) {
            Text(
                "Sostituisci",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun AiCoverCredits(
    year: Int?,
    album: String?,
    songwriters: List<String>,
    composers: List<String>,
    lyricists: List<String>,
    producers: List<String>,
    label: String?,
) {
    year?.let {
        Text(
            "Anno AI: $it",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    album?.let {
        Text(
            "Album: $it",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    AiCoverCreditsWithoutYearAlbum(
        songwriters = songwriters,
        composers = composers,
        lyricists = lyricists,
        producers = producers,
        label = label,
    )
}

@Composable
private fun AiCoverCreditsWithoutYearAlbum(
    songwriters: List<String>,
    composers: List<String>,
    lyricists: List<String>,
    producers: List<String>,
    label: String?,
) {
    if (songwriters.isNotEmpty()) {
        Text("Autori: ${songwriters.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (composers.isNotEmpty()) {
        Text("Compositori: ${composers.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (lyricists.isNotEmpty()) {
        Text("Parolieri: ${lyricists.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (producers.isNotEmpty()) {
        Text("Produttori: ${producers.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    label?.let {
        Text("Etichetta: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun mergePlayables(
    current: List<AiCoverPlayable>,
    incoming: List<AiCoverPlayable>,
): List<AiCoverPlayable> {
    val merged = linkedMapOf<String, AiCoverPlayable>()
    (current + incoming).forEach { item ->
        merged.putIfAbsent(item.song.id, item)
    }
    return merged.values.sortedWith(
        compareBy<AiCoverPlayable> { if (it.candidate.year == null) 1 else 0 }
            .thenBy { it.candidate.year ?: Int.MAX_VALUE }
            .thenBy { it.candidate.artist.lowercase() },
    )
}
