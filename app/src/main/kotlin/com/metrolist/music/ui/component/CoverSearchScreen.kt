/**
 * MusicLab AI-first cover search screen.
 * Gemini decides cover/remix/live versions and all editorial credits.
 * YouTube/YouTube Music are used only to locate playable media.
 */
package com.metrolist.music.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.extensions.toMediaItem
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
    val menuState = LocalMenuState.current
    val density = LocalDensity.current
    val closeThresholdPx = with(density) { 64.dp.toPx() }
    var handleDrag by remember { mutableFloatStateOf(0f) }

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

    val coverResults = playables.filter { it.candidate.category == AiCoverCategory.COVER }
    val remixResults = playables.filter { it.candidate.category == AiCoverCategory.REMIX }
    val liveResults = playables.filter { it.candidate.category == AiCoverCategory.LIVE }

    if (showDiagnosticsDialog) {
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("Verifica Cerca cover AI") },
            text = {
                Column {
                    Text(
                        if (geminiConfig != null) "Gemini AI: attiva · decisione diretta" else "Gemini AI: non configurata",
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
                    )
                    Spacer(Modifier.height(7.dp))
                    Text("Cover: ${coverResults.size} · Remix: ${remixResults.size} · Live: ${liveResults.size}")
                    Text("Riproduzione YouTube Music: $youtubeMusicHits", style = MaterialTheme.typography.bodySmall)
                    Text("Riproduzione YouTube: $youtubeHits", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "Anno, album e crediti arrivano esclusivamente dall'AI. Nessun database esterno decide o conferma i risultati.",
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .pointerInput(closeThresholdPx) {
                        detectVerticalDragGestures(
                            onDragStart = { handleDrag = 0f },
                            onVerticalDrag = { _, dragAmount -> if (dragAmount > 0f) handleDrag += dragAmount },
                            onDragCancel = { handleDrag = 0f },
                            onDragEnd = {
                                if (handleDrag >= closeThresholdPx) navController.popBackStack()
                                handleDrag = 0f
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 42.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Cerca cover",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showDiagnosticsDialog = true }) { Text("ⓘ") }
                TextButton(onClick = { navController.popBackStack() }) { Text("Chiudi") }
            }

            Text(
                text = "$title · $originalArtist",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (geminiConfig != null) "AI: attiva" else "AI: non configurata",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (geminiConfig != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    keyDraft = dedicatedGeminiKey.ifBlank { sharedGoogleKey }
                    showKeyDialog = true
                }) { Text("Chiave AI") }
            }

            originalInfo?.let { info ->
                OriginalStartCard(
                    info = info,
                    song = startingSong,
                    onPlay = { startingSong?.let(::play) },
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                FilterChip(
                    selected = selectedTab == AiCoverTab.COVER,
                    onClick = { selectedTab = AiCoverTab.COVER },
                    label = { Text("Cover · ${coverResults.size}", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = selectedTab == AiCoverTab.REMIX,
                    onClick = { selectedTab = AiCoverTab.REMIX },
                    label = { Text("Remix · ${remixResults.size}", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = selectedTab == AiCoverTab.LIVE,
                    onClick = { selectedTab = AiCoverTab.LIVE },
                    label = { Text("Live · ${liveResults.size}", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f),
                )
            }

            if (initialLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text("L'AI cerca le prime versioni…")
                    }
                }
                return@Column
            }

            if (geminiConfig == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Configura Gemini: Cerca cover ora funziona esclusivamente con l'intelligenza artificiale.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            if (backgroundLoading) {
                Row(
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "I primi risultati sono pronti · altre versioni arrivano in background…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.height(6.dp))
            }

            val visible = when (selectedTab) {
                AiCoverTab.COVER -> coverResults
                AiCoverTab.REMIX -> remixResults
                AiCoverTab.LIVE -> liveResults
            }

            when {
                visible.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        items(visible, key = { "${it.candidate.stableKey}-${it.song.id}" }) { result ->
                            AiCoverResultRow(
                                result = result,
                                onPlay = { play(result.song) },
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
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }

                backgroundLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Cerco altre versioni in background…")
                    }
                }

                failed -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("La ricerca AI non è riuscita. Apri ⓘ per il feedback.")
                    }
                }

                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (selectedTab) {
                                AiCoverTab.COVER -> "L'AI non conosce altre cover riproducibili per questo brano."
                                AiCoverTab.REMIX -> "Nessun remix riproducibile indicato dall'AI."
                                AiCoverTab.LIVE -> "Nessuna versione live riproducibile indicata dall'AI."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OriginalStartCard(
    info: AiCoverOriginalInfo,
    song: SongItem?,
    onPlay: () -> Unit,
) {
    Text(
        "Originale di partenza",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 5.dp),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPlay, onLongClick = {}),
    ) {
        song?.let {
            AsyncImage(
                model = it.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(7.dp)),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                info.title.ifBlank { song?.title.orEmpty() },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                info.artist.ifBlank { song?.artists?.joinToString(", ") { it.name }.orEmpty() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                "Crediti: AI",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiCoverResultRow(
    result: AiCoverPlayable,
    onPlay: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPlay, onLongClick = onLongClick)
            .padding(vertical = 7.dp),
    ) {
        AsyncImage(
            model = result.song.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(ListThumbnailSize)
                .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                result.candidate.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                result.candidate.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AiCoverCredits(
                year = result.candidate.year,
                album = result.candidate.album,
                songwriters = result.candidate.songwriters,
                composers = result.candidate.composers,
                lyricists = result.candidate.lyricists,
                producers = result.candidate.producers,
                label = result.candidate.label,
            )
            Text(
                "Crediti: AI · Riproduzione: ${result.playbackSource}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Tocca la locandina per ascoltare",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Text("Anno AI: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
