package com.metrolist.music.ui.component

import android.widget.Toast
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import androidx.media3.common.Player
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.DEFAULT_MUSIC_AI_CLOUD_ENDPOINT
import com.metrolist.music.constants.DiscogsTokenKey
import com.metrolist.music.constants.MusicAiCloudEndpointKey
import com.metrolist.music.constants.MusicAiCloudMemoryEnabledKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.discogs.CompilationTrackResolver
import com.metrolist.music.discogs.DiscogsCredit
import com.metrolist.music.discogs.DiscogsTrack
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.CoverPlaybackMemory
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

private const val DIRECT_VERSION_PAGE_SIZE = 20
// Experimental family 02: bounded burst, never affect regular LAB68.
private const val TURBO_PACKAGE = "it.verlezza.musiclab.turbo01"
private const val TURBO_RESULT_QUOTA = 50
private const val TURBO_MAX_RANK_CANDIDATES = 100
private const val TURBO_BURST_DEADLINE_MS = 24_000L
private const val TURBO_ARCHIVE_TIMEOUT_MS = 4_500L
private const val TURBO_COVER_ORIGINAL_GATE_MS = 900L
private const val DIRECT_COVER_PAGE_SIZE = 10
private const val DIRECT_VERSION_SOURCE_FETCH_SIZE = 30
private const val DIRECT_VERSION_PREFETCH_DISTANCE = 2
private const val DIRECT_VERSION_BOTTOM_SAFE_DP = 260
private const val DIRECT_VIDEO_BATCH_SIZE = 5
private const val DIRECT_COVER_RANK_MAX_SOURCE_PAGES = 5
private const val DIRECT_COVER_RANK_TARGET = 120
private const val DIRECT_COVER_PLAYBACK_BATCH_SIZE = 1
private const val DIRECT_VIDEO_PARALLELISM = 2
// LAB64 Pollicino: prioritize 10 originals, then the first 10 ranked covers.
private const val DIRECT_ORIGINAL_PRIORITY_COUNT = 10
private const val DIRECT_ORIGINAL_FIRST_GATE_MS = 7_600L
// LAB65C: permit a verified partial group only after a bounded grace period.
// LAB66: never idle for 12s before showing already-verified ranked songs.
private const val DIRECT_COVER_PARTIAL_BATCH_GRACE_MS = 2_800L
// Fast first metadata pass, with full 3.8s retry budget retained for quality.
private const val DIRECT_VIDEO_FIRST_PASS_TIMEOUT_MS = 2_400L
private const val DIRECT_VIDEO_EXISTING_ID_TIMEOUT_MS = 1_400L
private const val DIRECT_AUTO_VIDEO_LOOKUP_TIMEOUT_MS = 3_800L
// LAB60 Pollicino: cap costly up-front Discogs details, keep discovered candidates.
private const val DIRECT_COVER_INITIAL_DETAIL_BUDGET = 12
private const val DIRECT_BACKGROUND_PREFETCH_AHEAD = 20
private const val DIRECT_PREPARED_VIDEO_CACHE_LIMIT = 48
// LAB63: a tap or Retry may wait at most 3 seconds for a VIDEO ID, never for audio.
private const val DIRECT_TAPPED_ROW_RESOLVE_TIMEOUT_MS = 3_000L
private val DirectCoverGeminiApiKey = stringPreferencesKey("coverGeminiApiKey")

private enum class DirectVersionCategory {
    ALL,
    STUDIO,
    LIVE,
    REMIX,
    FOREIGN,
}

private enum class DirectVersionSort {
    RELEVANCE,
    OLDEST,
    NEWEST,
}

private fun directDisplayCategory(seed: DiscogsVersionSeed): DirectVersionCategory {
    val text = (seed.trackTitle + " " + seed.releaseTitle).lowercase()
    val liveTitle =
        Regex("""\b(live|dal vivo|concert|concerto)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    val remixTitle =
        Regex("""\b(remix|mix|extended mix|radio mix|club mix|dance mix)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    return when {
        liveTitle || seed.kind == DiscogsVersionKind.LIVE -> DirectVersionCategory.LIVE
        remixTitle || seed.kind == DiscogsVersionKind.REMIX -> DirectVersionCategory.REMIX
        !seed.language.isNullOrBlank() -> DirectVersionCategory.FOREIGN
        else -> DirectVersionCategory.STUDIO
    }
}

private fun primaryDiscoverySource(seed: DiscogsVersionSeed): String {
    val preferred =
        listOf(
            "Discogs",
            "COVER.INFO",
            "Spotify",
            "Apple/iTunes",
            "Last.fm",
            "MusicBrainz",
            "LRCLIB",
            "Wikidata",
            "Archivio Cloud",
        )
    return preferred.firstOrNull { wanted ->
        seed.sourceNames.any { source -> source.contains(wanted, ignoreCase = true) }
    } ?: seed.sourceNames.firstOrNull()?.takeIf(String::isNotBlank) ?: "MusicLab"
}

private data class DirectVersionSession(
    var title: String,
    var artistFilter: String = "",
    var releaseTitle: String = "",
    var year: String = "",
    var format: String = "",
    var country: String = "",
    var label: String = "",
    var genre: String = "",
    var style: String = "",
    var catalogNumber: String = "",
    var filtersExpanded: Boolean = false,
    var headerExpanded: Boolean = false,
    var category: DirectVersionCategory = DirectVersionCategory.ALL,
    var sortMode: DirectVersionSort = DirectVersionSort.RELEVANCE,
    var results: List<DiscogsVersionSeed> = emptyList(),
    var currentPage: Int = 0,
    var totalPages: Int = 0,
    var totalDiscogsResults: Int = 0,
    var activeCriteria: DiscogsVersionSearchCriteria? = null,
    var listIndex: Int = 0,
    var listOffset: Int = 0,
    var selectedFingerprint: String? = null,
    var initialized: Boolean = false,
    var originalWorkCredits: List<DiscogsCredit> = emptyList(),
    var stableOrder: List<String> = emptyList(),
    // LAB61: a fresh Cover session starts with ten entries, not twenty.
    // Originali is still coerced to its native pageSize of twenty.
    var visibleLimit: Int = DIRECT_COVER_PAGE_SIZE,
    var resultPageIndex: Int = 0,
    var sourceDiagnostics: List<CoverSourceDiagnostic> = emptyList(),
    var sourceDiscoveryComplete: Boolean = false,
    var originalYear: Int? = null,
    var rankingFrozen: Boolean = false,
    var publishedReadyLimit: Int = 0,
    var publishedOriginalSnapshots: List<DiscogsVersionSeed> = emptyList(),
    var publishedCoverSnapshots: List<DiscogsVersionSeed> = emptyList(),
    var originalSectionFrozen: Boolean = false,
    var originalGateStartedAtMs: Long = 0L,
    var coverBatchStartedAtMs: Long = 0L,
    var turboFinished: Boolean = false,
    var turboBurstStartedAtMs: Long = 0L,
    var turboArchiveMatches: Int = 0,
    val rejectedKeys: MutableSet<String> = linkedSetOf(),
    val approvedKeys: MutableSet<String> = linkedSetOf(),
    val usedVideoIds: MutableSet<String> = linkedSetOf(),
    val knownVideoBindings: MutableMap<String, Triple<String, String, String>> = linkedMapOf(),
    val preparedVideoSongs: MutableMap<String, SongItem> = ConcurrentHashMap(),
    val playReadyVideoIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    val transientVideoRetries: MutableMap<String, Int> = ConcurrentHashMap(),
    val automaticVideoAttempts: MutableMap<String, Int> = ConcurrentHashMap(),
)

private object DirectVersionSessionStore {
    private val sessions = ConcurrentHashMap<String, DirectVersionSession>()

    fun get(
        key: String,
        initialTitle: String,
    ): DirectVersionSession {
        if (sessions.size > 8 && !sessions.containsKey(key)) {
            sessions.keys.firstOrNull()?.let { staleKey ->
                sessions.remove(staleKey)?.let { stale ->
                    stale.preparedVideoSongs.clear()
                    stale.knownVideoBindings.clear()
                    stale.usedVideoIds.clear()
                    stale.playReadyVideoIds.clear()
                    stale.transientVideoRetries.clear()
                    stale.automaticVideoAttempts.clear()
                }
            }
        }
        return sessions.getOrPut(key) {
            DirectVersionSession(title = initialTitle)
        }
    }
}

@Composable
internal fun DiscogsDirectVersionBrowser(
    mode: DiscogsDirectMode,
    initialTitle: String,
    lockedArtist: String,
    navController: NavHostController,
) {
    val context = LocalContext.current
    val isTurbo = BuildConfig.APPLICATION_ID == TURBO_PACKAGE
    val playerConnection = LocalPlayerConnection.current
    val discogsToken by rememberPreference(DiscogsTokenKey, "")
    val aiProvider by rememberPreference(AiProviderKey, "OpenRouter")
    val sharedApiKey by rememberPreference(OpenRouterApiKey, "")
    val sharedModel by rememberPreference(OpenRouterModelKey, "")
    val dedicatedGeminiKey by rememberPreference(DirectCoverGeminiApiKey, "")
    val cloudMemoryEnabled by rememberPreference(MusicAiCloudMemoryEnabledKey, true)
    val cloudEndpoint by rememberPreference(MusicAiCloudEndpointKey, DEFAULT_MUSIC_AI_CLOUD_ENDPOINT)
    val effectiveGoogleKey = dedicatedGeminiKey.ifBlank {
        sharedApiKey.takeIf {
            it.isNotBlank() && (aiProvider == "Gemini" || it.trim().startsWith("AIza"))
        }.orEmpty()
    }
    val effectiveGeminiModel =
        if (aiProvider == "Gemini" && sharedModel.isNotBlank() && !sharedModel.contains('/')) {
            sharedModel
        } else {
            "gemini-3.5-flash-lite"
        }
    val effectiveCloudEndpoint = cloudEndpoint.trim().ifBlank { DEFAULT_MUSIC_AI_CLOUD_ENDPOINT }
    val foreignScoutConfig =
        if (effectiveGoogleKey.isNotBlank() || effectiveCloudEndpoint.isNotBlank()) {
            GeminiCoverVerificationConfig(
                apiKey = effectiveGoogleKey,
                model = effectiveGeminiModel,
                cloudEndpoint = effectiveCloudEndpoint,
                useCloudMemory = cloudMemoryEnabled,
            )
        } else {
            null
        }
    val scope = rememberCoroutineScope()
    val pageSize = if (mode == DiscogsDirectMode.COVER) DIRECT_COVER_PAGE_SIZE else DIRECT_VERSION_PAGE_SIZE

    val sessionKey = buildString {
        append(mode.name)
        append('|')
        append(initialTitle.trim().lowercase())
        append('|')
        append(lockedArtist.trim().lowercase())
    }
    val session = remember(sessionKey) {
        DirectVersionSessionStore.get(sessionKey, initialTitle)
    }

    var title by remember(sessionKey) { mutableStateOf(session.title) }
    var resolvedOriginalArtist by remember(sessionKey) { mutableStateOf(lockedArtist) }
    var artistFilter by remember(sessionKey) { mutableStateOf(session.artistFilter) }
    var releaseTitle by remember(sessionKey) { mutableStateOf(session.releaseTitle) }
    var year by remember(sessionKey) { mutableStateOf(session.year) }
    var format by remember(sessionKey) { mutableStateOf(session.format) }
    var country by remember(sessionKey) { mutableStateOf(session.country) }
    var label by remember(sessionKey) { mutableStateOf(session.label) }
    var genre by remember(sessionKey) { mutableStateOf(session.genre) }
    var style by remember(sessionKey) { mutableStateOf(session.style) }
    var catalogNumber by remember(sessionKey) { mutableStateOf(session.catalogNumber) }
    var filtersExpanded by remember(sessionKey) { mutableStateOf(session.filtersExpanded) }
    var headerExpanded by remember(sessionKey) { mutableStateOf(session.headerExpanded) }
    var category by remember(sessionKey) { mutableStateOf(session.category) }
    var sortMode by remember(sessionKey) { mutableStateOf(session.sortMode) }

    var results by remember(sessionKey) { mutableStateOf(session.results) }
    var currentPage by remember(sessionKey) { mutableStateOf(session.currentPage) }
    var totalPages by remember(sessionKey) { mutableStateOf(session.totalPages) }
    var totalDiscogsResults by remember(sessionKey) { mutableStateOf(session.totalDiscogsResults) }
    var activeCriteria by remember(sessionKey) { mutableStateOf(session.activeCriteria) }
    var selectedFingerprint by remember(sessionKey) { mutableStateOf(session.selectedFingerprint) }

    var loading by remember(sessionKey) { mutableStateOf(false) }
    var loadingMore by remember(sessionKey) { mutableStateOf(false) }
    var error by remember(sessionKey) { mutableStateOf<String?>(null) }
    var paginationError by remember(sessionKey) { mutableStateOf<String?>(null) }
    var resolvingFingerprint by remember(sessionKey) { mutableStateOf<String?>(null) }
    var sourceDiscoveryLoading by remember(sessionKey) { mutableStateOf(false) }
    var detailSeed by remember(sessionKey) { mutableStateOf<DiscogsVersionSeed?>(null) }
    var showSourceDiagnostics by remember(sessionKey) { mutableStateOf(false) }
    var sourceDiagnostics by remember(sessionKey) { mutableStateOf(session.sourceDiagnostics) }
    var visibleLimit by remember(sessionKey) {
        mutableStateOf(session.visibleLimit.coerceAtLeast(pageSize))
    }
    var resultPageIndex by remember(sessionKey) { mutableStateOf(session.resultPageIndex) }
    var rankingFrozen by remember(sessionKey) { mutableStateOf(session.rankingFrozen) }
    var publishedReadyLimit by remember(sessionKey) { mutableStateOf(session.publishedReadyLimit) }
    var publishedOriginalSnapshots by remember(sessionKey) { mutableStateOf(session.publishedOriginalSnapshots) }
    var publishedCoverSnapshots by remember(sessionKey) { mutableStateOf(session.publishedCoverSnapshots) }
    var originalSectionFrozen by remember(sessionKey) { mutableStateOf(session.originalSectionFrozen) }
    var searchJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var paginationJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var videoPreloadJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var verificationJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var backgroundResumeJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var playbackLaunchJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var backgroundPausedForPlayback by remember(sessionKey) { mutableStateOf(false) }
    var sourcePrefetchedForVisibleLimit by remember(sessionKey) { mutableStateOf(-1) }
    var decisionSavingFingerprint by remember(sessionKey) { mutableStateOf<String?>(null) }
    var headerSwipeDistance by remember(sessionKey) { mutableStateOf(0f) }
    var coverSwipeJob by remember(sessionKey) { mutableStateOf<Job?>(null) }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = session.listIndex,
        initialFirstVisibleItemScrollOffset = session.listOffset,
    )

    DisposableEffect(sessionKey) {
        if (mode == DiscogsDirectMode.COVER) {
            playerConnection?.service?.setCoverPerformanceLoad(active = true, heavy = false)
        }
        onDispose {
            // LAB57 hard Cover lifecycle barrier: leaving this screen cancels every
            // owned coroutine and releases the playback-quality governor.
            playbackLaunchJob?.cancel()
            coverSwipeJob?.cancel()
            CoverSwipeBridge.clear(sessionKey)
            searchJob?.cancel()
            paginationJob?.cancel()
            verificationJob?.cancel()
            videoPreloadJob?.cancel()
            backgroundResumeJob?.cancel()
            if (mode == DiscogsDirectMode.COVER) {
                playerConnection?.service?.setCoverPerformanceLoad(active = false, heavy = false)
            }
        }
    }

    fun persistInputs() {
        session.title = title
        session.artistFilter = artistFilter
        session.releaseTitle = releaseTitle
        session.year = year
        session.format = format
        session.country = country
        session.label = label
        session.genre = genre
        session.style = style
        session.catalogNumber = catalogNumber
        session.filtersExpanded = filtersExpanded
        session.headerExpanded = headerExpanded
        session.category = category
        session.sortMode = sortMode
    }

    fun buildCriteria(): DiscogsVersionSearchCriteria =
        DiscogsVersionSearchCriteria(
            // LAB59: metadata such as "(Remastered in 192 KHz)" is not
            // part of the musical work's title; keep live/remix variants intact.
            title = TitleMeaningResolver.stripTrailingArtistHint(title)
                .replace(Regex("""\s*\([^)]*\b(?:remaster(?:ed)?|\d+\s*k(?:h)?z|hi[- ]?res)\b[^)]*\)\s*$""", RegexOption.IGNORE_CASE), "")
                .trim(),
            artist = null,
            releaseTitle = null,
            year = null,
            format = null,
            country = null,
            label = null,
            genre = null,
            style = null,
            catalogNumber = null,
        )

    fun cloudCategory(seed: DiscogsVersionSeed): String =
        when {
            mode == DiscogsDirectMode.ORIGINAL &&
                seed.kind == DiscogsVersionKind.STUDIO &&
                seed.language.isNullOrBlank() -> "originale"
            !seed.language.isNullOrBlank() -> "straniera"
            seed.kind == DiscogsVersionKind.LIVE -> "live"
            seed.kind == DiscogsVersionKind.REMIX -> "remix"
            else -> "cover"
        }

    fun rejectionKey(seed: DiscogsVersionSeed): String =
        CloudMusicDiscovery.memoryKey(
            title = seed.trackTitle,
            artist = seed.artist,
            category = cloudCategory(seed),
        )

    fun isRejected(seed: DiscogsVersionSeed): Boolean =
        rejectionKey(seed) in session.rejectedKeys

    fun isApproved(seed: DiscogsVersionSeed): Boolean =
        seed.manuallyApproved || rejectionKey(seed) in session.approvedKeys

    fun isHiddenForCurrentCover(seed: DiscogsVersionSeed): Boolean =
        mode == DiscogsDirectMode.COVER && isRejected(seed)

    fun memoryCandidateToSeed(candidate: AiCoverCandidate): DiscogsVersionSeed {
        val score =
            when (candidate.brainStatus) {
                AiBrainDecisionStatus.APPROVED -> 20
                AiBrainDecisionStatus.PROBABLE -> 14
                AiBrainDecisionStatus.UNCERTAIN -> 7
                AiBrainDecisionStatus.REJECTED -> 1
                null -> 5
            }
        val seed =
            DiscogsVersionSource.externalSeed(
                CoverSourceCandidate(
                    title = candidate.title,
                    artist = candidate.artist,
                    sources = listOf("Archivio Cloud"),
                    year = candidate.year,
                    album = candidate.album,
                    coverUrl = candidate.coverUrl,
                    language = candidate.language,
                    category = candidate.category,
                    evidenceScore = score,
                ),
                targetTitle = TitleMeaningResolver.stripTrailingArtistHint(title),
                originalArtist = resolvedOriginalArtist,
            ).copy(
                manuallyApproved = candidate.brainStatus == AiBrainDecisionStatus.APPROVED,
            )
        val playbackId = candidate.playbackVideoId?.trim().orEmpty()
        return if (playbackId.isNotBlank()) {
            DiscogsVersionSource.markVideoResolved(
                seed = seed,
                videoId = playbackId,
                videoTitle = candidate.playbackVideoTitle?.takeIf(String::isNotBlank) ?: candidate.title,
                source = candidate.playbackVideoSource?.takeIf(String::isNotBlank) ?: "Archivio Cloud",
            )
        } else {
            seed
        }
    }

    fun seedToBrainCandidate(seed: DiscogsVersionSeed): AiCoverCandidate {
        fun creditNames(pattern: Regex): List<String> =
            seed.credits
                .filter { pattern.containsMatchIn(it.role.lowercase()) }
                .map { it.name }
                .filter(String::isNotBlank)
                .distinct()

        val category =
            when {
                !seed.language.isNullOrBlank() -> AiCoverCategory.FOREIGN
                seed.kind == DiscogsVersionKind.LIVE -> AiCoverCategory.LIVE
                seed.kind == DiscogsVersionKind.REMIX -> AiCoverCategory.REMIX
                else -> AiCoverCategory.COVER
            }
        val evidenceStrength =
            when {
                seed.confidenceScore >= 18 -> "very_strong"
                seed.confidenceScore >= 14 -> "strong"
                seed.confidenceScore >= 8 -> "medium"
                else -> "weak"
            }
        return AiCoverCandidate(
            title = seed.trackTitle,
            artist = seed.artist,
            category = category,
            language = seed.language,
            year = seed.year,
            album = seed.releaseTitle.takeIf(String::isNotBlank),
            songwriters = creditNames(Regex("\\b(songwriter|written|writer|words by)\\b")),
            composers = creditNames(Regex("\\b(composer|composed|music by)\\b")),
            lyricists = creditNames(Regex("\\b(lyrics|lyricist)\\b")),
            label = seed.labels.firstOrNull(),
            sameWorkScore = (seed.confidenceScore * 5).coerceIn(5, 100),
            versionTypeScore = (seed.confidenceScore * 5).coerceIn(5, 100),
            brainStatus = AiBrainDecisionStatus.UNCERTAIN,
            brainAdmission = "lab38b_manual_review",
            brainSignals =
                seed.sourceNames.map { sourceName ->
                    AiBrainSignal(
                        kind = sourceName.lowercase().replace(Regex("[^a-z0-9]+"), "_") + "_evidence",
                        strength = evidenceStrength,
                        direction = "positive",
                    )
                },
            coverUrl = seed.coverUrl,
            playbackVideoId = seed.resolvedVideoId,
            playbackVideoTitle = seed.resolvedVideoTitle,
            playbackVideoSource = seed.resolvedVideoSource,
            releaseDate = seed.releaseDate,
            discogsReleaseId = seed.releaseId.takeIf { it > 0 },
            discogsMasterId = seed.masterId,
            discogsReleaseTitle = seed.releaseTitle.takeIf(String::isNotBlank),
            versionFingerprint = seed.fingerprint,
        )
    }

    fun rememberPreparedVideoSong(song: SongItem) {
        if (
            song.id !in session.preparedVideoSongs &&
            session.preparedVideoSongs.size >= DIRECT_PREPARED_VIDEO_CACHE_LIMIT
        ) {
            session.preparedVideoSongs.keys.firstOrNull()?.let { staleId ->
                session.preparedVideoSongs.remove(staleId)
            }
        }
        session.preparedVideoSongs[song.id] = song
    }

    fun rememberKnownVideoBinding(seed: DiscogsVersionSeed) {
        val id = seed.resolvedVideoId?.trim().orEmpty()
        if (id.isBlank()) return
        val key = DiscogsVersionSource.recordingIdentityKey(seed)
        session.knownVideoBindings.putIfAbsent(
            key,
            Triple(
                id,
                seed.resolvedVideoTitle?.takeIf(String::isNotBlank) ?: seed.trackTitle,
                seed.resolvedVideoSource?.takeIf(String::isNotBlank) ?: "MusicLab interno",
            ),
        )
    }

    fun normalizeSearchLocalVideoBindings(source: List<DiscogsVersionSeed>): List<DiscogsVersionSeed> {
        val withVideo = source.filter { !it.resolvedVideoId.isNullOrBlank() }
        val winnerByVideo =
            withVideo
                .groupBy { it.resolvedVideoId!! }
                .mapValues { (_, group) ->
                    group.maxWithOrNull(
                        compareBy<DiscogsVersionSeed> { it.confidenceScore }
                            .thenBy { if (it.originalWorkReference) 1 else 0 }
                            .thenBy { it.sourceNames.distinct().size }
                            .thenBy { if (it.track != null) 1 else 0 },
                    ) ?: group.first()
                }

        val normalized =
            source.map { seed ->
                val id = seed.resolvedVideoId
                val winner = id?.let(winnerByVideo::get)
                if (id != null && winner != null && winner.fingerprint != seed.fingerprint) {
                    seed.copy(
                        resolvedVideoId = null,
                        resolvedVideoTitle = null,
                        resolvedVideoSource = null,
                        videoResolutionChecked = false,
                        confidenceReasons =
                            (seed.confidenceReasons +
                                "Flusso video già assegnato a un’altra versione in questa ricerca: cerco alternativa").distinct(),
                    )
                } else {
                    seed
                }
            }

        session.usedVideoIds.clear()
        normalized.mapNotNull { it.resolvedVideoId }.forEach(session.usedVideoIds::add)
        normalized.forEach(::rememberKnownVideoBinding)
        return normalized
    }

    suspend fun knownMusicLabVideo(
        seed: DiscogsVersionSeed,
        track: DiscogsTrack,
    ): Pair<SongItem, String>? {
        val key = DiscogsVersionSource.recordingIdentityKey(seed)
        val binding = session.knownVideoBindings[key] ?: return null
        val videoId = binding.first
        if (videoId in session.usedVideoIds) return null
        val song =
            session.preparedVideoSongs[videoId]
                ?: withTimeoutOrNull(1_800L) {
                    YouTube.queue(videoIds = listOf(videoId)).getOrNull()?.firstOrNull()
                }?.also(::rememberPreparedVideoSong)
                ?: return null
        if (!CompilationTrackResolver.isHardCompatible(track, song)) return null
        return song to ("MusicLab interno · " + binding.third)
    }

    fun consensusOriginalArtist(candidates: List<CoverSourceCandidate>): String? {
        val strong =
            candidates.filter { candidate ->
                candidate.artist.isNotBlank() &&
                    (candidate.originalWorkReference || candidate.workRelationConfirmed || candidate.evidenceScore >= 14)
            }
        if (strong.isEmpty()) return null

        val groups =
            strong.groupBy { TitleMeaningResolver.canonical(it.artist).removePrefix("the ") }
                .filterKeys(String::isNotBlank)
        val ranked = groups.entries.sortedByDescending { it.value.size }
        val winner = ranked.firstOrNull() ?: return null
        val total = strong.size.coerceAtLeast(1)
        val second = ranked.getOrNull(1)?.value?.size ?: 0
        val explicitOriginal = winner.value.any { it.originalWorkReference }
        val enoughConsensus = winner.value.size >= 3 && winner.value.size * 100 / total >= 40 && winner.value.size > second
        return if (explicitOriginal || enoughConsensus) winner.value.first().artist else null
    }
    fun mergeDiscoveryOutcomes(
        primary: CoverSourceOutcome,
        fallback: CoverSourceOutcome,
    ): CoverSourceOutcome {
        val candidates =
            (primary.candidates + fallback.candidates)
                .distinctBy { candidate ->
                    listOf(
                        TitleMeaningResolver.canonical(candidate.title),
                        TitleMeaningResolver.canonical(candidate.artist).removePrefix("the "),
                        candidate.category.name,
                    ).joinToString("|")
                }
        val diagnostics =
            (primary.diagnostics + fallback.diagnostics)
                .groupBy { it.name }
                .map { (name, items) ->
                    val notes = items.map { it.note }.filter(String::isNotBlank).distinct()
                    CoverSourceDiagnostic(
                        name = name,
                        available = items.any { it.available },
                        found = items.maxOfOrNull { it.found } ?: 0,
                        note = notes.joinToString(" · "),
                    )
                }
        return CoverSourceOutcome(
            candidates = candidates,
            diagnostics = diagnostics,
            originalYear = listOfNotNull(primary.originalYear, fallback.originalYear).minOrNull(),
            workId = primary.workId ?: fallback.workId,
            workCredits =
                (primary.workCredits + fallback.workCredits)
                    .distinctBy { credit ->
                        credit.name.lowercase() + "|" + credit.role.lowercase()
                    },
        )
    }
    fun mergePage(
        current: List<DiscogsVersionSeed>,
        incoming: List<DiscogsVersionSeed>,
        replace: Boolean,
    ): List<DiscogsVersionSeed> {
        incoming.forEach(::rememberKnownVideoBinding)
        if (!replace) current.forEach(::rememberKnownVideoBinding)

        val merged = linkedMapOf<String, DiscogsVersionSeed>()
        val bucketKeys = linkedMapOf<String, MutableList<String>>()

        fun indexSeed(key: String, seed: DiscogsVersionSeed) {
            val bucket = DiscogsVersionSource.crossSourceBucketKey(seed)
            val keys = bucketKeys.getOrPut(bucket) { mutableListOf() }
            if (key !in keys) keys += key
        }

        fun rankedSeed(seed: DiscogsVersionSeed): DiscogsVersionSeed =
            if (isRejected(seed)) {
                seed.copy(
                    confidenceScore = 1,
                    confidenceReasons =
                        (seed.confidenceReasons + "Filtro negativo: mantenuta in graduatoria a 1/20").distinct(),
                )
            } else {
                seed
            }

        if (!replace) {
            current.forEach { rawSeed ->
                val seed = rankedSeed(rawSeed)
                val key = DiscogsVersionSource.identityKey(seed)
                merged[key] = seed
                indexSeed(key, seed)
            }
        }

        incoming.forEach { rawSeed ->
            val seed = rankedSeed(rawSeed)
            val exactKey = DiscogsVersionSource.identityKey(seed)
            val bucket = DiscogsVersionSource.crossSourceBucketKey(seed)
            val equivalentKey =
                bucketKeys[bucket]
                    .orEmpty()
                    .firstOrNull { candidateKey ->
                        merged[candidateKey]?.let { existing ->
                            DiscogsVersionSource.sameCrossSourceVersion(existing, seed)
                        } == true
                    }
            val key = equivalentKey ?: exactKey
            val previous = merged[key]
            val mergedEvidence =
                if (previous == null) {
                    seed
                } else {
                    DiscogsVersionSource.mergeCrossSourceEvidence(previous, seed)
                }
            val updated = rankedSeed(mergedEvidence)
            merged[key] = updated
            indexSeed(key, updated)
        }

        val recordingDeduped = DiscogsVersionSource.dedupeVersions(merged.values.toList())
        return normalizeSearchLocalVideoBindings(recordingDeduped)
    }
    fun modeAcceptsSeed(seed: DiscogsVersionSeed): Boolean {
        val workTitle =
            TitleMeaningResolver.workAnchorTitle(activeCriteria?.title ?: title)
        val titleOk =
            workTitle.isBlank() ||
                TitleMeaningResolver.matchesBaseTitle(
                    targetTitle = workTitle,
                    value = seed.trackTitle,
                    artistAliases =
                        setOf(resolvedOriginalArtist, seed.artist)
                            .filter(String::isNotBlank)
                            .toSet(),
                )

        // LAB59: the year is evidence for ranking, never an admission gate.
        // Archival release years can precede an inferred original year.
        if (mode == DiscogsDirectMode.COVER) {
            if (titleOk) return true
            if (isApproved(seed)) return true

            val aiTrusted =
                seed.sourceNames.any { it.equals("AI Scout", ignoreCase = true) }
            if (aiTrusted) return true

            val independentServices =
                seed.sourceNames
                    .map { it.trim().lowercase() }
                    .filter {
                        it.isNotBlank() &&
                            it != "archivio cloud" &&
                            it != "musiclab interno"
                    }
                    .distinct()
                    .size
            val sharedCredits =
                DiscogsVersionSource.sharedWorkCreditNames(
                    seed = seed,
                    originalCredits = session.originalWorkCredits,
                ).isNotEmpty()

            // LAB57 foreign-service gate:
            // changed-title service candidates need either independent cross-source
            // confirmation or a structural work relation backed by matching credits.
            return independentServices >= 2 ||
                (seed.workRelationConfirmed && sharedCredits)
        }

        val originalArtistOk =
            resolvedOriginalArtist.isNotBlank() &&
                TitleMeaningResolver.sameArtist(seed.artist, resolvedOriginalArtist)
        return originalArtistOk && titleOk
    }

    fun isOriginalPerformerVersion(seed: DiscogsVersionSeed): Boolean =
        mode == DiscogsDirectMode.COVER &&
            resolvedOriginalArtist.isNotBlank() &&
            TitleMeaningResolver.sameArtist(seed.artist, resolvedOriginalArtist)

    fun sortGroup(source: List<DiscogsVersionSeed>, selectedSort: DirectVersionSort): List<DiscogsVersionSeed> =
        when (selectedSort) {
            DirectVersionSort.RELEVANCE ->
                source.sortedWith(
                    compareByDescending<DiscogsVersionSeed> { it.confidenceScore }
                        .thenByDescending { it.originalWorkReference }
                        .thenByDescending { it.sourceNames.distinct().size }
                        .thenByDescending { it.track != null }
                        .thenBy { it.artist.lowercase() }
                        .thenBy { it.trackTitle.lowercase() },
                )
            DirectVersionSort.OLDEST ->
                source.sortedWith(
                    compareBy<DiscogsVersionSeed> { it.year ?: Int.MAX_VALUE }
                        .thenByDescending { it.confidenceScore },
                )
            DirectVersionSort.NEWEST ->
                source.sortedWith(
                    compareByDescending<DiscogsVersionSeed> { it.year ?: Int.MIN_VALUE }
                        .thenByDescending { it.confidenceScore },
                )
        }

    fun sortedPool(source: List<DiscogsVersionSeed>): List<DiscogsVersionSeed> {
        val displayable =
            source.filter(DiscogsVersionSource::isDisplayableDirectSeed)
                .filter(::modeAcceptsSeed)
        if (mode != DiscogsDirectMode.COVER) return sortGroup(displayable, sortMode)

        val (originalVersions, trueCovers) = displayable.partition(::isOriginalPerformerVersion)
        return sortGroup(originalVersions, sortMode) + sortGroup(trueCovers, sortMode)
    }

    fun rebuildStableOrder() {
        session.stableOrder = sortedPool(results).map { it.fingerprint }
    }

    fun syncStableOrder() {
        val currentIds = results.mapTo(linkedSetOf()) { it.fingerprint }
        val kept = session.stableOrder.filter { it in currentIds }
        val known = kept.toHashSet()
        // LAB58: append late candidates in their scored order without moving committed cards.
        val appended = sortedPool(results).map { it.fingerprint }.filter { known.add(it) }
        session.stableOrder = kept + appended
    }

    fun orderedResults(source: List<DiscogsVersionSeed>): List<DiscogsVersionSeed> {
        val byId = source.associateBy { it.fingerprint }
        val seen = HashSet<String>(source.size)
        val ordered =
            buildList {
                session.stableOrder.forEach { fingerprint ->
                    byId[fingerprint]?.let { seed ->
                        if (seen.add(seed.fingerprint)) add(seed)
                    }
                }
                source.forEach { seed ->
                    if (seen.add(seed.fingerprint)) add(seed)
                }
            }.filter(DiscogsVersionSource::isDisplayableDirectSeed)
                .filter(::modeAcceptsSeed)

        val categoryFiltered =
            ordered.filter { seed ->
                when (category) {
                    DirectVersionCategory.ALL -> true
                    else -> directDisplayCategory(seed) == category
                }
            }

        // LAB54: Cover page boundaries are cut from one global 20→1 ranking.
        // Video readiness must never reshuffle a lower-score row above a higher one.
        if (mode == DiscogsDirectMode.COVER) return categoryFiltered

        val (failedVideo, usableOrPending) =
            categoryFiltered.partition { seed ->
                seed.videoResolutionChecked && seed.resolvedVideoId.isNullOrBlank()
            }
        return usableOrPending + failedVideo
    }

    fun replaceSeed(updated: DiscogsVersionSeed) {
        val updatedIdentity = DiscogsVersionSource.identityKey(updated)
        val index =
            results.indexOfFirst { current ->
                current.fingerprint == updated.fingerprint ||
                    (current.releaseId > 0 && current.releaseId == updated.releaseId) ||
                    DiscogsVersionSource.identityKey(current) == updatedIdentity
            }

        if (index < 0) {
            results = mergePage(results, listOf(updated), replace = false)
            session.results = results
            syncStableOrder()
            return
        }

        val current = results[index]
        val mergedEvidence = DiscogsVersionSource.mergeEvidence(current, updated)
        val merged =
            if (isRejected(updated)) {
                mergedEvidence.copy(
                    confidenceScore = 1,
                    confidenceReasons =
                        (mergedEvidence.confidenceReasons + "Filtro negativo: mantenuta in graduatoria a 1/20").distinct(),
                    manuallyApproved = false,
                )
            } else {
                mergedEvidence
            }
        val relevanceChanged =
            merged.confidenceScore != current.confidenceScore ||
                merged.originalWorkReference != current.originalWorkReference
        val identityChanged =
            DiscogsVersionSource.identityKey(merged) != DiscogsVersionSource.identityKey(current) ||
                DiscogsVersionSource.recordingIdentityKey(merged) != DiscogsVersionSource.recordingIdentityKey(current)
        val videoChanged = merged.resolvedVideoId != current.resolvedVideoId

        val mutable = results.toMutableList()
        mutable[index] = merged
        results =
            when {
                identityChanged ->
                    normalizeSearchLocalVideoBindings(
                        DiscogsVersionSource.dedupeVersions(mutable),
                    )
                videoChanged -> normalizeSearchLocalVideoBindings(mutable)
                else -> mutable
            }

        session.results = results
        // LAB66 Pollicino: a confirmed video URL does NOT change recording
        // identity or any committed rank. Avoid O(n log n) ranking rebuilds
        // and whole-screen Compose churn on every metadata-only update.
        if (!rankingFrozen && sortMode == DirectVersionSort.RELEVANCE && relevanceChanged) {
            rebuildStableOrder()
        } else if (identityChanged || !rankingFrozen) {
            syncStableOrder()
        }
    }

    fun playableTrack(seed: DiscogsVersionSeed): DiscogsTrack? =
        seed.track ?: if (
            // Some Discogs releases never expose a matched track; do not stall a group.
            seed.trackTitle.isNotBlank() &&
            seed.artist.isNotBlank()
        ) {
            DiscogsTrack(
                position = "",
                title = seed.trackTitle,
                artists = listOf(seed.artist),
                durationText = null,
                durationSeconds = seed.durationSeconds,
            )
        } else {
            null
        }


    suspend fun persistCloudPlaybackBinding(seed: DiscogsVersionSeed) {
        if ("Archivio Cloud" !in seed.sourceNames || seed.resolvedVideoId.isNullOrBlank()) return
        val config = foreignScoutConfig ?: return
        if (title.isBlank() || resolvedOriginalArtist.isBlank()) return

        runCatching {
            CloudMusicDiscovery.savePlaybackBinding(
                originalTitle = title,
                originalArtist = resolvedOriginalArtist,
                candidate = seedToBrainCandidate(seed),
                config = config,
            )
        }
    }

    suspend fun resolveVideoChunk(chunk: List<DiscogsVersionSeed>) = coroutineScope {
        if (chunk.isEmpty()) return@coroutineScope
        // LAB60 Pollicino: discovery never requests playback stream URLs.
        val jobs =
            chunk.map { seed ->
                launch {
                    val current =
                        results.firstOrNull { it.fingerprint == seed.fingerprint }
                            ?: return@launch
                    val track = playableTrack(current) ?: return@launch
                    var transientTimeout = false

                    suspend fun verifyCandidateSong(
                        song: SongItem?,
                        source: String?,
                    ): Pair<SongItem, String>? {
                        val candidate = song ?: return null
                        if (!CompilationTrackResolver.isHardCompatible(track, candidate)) return null
                        // LAB60: compatible video ID is enough. The native player
                        // alone obtains stream URLs when the user taps a result.
                        rememberPreparedVideoSong(candidate)
                        session.playReadyVideoIds += candidate.id
                        // LAB68: this verdict is HARD-compatible metadata, not
                        // an audio-stream probe. Reuse it for the same recording.
                        CoverPlaybackMemory.saveMetadataVerifiedVideo(
                            context, current.fingerprint, candidate.id,
                        )
                        return candidate to (source ?: "MusicLab")
                    }

                    var verified: Pair<SongItem, String>? = null
                    val existingId = current.resolvedVideoId?.trim().orEmpty()

                    if (existingId.isNotBlank()) {
                        val existingSong =
                            session.preparedVideoSongs[existingId]
                                ?: withTimeoutOrNull(DIRECT_VIDEO_EXISTING_ID_TIMEOUT_MS) {
                                    // LAB66: metadata networking must never execute heavy
                                    // provider work on Compose's UI dispatcher.
                                    withContext(Dispatchers.IO) {
                                        YouTube.queue(videoIds = listOf(existingId)).getOrNull()?.firstOrNull()
                                    }
                                }
                        if (existingSong == null) transientTimeout = true
                        verified = verifyCandidateSong(existingSong, current.resolvedVideoSource)
                        if (verified == null) {
                            session.playReadyVideoIds.remove(existingId)
                            if (existingSong != null) {
                                CoverPlaybackMemory.rejectVideo(context, current.fingerprint, existingId)
                            }
                        }
                    }

                    if (verified == null) {
                        val excluded =
                            (session.usedVideoIds + CoverPlaybackMemory.rejectedVideoIds(context, current.fingerprint))
                                .filterNot { it == existingId }
                                .toSet()
                        val resolved =
                            withTimeoutOrNull(DIRECT_AUTO_VIDEO_LOOKUP_TIMEOUT_MS) {
                                try {
                                    withContext(Dispatchers.IO) {
                                        CompilationTrackResolver.resolveTrack(
                                            track = track,
                                            discogsVideos = current.videos,
                                            fastFirst = true,
                                            excludedVideoIds = excluded,
                                            searchRound = CoverPlaybackMemory.nextSearchRound(context, current.fingerprint),
                                            preferLive = current.kind == DiscogsVersionKind.LIVE,
                                        )
                                    }
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    transientTimeout = true
                                    null
                                }
                            }
                        if (resolved == null) transientTimeout = true
                        verified = verifyCandidateSong(resolved?.song, resolved?.source)
                        if (resolved?.song != null && verified == null) {
                            CoverPlaybackMemory.rejectVideo(context, current.fingerprint, resolved.song.id)
                        }
                    }

                    val latest =
                        results.firstOrNull { it.fingerprint == current.fingerprint }
                            ?: current
                    val updated =
                        if (verified == null) {
                            if (existingId.isNotBlank()) session.playReadyVideoIds.remove(existingId)
                            val retries = session.transientVideoRetries[current.fingerprint] ?: 0
                            if (transientTimeout && retries < 1) {
                                // LAB58: one bounded retry for a REAL network timeout.
                                // Keep ranking position pending, do not discard as unavailable.
                                session.transientVideoRetries[current.fingerprint] = retries + 1
                                latest
                            } else {
                                session.transientVideoRetries.remove(current.fingerprint)
                                DiscogsVersionSource.markVideoUnavailable(latest)
                            }
                        } else {
                            session.transientVideoRetries.remove(current.fingerprint)
                            val song = verified!!.first
                            val source = verified!!.second
                            session.usedVideoIds += song.id
                            DiscogsVersionSource.markVideoResolved(
                                seed = latest,
                                videoId = song.id,
                                videoTitle = song.title,
                                source = source,
                            )
                        }

                    replaceSeed(updated)
                    val readyId = updated.resolvedVideoId?.trim().orEmpty()
                    if (
                        readyId.isNotBlank() &&
                        readyId in session.playReadyVideoIds &&
                        session.preparedVideoSongs.containsKey(readyId)
                    ) {
                        // LAB67: avoid serial remote cloud writes on video readiness.
                        scope.launch(Dispatchers.IO) {
                            persistCloudPlaybackBinding(updated)
                        }
                    }
                }
            }
        jobs.forEach { it.join() }
    }

    fun videoPriorityPool(source: List<DiscogsVersionSeed>): List<DiscogsVersionSeed> =
        orderedResults(source).sortedWith(
            compareByDescending<DiscogsVersionSeed> { it.confidenceScore }
                .thenByDescending { it.originalWorkReference }
                .thenByDescending { it.sourceNames.distinct().size }
                .thenByDescending { it.track != null }
                .thenBy { it.artist.lowercase() }
                .thenBy { it.trackTitle.lowercase() },
        )

    // LAB59: cover cards are videos, not extra release posters.  A valid YouTube
    // video binding is enough to display its OWN thumbnail immediately; the
    // expensive stream check only determines playback readiness, not visibility.
    fun hasVideoPreview(seed: DiscogsVersionSeed): Boolean =
        !seed.resolvedVideoId.isNullOrBlank()

    // LAB61 Pollicino: a video ID is a lightweight streaming link, not proof
    // of an already-opened audio stream. The native player resolves audio on tap.
    fun isPlayReady(seed: DiscogsVersionSeed): Boolean = hasVideoPreview(seed)

    // LAB65: only an ID checked against YouTube metadata/recording can
    // be published. A playback-verified ID from LAB63 is also trusted.
    // Deliberately NO audio stream is requested for this check.
    fun hasPublishableVideo(seed: DiscogsVersionSeed): Boolean {
        val id = seed.resolvedVideoId?.trim().orEmpty()
        if (id.isBlank() || id in CoverPlaybackMemory.rejectedVideoIds(context, seed.fingerprint)) return false
        return id in session.playReadyVideoIds ||
            CoverPlaybackMemory.verifiedVideo(context, seed.fingerprint) == id ||
            CoverPlaybackMemory.metadataVerifiedVideo(context, seed.fingerprint) == id
    }

    fun originalCandidatePool(): List<DiscogsVersionSeed> =
        sortGroup(
            orderedResults(results).filterNot(::isHiddenForCurrentCover)
                .filter(::isOriginalPerformerVersion), DirectVersionSort.RELEVANCE,
        )

    fun coverCandidatePool(): List<DiscogsVersionSeed> {
        val candidates = orderedResults(results)
            .filterNot(::isHiddenForCurrentCover)
            .filterNot(::isOriginalPerformerVersion)
        // Published cards are immutable; uncommitted candidates may be
        // reordered by score 20→1 BEFORE their five-item batch is shown.
        val byId = candidates.associateBy { it.fingerprint }
        val committed = publishedCoverSnapshots.mapNotNull { byId[it.fingerprint] }
        val used = committed.mapTo(HashSet<String>()) { it.fingerprint }
        val pending = sortGroup(candidates.filterNot { it.fingerprint in used }, DirectVersionSort.RELEVANCE)
        return committed + pending
    }

    fun remainingCoverPool(): List<DiscogsVersionSeed> {
        val committed = publishedCoverSnapshots.mapTo(HashSet<String>()) { it.fingerprint }
        return coverCandidatePool().filterNot { it.fingerprint in committed }
    }

    fun visibleCoverPool(): List<DiscogsVersionSeed> =
        coverCandidatePool().take(visibleLimit.coerceAtLeast(pageSize))

    // LAB64: more chances for high-confidence recordings, but never many
    // parallel jobs. All IDs remain in the ranked archive even if not found.
    fun autoVideoAttemptBudget(seed: DiscogsVersionSeed): Int = when {
        seed.confidenceScore >= 17 -> 4
        seed.confidenceScore >= 10 -> 3
        else -> 2
    }

    fun needsAutomaticVideo(seed: DiscogsVersionSeed): Boolean =
        !hasPublishableVideo(seed) && playableTrack(seed) != null &&
            (session.automaticVideoAttempts[seed.fingerprint] ?: 0) < autoVideoAttemptBudget(seed)

    fun videoAttemptSettled(seed: DiscogsVersionSeed): Boolean =
        hasPublishableVideo(seed) || playableTrack(seed) == null ||
            (session.automaticVideoAttempts[seed.fingerprint] ?: 0) >= autoVideoAttemptBudget(seed)

    fun videoPreparationPool(): List<DiscogsVersionSeed> {
        if (isTurbo && session.turboFinished) return emptyList()
        if (mode != DiscogsDirectMode.COVER) {
            return orderedResults(results).take(visibleLimit.coerceAtLeast(pageSize))
                .filter(::needsAutomaticVideo)
        }
        // LAB65: before Cover display, resolve a bounded original-first gate.
        if (!originalSectionFrozen) {
            return originalCandidatePool().take(DIRECT_ORIGINAL_PRIORITY_COUNT)
                .filter(::needsAutomaticVideo)
        }
        // Probe beyond 10 RAW candidates to fill 10 PLAYABLE slots.
        // Only the next five highest-ranked missing IDs are investigated.
        // Exhaust their bounded attempts before spending effort on weaker rows.
        // An unplayable 19/20 cannot silently be overtaken by a ready 2/20.
        return coverCandidatePool()
            .take(if (isTurbo) TURBO_MAX_RANK_CANDIDATES else Int.MAX_VALUE)
            .filter(::needsAutomaticVideo)
            .take(if (isTurbo) TURBO_RESULT_QUOTA else DIRECT_VIDEO_BATCH_SIZE)
    }

    fun preparationReadyVideoCount(): Int =
        // LAB65B: source IDs and metadata-verified IDs are not equivalent.
        results.count(::hasPublishableVideo)

    fun readyVideoCount(): Int =
        if (mode == DiscogsDirectMode.COVER) {
            visibleCoverPool().count(::hasPublishableVideo)
        } else {
            orderedResults(results).count { !it.resolvedVideoId.isNullOrBlank() }
        }

    // LAB65 Pollicino: append-only batches of FIVE verified ID bindings.
    // No artwork-only seeds, no list motion, no pre-listening.
    suspend fun publishReadyBatches() {
        if (mode != DiscogsDirectMode.COVER || !rankingFrozen) return

        if (!originalSectionFrozen) {
            val candidates = originalCandidatePool().take(DIRECT_ORIGINAL_PRIORITY_COUNT)
            val elapsed = SystemClock.elapsedRealtime() - session.originalGateStartedAtMs
            val gateMs = if (isTurbo) TURBO_COVER_ORIGINAL_GATE_MS else DIRECT_ORIGINAL_FIRST_GATE_MS
            if (!candidates.all(::videoAttemptSettled) && elapsed < gateMs) return

            // Seal originals BEFORE any cover row appears: never insert a
            // late original above a visible Cover row.
            publishedOriginalSnapshots = candidates.filter(::hasPublishableVideo)
            session.publishedOriginalSnapshots = publishedOriginalSnapshots
            originalSectionFrozen = true
            session.originalSectionFrozen = true
            if (session.coverBatchStartedAtMs == 0L) {
                session.coverBatchStartedAtMs = SystemClock.elapsedRealtime()
            }
        }

        val publicationTarget = if (isTurbo) TURBO_RESULT_QUOTA else visibleLimit
        while (publishedCoverSnapshots.size < publicationTarget) {
            val slots = minOf(DIRECT_VIDEO_BATCH_SIZE, publicationTarget - publishedCoverSnapshots.size)
            val remaining = remainingCoverPool()
            if (remaining.isEmpty()) break

            // Do not let rank 2 overtake rank 19 while rank 19 is pending.
            // Only a settled higher-score prefix may feed the next group.
            val settledPrefix = remaining.takeWhile(::videoAttemptSettled)
            val ready = settledPrefix.filter(::hasPublishableVideo)
            val sourceExhausted = remaining.all(::videoAttemptSettled) &&
                !sourceDiscoveryLoading && (currentPage <= 0 || currentPage >= totalPages)
            // LAB65C: 220 pages of Discogs must never prevent already VERIFIED
            // covers from appearing forever. Prefer groups of five; only at the
            // bounded safety deadline publish a smaller committed group.
            // takeWhile(videoAttemptSettled) still prevents 2/20 beating a
            // pending higher-scored 19/20.
            val graceElapsed = session.coverBatchStartedAtMs > 0L &&
                SystemClock.elapsedRealtime() - session.coverBatchStartedAtMs >=
                    DIRECT_COVER_PARTIAL_BATCH_GRACE_MS
            if (ready.size < slots && !sourceExhausted && !graceElapsed) break
            val group = ready.take(slots)
            if (group.isEmpty()) break

            publishedCoverSnapshots = publishedCoverSnapshots + group
            session.publishedCoverSnapshots = publishedCoverSnapshots
            publishedReadyLimit = publishedCoverSnapshots.size
            session.publishedReadyLimit = publishedReadyLimit
            session.coverBatchStartedAtMs = SystemClock.elapsedRealtime()
        }
    }

    suspend fun resolveNextVideoBatch(limit: Int = DIRECT_VIDEO_BATCH_SIZE) {
        // Do not let malformed/discographically incomplete seeds permanently
        // block the 5+5 queue; keep their ranking evidence for later inspection.
        videoPreparationPool()
            .filter { !hasPublishableVideo(it) && playableTrack(it) == null }
            .take(limit)
            .forEach { replaceSeed(DiscogsVersionSource.markVideoUnavailable(it)) }

        val batch =
            videoPreparationPool()
                .filter { seed ->
                    DiscogsVersionSource.isDisplayableDirectSeed(seed) &&
                        playableTrack(seed) != null &&
                        !hasPublishableVideo(seed)
                }
                .take(limit)
        if (batch.isEmpty()) {
            publishReadyBatches()
            return
        }
        batch.chunked(if (isTurbo && !playbackIsNormallyPlaying()) 4 else DIRECT_VIDEO_PARALLELISM).forEach { chunk ->
            chunk.forEach { seed ->
                session.automaticVideoAttempts[seed.fingerprint] =
                    (session.automaticVideoAttempts[seed.fingerprint] ?: 0) + 1
            }
            // LAB66: try a short first pass. Higher-ranked recordings keep
            // their original full timeout on subsequent rounds, preserving
            // discovery breadth rather than merely dropping difficult videos.
            // No audio stream is opened during either metadata pass.
            val firstPass = chunk.all {
                (session.automaticVideoAttempts[it.fingerprint] ?: 0) == 1
            }
            val budgetMs =
                if (firstPass) DIRECT_VIDEO_FIRST_PASS_TIMEOUT_MS
                else DIRECT_AUTO_VIDEO_LOOKUP_TIMEOUT_MS
            withTimeoutOrNull(budgetMs) {
                resolveVideoChunk(chunk)
            }
        }
        publishReadyBatches()
    }

    // LAB61: intentionally no media-metadata prewarming. Video IDs alone
    // populate the cards; audio stream setup belongs exclusively to playback.

    fun playbackIsNormallyPlaying(): Boolean =
        playerConnection?.isEffectivelyPlaying?.value == true

    fun playbackIsCritical(): Boolean =
        playerConnection?.isPlaybackPriorityBurstActive() == true ||
            playerConnection?.playbackState?.value == Player.STATE_BUFFERING

    fun backgroundWorkBlocked(): Boolean = backgroundPausedForPlayback || playbackIsCritical()

    fun scheduleDiscogsVerification() {
        if (isTurbo && session.turboFinished) return
        if (verificationJob?.isActive == true) return
        if (backgroundWorkBlocked()) return
        // LAB61: avoid racing heavy Discogs details against video-ID discovery.
        if (videoPreloadJob?.isActive == true) return
        verificationJob =
            scope.launch {
                while (true) {
                    if (backgroundWorkBlocked()) break
                    val pending =
                        (if (mode == DiscogsDirectMode.COVER) {
                            coverCandidatePool().take(visibleLimit.coerceAtLeast(pageSize))
                        } else {
                            videoPreparationPool()
                        }).firstOrNull { seed ->
                                !isHiddenForCurrentCover(seed) &&
                                    seed.releaseId > 0 &&
                                    seed.track == null &&
                                    !seed.discogsVerificationChecked
                            } ?: break

                    val enriched =
                        DiscogsVersionSource.enrichSeedMetadata(
                            token = discogsToken,
                            seed = pending,
                            targetTitle = title,
                            mode = mode,
                            originalArtist = resolvedOriginalArtist,
                        )
                    val updated =
                        if (mode == DiscogsDirectMode.COVER && rankingFrozen) {
                            DiscogsVersionSource.certifyForFrozenRanking(
                                seed = DiscogsVersionSource.applySharedWorkCreditEvidence(
                                    enriched,
                                    session.originalWorkCredits,
                                ),
                                targetTitle = TitleMeaningResolver.workAnchorTitle(title),
                                originalArtist = resolvedOriginalArtist,
                                originalYear = session.originalYear,
                                originalCredits = session.originalWorkCredits,
                            )
                        } else {
                            enriched
                        }
                    replaceSeed(updated)
                    // LAB61: do not restart parallel YouTube lookup on every
                    // Discogs metadata update; let the caller's next window own it.
                    delay(if (playbackIsNormallyPlaying()) 300 else 120)
                }
            }
    }

    fun scheduleVideoPreload() {
        if (isTurbo && session.turboFinished) return
        if (videoPreloadJob?.isActive == true) return
        if (playerConnection == null) return
        // LAB65B: only lightweight video metadata validation may run while
        // another song is playing; at most one request and no audio stream.
        if (mode != DiscogsDirectMode.COVER && playbackIsNormallyPlaying()) return
        if (backgroundWorkBlocked()) return
        if (mode == DiscogsDirectMode.COVER && !rankingFrozen) return

        // LAB61: active Discogs verification yields to video-ID discovery.
        verificationJob?.cancel()
        videoPreloadJob =
            scope.launch {
                if (mode == DiscogsDirectMode.COVER) {
                    playerConnection?.service?.setCoverPerformanceLoad(
                        active = true, heavy = !playbackIsNormallyPlaying(),
                    )
                }
                try {
                    if (backgroundWorkBlocked()) return@launch
                    if (isTurbo && session.turboBurstStartedAtMs == 0L) {
                        session.turboBurstStartedAtMs = SystemClock.elapsedRealtime()
                    }
                    // LAB60: do not warm unselected media metadata or streams.

                    while (true) {
                        if (isTurbo && (
                            publishedCoverSnapshots.size >= TURBO_RESULT_QUOTA ||
                            SystemClock.elapsedRealtime() - session.turboBurstStartedAtMs >= TURBO_BURST_DEADLINE_MS
                        )) break
                        if (backgroundWorkBlocked() ||
                            (mode != DiscogsDirectMode.COVER && playbackIsNormallyPlaying())) break
                        val workWindow = videoPreparationPool()
                        if (workWindow.isEmpty()) {
                            publishReadyBatches()
                            break
                        }

                        val readyBefore = preparationReadyVideoCount()
                        resolveNextVideoBatch(
                            limit =
                                if (playbackIsNormallyPlaying()) {
                                    DIRECT_COVER_PLAYBACK_BATCH_SIZE
                                } else if (isTurbo) {
                                    4
                                } else {
                                    DIRECT_VIDEO_PARALLELISM
                                },
                        )
                        // LAB60: do not warm unselected media metadata or streams.
                        val readyAfter = preparationReadyVideoCount()

                        val anyPending =
                            videoPreparationPool().any { seed ->
                                DiscogsVersionSource.isDisplayableDirectSeed(seed) &&
                                    playableTrack(seed) != null &&
                                    !hasPublishableVideo(seed)
                            }
                        if (readyAfter == readyBefore && !anyPending) break
                        // LAB67: keep audio-priority pacing without an extra
                        // half-second gap between every ranked result.
                        delay(if (playbackIsNormallyPlaying()) 150 else 40)
                    }

                    // LAB60: no whole-page playback pre-warming.
                    publishReadyBatches()
                } finally {
                    if (isTurbo && mode == DiscogsDirectMode.COVER) {
                        session.turboFinished = true
                        verificationJob?.cancel()
                        paginationJob?.cancel()
                        searchJob?.cancel()
                        CloudMusicDiscovery.cancelArchiveRequests()
                        sourceDiscoveryLoading = false
                    }
                    if (mode == DiscogsDirectMode.COVER) {
                        playerConnection?.service?.setCoverPerformanceLoad(active = true, heavy = false)
                    }
                }
            }
        // LAB61: only after video discovery has finished, resume Discogs details.
        videoPreloadJob?.invokeOnCompletion { cause ->
            if (cause == null) {
                scope.launch {
                    if (!backgroundWorkBlocked() && !playbackIsNormallyPlaying()) {
                        scheduleDiscogsVerification()
                    }
                }
            }
        }
    }

    fun pauseCoverBackgroundForPlayback() {
        // LAB54: only the short playback-start critical lane owns the network.
        // Discovery/pagination survive because Cover must remain usable while music plays.
        backgroundPausedForPlayback = true
        verificationJob?.cancel()
        videoPreloadJob?.cancel()
        backgroundResumeJob?.cancel()
    }

    fun resumeCoverBackgroundAfterPlaybackBurst() {
        backgroundResumeJob?.cancel()
        backgroundResumeJob =
            scope.launch {
                while (playbackIsCritical()) {
                    delay(250)
                }
                delay(180)
                if (playbackIsCritical()) return@launch
                // LAB59: discovery may stay cached, but heavy video probes must
                // not compete with an actively playing song.
                backgroundPausedForPlayback = false
                // Metadata ID checks are safe while music plays; detailed
                // source expansion remains idle-only.
                scheduleVideoPreload()
                if (!playbackIsNormallyPlaying()) scheduleDiscogsVerification()
            }
    }

    fun saveDecision(seed: DiscogsVersionSeed, status: AiBrainDecisionStatus) {
        val config = foreignScoutConfig
        if (config == null || config.cloudEndpoint.isBlank()) {
            Toast.makeText(context, "Archivio cloud non configurato.", Toast.LENGTH_SHORT).show()
            return
        }
        if (resolvedOriginalArtist.isBlank() || title.isBlank()) {
            Toast.makeText(context, "Opera originale non identificata.", Toast.LENGTH_SHORT).show()
            return
        }
        decisionSavingFingerprint = seed.fingerprint
        scope.launch {
            val candidate = seedToBrainCandidate(seed)
            val saved =
                CloudMusicDiscovery.saveBrainDecision(
                    originalTitle = title,
                    originalArtist = resolvedOriginalArtist,
                    candidate = candidate,
                    status = status,
                    config = config,
                    categoryOverride =
                        if (mode == DiscogsDirectMode.ORIGINAL) {
                            cloudCategory(seed)
                        } else {
                            null
                        },
                )
            decisionSavingFingerprint = null
            if (!saved) {
                Toast.makeText(context, "Salvataggio cloud non riuscito.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (status == AiBrainDecisionStatus.REJECTED) {
                session.approvedKeys -= rejectionKey(seed)
                session.rejectedKeys += rejectionKey(seed)
                val hidden =
                    seed.copy(
                        confidenceReasons =
                            (seed.confidenceReasons + "Disapprovata manualmente per questa specifica ricerca").distinct(),
                        manuallyApproved = false,
                    )
                replaceSeed(hidden)
                detailSeed = null
                rebuildStableOrder()
                scheduleVideoPreload()
                Toast.makeText(
                    context,
                    "Disapprovata: non comparirà più tra le cover di questa canzone.",
                    Toast.LENGTH_LONG,
                ).show()
            } else if (status == AiBrainDecisionStatus.APPROVED) {
                session.rejectedKeys -= rejectionKey(seed)
                session.approvedKeys += rejectionKey(seed)
                val approved =
                    seed.copy(
                        confidenceScore = 20,
                        confidenceReasons =
                            (seed.confidenceReasons + "Approvata manualmente e salvata nel cloud").distinct(),
                        sourceNames = (seed.sourceNames + "Archivio Cloud").distinct(),
                        manuallyApproved = true,
                    )
                replaceSeed(approved)
                detailSeed = null
                Toast.makeText(
                    context,
                    "Approvata e salvata nell'Archivio MusicLab.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // LAB63 Pollicino: lock the very first visible artwork per ranked recording.
    // It is not changed when video searches discover an alternative URL.
    fun stableArtworkFor(seed: DiscogsVersionSeed): String? {
        // LAB67: video preview is taken from the verified video's ID,
        // never a potentially unrelated Discogs album/release poster.
        // Already-published Cover cards keep their video IDs immutable.
        val videoId = seed.resolvedVideoId?.trim()?.takeIf(String::isNotBlank)
        if (mode == DiscogsDirectMode.COVER && videoId != null) {
            return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        }
        val candidate = seed.coverUrl?.takeIf(String::isNotBlank)
            ?: videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        return CoverPlaybackMemory.stableArtwork(context, seed.fingerprint, candidate)
    }

    suspend fun playResolvedContext(selectedFingerprint: String) {
        val connection = playerConnection ?: return
        var selectedSeed =
            orderedResults(results)
                .firstOrNull { it.fingerprint == selectedFingerprint && !it.resolvedVideoId.isNullOrBlank() }
                ?: return
        var selectedId = selectedSeed.resolvedVideoId?.trim().orEmpty()
        if (selectedId.isBlank()) return

        // LAB59: the selected video already has a trusted source binding.
        // Get lightweight metadata from cache (or one short queue lookup), then
        // pass it straight to the native player.  A second synchronous stream
        // probe here caused many seconds of tap-to-play latency and doubled
        // network contention with ExoPlayer's own stream request.
        // LAB61: even the 900 ms queue lookup was delaying first sound.
        // A known YouTube ID can go straight into the native player metadata item.
        val selectedSong = session.preparedVideoSongs[selectedId]

        val selectedArtwork = stableArtworkFor(selectedSeed)
        if (!selectedArtwork.isNullOrBlank()) {
            CoverPlaybackMemory.pinVideoArtwork(context, selectedId, selectedArtwork)
        }
        val selectedItem =
            selectedSong?.toMediaMetadata()?.copy(thumbnailUrl = selectedArtwork ?: selectedSong?.thumbnail)
                ?.toMediaItem()
                ?: MediaMetadata(
                    id = selectedId,
                    title = selectedSeed.resolvedVideoTitle?.takeIf(String::isNotBlank)
                        ?: selectedSeed.trackTitle,
                    artists =
                        listOf(
                            MediaMetadata.Artist(
                                id = null,
                                name = selectedSeed.artist,
                            ),
                        ),
                    duration = selectedSeed.durationSeconds ?: -1,
                    thumbnailUrl = selectedArtwork,
                ).toMediaItem()

        val queueTitle =
            if (mode == DiscogsDirectMode.COVER) "Cover · $title" else "Originali · $title"

        connection.playQueue(
            ListQueue(
                title = queueTitle,
                items = listOf(selectedItem),
                startIndex = 0,
            ),
        )
        // Swipe navigation is exclusive to COVER performer rows, not the
        // original performer, the standalone player, or another app screen.
        if (mode == DiscogsDirectMode.COVER && !isOriginalPerformerVersion(selectedSeed)) {
            CoverSwipeBridge.activate(sessionKey, selectedId)
        } else {
            CoverSwipeBridge.stop(sessionKey)
        }
        // A compatible YouTube ID is not a verified playable stream.
        // Persist the winning ID only once Media3 is READY and this exact track
        // is really playing. Never pin a failed or stalled candidate.
        scope.launch {
            repeat(50) {
                delay(180)
                if (connection.mediaMetadata.value?.id == selectedId &&
                    connection.playbackState.value == Player.STATE_READY &&
                    connection.isEffectivelyPlaying.value
                ) {
                    CoverPlaybackMemory.saveVerifiedVideo(context, selectedFingerprint, selectedId, selectedArtwork)
                    return@launch
                }
            }
        }
    }

    suspend fun loadPage(
        criteria: DiscogsVersionSearchCriteria,
        page: Int,
        replace: Boolean,
        requestedSort: DirectVersionSort = sortMode,
    ): Boolean {
        // LAB38A sort/filter controls are local UI operations. Discogs pagination
        // must not restart or change merely because Bruno changes presentation order.
        val discogsSort: String? = null
        val discogsOrder: String? = null

        val pageResult = DiscogsVersionSource.loadVersionPage(
            token = discogsToken,
            mode = mode,
            criteria = criteria,
            originalArtist = resolvedOriginalArtist,
            page = page,
            perPage = DIRECT_VERSION_SOURCE_FETCH_SIZE,
            sort = discogsSort,
            sortOrder = discogsOrder,
        ).getOrElse { failure ->
            val message = failure.message ?: "Errore Discogs"
            if (replace) error = message else paginationError = message
            val discogsDiagnostic =
                CoverSourceDiagnostic(
                    name = "Discogs",
                    available = false,
                    found = 0,
                    note = message,
                )
            sourceDiagnostics =
                listOf(discogsDiagnostic) +
                    sourceDiagnostics.filterNot { it.name == "Discogs" }
            session.sourceDiagnostics = sourceDiagnostics
            return false
        }

        val incomingItems =
            if (mode == DiscogsDirectMode.COVER && rankingFrozen) {
                // LAB60: on-demand pages fetch summaries only; detailed releases
                // are certified by the existing per-visible-row verification job.
                pageResult.items.map { seed ->
                    DiscogsVersionSource.certifyForFrozenRanking(
                        seed = DiscogsVersionSource.applySharedWorkCreditEvidence(
                            seed,
                            session.originalWorkCredits,
                        ),
                        targetTitle = TitleMeaningResolver.workAnchorTitle(criteria.title),
                        originalArtist = resolvedOriginalArtist,
                        originalYear = session.originalYear,
                        originalCredits = session.originalWorkCredits,
                    )
                }
            } else {
                pageResult.items
            }

        results = mergePage(results, incomingItems, replace)
        currentPage = pageResult.page
        totalPages = pageResult.pages
        totalDiscogsResults = pageResult.totalDiscogsResults
        activeCriteria = criteria
        error = null
        paginationError = null

        session.results = results
        session.currentPage = currentPage
        session.totalPages = totalPages
        session.totalDiscogsResults = totalDiscogsResults
        session.activeCriteria = activeCriteria
        session.initialized = true
        if (mode == DiscogsDirectMode.COVER) {
            if (rankingFrozen) syncStableOrder() else rebuildStableOrder()
        } else {
            syncStableOrder()
        }

        val discogsFound = results.count { "Discogs" in it.sourceNames }
        val discogsDiagnostic =
            CoverSourceDiagnostic(
                name = "Discogs",
                available = true,
                found = discogsFound,
                note = "$totalDiscogsResults release disponibili · pagina sorgente $currentPage/$totalPages",
            )
        sourceDiagnostics =
            listOf(discogsDiagnostic) +
                sourceDiagnostics.filterNot { it.name == "Discogs" }
        session.sourceDiagnostics = sourceDiagnostics
        return true
    }

    fun runSearch(requestedSort: DirectVersionSort = sortMode) {
        sortMode = requestedSort
        session.sortMode = requestedSort
        persistInputs()
        val explicitArtistHint = TitleMeaningResolver.trailingArtistHint(title)
        if (!explicitArtistHint.isNullOrBlank()) {
            resolvedOriginalArtist = explicitArtistHint
        }
        val criteria = buildCriteria()
        if (criteria.title.isBlank()) {
            error = "Inserisci il titolo del brano."
            return
        }
        if (mode == DiscogsDirectMode.ORIGINAL && resolvedOriginalArtist.isBlank()) {
            error = "Interprete originale di riferimento mancante."
            return
        }
        val repeatSameWork = activeCriteria?.title?.equals(criteria.title, ignoreCase = true) == true
        if (isTurbo && repeatSameWork && session.turboFinished) {
            // Re-enter a completed 50-result burst without re-querying providers.
            loading = false
            sourceDiscoveryLoading = false
            return
        }
        session.turboFinished = false
        session.turboBurstStartedAtMs = 0L
        session.turboArchiveMatches = 0
        val retainedResults = if (repeatSameWork) results else emptyList()
        val retainedOrder = if (repeatSameWork) session.stableOrder else emptyList()
        searchJob?.cancel()
        paginationJob?.cancel()
        verificationJob?.cancel()
        videoPreloadJob?.cancel()
        loading = true
        loadingMore = false
        error = null
        paginationError = null
        // LAB59 instant cache replay: a repeat search must not flash an empty list.
        results = retainedResults
        currentPage = 0
        totalPages = 0
        totalDiscogsResults = 0
        activeCriteria = criteria
        selectedFingerprint = null
        visibleLimit = pageSize
        resultPageIndex = 0
        headerExpanded = false
        session.headerExpanded = false
        sourcePrefetchedForVisibleLimit = -1
        sourceDiagnostics = emptyList()
        sourceDiscoveryLoading = true
        rankingFrozen = false
        publishedReadyLimit = 0
        publishedOriginalSnapshots = emptyList()
        publishedCoverSnapshots = emptyList()
        originalSectionFrozen = false
        session.originalGateStartedAtMs = 0L
        session.coverBatchStartedAtMs = 0L
        CoverSwipeBridge.stop(sessionKey)
        if (mode == DiscogsDirectMode.COVER) {
            // LAB59: searching must not downgrade or compete with active audio.
            playerConnection?.service?.setCoverPerformanceLoad(
                active = true,
                heavy = !playbackIsNormallyPlaying(),
            )
        }

        session.results = retainedResults
        session.currentPage = 0
        session.totalPages = 0
        session.totalDiscogsResults = 0
        session.activeCriteria = criteria
        session.selectedFingerprint = null
        session.listIndex = 0
        session.listOffset = 0
        session.stableOrder = retainedOrder
        session.visibleLimit = pageSize
        session.resultPageIndex = 0
        session.sourceDiagnostics = emptyList()
        session.sourceDiscoveryComplete = false
        session.originalYear = null
        session.rankingFrozen = false
        session.publishedReadyLimit = 0
        session.publishedOriginalSnapshots = emptyList()
        session.publishedCoverSnapshots = emptyList()
        session.originalSectionFrozen = false
        session.originalGateStartedAtMs = 0L
        session.coverBatchStartedAtMs = 0L
        if (!repeatSameWork) {
            session.usedVideoIds.clear()
            session.knownVideoBindings.clear()
            session.preparedVideoSongs.clear()
            session.playReadyVideoIds.clear()
            session.transientVideoRetries.clear()
            session.automaticVideoAttempts.clear()
        }
        session.originalWorkCredits = emptyList()

        searchJob =
            scope.launch {
                listState.scrollToItem(0)

            // LAB59: three independent discovery lanes start immediately.
            // Previously a slow cloud lookup blocked even the FIRST Discogs page.
            val firstPageDeferred = async {
                loadPage(
                    criteria = criteria,
                    page = 1,
                    replace = false,
                    requestedSort = requestedSort,
                )
            }
            // LAB68: a bounded on-disk snapshot can be read independently
            // from a fresh Cloud refresh. Never gate already-vetted cards on HTTP.
            val cachedMemoryDeferred = async(Dispatchers.IO) {
                foreignScoutConfig?.let { config ->
                    CloudMusicDiscovery.cachedMemoryState(
                        context = context,
                        title = criteria.title,
                        artist = resolvedOriginalArtist,
                        config = config,
                        mode = if (mode == DiscogsDirectMode.ORIGINAL) "originals" else "cover",
                    )
                }
            }
            val memoryDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                foreignScoutConfig?.let { config ->
                    runCatching {
                        CloudMusicDiscovery.discoverMemoryState(
                            title = criteria.title,
                            artist = resolvedOriginalArtist,
                            config = config,
                            mode = if (mode == DiscogsDirectMode.ORIGINAL) "originals" else "cover",
                            limit = 150,
                            cacheContext = context,
                        )
                    }.getOrNull()
                }
            }
            val externalDeferred =
                async(kotlinx.coroutines.Dispatchers.IO) {
                    CoverDiscoverySources.discover(
                        title = criteria.title,
                        originalArtist = resolvedOriginalArtist,
                        mode = mode,
                        aiConfig = foreignScoutConfig,
                        maxNetworkFanOut = if (playbackIsNormallyPlaying()) 1 else 2,
                        onEarlyVideoCandidates = { early ->
                            // LAB59: COVER.INFO can supply direct YouTube video IDs
                            // before the entire multi-provider search has returned.
                            // Only the currently active search may publish them.
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (activeCriteria?.title == criteria.title && early.isNotEmpty()) {
                                    val videoSeeds = early.map { candidate ->
                                        DiscogsVersionSource.externalSeed(
                                            candidate = candidate,
                                            targetTitle = criteria.title,
                                            originalArtist = resolvedOriginalArtist,
                                        )
                                    }
                                    results = mergePage(results, videoSeeds, replace = false)
                                    session.results = results
                                    if (!rankingFrozen) rebuildStableOrder() else syncStableOrder()
                                }
                            }
                        },
                    )
                }

            val cachedMemory = cachedMemoryDeferred.await()
            val memoryState = cachedMemory ?: memoryDeferred.await()

            if (mode == DiscogsDirectMode.COVER && explicitArtistHint.isNullOrBlank()) {
                memoryState
                    ?.discovery
                    ?.original
                    ?.artist
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { resolvedOriginalArtist = it }
            }

            session.rejectedKeys.clear()
            session.rejectedKeys.addAll(memoryState?.rejectedKeys.orEmpty())
            session.approvedKeys.clear()

            // LAB53: weak/unconfirmed evidence remains ranking-only. A manual user
            // rejection is different: it hides that candidate only for this work/search.
            val memoryVersions =
                memoryState
                    ?.discovery
                    ?.versions
                    .orEmpty()

            session.approvedKeys.addAll(
                memoryVersions
                    .filter { it.brainStatus == AiBrainDecisionStatus.APPROVED }
                    .map { candidate ->
                        val categoryName =
                            if (mode == DiscogsDirectMode.ORIGINAL) {
                                when (candidate.category) {
                                    AiCoverCategory.LIVE -> "live"
                                    AiCoverCategory.REMIX -> "remix"
                                    AiCoverCategory.FOREIGN -> "straniera"
                                    AiCoverCategory.COVER -> "originale"
                                }
                            } else {
                                when (candidate.category) {
                                    AiCoverCategory.LIVE -> "live"
                                    AiCoverCategory.REMIX -> "remix"
                                    AiCoverCategory.FOREIGN -> "straniera"
                                    AiCoverCategory.COVER -> "cover"
                                }
                            }
                        CloudMusicDiscovery.memoryKey(
                            candidate.title,
                            candidate.artist,
                            categoryName,
                        )
                    },
            )

            if (memoryVersions.isNotEmpty()) {
                // LAB59: first Discogs page and direct COVER.INFO videos run
                // concurrently with Cloud memory. Never replace their results
                // when the slower Cloud response arrives.
                results =
                    mergePage(
                        current = results,
                        incoming = memoryVersions.map(::memoryCandidateToSeed),
                        replace = false,
                    )
                session.results = results
                // LAB68: a Cloud snapshot with previously HARD-compatible video
                // IDs can be committed without waiting for Discogs, AI or
                // COVER.INFO. Pending higher-ranked IDs still block lower ones.
                // A cold/uncertified archive remains ranking-only until vetted.
                if (mode == DiscogsDirectMode.COVER &&
                    !rankingFrozen && results.any(::hasPublishableVideo)) {
                    val anchor = TitleMeaningResolver.workAnchorTitle(criteria.title)
                    val cloudYear = memoryState?.discovery?.original?.year
                    val cloudSnapshot = results
                    val certified = withContext(Dispatchers.Default) {
                        cloudSnapshot.map { seed ->
                            DiscogsVersionSource.certifyForFrozenRanking(
                                seed = seed,
                                targetTitle = anchor,
                                originalArtist = resolvedOriginalArtist,
                                originalYear = cloudYear,
                                originalCredits = emptyList(),
                            )
                        }
                    }
                    if (activeCriteria == criteria && !rankingFrozen) {
                        results = mergePage(results, certified, replace = false)
                        session.results = results
                        session.originalYear = cloudYear
                        rebuildStableOrder()
                        rankingFrozen = true
                        session.rankingFrozen = true
                        // An already-verified archive does not need the 7.6s
                        // original gate nor the 2.8s partial-batch wait.
                        session.originalGateStartedAtMs =
                            SystemClock.elapsedRealtime() - DIRECT_ORIGINAL_FIRST_GATE_MS
                        session.coverBatchStartedAtMs =
                            SystemClock.elapsedRealtime() - DIRECT_COVER_PARTIAL_BATCH_GRACE_MS
                        publishReadyBatches()
                        scheduleVideoPreload()
                    }
                }
            }
            val memoryDiagnostic =
                CoverSourceDiagnostic(
                    name = "Archivio Cloud",
                    available = memoryState != null,
                    found = memoryVersions.size,
                    note =
                        if (session.rejectedKeys.isEmpty()) {
                            "cloud-first"
                        } else {
                            "cloud-first · ${session.rejectedKeys.size} disapprovate nascoste per questa ricerca"
                        },
                )
            // Keep diagnostics from providers that completed before Cloud.
            sourceDiagnostics = listOf(memoryDiagnostic) +
                sourceDiagnostics.filterNot { it.name == "Archivio Cloud" }
            session.sourceDiagnostics = sourceDiagnostics

            val firstPageLoaded = firstPageDeferred.await()

            var external =
                runCatching { externalDeferred.await() }
                    .getOrElse { CoverSourceOutcome(emptyList(), emptyList()) }

            if (mode == DiscogsDirectMode.COVER && explicitArtistHint.isNullOrBlank()) {
                consensusOriginalArtist(external.candidates)
                    ?.let { resolvedOriginalArtist = it }
            }

            // Only when identity/coverage is weak, use the simplified work anchor as
            // a second discovery lane. The original decorated title remains untouched
            // and its live/remix/duet/etc. signals are still represented by the first lane.
            val workAnchor = TitleMeaningResolver.workAnchorTitle(criteria.title)
            val hasExplicitOriginalEvidence = external.candidates.any { it.originalWorkReference }
            val weakCoverage = external.candidates.size < 5 || totalDiscogsResults == 0
            val needsAnchorFallback =
                workAnchor.isNotBlank() &&
                    !workAnchor.equals(criteria.title, ignoreCase = true) &&
                    (!hasExplicitOriginalEvidence || weakCoverage)

            if (needsAnchorFallback) {
                val fallback =
                    runCatching {
                        CoverDiscoverySources.discover(
                            title = workAnchor,
                            originalArtist = resolvedOriginalArtist,
                            mode = mode,
                            aiConfig = foreignScoutConfig,
                            maxNetworkFanOut = if (playbackIsNormallyPlaying()) 1 else 2,
                        )
                    }.getOrElse { CoverSourceOutcome(emptyList(), emptyList()) }
                external = mergeDiscoveryOutcomes(external, fallback)

                if (mode == DiscogsDirectMode.COVER && explicitArtistHint.isNullOrBlank()) {
                    consensusOriginalArtist(external.candidates)
                        ?.let { resolvedOriginalArtist = it }
                }

                if (totalDiscogsResults == 0) {
                    loadPage(
                        criteria = criteria.copy(title = workAnchor),
                        page = 1,
                        replace = false,
                        requestedSort = requestedSort,
                    )
                }
            }

            // LAB58: collect a bounded Discogs search horizon BEFORE the
            // global ranking is certified and frozen. All collected candidates
            // are scored together before any Cover video card is published.
            if (mode == DiscogsDirectMode.COVER && firstPageLoaded) {
                var rankPagesFetched = 1
                while (
                    currentPage > 0 &&
                    currentPage < totalPages &&
                    rankPagesFetched < DIRECT_COVER_RANK_MAX_SOURCE_PAGES &&
                    results.size < DIRECT_COVER_RANK_TARGET &&
                    !backgroundWorkBlocked()
                ) {
                    if (!loadPage(criteria, currentPage + 1, replace = false)) break
                    rankPagesFetched++
                }
            }

            if (external.candidates.isNotEmpty()) {
                val externalSeeds =
                    external.candidates.map { candidate ->
                        DiscogsVersionSource.externalSeed(
                            candidate = candidate,
                            targetTitle = workAnchor.takeIf { needsAnchorFallback } ?: criteria.title,
                            originalArtist = resolvedOriginalArtist,
                        )
                    }
                results = mergePage(results, externalSeeds, replace = false)
                session.results = results
            }
            val identityDiagnostic =
                CoverSourceDiagnostic(
                    name = "Identità opera",
                    available = resolvedOriginalArtist.isNotBlank(),
                    found = external.candidates.count { it.originalWorkReference },
                    note =
                        buildString {
                            append("titolo base: ")
                            append(TitleMeaningResolver.workAnchorTitle(criteria.title))
                            append(" · interprete: ")
                            append(resolvedOriginalArtist.ifBlank { "non risolto" })
                            if (needsAnchorFallback) append(" · fallback titolo-base usato")
                        },
                )
            sourceDiagnostics =
                sourceDiagnostics.filter { diagnostic ->
                    diagnostic.name == "Discogs" || diagnostic.name == "Archivio Cloud"
                } + external.diagnostics + identityDiagnostic
            session.sourceDiagnostics = sourceDiagnostics
            session.sourceDiscoveryComplete = true
            sourceDiscoveryLoading = false

            if (mode == DiscogsDirectMode.COVER) {
                val workTitle = TitleMeaningResolver.workAnchorTitle(criteria.title)

                // LAB67: run independent Discogs credits and initial detail
                // verification concurrently, merging only on the UI lane.
                val detailSeeds = if (discogsToken.isNotBlank()) {
                    results.filter { seed ->
                        seed.releaseId > 0 && !seed.discogsVerificationChecked
                    }.take(DIRECT_COVER_INITIAL_DETAIL_BUDGET)
                } else {
                    emptyList()
                }
                val (discogsCredits, verifiedRankSeeds) = coroutineScope {
                    val creditsDeferred = async(Dispatchers.IO) {
                        if (discogsToken.isNotBlank() && resolvedOriginalArtist.isNotBlank()) {
                            withTimeoutOrNull(5_500L) {
                                DiscogsVersionSource.loadOriginalWorkCredits(
                                    token = discogsToken,
                                    title = workTitle,
                                    originalArtist = resolvedOriginalArtist,
                                )
                            }.orEmpty()
                        } else emptyList()
                    }
                    val detailsDeferred = async(Dispatchers.IO) {
                        if (detailSeeds.isNotEmpty()) {
                            withTimeoutOrNull(4_500L) {
                                DiscogsVersionSource.enrichSeedsForRanking(
                                    token = discogsToken,
                                    seeds = detailSeeds,
                                    targetTitle = workTitle,
                                    mode = mode,
                                    originalArtist = resolvedOriginalArtist,
                                )
                            }.orEmpty()
                        } else emptyList()
                    }
                    creditsDeferred.await() to detailsDeferred.await()
                }

                session.originalWorkCredits =
                    (external.workCredits + discogsCredits)
                        .distinctBy { credit ->
                            credit.name.lowercase() + "|" + credit.role.lowercase()
                        }

                // LAB60 Pollicino: 4,000 Discogs catalog hits are a count,
                // not 4,000 release-detail requests. Certify only a bounded
                // up-front batch and preserve every other discovered candidate.
                val rankingEnriched =
                    if (verifiedRankSeeds.isNotEmpty()) {
                        mergePage(results, verifiedRankSeeds, replace = false)
                    } else {
                        results
                    }

                session.originalYear =
                    external.originalYear
                        ?: rankingEnriched
                            .filter { seed ->
                                resolvedOriginalArtist.isNotBlank() &&
                                    TitleMeaningResolver.sameArtist(seed.artist, resolvedOriginalArtist) &&
                                    TitleMeaningResolver.matchesBaseTitle(workTitle, seed.trackTitle)
                            }
                            .mapNotNull { it.year }
                            .minOrNull()

                results =
                    rankingEnriched.map { seed ->
                        val withCredits =
                            DiscogsVersionSource.applySharedWorkCreditEvidence(
                                seed,
                                session.originalWorkCredits,
                            )
                        DiscogsVersionSource.certifyForFrozenRanking(
                            seed = withCredits,
                            targetTitle = workTitle,
                            originalArtist = resolvedOriginalArtist,
                            originalYear = session.originalYear,
                            originalCredits = session.originalWorkCredits,
                        )
                    }
                session.results = results
                // LAB68: never reset a committed Cloud-first list when the
                // slower catalogs finish. Only uncommitted IDs may be appended.
                if (rankingFrozen) {
                    syncStableOrder()
                } else {
                    rebuildStableOrder()
                    rankingFrozen = true
                    session.rankingFrozen = true
                    session.originalGateStartedAtMs = SystemClock.elapsedRealtime()
                    publishedReadyLimit = 0
                    session.publishedReadyLimit = 0
                }
                scheduleVideoPreload()
                publishReadyBatches()
            } else {
                rebuildStableOrder()
            }

            session.visibleLimit = visibleLimit
            session.sourceDiscoveryComplete = true
            sourceDiscoveryLoading = false
            loading = false

            if (mode == DiscogsDirectMode.COVER) {
                playerConnection?.service?.setCoverPerformanceLoad(active = true, heavy = false)
            }

            scheduleVideoPreload()
            if (firstPageLoaded) {
                scheduleDiscogsVerification()
            }
        }
        searchJob?.invokeOnCompletion {
            scope.launch {
                loading = false
                sourceDiscoveryLoading = false
            }
        }
    }

    fun loadNextPage() {
        if (isTurbo && mode == DiscogsDirectMode.COVER) {
            if (visibleLimit < TURBO_RESULT_QUOTA &&
                publishedCoverSnapshots.size > visibleLimit) {
                visibleLimit = minOf(TURBO_RESULT_QUOTA, visibleLimit + pageSize)
                session.visibleLimit = visibleLimit
            }
            return
        }
        val criteria = activeCriteria ?: return
        if (loading || loadingMore) return

        if (mode == DiscogsDirectMode.COVER) {
            val poolSize = coverCandidatePool().size
            val target = visibleLimit + pageSize
            if (poolSize >= target || currentPage <= 0 || currentPage >= totalPages) {
                if (poolSize <= publishedCoverSnapshots.size && (currentPage <= 0 || currentPage >= totalPages)) return
                visibleLimit = target
                session.visibleLimit = visibleLimit
                verificationJob?.cancel()
                // LAB61: the existing bounded video job sees the enlarged page;
                // avoid cancellation/restart races while a row is being resolved.
                scope.launch { publishReadyBatches() }
                scheduleVideoPreload()
                scheduleDiscogsVerification()
                return
            }

            // LAB60: manual paging never silently exits because a song buffers.
            if (currentPage <= 0 || currentPage >= totalPages) return
            loadingMore = true
            paginationError = null
            paginationJob?.cancel()
            paginationJob =
                scope.launch {
                    try {
                        val loaded = loadPage(criteria, currentPage + 1, replace = false)
                        if (loaded) {
                            visibleLimit = visibleLimit + pageSize
                            session.visibleLimit = visibleLimit
                            verificationJob?.cancel()
                            publishReadyBatches()
                            scheduleVideoPreload()
                            scheduleDiscogsVerification()
                        }
                    } finally {
                        loadingMore = false
                    }
                }
            return
        }

        if (backgroundWorkBlocked()) return
        val target = visibleLimit + pageSize
        val currentlyAvailable = readyVideoCount()
        if (currentlyAvailable >= target) {
            visibleLimit = target
            session.visibleLimit = visibleLimit
            scheduleVideoPreload()
            scheduleDiscogsVerification()
            return
        }
        if (currentPage <= 0 || currentPage >= totalPages) {
            visibleLimit = target
            session.visibleLimit = visibleLimit
            scheduleVideoPreload()
            scheduleDiscogsVerification()
            return
        }

        loadingMore = true
        paginationError = null
        paginationJob?.cancel()
        paginationJob =
            scope.launch {
                try {
                    var available = readyVideoCount()
                    var page = currentPage
                    while (available < target && page < totalPages) {
                        page += 1
                        val loaded = loadPage(criteria, page, replace = false)
                        if (!loaded) break
                        available = readyVideoCount()
                    }
                    visibleLimit = target
                    session.visibleLimit = visibleLimit
                    scheduleVideoPreload()
                    scheduleDiscogsVerification()
                } finally {
                    loadingMore = false
                }
            }
    }

    // LAB63 Pollicino: Retry shares the same hard 3-second cap as tapping
    // a missing Cover video. No unbounded cloud/COVER.INFO/YouTube chain on UI.
    fun retryMissingVideo(seed: DiscogsVersionSeed) {
        if (resolvingFingerprint == seed.fingerprint) return
        val track = playableTrack(seed)
        if (track == null) {
            Toast.makeText(context, "Dati versione insufficienti per la ricerca video.", Toast.LENGTH_SHORT).show()
            return
        }
        resolvingFingerprint = seed.fingerprint
        scope.launch {
            try {
                val current = results.firstOrNull { it.fingerprint == seed.fingerprint } ?: seed
                withTimeoutOrNull(DIRECT_TAPPED_ROW_RESOLVE_TIMEOUT_MS) {
                    resolveVideoChunk(listOf(current.copy(videoResolutionChecked = false)))
                }
            } finally {
                resolvingFingerprint = null
            }
        }
    }

    fun play(seed: DiscogsVersionSeed) {
        CoverSwipeBridge.stop(sessionKey)
        session.listIndex = listState.firstVisibleItemIndex
        session.listOffset = listState.firstVisibleItemScrollOffset
        selectedFingerprint = seed.fingerprint
        session.selectedFingerprint = seed.fingerprint
        resolvingFingerprint = seed.fingerprint

        // The selected row owns the device immediately. Activate playback priority
        // BEFORE any tapped-row lookup so resolver work, preload and list discovery
        // can never outrank first sound.
        playerConnection?.beginPlaybackPriorityBurst("musiclab-version")
        playbackLaunchJob?.cancel()
        pauseCoverBackgroundForPlayback()
        backgroundResumeJob?.cancel()

        playbackLaunchJob = scope.launch {
            var currentSeed =
                results.firstOrNull { it.fingerprint == seed.fingerprint } ?: seed

            if (currentSeed.resolvedVideoId.isNullOrBlank()) {
                // LAB63: a previously verified recording starts immediately,
                // even when the current catalog page has no fresh video ID.
                CoverPlaybackMemory.verifiedVideo(context, currentSeed.fingerprint)
                    ?.takeIf { it !in CoverPlaybackMemory.rejectedVideoIds(context, currentSeed.fingerprint) }
                    ?.let { savedId ->
                        currentSeed = DiscogsVersionSource.markVideoResolved(
                            currentSeed, savedId, currentSeed.trackTitle, "MusicLab locale verificato",
                        )
                        replaceSeed(currentSeed)
                    }
            }
            if (currentSeed.resolvedVideoId.isNullOrBlank()) {
                // LAB61: even a previous timeout must not make a ranked row
                // permanently unplayable. Retry the chosen row on tap only.
                if (currentSeed.videoResolutionChecked) {
                    currentSeed = currentSeed.copy(videoResolutionChecked = false)
                    replaceSeed(currentSeed)
                }
                // Resolve only the tapped row and never allow a slow provider chain
                // to hold the playback lane indefinitely. Background discovery can
                // continue after the priority burst if this quick attempt times out.
                withTimeoutOrNull(DIRECT_TAPPED_ROW_RESOLVE_TIMEOUT_MS) {
                    resolveVideoChunk(listOf(currentSeed))
                }
                currentSeed =
                    results.firstOrNull { it.fingerprint == seed.fingerprint } ?: currentSeed
            }

            if (!currentSeed.resolvedVideoId.isNullOrBlank()) {
                resolvingFingerprint = null
                try {
                    playResolvedContext(currentSeed.fingerprint)
                } finally {
                    resumeCoverBackgroundAfterPlaybackBurst()
                }
                return@launch
            }

            resolvingFingerprint = null
            resumeCoverBackgroundAfterPlaybackBurst()
            Toast.makeText(
                context,
                if (currentSeed.videoResolutionChecked) {
                    "Video esatto non verificato per questa versione."
                } else {
                    "Video in preparazione automatica: riprova tra poco."
                },
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // LAB64: the Cover player can swipe through the full ranked result pool,
    // not only the 10 songs currently published in the list. The list itself
    // still expands solely when the user taps "Carica altre 10".
    fun swipeCover(direction: Int, playingId: String) {
        if (mode != DiscogsDirectMode.COVER || coverSwipeJob?.isActive == true ||
            playerConnection?.mediaMetadata?.value?.id != playingId) return
        coverSwipeJob = scope.launch {
            var ranked = orderedResults(results)
                .filterNot(::isOriginalPerformerVersion)
                .filterNot(::isHiddenForCurrentCover)
                .filter(::hasPublishableVideo)
            var position = ranked.indexOfFirst { it.fingerprint == selectedFingerprint }
            if (position < 0) return@launch
            var next = ranked.getOrNull(position + direction)
            if (next == null && direction > 0 && currentPage > 0 && currentPage < totalPages) {
                // Extra metadata fetch only on explicit Cover swipe; do not
                // advance the visible Cover list's manual page boundary.
                val criteria = activeCriteria
                if (criteria != null) {
                    withTimeoutOrNull(4_000L) {
                        loadPage(criteria, currentPage + 1, replace = false)
                    }
                    ranked = orderedResults(results)
                        .filterNot(::isOriginalPerformerVersion)
                        .filterNot(::isHiddenForCurrentCover)
                        .filter(::hasPublishableVideo)
                    position = ranked.indexOfFirst { it.fingerprint == selectedFingerprint }
                    next = ranked.getOrNull(position + direction)
                }
            }
            if (next != null) {
                play(next)
            } else {
                Toast.makeText(context, "Nessun'altra cover disponibile in questa direzione.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    SideEffect {
        if (mode == DiscogsDirectMode.COVER) {
            CoverSwipeBridge.bind(sessionKey) { direction, playingId ->
                swipeCover(direction, playingId)
            }
        }
    }

    LaunchedEffect(
        title,
        artistFilter,
        releaseTitle,
        year,
        format,
        country,
        label,
        genre,
        style,
        catalogNumber,
        filtersExpanded,
        category,
        sortMode,
    ) {
        persistInputs()
    }

    // LAB65B: release the original-first gate even when ID lookups are
    // blocked by buffering, all completed or interrupted.
    LaunchedEffect(sessionKey, rankingFrozen, originalSectionFrozen) {
        if (mode != DiscogsDirectMode.COVER || !rankingFrozen || originalSectionFrozen) return@LaunchedEffect
        val remaining = (DIRECT_ORIGINAL_FIRST_GATE_MS -
            (SystemClock.elapsedRealtime() - session.originalGateStartedAtMs)).coerceAtLeast(0L)
        delay(remaining)
        if (!originalSectionFrozen) {
            publishReadyBatches()
            scheduleVideoPreload()
        }
    }

    // LAB65C: source pagination can contain hundreds of remote pages. A
    // time-bound publisher retries the current verified prefix independently
    // of provider completion, without ever admitting cards without video IDs.
    LaunchedEffect(sessionKey, rankingFrozen, originalSectionFrozen, publishedCoverSnapshots.size, visibleLimit) {
        if (mode != DiscogsDirectMode.COVER || !rankingFrozen || !originalSectionFrozen ||
            publishedCoverSnapshots.size >= visibleLimit) return@LaunchedEffect
        val remaining = (DIRECT_COVER_PARTIAL_BATCH_GRACE_MS -
            (SystemClock.elapsedRealtime() - session.coverBatchStartedAtMs)).coerceAtLeast(0L)
        delay(remaining)
        publishReadyBatches()
    }

    LaunchedEffect(visibleLimit, sourceDiagnostics, rankingFrozen, publishedReadyLimit) {
        session.visibleLimit = visibleLimit
        session.sourceDiagnostics = sourceDiagnostics
        session.rankingFrozen = rankingFrozen
        session.publishedReadyLimit = publishedReadyLimit
    }

    // LAB68: ignore unrelated player/animation recompositions when the
    // ranking inputs did not change. No cached mutable UI state is duplicated.
    val orderedPool = remember(results, session.stableOrder, resolvedOriginalArtist, sortMode, mode) {
        orderedResults(results)
    }
    val navigablePool =
        if (mode == DiscogsDirectMode.COVER) {
            orderedPool.filterNot(::isHiddenForCurrentCover)
        } else {
            orderedPool
        }
    // LAB58: the Cover publication quota never counts Originali.
    val publishPool =
        if (mode == DiscogsDirectMode.COVER) {
            navigablePool.filterNot(::isOriginalPerformerVersion)
        } else {
            navigablePool
        }
    val visibleMembershipPool =
        if (mode == DiscogsDirectMode.COVER) {
            if (rankingFrozen) publishPool.take(visibleLimit.coerceAtLeast(pageSize)) else emptyList()
        } else {
            navigablePool
        }
    val nextBlockPool =
        if (mode == DiscogsDirectMode.COVER && rankingFrozen) {
            publishPool.drop(visibleLimit.coerceAtLeast(pageSize)).take(pageSize)
        } else {
            emptyList()
        }
    val readyPool =
        if (mode == DiscogsDirectMode.COVER) {
            visibleMembershipPool.filter(::isPlayReady)
        } else {
            visibleMembershipPool
        }
    // LAB65: only committed, validated, immutable rows reach Compose.
    val visibleOriginalVersions =
        if (mode == DiscogsDirectMode.COVER) publishedOriginalSnapshots
        else emptyList()
    val visibleTrueCovers =
        if (mode == DiscogsDirectMode.COVER) publishedCoverSnapshots.take(visibleLimit)
        else emptyList()
    val visibleResults =
        if (mode == DiscogsDirectMode.COVER) {
            visibleOriginalVersions + visibleTrueCovers
        } else {
            readyPool.take(visibleLimit)
        }
    val nextBlockReady = nextBlockPool.count(::isPlayReady)

    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collectLatest { (index, offset) ->
            val movingForward =
                index > previousIndex ||
                    (index == previousIndex && offset > previousOffset + 8)
            if (movingForward) {
                headerExpanded = false
                session.headerExpanded = false
            }
            previousIndex = index
            previousOffset = offset
            session.listIndex = index
            session.listOffset = offset
        }
    }

    LaunchedEffect(listState, activeCriteria, currentPage, totalPages, visibleResults.size, visibleLimit) {
        if (mode != DiscogsDirectMode.COVER) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            }.collectLatest { lastVisible ->
                if (
                    activeCriteria != null &&
                    visibleResults.size >= visibleLimit &&
                    lastVisible >= 0 &&
                    visibleResults.size - lastVisible <= DIRECT_VERSION_PREFETCH_DISTANCE &&
                    !loading &&
                    !loadingMore &&
                    (readyPool.size > visibleLimit || currentPage < totalPages)
                ) {
                    loadNextPage()
                }
            }
        }
    }

    LaunchedEffect(
        category,
        activeCriteria,
        currentPage,
        totalPages,
        orderedPool.size,
        loading,
        loadingMore,
    ) {
        if (
            mode != DiscogsDirectMode.COVER &&
            activeCriteria != null &&
            readyPool.size < visibleLimit &&
            currentPage > 0 &&
            currentPage < totalPages &&
            !loading &&
            !loadingMore &&
            videoPreloadJob?.isActive != true &&
            verificationJob?.isActive != true
        ) {
            delay(250)
            loadNextPage()
        }
    }

    LaunchedEffect(
        visibleLimit,
        navigablePool.size,
        activeCriteria,
        currentPage,
        totalPages,
        loading,
        loadingMore,
    ) {
        val criteria = activeCriteria ?: return@LaunchedEffect
        if (
            mode == DiscogsDirectMode.COVER &&
            !isTurbo &&
            publishPool.size < visibleLimit + pageSize &&
            currentPage > 0 &&
            currentPage < totalPages &&
            !loading &&
            !loadingMore &&
            paginationJob?.isActive != true &&
            sourcePrefetchedForVisibleLimit != visibleLimit &&
            !backgroundWorkBlocked() &&
            !playbackIsNormallyPlaying()
        ) {
            sourcePrefetchedForVisibleLimit = visibleLimit
            delay(220)
            paginationJob =
                scope.launch {
                    try {
                        loadPage(criteria, currentPage + 1, replace = false)
                    } finally {
                        scheduleVideoPreload()
                        scheduleDiscogsVerification()
                    }
                }
        }
    }


    LaunchedEffect(sessionKey, discogsToken) {
        if (
            !session.initialized &&
            initialTitle.isNotBlank()
        ) {
            runSearch()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .pointerInput(sessionKey, headerExpanded) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dragAmount ->
                                headerSwipeDistance += dragAmount
                            },
                            onDragEnd = {
                                when {
                                    headerSwipeDistance >= 70f -> headerExpanded = true
                                    headerSwipeDistance <= -70f -> headerExpanded = false
                                }
                                session.headerExpanded = headerExpanded
                                headerSwipeDistance = 0f
                            },
                            onDragCancel = { headerSwipeDistance = 0f },
                        )
                    },
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (headerExpanded) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (mode == DiscogsDirectMode.COVER) "Cover · MusicLab" else "Originali · MusicLab",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { navController.navigate(MusicLabArchiveNavigationBridge.ROUTE) },
                        ) {
                            Text("Archivio")
                        }
                        TextButton(onClick = { navController.popBackStack() }) {
                            Text("×", style = MaterialTheme.typography.titleLarge)
                        }
                    }

                    if (mode == DiscogsDirectMode.ORIGINAL) {
                        Text(
                            text = "Artista fisso: $resolvedOriginalArtist",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else if (resolvedOriginalArtist.isNotBlank()) {
                        Text(
                            text = "Interprete originale di riferimento: $resolvedOriginalArtist",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    DirectCategorySelector(
                        selected = category,
                        results = results,
                        onSelected = { selected ->
                            category = selected
                            session.category = selected
                            visibleLimit = pageSize
                            session.visibleLimit = visibleLimit
                            publishedReadyLimit = 0
                            session.publishedReadyLimit = 0
                            publishedOriginalSnapshots = emptyList()
                            publishedCoverSnapshots = emptyList()
                            session.publishedOriginalSnapshots = emptyList()
                            session.publishedCoverSnapshots = emptyList()
                            originalSectionFrozen = false
                            session.originalSectionFrozen = false
                            session.originalGateStartedAtMs = 0L
                            session.coverBatchStartedAtMs = 0L
                            resultPageIndex = 0
                            session.resultPageIndex = 0
                            verificationJob?.cancel()
                            videoPreloadJob?.cancel()
                            scope.launch { listState.scrollToItem(0) }
                            scheduleVideoPreload()
                            scheduleDiscogsVerification()
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Titolo brano") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { runSearch() },
                        enabled = !loading,
                    ) {
                        Text(if (loading) "…" else "Cerca")
                    }
                }

                DirectSortSelector(
                    selected = sortMode,
                    onSelected = { selected ->
                        if (selected != sortMode) {
                            sortMode = selected
                            session.sortMode = selected
                            // LAB59: sort is in-memory only. Do not re-query,
                            // discard video bindings, or restart playback/preloads.
                            scope.launch { listState.scrollToItem(0) }
                        }
                    },
                )

                if (headerExpanded) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { showSourceDiagnostics = true },
                            enabled = sourceDiagnostics.isNotEmpty(),
                        ) {
                            Text("Fonti")
                        }
                    }

                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }

                    if (activeCriteria != null && (currentPage > 0 || results.isNotEmpty())) {
                        if (mode == DiscogsDirectMode.COVER) {
                            Text(
                                text =
                                    "Risultati certificati: ${publishPool.size} · " +
                                        "Cover in pagina: ${visibleTrueCovers.size}/$visibleLimit" +
                                        " · Video identificati: ${visibleTrueCovers.count(::hasVideoPreview)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (visibleTrueCovers.any { !hasVideoPreview(it) }) {
                                Text(
                                    "Video da associare: ${visibleTrueCovers.count { !hasVideoPreview(it) }} · ricerca senza precaricare audio",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (nextBlockPool.isNotEmpty()) {
                                Text(
                                    "Prossime ${nextBlockPool.size} cover in attesa della pagina successiva",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Text(
                                text =
                                    "Risultati raccolti: ${results.size} · Mostrati: ${visibleResults.size} · " +
                                        "Release Discogs disponibili: $totalDiscogsResults",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (sourceDiscoveryLoading) {
                            Text(
                                "Interrogo in parallelo le fonti di scoperta e raccolgo candidati…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (verificationJob?.isActive == true) {
                            val pending =
                                results.count {
                                    it.releaseId > 0 &&
                                        it.track == null &&
                                        !it.discogsVerificationChecked
                                }
                            if (pending > 0) {
                                Text(
                                    "Verifica Discogs in background: $pending candidati ancora da controllare",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else if (error != null) {
                    Text(
                        error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 0.dp,
                bottom = DIRECT_VERSION_BOTTOM_SAFE_DP.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!loading && activeCriteria == null && results.isEmpty()) {
                item(key = "discogs_direct_empty_${mode.name}") {
                    Text(
                        "Cerca cover/versioni: MusicLab raccoglie tutte le piste, prepara prima i video col punteggio più alto e le ordina da 20 a 1 senza scarti automatici.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            if (!loading && activeCriteria != null && navigablePool.isEmpty() && !sourceDiscoveryLoading) {
                item(key = "discogs_direct_no_results_${mode.name}") {
                    Text(
                        "Nessun candidato disponibile con questo filtro.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            } else if (
                mode == DiscogsDirectMode.COVER &&
                activeCriteria != null &&
                (sourceDiscoveryLoading || visibleMembershipPool.isNotEmpty()) &&
                visibleResults.isEmpty()
            ) {
                item(key = "discogs_direct_preparing_cover") {
                    Text(
                        if (!rankingFrozen) {
                            "Certifico opera, data, crediti e punteggio prima di pubblicare la graduatoria…"
                        } else {
                            "Verifico gli ID video per il prossimo blocco di 5 canzoni…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            if (mode == DiscogsDirectMode.COVER) {
                if (visibleOriginalVersions.isNotEmpty()) {
                    item(key = "discogs_direct_original_versions_header") {
                        Text(
                            "Versioni di ${resolvedOriginalArtist.ifBlank { "interprete originale" }}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    items(
                        items = visibleOriginalVersions,
                        key = { seed -> "discogs_direct_original_${seed.fingerprint}" },
                    ) { seed ->
                        DiscogsVersionCard(
                            seed = seed,
                            showVideoPreview = true,
                            stableArtworkUrl = remember(seed.fingerprint, seed.coverUrl, seed.resolvedVideoId) {
                                stableArtworkFor(seed)
                            },
                            showConfidence = category == DirectVersionCategory.ALL,
                            selected = seed.fingerprint == selectedFingerprint,
                            resolving = seed.fingerprint == resolvingFingerprint,
                            onPlay = { play(seed) },
                            onRetryVideo = { retryMissingVideo(seed) },
                            onDetails = { detailSeed = seed },
                        )
                    }
                }
                if (visibleTrueCovers.isNotEmpty()) {
                    item(key = "discogs_direct_true_covers_header") {
                        Text(
                            "Cover di altri interpreti",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    items(
                        items = visibleTrueCovers,
                        key = { seed -> "discogs_direct_cover_${seed.fingerprint}" },
                    ) { seed ->
                        DiscogsVersionCard(
                            seed = seed,
                            showVideoPreview = true,
                            stableArtworkUrl = remember(seed.fingerprint, seed.coverUrl, seed.resolvedVideoId) {
                                stableArtworkFor(seed)
                            },
                            showConfidence = category == DirectVersionCategory.ALL,
                            selected = seed.fingerprint == selectedFingerprint,
                            resolving = seed.fingerprint == resolvingFingerprint,
                            onPlay = { play(seed) },
                            onRetryVideo = { retryMissingVideo(seed) },
                            onDetails = { detailSeed = seed },
                        )
                    }
                }
            } else {
                items(
                    items = visibleResults,
                    key = { seed -> "discogs_direct_originals_${seed.fingerprint}" },
                ) { seed ->
                    DiscogsVersionCard(
                        seed = seed,
                        showVideoPreview = false,
                        stableArtworkUrl = remember(seed.fingerprint, seed.coverUrl, seed.resolvedVideoId) {
                                stableArtworkFor(seed)
                            },
                        showConfidence = true,
                        selected = seed.fingerprint == selectedFingerprint,
                        resolving = seed.fingerprint == resolvingFingerprint,
                        onPlay = { play(seed) },
                        onRetryVideo = { retryMissingVideo(seed) },
                        onDetails = { detailSeed = seed },
                    )
                }
            }

            if (
                mode == DiscogsDirectMode.COVER &&
                activeCriteria != null &&
                !loading &&
                (
                    (publishedCoverSnapshots.size >= visibleLimit && publishPool.size > visibleLimit) ||
                        (publishedCoverSnapshots.size >= visibleLimit && currentPage > 0 && currentPage < totalPages)
                    )
            ) {
                item(key = "discogs_direct_cover_more_${visibleLimit}") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (loadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        Button(
                            onClick = ::loadNextPage,
                            enabled = !loadingMore,
                        ) {
                            Text("Carica altri $pageSize")
                        }
                        paginationError?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            } else if (
                mode != DiscogsDirectMode.COVER &&
                (
                    loadingMore ||
                        (
                            activeCriteria != null &&
                                currentPage > 0 &&
                                (readyPool.size > visibleLimit || currentPage < totalPages)
                            )
                    )
            ) {
                item(key = "discogs_direct_more_${mode.name}_${currentPage + 1}") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (loadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Preparo il prossimo blocco da $pageSize risultati…")
                        } else {
                            TextButton(onClick = ::loadNextPage) {
                                Text("Carica i prossimi $pageSize")
                            }
                        }
                    }
                }
            }
        }
    }
    detailSeed?.let { seed ->
        DiscogsVersionDetailsDialog(
            seed = seed,
            saving = decisionSavingFingerprint == seed.fingerprint,
            approved = isApproved(seed),
            onDismiss = { detailSeed = null },
            onSearch = { value ->
                value.trim().takeIf(String::isNotBlank)?.let { query ->
                    navController.navigate(SearchRoutes.resultRoute(query))
                }
            },
            onApprove = { saveDecision(seed, AiBrainDecisionStatus.APPROVED) },
            onReject = { saveDecision(seed, AiBrainDecisionStatus.REJECTED) },
        )
    }

    if (showSourceDiagnostics) {
        SourceDiagnosticsDialog(
            diagnostics = sourceDiagnostics,
            onDismiss = { showSourceDiagnostics = false },
        )
    }

}

@Composable
private fun DiscogsVersionDetailsDialog(
    seed: DiscogsVersionSeed,
    saving: Boolean,
    approved: Boolean,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onApprove,
                    enabled = !saving && !approved,
                ) {
                    Text(
                        when {
                            saving -> "Salvo…"
                            approved -> "Approvata"
                            else -> "Approva"
                        },
                    )
                }
                TextButton(
                    onClick = onReject,
                    enabled = !saving,
                ) {
                    Text("Disapprova")
                }
            }
        },
        dismissButton = {},
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Dettagli versione", modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("×", style = MaterialTheme.typography.titleLarge)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                fun creditNames(pattern: Regex): String =
                    seed.credits
                        .filter { pattern.containsMatchIn(it.role.lowercase()) }
                        .map { it.name.trim() }
                        .filter(String::isNotBlank)
                        .distinct()
                        .joinToString()
                        .ifBlank { "non disponibile" }

                val authors =
                    creditNames(
                        Regex("""\b(songwriter|writer|written|words|lyricist|lyrics|autore)\b"""),
                    )
                val composers =
                    creditNames(
                        Regex("""\b(composer|composed|music by|compositore)\b"""),
                    )
                val publicationDate =
                    seed.releaseDate?.takeIf(String::isNotBlank)
                        ?: seed.displayDate?.takeIf(String::isNotBlank)
                        ?: "non disponibile"

                Text(
                    "Titolo: ${seed.trackTitle}",
                    modifier = Modifier.clickable { onSearch(seed.trackTitle) },
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("Data pubblicazione: $publicationDate")
                Text("Autore: $authors")
                Text(
                    "Interprete: ${seed.artist}",
                    modifier = Modifier.clickable { onSearch(seed.artist) },
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("Compositore: $composers")
                if (directDisplayCategory(seed) == DirectVersionCategory.FOREIGN) {
                    Text("Paese d'origine: ${seed.country ?: "non disponibile"}")
                }
            }
        },
    )
}

@Composable
private fun SourceDiagnosticsDialog(
    diagnostics: List<CoverSourceDiagnostic>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Fonti della ricerca", modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("×", style = MaterialTheme.typography.titleLarge)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                diagnostics.forEach { diagnostic ->
                    val state = if (diagnostic.available) "✅" else "⚪"
                    Text(
                        "$state ${diagnostic.name} · ${diagnostic.found}" +
                            diagnostic.note.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Le fonti forniscono prove; MusicLab certifica identità, cronologia, crediti e punteggio prima di congelare la graduatoria.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun DirectCategorySelector(
    selected: DirectVersionCategory,
    results: List<DiscogsVersionSeed>,
    onSelected: (DirectVersionCategory) -> Unit,
) {
    val studio = results.count { directDisplayCategory(it) == DirectVersionCategory.STUDIO }
    val live = results.count { directDisplayCategory(it) == DirectVersionCategory.LIVE }
    val remix = results.count { directDisplayCategory(it) == DirectVersionCategory.REMIX }
    val foreign = results.count { directDisplayCategory(it) == DirectVersionCategory.FOREIGN }

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DirectChip("Tutto · ${results.size}", selected == DirectVersionCategory.ALL) {
            onSelected(DirectVersionCategory.ALL)
        }
        DirectChip("Studio · $studio", selected == DirectVersionCategory.STUDIO) {
            onSelected(DirectVersionCategory.STUDIO)
        }
        DirectChip("Live · $live", selected == DirectVersionCategory.LIVE) {
            onSelected(DirectVersionCategory.LIVE)
        }
        DirectChip("Mix/Remix · $remix", selected == DirectVersionCategory.REMIX) {
            onSelected(DirectVersionCategory.REMIX)
        }
        DirectChip("Straniere · $foreign", selected == DirectVersionCategory.FOREIGN) {
            onSelected(DirectVersionCategory.FOREIGN)
        }
    }
}

@Composable
private fun DirectSortSelector(
    selected: DirectVersionSort,
    onSelected: (DirectVersionSort) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Ordina:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DirectChip("Punteggio 20→1", selected == DirectVersionSort.RELEVANCE) {
            onSelected(DirectVersionSort.RELEVANCE)
        }
        DirectChip("Più vecchi", selected == DirectVersionSort.OLDEST) {
            onSelected(DirectVersionSort.OLDEST)
        }
        DirectChip("Più nuovi", selected == DirectVersionSort.NEWEST) {
            onSelected(DirectVersionSort.NEWEST)
        }
    }
}

@Composable
private fun DirectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun DirectFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun DiscogsVersionCard(
    seed: DiscogsVersionSeed,
    showVideoPreview: Boolean,
    stableArtworkUrl: String?,
    showConfidence: Boolean,
    selected: Boolean,
    resolving: Boolean,
    onPlay: () -> Unit,
    onRetryVideo: () -> Unit,
    onDetails: () -> Unit,
) {
    // LAB66: keep the approved 144/76 dp card sizes, but decode only to
    // the actual on-screen pixel size, not the full resolution of the source.
    // Reuse the same Coil request as Compose recomposes other result rows.
    val imageContext = LocalContext.current
    val imagePixels = with(LocalDensity.current) {
        (if (showVideoPreview) 144.dp else 76.dp).roundToPx()
    }
    val artworkRequest = remember(imageContext, stableArtworkUrl, imagePixels) {
        ImageRequest.Builder(imageContext)
            .data(stableArtworkUrl)
            .size(Size(imagePixels, imagePixels))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (showVideoPreview) Modifier.height(184.dp) else Modifier)
                .padding(horizontal = 12.dp)
                .clickable(onClick = onPlay),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val videoId = seed.resolvedVideoId?.trim().orEmpty()
            if (showVideoPreview) {
                // LAB62 Pollicino: one image slot per Cover. Video ID wins.
                // While video matching is pending, a verified release image
                // temporarily fills the SAME slot; never create a second poster.
                AsyncImage(
                    model = artworkRequest,
                    contentDescription = seed.resolvedVideoTitle ?: "Anteprima versione musicale",
                    modifier =
                        Modifier
                            .size(144.dp)
                            .clickable(onClick = onPlay),
                    contentScale = ContentScale.Crop,
                )
            } else {
                AsyncImage(
                    model = artworkRequest,
                    contentDescription = null,
                    modifier = Modifier.size(76.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    seed.trackTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    seed.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Fonte: ${primaryDiscoverySource(seed)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showConfidence) {
                    Text(
                        "Affidabilità: ${seed.confidenceScore}/20",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val publicationDate =
                    seed.releaseDate?.takeIf(String::isNotBlank)
                        ?: seed.displayDate?.takeIf(String::isNotBlank)
                        ?: "non disponibile"
                Text(
                    "Data pubblicazione: $publicationDate",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (resolving) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Cerco il collegamento video…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else if (showVideoPreview) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDetails) {
                            Text("Dettagli")
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        val videoReady = !seed.resolvedVideoId.isNullOrBlank()
                        Text(
                            when {
                                videoReady -> "Video pronto"
                                seed.videoResolutionChecked -> "Video non trovato · Tocca per cercare"
                                else -> "Video in verifica…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                when {
                                    videoReady -> Color(0xFF2E7D32)
                                    seed.videoResolutionChecked -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            fontWeight = if (videoReady) FontWeight.SemiBold else FontWeight.Normal,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .then(
                                        if (seed.videoResolutionChecked && !videoReady) {
                                            Modifier.clickable(onClick = onRetryVideo)
                                        } else {
                                            Modifier
                                        },
                                    ),
                        )
                        TextButton(onClick = onDetails) {
                            Text("Dettagli")
                        }
                    }
                }
            }
        }
    }
}
