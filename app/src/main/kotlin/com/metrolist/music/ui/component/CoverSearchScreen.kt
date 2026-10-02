/**
 * MusicLab AI-first cover search screen.
 * AI owns all editorial metadata. YouTube/YouTube Music only locate playable media.
 */
package com.metrolist.music.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.metrolist.music.constants.DEFAULT_MUSIC_AI_CLOUD_ENDPOINT
import com.metrolist.music.constants.MusicAiCoverEnabledKey
import com.metrolist.music.constants.MusicAiCloudEndpointKey
import com.metrolist.music.constants.MusicAiCloudMemoryEnabledKey
import com.metrolist.music.constants.MusicAiEngineEnabledKey
import com.metrolist.music.constants.MusicAiForeignEnabledKey
import com.metrolist.music.constants.MusicAiLiveEnabledKey
import com.metrolist.music.constants.MusicAiRemixEnabledKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.YouTubeMatchOverride
import com.metrolist.music.ui.menu.YouTubeSongMenu
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val CoverAiFirstGeminiApiKey = stringPreferencesKey("coverGeminiApiKey")
private const val COVER_PAGE_SIZE = 10
private const val MIN_COVER_PLAYABLE_TARGET = 50
private const val MAX_COVER_RECOVERY_ROUNDS = 6

private enum class AiCoverTab {
    COVER,
    LIVE,
    REMIX,
    FOREIGN,
    ALL,
}

private class AiCoverSession {
    var initialLoaded: Boolean = false
    var backgroundComplete: Boolean = false
    var originalInfo: AiCoverOriginalInfo? = null
    var sourceEvidence: AiCoverSourceEvidence? = null
    var knownCandidates: List<AiCoverCandidate> = emptyList()
    var playables: List<AiCoverPlayable> = emptyList()
    var initialCandidateCount: Int = 0
    var expandedCandidateCount: Int = 0
    var initialPlayableCount: Int = 0
    var youtubeMusicHits: Int = 0
    var youtubeHits: Int = 0
    var spotifyAvailable: Boolean = false
    var spotifyDiscovered: Int = 0
    var spotifyEnriched: Int = 0

    fun invalidate() {
        initialLoaded = false
        backgroundComplete = false
        originalInfo = null
        sourceEvidence = null
        knownCandidates = emptyList()
        playables = emptyList()
        initialCandidateCount = 0
        expandedCandidateCount = 0
        initialPlayableCount = 0
        youtubeMusicHits = 0
        youtubeHits = 0
        spotifyAvailable = false
        spotifyDiscovered = 0
        spotifyEnriched = 0
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

    val aiMasterEnabled by rememberPreference(MusicAiEngineEnabledKey, true)
    val coverAiEnabled by rememberPreference(MusicAiCoverEnabledKey, true)
    val liveAiEnabled by rememberPreference(MusicAiLiveEnabledKey, true)
    val remixAiEnabled by rememberPreference(MusicAiRemixEnabledKey, true)
    val foreignAiEnabled by rememberPreference(MusicAiForeignEnabledKey, true)
    val cloudMemoryEnabled by rememberPreference(MusicAiCloudMemoryEnabledKey, true)
    val cloudEndpoint by rememberPreference(MusicAiCloudEndpointKey, DEFAULT_MUSIC_AI_CLOUD_ENDPOINT)
    val effectiveCloudEndpoint = cloudEndpoint.trim().ifBlank { DEFAULT_MUSIC_AI_CLOUD_ENDPOINT }

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
    val geminiConfig = if (effectiveKey.isNotBlank() || effectiveCloudEndpoint.isNotBlank()) {
        GeminiCoverVerificationConfig(
            apiKey = effectiveKey,
            model = effectiveModel,
            cloudEndpoint = effectiveCloudEndpoint,
            useCloudMemory = cloudMemoryEnabled,
        )
    } else {
        null
    }

    var selectedTab by remember(sessionKey) { mutableStateOf(AiCoverTab.COVER) }
    var selectedCoverageMode by remember(sessionKey) { mutableStateOf(AiCoverageMode.DEFAULT) }
    var visibleResultCount by remember(sessionKey, selectedTab, selectedCoverageMode) { mutableIntStateOf(COVER_PAGE_SIZE) }
    val listState = rememberLazyListState()
    var initialLoading by remember(sessionKey) { mutableStateOf(!session.initialLoaded) }
    var backgroundLoading by remember(sessionKey) { mutableStateOf(session.initialLoaded && !session.backgroundComplete) }
    var allModeResolving by remember(sessionKey) { mutableStateOf(false) }
    var failed by remember(sessionKey) { mutableStateOf(false) }
    var originalInfo by remember(sessionKey) { mutableStateOf(session.originalInfo) }
    var knownCandidates by remember(sessionKey) { mutableStateOf(session.knownCandidates) }
    var playables by remember(sessionKey) { mutableStateOf(session.playables) }

    var initialCandidateCount by remember(sessionKey) { mutableStateOf(session.initialCandidateCount) }
    var expandedCandidateCount by remember(sessionKey) { mutableStateOf(session.expandedCandidateCount) }
    var initialPlayableCount by remember(sessionKey) { mutableStateOf(session.initialPlayableCount) }
    var youtubeMusicHits by remember(sessionKey) { mutableStateOf(session.youtubeMusicHits) }
    var youtubeHits by remember(sessionKey) { mutableStateOf(session.youtubeHits) }
    var spotifyAvailable by remember(sessionKey) { mutableStateOf(session.spotifyAvailable) }
    var spotifyDiscovered by remember(sessionKey) { mutableStateOf(session.spotifyDiscovered) }
    var spotifyEnriched by remember(sessionKey) { mutableStateOf(session.spotifyEnriched) }

    var startingSong by remember(sessionKey) { mutableStateOf<SongItem?>(null) }
    var startingYear by remember(sessionKey) { mutableStateOf<Int?>(null) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    var detailResult by remember(sessionKey) { mutableStateOf<AiCoverPlayable?>(null) }
    var detailCredits by remember(sessionKey) { mutableStateOf<GeminiVersionCredits?>(null) }
    var detailLoading by remember(sessionKey) { mutableStateOf(false) }
    val brainReviewScope = rememberCoroutineScope()

    fun categoryEnabled(category: AiCoverCategory): Boolean = when (category) {
        AiCoverCategory.COVER -> true
        AiCoverCategory.LIVE -> liveAiEnabled
        AiCoverCategory.REMIX -> remixAiEnabled
        AiCoverCategory.FOREIGN -> foreignAiEnabled
    }

    LaunchedEffect(currentYouTubeId) {
        val id = currentYouTubeId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        startingSong = withContext(Dispatchers.IO) {
            YouTube.queue(listOf(id)).getOrNull()?.firstOrNull()
        }
        startingYear = startingSong?.let { song ->
            withContext(Dispatchers.IO) { CoverYearResolver.resolve(song) }
        }
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("Gemini per Cerca cover") },
            text = {
                Column {
                    Text(
                        "L'AI decide direttamente cover, live, remix, versioni straniere e metadati. YouTube e YouTube Music servono soltanto alla riproduzione.",
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
                    visibleResultCount = COVER_PAGE_SIZE
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
        aiMasterEnabled,
        coverAiEnabled,
        liveAiEnabled,
        remixAiEnabled,
        foreignAiEnabled,
        cloudMemoryEnabled,
        cloudEndpoint,
    ) {
        if (!aiMasterEnabled || !coverAiEnabled) {
            initialLoading = false
            backgroundLoading = false
            return@LaunchedEffect
        }

        val config = geminiConfig
        if (config == null) {
            initialLoading = false
            backgroundLoading = false
            return@LaunchedEffect
        }

        failed = false

        val sourceEvidence = session.sourceEvidence ?: withContext(Dispatchers.IO) {
            AiCoverSourceEvidence.fromMusicBrainz(
                runCatching { MusicBrainzCoverSource.lookup(title, originalArtist) }
                    .getOrElse { MusicBrainzLookup(emptyList(), MusicBrainzStatus.NETWORK_ERROR) },
            )
        }.also { session.sourceEvidence = it }

        if (!session.initialLoaded) {
            initialLoading = true
            val discovery = runCatching {
                AiCoverFlowResolver.discoverInitial(
                    originalTitle = title,
                    originalArtist = originalArtist,
                    config = config,
                    sourceEvidence = sourceEvidence,
                )
            }.onFailure { failed = true }
                .getOrDefault(AiCoverDiscoveryResult(null, emptyList()))

            val resolvedOriginalInfo =
                discovery.original ?: AiCoverOriginalInfo(title = title, artist = originalArtist)
            val initialWithEvidence = AiCoverFlowResolver.applyEvidencePolicy(
                originalTitle = resolvedOriginalInfo.title.ifBlank { title },
                originalInfo = resolvedOriginalInfo,
                candidates = sourceEvidence.attachToAiAccepted(discovery.versions),
            )
            originalInfo = resolvedOriginalInfo
            knownCandidates = initialWithEvidence
            initialCandidateCount = initialWithEvidence.size

            val resolved = runCatching {
                AiCoverSearchEngine.resolveCandidates(
                    candidates = initialWithEvidence.filter { it.brainStatus != AiBrainDecisionStatus.REJECTED },
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
            spotifyAvailable = session.spotifyAvailable
            spotifyDiscovered = session.spotifyDiscovered
            spotifyEnriched = session.spotifyEnriched
        }

        if (session.backgroundComplete) {
            backgroundLoading = false
            return@LaunchedEffect
        }

        backgroundLoading = true
        runCatching {
            AiCoverFlowResolver.discoverExpandedBatches(
                originalTitle = originalInfo?.title?.ifBlank { title } ?: title,
                originalArtist = originalInfo?.artist?.ifBlank { originalArtist } ?: originalArtist,
                existing = knownCandidates,
                config = config,
                sourceEvidence = sourceEvidence,
            ) { discoveredBatch ->
                val evidencedBatch = AiCoverFlowResolver.applyEvidencePolicy(
                    originalTitle = originalInfo?.title?.ifBlank { title } ?: title,
                    originalInfo = originalInfo,
                    candidates = sourceEvidence.attachToAiAccepted(discoveredBatch),
                )
                val enabledBatch = evidencedBatch.filter { candidate -> categoryEnabled(candidate.category) }
                if (enabledBatch.isEmpty()) return@discoverExpandedBatches

                withContext(Dispatchers.Main) {
                    val fresh = enabledBatch.filter { candidate ->
                        knownCandidates.none { it.stableKey == candidate.stableKey }
                    }
                    if (fresh.isNotEmpty()) {
                        knownCandidates = (knownCandidates + fresh).distinctBy { it.stableKey }
                        expandedCandidateCount += fresh.size
                        session.knownCandidates = knownCandidates
                        session.expandedCandidateCount = expandedCandidateCount
                    }
                }

                val toResolve = enabledBatch.filter { candidate ->
                    candidate.brainStatus != AiBrainDecisionStatus.REJECTED &&
                        playables.none { it.candidate.stableKey == candidate.stableKey }
                }
                if (toResolve.isEmpty()) return@discoverExpandedBatches

                val resolved = AiCoverSearchEngine.resolveCandidates(
                    candidates = toResolve,
                    currentYouTubeId = currentYouTubeId,
                    pauseBetweenBatches = false,
                )

                withContext(Dispatchers.Main) {
                    playables = mergePlayables(playables, resolved.playables)
                    youtubeMusicHits += resolved.stats.youtubeMusicHits
                    youtubeHits += resolved.stats.youtubeHits
                    session.playables = playables
                    session.youtubeMusicHits = youtubeMusicHits
                    session.youtubeHits = youtubeHits
                }
            }
        }.onFailure { failed = true }

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
                AiCoverFlowResolver.discoverRecoveryBatch(
                    originalTitle = originalInfo?.title?.ifBlank { title } ?: title,
                    originalArtist = originalInfo?.artist?.ifBlank { originalArtist } ?: originalArtist,
                    existing = knownCandidates,
                    config = config,
                    round = round,
                )
            }.getOrDefault(emptyList())
            val recoveredWithEvidence = AiCoverFlowResolver.applyEvidencePolicy(
                originalTitle = originalInfo?.title?.ifBlank { title } ?: title,
                originalInfo = originalInfo,
                candidates = sourceEvidence.attachToAiAccepted(recovered),
            )
                .filter { categoryEnabled(it.category) }
                .filter { candidate -> knownCandidates.none { it.stableKey == candidate.stableKey } }

            if (recoveredWithEvidence.isNotEmpty()) {
                knownCandidates = (knownCandidates + recoveredWithEvidence).distinctBy { it.stableKey }
                expandedCandidateCount += recoveredWithEvidence.size
                session.knownCandidates = knownCandidates
                session.expandedCandidateCount = expandedCandidateCount

                val resolved = runCatching {
                    AiCoverSearchEngine.resolveCandidates(
                        candidates = recoveredWithEvidence.filter { it.brainStatus != AiBrainDecisionStatus.REJECTED },
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
        backgroundLoading = false
    }

    fun play(song: SongItem) {
        val connection = playerConnection ?: return
        connection.playNext(song.toMediaItem())
        connection.seekToNext()
        PlayerBottomSheetBridge.collapseToMiniPlayerNow()
    }

    fun replaceWith(result: AiCoverPlayable) {
        val sourceId = currentYouTubeId?.takeIf { it.isNotBlank() } ?: return
        service?.setYouTubeMatchOverride(
            sourceId,
            YouTubeMatchOverride(
                videoId = result.song.id,
                title = result.candidate.title,
                artist = result.candidate.artist,
                thumbnail = result.song.thumbnail,
            ),
        )
        PlayerBottomSheetBridge.collapseToMiniPlayerNow()
        navController.popBackStack()
    }

    fun updateBrainCandidate(updated: AiCoverCandidate) {
        knownCandidates = knownCandidates.map { candidate ->
            if (candidate.stableKey == updated.stableKey) updated else candidate
        }
        playables = playables.map { playable ->
            if (playable.candidate.stableKey == updated.stableKey) playable.copy(candidate = updated) else playable
        }
        session.knownCandidates = knownCandidates
        session.playables = playables
    }

    fun saveBrainDecision(result: AiCoverPlayable, status: AiBrainDecisionStatus) {
        val previousStatus = result.candidate.brainStatus
        val optimisticCandidate = result.candidate.copy(brainStatus = status)

        // LAB22: the UI decision is immediate. Persistence follows in background.
        updateBrainCandidate(optimisticCandidate)

        val config = geminiConfig ?: return
        val sourceTitle = originalInfo?.title?.ifBlank { title } ?: title
        val sourceArtist = originalInfo?.artist?.ifBlank { originalArtist } ?: originalArtist
        brainReviewScope.launch {
            val saved = CloudMusicDiscovery.saveBrainDecision(
                originalTitle = sourceTitle,
                originalArtist = sourceArtist,
                candidate = optimisticCandidate,
                status = status,
                config = config,
            )
            if (!saved) {
                updateBrainCandidate(result.candidate.copy(brainStatus = previousStatus))
                failed = true
            }
        }
    }

    fun verifyCandidateBetter(result: AiCoverPlayable) {
        val config = geminiConfig ?: return
        val sourceTitle = originalInfo?.title?.ifBlank { title } ?: title
        val sourceArtist = originalInfo?.artist?.ifBlank { originalArtist } ?: originalArtist
        brainReviewScope.launch {
            CloudMusicDiscovery.verifyCandidate(
                originalTitle = sourceTitle,
                originalArtist = sourceArtist,
                candidate = result.candidate,
                mode = "cover",
                config = config,
            )?.let(::updateBrainCandidate)
        }
    }

    val coverResults = playables.filter { it.candidate.category == AiCoverCategory.COVER }
    val liveResults = playables.filter { it.candidate.category == AiCoverCategory.LIVE && liveAiEnabled }
    val remixResults = playables.filter { it.candidate.category == AiCoverCategory.REMIX && remixAiEnabled }
    val foreignResults = playables.filter { it.candidate.category == AiCoverCategory.FOREIGN && foreignAiEnabled }
    val allResults = playables.filter { categoryEnabled(it.candidate.category) }
    val selectedTabResults = when (selectedTab) {
        AiCoverTab.COVER -> coverResults
        AiCoverTab.LIVE -> liveResults
        AiCoverTab.REMIX -> remixResults
        AiCoverTab.FOREIGN -> foreignResults
        AiCoverTab.ALL -> allResults
    }
    val coverageVisibleResults = AiCoverageFilter.visibleItems(
        selectedTabResults,
        selectedCoverageMode,
    ) { it.candidate }
    val confirmedResults = coverageVisibleResults.filter { it.candidate.brainStatus == AiBrainDecisionStatus.APPROVED }
    val reviewResults = coverageVisibleResults.filter { it.candidate.brainStatus == AiBrainDecisionStatus.UNCERTAIN }
    val selectedResults = coverageVisibleResults.filter {
        it.candidate.brainStatus != AiBrainDecisionStatus.UNCERTAIN &&
            it.candidate.brainStatus != AiBrainDecisionStatus.APPROVED
    }
    LaunchedEffect(
        sessionKey,
        selectedCoverageMode,
        backgroundLoading,
        knownCandidates.map { "${it.stableKey}:${it.brainStatus}" },
    ) {
        if (selectedCoverageMode != AiCoverageMode.ALL || backgroundLoading) {
            allModeResolving = false
            return@LaunchedEffect
        }

        val alreadyLocalized = playables.map { it.candidate.stableKey }.toSet()
        val unresolved = knownCandidates.filter { candidate ->
            candidate.brainStatus != AiBrainDecisionStatus.REJECTED &&
                categoryEnabled(candidate.category) &&
                candidate.stableKey !in alreadyLocalized
        }
        if (unresolved.isEmpty()) {
            allModeResolving = false
            return@LaunchedEffect
        }

        allModeResolving = true
        val exhaustive = runCatching {
            withContext(Dispatchers.IO) {
                AiCoverSearchEngine.resolveCandidates(
                    candidates = unresolved,
                    currentYouTubeId = currentYouTubeId,
                    pauseBetweenBatches = true,
                    exhaustive = true,
                )
            }
        }.getOrDefault(AiCoverResolveResult(emptyList(), AiCoverResolveStats()))

        playables = mergePlayables(playables, exhaustive.playables)
        youtubeMusicHits += exhaustive.stats.youtubeMusicHits
        youtubeHits += exhaustive.stats.youtubeHits
        session.playables = playables
        session.youtubeMusicHits = youtubeMusicHits
        session.youtubeHits = youtubeHits
        allModeResolving = false
    }

    val shouldLoadNextPage by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 1
        }
    }

    LaunchedEffect(shouldLoadNextPage, selectedTab, selectedResults.size) {
        if (shouldLoadNextPage && visibleResultCount < selectedResults.size) {
            visibleResultCount = minOf(visibleResultCount + COVER_PAGE_SIZE, selectedResults.size)
        }
    }

    LaunchedEffect(detailResult?.song?.id, geminiConfig, originalInfo?.title, originalInfo?.artist) {
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

    val locatorEligibleCount = knownCandidates.count { it.brainStatus != AiBrainDecisionStatus.REJECTED }
    val titleMismatchCount = knownCandidates.count {
        it.brainAdmission?.contains("title_meaning_mismatch") == true
    }
    val waitingEvidenceCount = knownCandidates.count {
        it.brainStatus == AiBrainDecisionStatus.UNCERTAIN &&
            (it.brainAdmission?.contains("waiting_same_work_evidence") == true)
    }
    val unresolvedLocatorCount = (locatorEligibleCount - playables.size).coerceAtLeast(0)

    if (showDiagnosticsDialog) {
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("Verifica ricerca Cover") },
            text = {
                Column {
                    Text(
                        when {
                            !aiMasterEnabled -> "Motore AI: disattivato"
                            !coverAiEnabled -> "Cover AI: disattivata"
                            effectiveCloudEndpoint.isNotBlank() && effectiveKey.isNotBlank() ->
                                "Resolver: Cloud MusicLab + Gemini diretto"
                            effectiveCloudEndpoint.isNotBlank() ->
                                "Resolver: Cloud MusicLab · Gemini diretto non configurato"
                            effectiveKey.isNotBlank() ->
                                "Resolver: Gemini diretto"
                            else -> "Resolver AI: non configurato"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(7.dp))
                    Text("Candidati iniziali: $initialCandidateCount", style = MaterialTheme.typography.bodySmall)
                    Text("Riproducibili mostrati subito: $initialPlayableCount", style = MaterialTheme.typography.bodySmall)
                    Text("Candidati aggiuntivi: $expandedCandidateCount", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (spotifyAvailable) {
                            "Spotify assist: +$spotifyDiscovered nuovi · $spotifyEnriched arricchiti"
                        } else {
                            "Spotify assist: non disponibile/non collegato"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Ammessi al locator: $locatorEligibleCount", style = MaterialTheme.typography.bodySmall)
                    Text("Respinti per titolo diverso: $titleMismatchCount", style = MaterialTheme.typography.bodySmall)
                    Text("Da verificare per evidenza: $waitingEvidenceCount", style = MaterialTheme.typography.bodySmall)
                    Text("Ammessi ma non localizzati: $unresolvedLocatorCount", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        if (backgroundLoading) "Ricerca estesa: in background" else "Ricerca estesa: completata",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(7.dp))
                    Text("Studio: ${coverResults.size} · Live: ${liveResults.size} · Remix: ${remixResults.size} · Straniere: ${foreignResults.size}")
                    Text("Riproduzione YouTube Music: $youtubeMusicHits", style = MaterialTheme.typography.bodySmall)
                    Text("Riproduzione YouTube: $youtubeHits", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "I metadati sono decisi dall'AI. YouTube e YouTube Music servono soltanto a localizzare la riproduzione.",
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

    detailResult?.let { selected ->
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
                Text("Cover", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { showDiagnosticsDialog = true }) { Text("ⓘ") }
                TextButton(onClick = { navController.popBackStack() }) { Text("Chiudi") }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
            ) {
                if (!aiMasterEnabled || !coverAiEnabled) {
                    item {
                        Text(
                            if (!aiMasterEnabled) {
                                "Il Motore AI MusicLab è disattivato. MusicLab continua a funzionare in modalità classica."
                            } else {
                                "La funzione Cover AI è disattivata nelle Impostazioni."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                } else {
                    startingSong?.let { originalSong ->
                        item {
                            CoverSectionTitle("Originale di partenza")
                            CoverStartRow(
                                info = originalInfo,
                                fallbackYear = startingYear,
                                song = originalSong,
                                onPlay = { play(originalSong) },
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    if (initialLoading) {
                        item {
                            Row(modifier = Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
                                liveCount = liveResults.size,
                                remixCount = remixResults.size,
                                foreignCount = foreignResults.size,
                                allCount = allResults.size,
                                liveEnabled = liveAiEnabled,
                                remixEnabled = remixAiEnabled,
                                foreignEnabled = foreignAiEnabled,
                                onSelected = { selectedTab = it },
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Copertura",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 5.dp),
                            )
                            AiCoverageSelector(
                                selected = selectedCoverageMode,
                                onSelected = { selectedCoverageMode = it },
                            )

                            if (backgroundLoading || allModeResolving) {
                                Row(
                                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (allModeResolving) "Tutto: cerco su YouTube tutte le versioni AI ancora non localizzate…" else "Altre versioni stanno arrivando in background…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                Text(
                                    text = "Studio = incisioni in studio · Live = dal vivo · Remix = remix/rework · Straniere = adattamenti in altra lingua · Tutto = tutte le categorie.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 7.dp, bottom = 6.dp),
                                )
                            }
                        }

                        if (confirmedResults.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                CoverSectionTitle("Confermate")
                                Text(
                                    "Versioni già approvate e inviate alla memoria MusicLab.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                            items(
                                items = confirmedResults,
                                key = { "confirmed-${selectedTab.name}-${it.candidate.stableKey}-${it.song.id}" },
                            ) { result ->
                                AiCoverResultRow(
                                    result = result,
                                    onPlay = { play(result.song) },
                                    onReplace = { replaceWith(result) },
                                    onDetails = { detailResult = result },
                                    onTitleSearch = { navController.navigate(SearchRoutes.resultRoute(result.candidate.title)) },
                                    onLongClick = {
                                        menuState.show {
                                            YouTubeSongMenu(
                                                song = result.song,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }

                        val page = selectedResults.take(visibleResultCount)
                        if (page.isNotEmpty()) {
                            item {
                                CoverSectionTitle("Da confermare")
                            }
                            items(
                                items = page,
                                key = { "${selectedTab.name}-${it.candidate.stableKey}-${it.song.id}" },
                            ) { result ->
                                AiCoverResultRow(
                                    result = result,
                                    onPlay = { play(result.song) },
                                    onReplace = { replaceWith(result) },
                                    onDetails = { detailResult = result },
                                    onTitleSearch = { navController.navigate(SearchRoutes.resultRoute(result.candidate.title)) },
                                    onLongClick = {
                                        menuState.show {
                                            YouTubeSongMenu(
                                                song = result.song,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        } else if (backgroundLoading || allModeResolving) {
                            item {
                                Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Cerco altre versioni in background…")
                                }
                            }
                        } else if (failed) {
                            item { Text("La ricerca AI non è riuscita. Apri ⓘ per il feedback.") }
                        } else {
                            item {
                                Text(
                                    when (selectedTab) {
                                        AiCoverTab.COVER -> "L'AI non ha trovato altre cover da studio riproducibili."
                                        AiCoverTab.LIVE -> "Nessuna versione live riproducibile indicata dall'AI."
                                        AiCoverTab.REMIX -> "Nessun remix riproducibile indicato dall'AI."
                                        AiCoverTab.FOREIGN -> "Nessuna versione straniera riproducibile indicata dall'AI."
                                        AiCoverTab.ALL -> "Nessuna versione riproducibile localizzata."
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (reviewResults.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(14.dp))
                                CoverSectionTitle("Da verificare")
                                Text(
                                    "Risultati che il Brain conserva per una decisione esplicita.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                            items(
                                items = reviewResults,
                                key = { "review-${it.candidate.stableKey}-${it.song.id}" },
                            ) { result ->
                                AiCoverResultRow(
                                    result = result,
                                    onPlay = { play(result.song) },
                                    onReplace = { replaceWith(result) },
                                    onDetails = { detailResult = result },
                                    onTitleSearch = { navController.navigate(SearchRoutes.resultRoute(result.candidate.title)) },
                                    onLongClick = {
                                        menuState.show {
                                            YouTubeSongMenu(
                                                song = result.song,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    TextButton(onClick = { play(result.song) }) { Text("Ascolta") }
                                    TextButton(onClick = { saveBrainDecision(result, AiBrainDecisionStatus.APPROVED) }) { Text("Conferma") }
                                    TextButton(onClick = { saveBrainDecision(result, AiBrainDecisionStatus.REJECTED) }) { Text("Rifiuta") }
                                    TextButton(onClick = { verifyCandidateBetter(result) }) { Text("Verifica meglio") }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
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
    liveCount: Int,
    remixCount: Int,
    foreignCount: Int,
    allCount: Int,
    liveEnabled: Boolean,
    remixEnabled: Boolean,
    foreignEnabled: Boolean,
    onSelected: (AiCoverTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CoverResultChip("Studio", coverCount, selected == AiCoverTab.COVER) { onSelected(AiCoverTab.COVER) }
        if (liveEnabled) CoverResultChip("Live", liveCount, selected == AiCoverTab.LIVE) { onSelected(AiCoverTab.LIVE) }
        if (remixEnabled) CoverResultChip("Remix", remixCount, selected == AiCoverTab.REMIX) { onSelected(AiCoverTab.REMIX) }
        if (foreignEnabled) CoverResultChip("Straniere", foreignCount, selected == AiCoverTab.FOREIGN) { onSelected(AiCoverTab.FOREIGN) }
        CoverResultChip("Tutto", allCount, selected == AiCoverTab.ALL) { onSelected(AiCoverTab.ALL) }
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
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = {}),
    ) {
        Text(
            text = "$label · $count",
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
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
    onTitleSearch: () -> Unit,
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
            Text(
                result.candidate.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onTitleSearch),
            )
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
    Text(
        text = year?.let { "Data: $it" } ?: "Data non disponibile",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    album?.let {
        Text("Album: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    AiCoverCreditsWithoutYearAlbum(songwriters, composers, lyricists, producers, label)
}

@Composable
private fun AiCoverCreditsWithoutYearAlbum(
    songwriters: List<String>,
    composers: List<String>,
    lyricists: List<String>,
    producers: List<String>,
    label: String?,
) {
    if (songwriters.isNotEmpty()) Text("Autori: ${songwriters.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (composers.isNotEmpty()) Text("Compositori: ${composers.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (lyricists.isNotEmpty()) Text("Parolieri: ${lyricists.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (producers.isNotEmpty()) Text("Produttori: ${producers.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    label?.let { Text("Etichetta: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

private fun mergePlayables(
    current: List<AiCoverPlayable>,
    incoming: List<AiCoverPlayable>,
): List<AiCoverPlayable> {
    val merged = linkedMapOf<String, AiCoverPlayable>()
    (current + incoming).forEach { item -> merged.putIfAbsent(item.song.id, item) }
    return merged.values.sortedWith(
        compareBy<AiCoverPlayable> { if (it.candidate.year == null) 1 else 0 }
            .thenBy { it.candidate.year ?: Int.MAX_VALUE }
            .thenBy { it.candidate.artist.lowercase() },
    )
}