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
import coil3.imageLoader
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private const val DIRECT_VERSION_PAGE_SIZE = 20
// Experimental family 02: bounded burst, never affect regular LAB68.
private const val TURBO_PACKAGE = "it.verlezza.musiclab.turbo01"
private const val TURBO_RESULT_QUOTA = 50
private const val TURBO_MAX_RANK_CANDIDATES = 100
private const val TURBO_BURST_DEADLINE_MS = 10_000L
// LAB22 canonical HARD deadlines per card's first real viewport appearance.
// The first five seconds get first priority. The next five keep searching
// for that same recording. Only the final five may adopt a different cover.
private const val TURBO_PRIMARY_VIDEO_MS = 5_000L
private const val TURBO_PRIMARY_GRACE_MS = 5_000L
private const val TURBO_REPLACEMENT_VIDEO_MS = 5_000L
private const val TURBO_AUTO_TOTAL_MS =
    TURBO_PRIMARY_VIDEO_MS + TURBO_PRIMARY_GRACE_MS + TURBO_REPLACEMENT_VIDEO_MS
// LAB26: 50 video probes cannot fit in 15 seconds with four network slots.
// The 25+25 poster deadline remains unchanged; this is a separate, bounded
// background verification window and yields immediately to audio rebuffering.
private const val TURBO_VIDEO_COVERAGE_MAX_MS = 100_000L
private const val TURBO_VIDEO_FIRST_ATTEMPT_MS = 3_400L
private const val TURBO_RESERVE_MAX = 100
private const val TURBO_FIRST_POSTER_DEADLINE_MS = 1_200L
private const val TURBO_FIRST_POSTER_TARGET_MS = 5_000L
private const val TURBO_MIN_EARLY_POSTER_BLOCK = 10
private const val TURBO_POSTER_SECOND_BLOCK = 40
// LAB27: one final video-first, batch-published refill after normal verification.
private const val TURBO_VIDEO_REFILL_MAX_MS = 45_000L
private const val TURBO_VIDEO_REFILL_DISCOVERY_MS = 7_000L
private const val TURBO_STREAM_PROBE_MS = 850L
private const val TURBO_THUMBNAIL_RESERVE_MS = 400L
private const val TURBO_SOURCE_WAIT_MS = 8_200L
private const val TURBO_COVER_INFO_WAIT_MS = 8_200L
private const val TURBO_ARCHIVE_TIMEOUT_MS = 6_500L
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
private const val DIRECT_PREPARED_VIDEO_CACHE_LIMIT = 64
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
    var turboReserve: List<DiscogsVersionSeed> = emptyList(),
    var turboVideoComplete: Boolean = false,
    // LAB25: one bounded automatic repair on re-entry, not an eternal cloud negative cache.
    var turboLastAutoRepairAtMs: Long = 0L,
    var turboAutoStartedAtMs: Long = 0L,
    val turboVideoFirstProbeAtMs: MutableMap<String, Long> = linkedMapOf(),
    val turboVideoReasons: MutableMap<String, String> = linkedMapOf(),
    val turboPosterPublishedAtMs: MutableMap<String, Long> = linkedMapOf(),
    // Stable viewport slots survive a video-first recording replacement.
    val turboSlotKeys: MutableMap<String, String> = linkedMapOf(),
    // LAB21: first actual viewport appearance is immutable while scrolling.
    val turboFirstVisibleAtMs: MutableMap<String, Long> = linkedMapOf(),
    val turboAutoClosedFingerprints: MutableSet<String> = linkedSetOf(),
    // LAB21: manual taps never retry the same rejected spare.
    val turboManualTriedSpareIds: MutableMap<String, MutableSet<String>> = linkedMapOf(),
    val rejectedKeys: MutableSet<String> = linkedSetOf(),
    val approvedKeys: MutableSet<String> = linkedSetOf(),
    val usedVideoIds: MutableSet<String> = linkedSetOf(),
    val knownVideoBindings: MutableMap<String, Triple<String, String, String>> = linkedMapOf(),
    val preparedVideoSongs: MutableMap<String, SongItem> = ConcurrentHashMap(),
    val turboStreamReadyIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
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
    // LAB12: protect UI/audio and source quotas from an unbounded 50-way
    // metadata burst. Four verifiers may run; providers retain own limits.
    val turboVideoCheckGate = remember(sessionKey) { Semaphore(4) }

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
    var turboStreamProofVersion by remember(sessionKey) { mutableStateOf(0) }
    var originalSectionFrozen by remember(sessionKey) { mutableStateOf(session.originalSectionFrozen) }
    var searchJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var paginationJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var videoPreloadJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var turboAutoVideoJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    val turboVisibleJobs = remember(sessionKey) { mutableMapOf<String, Job>() }
    val turboReplacementClaims = remember(sessionKey) { mutableSetOf<String>() }
    val turboReplacementGate = remember(sessionKey) { Semaphore(1) }
    // A UI state tick makes the manual-swap prompt visible exactly when
    // the automatic 15s clock closes, even if the row data is unchanged.
    var turboAutoClosedTick by remember(sessionKey) { mutableStateOf(0) }
    var turboSearchGeneration by remember(sessionKey) { mutableStateOf(0) }
    var verificationJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var backgroundResumeJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var playbackLaunchJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var manualVideoSearchJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
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
            // Turbo LAB05: reopening a completed burst is cache-only.  The
            // playback governor must not be kept active for a sealed session.
            playerConnection?.service?.setCoverPerformanceLoad(
                active = !isTurbo || !session.turboFinished,
                heavy = false,
            )
        }
        onDispose {
            // LAB57 hard Cover lifecycle barrier: leaving this screen cancels every
            // owned coroutine and releases the playback-quality governor.
            playbackLaunchJob?.cancel()
            manualVideoSearchJob?.cancel()
            coverSwipeJob?.cancel()
            CoverSwipeBridge.clear(sessionKey)
            searchJob?.cancel()
            paginationJob?.cancel()
            verificationJob?.cancel()
            videoPreloadJob?.cancel()
            turboVisibleJobs.values.forEach { it.cancel() }
            turboVisibleJobs.clear()
            backgroundResumeJob?.cancel()
            if (mode == DiscogsDirectMode.COVER) {
                if (isTurbo) CloudMusicDiscovery.cancelArchiveRequests()
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

    // LAB17: local editor verdict wins over stale Cloud memory or stale
    // per-screen flags. A decision also works when offline/no Cloud endpoint.
    fun localEditorialDecision(seed: DiscogsVersionSeed): String? =
        CoverPlaybackMemory.editorialDecision(
            context,
            TitleMeaningResolver.workAnchorTitle(title),
            rejectionKey(seed),
        )

    fun isRejected(seed: DiscogsVersionSeed): Boolean =
        when (localEditorialDecision(seed)) {
            "rejected" -> true
            "approved" -> false
            else -> rejectionKey(seed) in session.rejectedKeys
        }

    fun isApproved(seed: DiscogsVersionSeed): Boolean =
        when (localEditorialDecision(seed)) {
            "approved" -> true
            "rejected" -> false
            else -> seed.manuallyApproved || rejectionKey(seed) in session.approvedKeys
        }

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
        // A cover performer can dominate search frequency or evidence score.
        // Only explicit original-work attribution may choose the original artist.
        val documented =
            candidates.filter { it.artist.isNotBlank() && it.originalWorkReference }
        if (documented.isEmpty()) return null
        val groups = documented.groupBy {
            TitleMeaningResolver.canonical(it.artist).removePrefix("the ")
        }.filterKeys(String::isNotBlank)
        val ranked = groups.entries.sortedByDescending { it.value.size }
        val winner = ranked.firstOrNull() ?: return null
        // Conflicting documentary attributions must remain unresolved.
        if (ranked.getOrNull(1)?.value?.size == winner.value.size) return null
        return winner.value.first().artist
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
            // Explicit COVER.INFO follow-up relationships are a stronger
            // musical-work link than translated title equality. They have
            // already been anchored to the matching original song's page.
            if (seed.workRelationConfirmed &&
                seed.sourceNames.any { it.equals("COVER.INFO", ignoreCase = true) }
            ) return true

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

    // LAB12: an album reissue isn't a new version. The alternate
    // interpretation must be explicit or have a verified recording kind.
    val originalVariantMarker = Regex(
        """\b(live|dal vivo|concert|concerto|acoustic|acustic[oa]|unplugged|remix|rework|alternate take|alternative take|demo|duet|duetto|feat\.?|featuring|instrumental|guitar|chitarra|piano version|re.recorded|new recording|session|performance)\b""",
        RegexOption.IGNORE_CASE,
    )
    fun isDistinctOriginalVersion(seed: DiscogsVersionSeed): Boolean {
        if (!isOriginalPerformerVersion(seed)) return false
        val description = listOf(seed.trackTitle, seed.releaseTitle, seed.resolvedVideoTitle.orEmpty())
            .joinToString(" ")
        return seed.kind == DiscogsVersionKind.LIVE ||
            seed.kind == DiscogsVersionKind.REMIX ||
            seed.kind == DiscogsVersionKind.ACOUSTIC ||
            originalVariantMarker.containsMatchIn(description)
    }

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
        // LAB12 frame pacing: no new Compose list instance for identical
        // evidence; this matters most while music and LazyColumn are active.
        if (merged == current) return
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
        // LAB10: freeze poster membership, artwork and ranking, NOT playback bindings.
        // Publish each verified ID into its existing visible card immediately.
        if (isTurbo && mode == DiscogsDirectMode.COVER &&
            (publishedCoverSnapshots.isNotEmpty() || publishedOriginalSnapshots.isNotEmpty())) {
            val refreshed = results.firstOrNull { it.fingerprint == merged.fingerprint }
            if (refreshed != null) {
                // Video IDs may arrive after ranking is frozen; keep cards,
                // editorial artwork and their 1–20 scores unchanged.
                fun videoBindingOnly(poster: DiscogsVersionSeed): DiscogsVersionSeed =
                    if (poster.fingerprint == refreshed.fingerprint) {
                        poster.copy(
                            resolvedVideoId = refreshed.resolvedVideoId,
                            resolvedVideoTitle = refreshed.resolvedVideoTitle,
                            resolvedVideoSource = refreshed.resolvedVideoSource,
                            videoResolutionChecked = refreshed.videoResolutionChecked,
                        )
                    } else poster
                fun bindingChanged(poster: DiscogsVersionSeed): Boolean =
                    poster.fingerprint == refreshed.fingerprint &&
                        (poster.resolvedVideoId != refreshed.resolvedVideoId ||
                            poster.resolvedVideoTitle != refreshed.resolvedVideoTitle ||
                            poster.resolvedVideoSource != refreshed.resolvedVideoSource ||
                            poster.videoResolutionChecked != refreshed.videoResolutionChecked)
                if (publishedCoverSnapshots.any(::bindingChanged)) {
                    publishedCoverSnapshots = publishedCoverSnapshots.map(::videoBindingOnly)
                    session.publishedCoverSnapshots = publishedCoverSnapshots
                }
                if (publishedOriginalSnapshots.any(::bindingChanged)) {
                    publishedOriginalSnapshots = publishedOriginalSnapshots.map(::videoBindingOnly)
                    session.publishedOriginalSnapshots = publishedOriginalSnapshots
                }
            }
        }
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

    suspend fun resolveVideoChunk(
        chunk: List<DiscogsVersionSeed>,
        playbackPriority: Boolean = false,
    ) = coroutineScope {
        if (chunk.isEmpty()) return@coroutineScope
        // LAB15 AUDIO FIRST: a video metadata lookup must not compete with
        // startup, rebuffering or a low current-song audio reserve. Discovery
        // remains resumable after the player recovers.
        if (isTurbo && mode == DiscogsDirectMode.COVER && !playbackPriority &&
            (backgroundPausedForPlayback ||
                playerConnection?.isPlaybackPriorityBurstActive() == true ||
                playerConnection?.playbackState?.value == Player.STATE_BUFFERING ||
                playerConnection?.service?.isCoverAudioAtRisk() == true)) return@coroutineScope
        // LAB12: each job is IO-only and respects a shared four-slot budget.
        val jobs =
            chunk.map { seed ->
                launch {
                    turboVideoCheckGate.withPermit {
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
                        val documentedPerformer =
                            source?.equals("COVER.INFO", ignoreCase = true) == true &&
                                current.workRelationConfirmed &&
                                current.resolvedVideoId?.trim() == candidate.id &&
                                current.sourceNames.any {
                                    it.equals("COVER.INFO", ignoreCase = true)
                                }
                        // The editorial relation proves the performer; YouTube
                        // metadata must still satisfy exact title, version and
                        // duration validation. Never apply this to blind searches.
                        if (!CompilationTrackResolver.isHardCompatible(
                                track, candidate,
                                documentedPerformerMatch = documentedPerformer,
                            )) return null
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

                    if (verified == null && existingId.isBlank()) {
                        // Prefer MusicLab's own previously bound and prepared
                        // recording before sending another external search.
                        // knownMusicLabVideo reuses an in-memory SongItem with
                        // zero network traffic when it is already prepared.
                        val known = knownMusicLabVideo(current, track)
                        verified = verifyCandidateSong(known?.first, known?.second)
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
                                            turboFastOnly = isTurbo && mode == DiscogsDirectMode.COVER,
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

                    // LAB22 hard primary deadline: late provider results may
                    // safely populate metadata caches but must never change
                    // the visible poster after its T+10 primary window.
                    if (isTurbo && mode == DiscogsDirectMode.COVER && !playbackPriority) {
                        // LAB26: validate against this row's *actual probe* clock.
                        // The old global T+10 gate discarded correct videos from
                        // the last 25 rows even when their search succeeded.
                        val rowStartedAt = session.turboVideoFirstProbeAtMs[current.fingerprint]
                            ?: session.turboPosterPublishedAtMs[current.fingerprint]
                            ?: session.turboAutoStartedAtMs
                        if (!isActive || SystemClock.elapsedRealtime() >=
                            rowStartedAt + TURBO_AUTO_TOTAL_MS) return@launch
                    }
                    replaceSeed(updated)
                    val readyId = updated.resolvedVideoId?.trim().orEmpty()
                    // LAB12: do not open 50 audio streams while another song
                    // is playing. Fast HARD-compatible metadata is enough
                    // to show the video; only the native player opens audio
                    // after an explicit tap on the card.
                    if (
                        readyId.isNotBlank() &&
                        readyId in session.playReadyVideoIds &&
                        session.preparedVideoSongs.containsKey(readyId)
                    ) {
                        // LAB67: avoid serial remote cloud writes on video readiness.
                        if (!isTurbo) {
                            scope.launch(Dispatchers.IO) {
                                persistCloudPlaybackBinding(updated)
                            }
                        }
                    }
                    }
                }
            }
        jobs.forEach { it.join() }
    }

    // LAB08 Pollicino — video-ready candidates never wait behind higher
    // scored candidates with NO video ID. Among video-ready records, always
    // check 20/20 before 19/20, 18/20 ... down to 1/20. When none has an ID,
    // search for IDs in descending score order using the remaining time.
    // Having an ID is NOT proof of playback: metadata verification is mandatory.
    fun videoLookupPriority(source: List<DiscogsVersionSeed>): List<DiscogsVersionSeed> =
        source.sortedWith(
            compareByDescending<DiscogsVersionSeed> { !it.resolvedVideoId.isNullOrBlank() }
                .thenByDescending { it.confidenceScore }
                .thenByDescending { it.originalWorkReference }
                .thenByDescending { it.sourceNames.distinct().size }
                .thenBy { it.artist.lowercase() }
                .thenBy { it.trackTitle.lowercase() },
        )

    fun videoPriorityPool(source: List<DiscogsVersionSeed>): List<DiscogsVersionSeed> =
        videoLookupPriority(orderedResults(source))

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

    /**
     * LAB17: the historic 1..20 number is documentary evidence, NOT a
     * guaranteed match between the chosen recording and a YouTube video.
     * Keep the native discographic ranking, but show a conservative display
     * confidence until a metadata-verified video independently agrees on
     * song, performer, version and duration.
     *
     * Never relabel low-proof videos as 20/20 just because a release exists
     * in a catalogue or has been editorially approved.
     */
    fun videoCalibratedDisplaySeed(seed: DiscogsVersionSeed): DiscogsVersionSeed {
        val raw = seed.confidenceScore.coerceIn(1, 20)
        val videoId = seed.resolvedVideoId?.trim().orEmpty()
        val match = playableTrack(seed)?.let { track ->
            if (videoId.isBlank() || !hasPublishableVideo(seed)) null
            else session.preparedVideoSongs[videoId]?.takeIf {
                CompilationTrackResolver.isHardCompatible(track, it)
            }?.let { CompilationTrackResolver.scoreSong(track, it) }
        }
        val verifiedCeiling = when {
            match == null && hasPublishableVideo(seed) -> 15
            match == null -> 12
            match >= 110 -> 20
            match >= 98 -> 18
            match >= 88 -> 16
            else -> 14
        }
        val calibrated = minOf(raw, verifiedCeiling)
        return if (calibrated == raw) seed else seed.copy(
            confidenceScore = calibrated,
            confidenceReasons = (seed.confidenceReasons +
                "Punteggio video prudenziale: conferma interprete/versione non ancora completa").distinct(),
        )
    }

    fun hasTurboStreamReady(seed: DiscogsVersionSeed): Boolean {
        val proofVersion = turboStreamProofVersion
        return isTurbo && proofVersion >= 0 && !seed.resolvedVideoId.isNullOrBlank() &&
            seed.resolvedVideoId in session.turboStreamReadyIds
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

    // Canonical global ranking: confirmed original variations first,
    // then other artists' cover recordings. The UI will freeze the order.
    fun turboRankedPool(): List<DiscogsVersionSeed> {
        val originalVariants = originalCandidatePool()
            .filter(::isDistinctOriginalVersion)
            .map { seed ->
                val documented = seed.workRelationConfirmed ||
                    seed.discogsVerificationChecked ||
                    seed.sourceNames.distinct().size >= 2 ||
                    !seed.resolvedVideoId.isNullOrBlank()
                seed.copy(
                    confidenceScore = if (documented) 20 else seed.confidenceScore.coerceAtMost(17),
                    confidenceReasons = (seed.confidenceReasons +
                        if (documented) "Variante originale documentata"
                        else "Variante originale: prova in attesa").distinct(),
                )
            }
        return (sortGroup(originalVariants, DirectVersionSort.RELEVANCE) +
            sortGroup(coverCandidatePool(), DirectVersionSort.RELEVANCE))
            .distinctBy(DiscogsVersionSource::recordingIdentityKey)
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
        // All Turbo discoveries publish exactly ONCE, in the search coordinator's
        // sealed finally block. Legacy five-row publication must never run here.
        if (isTurbo && mode == DiscogsDirectMode.COVER) return
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
        // The non-Turbo legacy batch retains incomplete candidates for inspection.
        // Turbo LAB21 does not use this path for automatic video resolution.
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
        batch.chunked(if (isTurbo && playerConnection?.isEffectivelyPlaying?.value != true) 4 else DIRECT_VIDEO_PARALLELISM).forEach { chunk ->
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
        if (isTurbo && mode == DiscogsDirectMode.COVER) return
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
        // LAB02 owns video validation in one single bounded coordinator.
        if (isTurbo && mode == DiscogsDirectMode.COVER) return
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
                var turboStopCommitted = false
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
                        )) {
                            turboStopCommitted = true
                            break
                        }
                        if (backgroundWorkBlocked() ||
                            (mode != DiscogsDirectMode.COVER && playbackIsNormallyPlaying())) break
                        val workWindow = videoPreparationPool()
                        if (workWindow.isEmpty()) {
                            publishReadyBatches()
                            if (isTurbo && (
                                sourceDiscoveryLoading ||
                                (!originalSectionFrozen &&
                                    SystemClock.elapsedRealtime() - session.originalGateStartedAtMs <
                                        TURBO_COVER_ORIGINAL_GATE_MS)
                            )) {
                                delay(120)
                                continue
                            }
                            if (isTurbo) turboStopCommitted = true
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
                        if (readyAfter == readyBefore && !anyPending) {
                            if (isTurbo && sourceDiscoveryLoading) {
                                delay(120)
                                continue
                            }
                            if (isTurbo) turboStopCommitted = true
                            break
                        }
                        // LAB67: keep audio-priority pacing without an extra
                        // half-second gap between every ranked result.
                        delay(if (playbackIsNormallyPlaying()) 150 else 40)
                    }

                    // LAB60: no whole-page playback pre-warming.
                    publishReadyBatches()
                } finally {
                    if (isTurbo && mode == DiscogsDirectMode.COVER && turboStopCommitted) {
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

    // LAB10: automatic VIDEO discovery is independent of the 10-second poster seal.
    // Reuse the existing bounded metadata resolver; never request audio streams here.
    // Foreground playback and manual recovery always have priority.
    // LAB11: obsolete background video worker removed.


    /**
     * LAB14: a separate VIDEO-ONLY coordinator owns the already published
     * positions 1..50. Poster collection stays strictly capped at 10s; this
     * worker continues while the Cover screen is mounted. It never opens an
     * audio stream or mutates the native ExoPlayer/cache.
     *
     * Position order is scheduling order, not a serial network bottleneck.
     * A maximum of three positions may be examined together. When audio
     * reserve is low, Cover yields its HTTP capacity immediately.
     */
    // LAB21 supersedes the previous 10-second/background worker and refill.
    // All automatic video work is owned by the single 5+5+5 viewport coordinator.

    /**
     * LAB22 authoritative 5+5+5 rule; earlier automatic timer proposals are obsolete:
     *   0..5s   primary matching video, with priority over all replacement work
     *   5..10s  second bounded lookup for the SAME recording if still missing
     *   10..15s alternate COVER recording, thumbnail and verified video
     *   15s     stop/cancel ALL automatic work for this row, including late writes
     *
     * T0 is the first actual LazyColumn viewport appearance. Scrolling or
     * re-entering never resets T0. Manual user requests remain independent.
     */
    suspend fun lab21VerifyAlternative(seed: DiscogsVersionSeed): DiscogsVersionSeed? {
        val track = playableTrack(seed) ?: return null
        val id = seed.resolvedVideoId?.trim().orEmpty()
        val visibleIds = (publishedOriginalSnapshots + publishedCoverSnapshots)
            .mapNotNullTo(HashSet<String>()) { it.resolvedVideoId?.trim()?.takeIf(String::isNotBlank) }
        val rejectedIds = CoverPlaybackMemory.rejectedVideoIds(context, seed.fingerprint)
        if (id.isNotBlank() && id !in visibleIds && id !in rejectedIds &&
            hasPublishableVideo(seed)) return seed

        val known = if (id.isNotBlank() && id !in visibleIds && id !in rejectedIds) {
            session.preparedVideoSongs[id] ?: withTimeoutOrNull(900L) {
                withContext(Dispatchers.IO) {
                    YouTube.queue(videoIds = listOf(id)).getOrNull()?.firstOrNull()
                }
            }
        } else null
        val verified = known?.takeIf { CompilationTrackResolver.isHardCompatible(track, it) }
        val candidate = verified ?: withTimeoutOrNull(1_700L) {
            withContext(Dispatchers.IO) {
                CompilationTrackResolver.resolveTrack(
                    track = track,
                    discogsVideos = seed.videos,
                    fastFirst = true,
                    excludedVideoIds = visibleIds + rejectedIds +
                        listOfNotNull(known?.id?.takeIf { verified == null }),
                    searchRound = CoverPlaybackMemory.nextSearchRound(context, seed.fingerprint),
                    preferLive = seed.kind == DiscogsVersionKind.LIVE,
                    turboFastOnly = true,
                )?.song
            }
        } ?: return null
        if (!CompilationTrackResolver.isHardCompatible(track, candidate) ||
            candidate.id in visibleIds || candidate.id in rejectedIds) return null
        rememberPreparedVideoSong(candidate)
        session.playReadyVideoIds.add(candidate.id)
        session.usedVideoIds.add(candidate.id)
        CoverPlaybackMemory.saveMetadataVerifiedVideo(
            context, seed.fingerprint, candidate.id,
        )
        return DiscogsVersionSource.markVideoResolved(
            seed, candidate.id, candidate.title, "MusicLab · Turbo LAB22 5+5+5",
        )
    }

    // LAB24: one existing coordinator, four fair result partitions and
    // immediate cancellation on playback start. Do not duplicate workers.
    LaunchedEffect(sessionKey, turboSearchGeneration) {
        if (!isTurbo || mode != DiscogsDirectMode.COVER) return@LaunchedEffect
        turboAutoVideoJob = currentCoroutineContext()[Job]
        while (isActive && (publishedOriginalSnapshots + publishedCoverSnapshots).isEmpty()) {
            delay(50L)
        }
        if (!isActive) return@LaunchedEffect
        val globalStart = session.turboPosterPublishedAtMs.values.minOrNull()
            ?: SystemClock.elapsedRealtime()
        val globalEnd = globalStart + TURBO_AUTO_TOTAL_MS
        session.turboAutoStartedAtMs = globalStart
        val activeRows = mutableSetOf<String>()
        // Persist attempt counts across a cancelled/resumed video coordinator.
        val firstPasses = session.automaticVideoAttempts
        val fallbackPasses = mutableSetOf<String>()
        fun postedRows(): List<DiscogsVersionSeed> =
            (publishedOriginalSnapshots + publishedCoverSnapshots).take(TURBO_RESULT_QUOTA)
        fun currentVideoEndMs(): Long {
            // One bounded overall window, independent from the 10-second
            // 25+25 poster publisher. Each row receives its own 5+5+5 clock
            // from its first real verification attempt, not an expired clock
            // inherited from the first group.
            return globalStart + TURBO_VIDEO_COVERAGE_MAX_MS
        }
        fun registerClock() {
            postedRows().forEach { row ->
                val publishedAt = session.turboPosterPublishedAtMs[row.fingerprint] ?: globalStart
                session.turboFirstVisibleAtMs.putIfAbsent(row.fingerprint, publishedAt)
            }
        }
        if (SystemClock.elapsedRealtime() >= currentVideoEndMs()) {
            registerClock()
            postedRows().forEach { session.turboAutoClosedFingerprints.add(it.fingerprint) }
            session.turboVideoComplete = true
            turboAutoClosedTick++
            return@LaunchedEffect
        }
        suspend fun tryReplacement(fingerprint: String) {
            val rowStartedAt = session.turboVideoFirstProbeAtMs[fingerprint]
                ?: globalStart
            val autoEndsAt = minOf(
                currentVideoEndMs(), rowStartedAt + TURBO_AUTO_TOTAL_MS,
            )
                        val budgetMs = autoEndsAt - SystemClock.elapsedRealtime()
                        if (budgetMs <= 0L) return
                        withTimeoutOrNull(budgetMs) {
                            turboReplacementGate.withPermit {
                                val original = (publishedOriginalSnapshots + publishedCoverSnapshots)
                                    .firstOrNull { it.fingerprint == fingerprint }
                                    ?: return@withPermit
                                val originalSide = isDistinctOriginalVersion(original)
                                val present = publishedOriginalSnapshots + publishedCoverSnapshots
                                val occupied = present.mapTo(HashSet<String>()) {
                                    DiscogsVersionSource.recordingIdentityKey(it)
                                }
                                val workTitle = activeCriteria?.title.orEmpty()
                                val alternatives = (session.turboReserve + turboRankedPool())
                                    .distinctBy(DiscogsVersionSource::recordingIdentityKey)
                                    .filter { spare ->
                                        spare.fingerprint != fingerprint &&
                                        spare.fingerprint !in turboReplacementClaims &&
                                        !isRejected(spare) && modeAcceptsSeed(spare) &&
                                        DiscogsVersionSource.recordingIdentityKey(spare) !in occupied &&
                                        isDistinctOriginalVersion(spare) == originalSide &&
                                        playableTrack(spare) != null &&
                                        TitleMeaningResolver.matchesBaseTitle(
                                            workTitle, spare.trackTitle,
                                        ) &&
                                        (spare.coverUrl != original.coverUrl ||
                                            spare.resolvedVideoId != original.resolvedVideoId)
                                    }.sortedWith(
                                        compareByDescending<DiscogsVersionSeed> {
                                            hasPublishableVideo(it)
                                        }.thenByDescending { !it.resolvedVideoId.isNullOrBlank() }
                                            .thenByDescending { it.confidenceScore }
                                    )
                                for (spare in alternatives.take(4)) {
                                    if (!isActive || SystemClock.elapsedRealtime() >= autoEndsAt) break
                                    // The initial lane always has more permits
                                    // than the single replacement lane.
                                    if (!turboReplacementClaims.add(spare.fingerprint)) continue
                                    val verified = lab21VerifyAlternative(spare) ?: continue
                                    // Hard 15s: a late provider reply may only
                                    // update its safe cache, never the visible row.
                                    if (!isActive || SystemClock.elapsedRealtime() >= autoEndsAt) break
                                    val videoId = verified.resolvedVideoId?.trim().orEmpty()
                                    if (videoId.isBlank() ||
                                        videoId == original.resolvedVideoId) continue
                                    val videoPoster =
                                        "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                                    if (videoPoster == original.coverUrl) continue
                                    val replacement = verified.copy(
                                        coverUrl = videoPoster,
                                        sourceNames = (verified.sourceNames +
                                            "MusicLab LAB23 video poster").distinct(),
                                    )
                                    val latest = (publishedOriginalSnapshots + publishedCoverSnapshots)
                                        .firstOrNull { it.fingerprint == fingerprint }
                                        ?: break
                                    if (hasPublishableVideo(latest)) break
                                    val change: (DiscogsVersionSeed) -> DiscogsVersionSeed = {
                                        if (it.fingerprint == fingerprint) replacement else it
                                    }
                                    if (publishedOriginalSnapshots.any { it.fingerprint == fingerprint }) {
                                        publishedOriginalSnapshots = publishedOriginalSnapshots.map(change)
                                        session.publishedOriginalSnapshots = publishedOriginalSnapshots
                                    } else {
                                        publishedCoverSnapshots = publishedCoverSnapshots.map(change)
                                        session.publishedCoverSnapshots = publishedCoverSnapshots
                                    }
                                    results = results.map(change)
                                        .distinctBy(DiscogsVersionSource::recordingIdentityKey)
                                    session.results = results
                                    session.stableOrder = session.stableOrder.map {
                                        if (it == fingerprint) replacement.fingerprint else it
                                    }
                                    session.turboReserve = session.turboReserve.filterNot {
                                        it.fingerprint == replacement.fingerprint
                                    }
                                    // A replacement inherits exactly this viewport slot.
                                    session.turboSlotKeys[replacement.fingerprint] =
                                        session.turboSlotKeys[fingerprint] ?: fingerprint
                                    session.turboFirstVisibleAtMs[replacement.fingerprint] = globalStart
                                    session.turboAutoClosedFingerprints.add(replacement.fingerprint)
                                    break
                                }
                            }
                        }
        }
        coroutineScope {
            val workers = (0 until 4).map { lane ->
                launch {
                    while (isActive && SystemClock.elapsedRealtime() < currentVideoEndMs()) {
                        val now = SystemClock.elapsedRealtime()
                        registerClock()
                        if (backgroundWorkBlocked() ||
                            playerConnection?.service?.isCoverAudioAtRisk() == true ||
                            // Preserve two metadata lanes when audio is healthy,
                            // zero during buffering/startup, four when idle.
                            (playbackIsNormallyPlaying() && lane >= 2)) {
                            delay(120L)
                            continue
                        }
                        val rows = postedRows()
                        val visibleKeys = listState.layoutInfo.visibleItemsInfo
                            .map { it.key.toString() }
                        val visible = rows.filter { row ->
                            visibleKeys.any { key -> key.endsWith("_" + row.fingerprint) }
                        }.mapTo(HashSet<String>()) { it.fingerprint }
                        // LAB26: four fair partitions, including the last 25.
                        // All unprobed positions precede every second attempt.
                        val partitions = listOf(
                            rows.take(10),
                            rows.drop(10).take(15),
                            rows.drop(25).take(13),
                            rows.drop(38),
                        )
                        val own = partitions[lane]
                        val ordered = own + rows.filterNot { it in own }
                        val allFirstProbed = (rows.size == TURBO_RESULT_QUOTA ||
                            session.turboFinished) && rows.all { candidate ->
                                hasPublishableVideo(candidate) ||
                                    (firstPasses[candidate.fingerprint] ?: 0) > 0 ||
                                    playableTrack(candidate) == null
                            }
                        val firstQueue = ordered.filter { candidate ->
                            (firstPasses[candidate.fingerprint] ?: 0) == 0
                        }
                        val retryQueue = if (allFirstProbed) ordered.filter { candidate ->
                            (firstPasses[candidate.fingerprint] ?: 0) in 1..1
                        } else emptyList()
                        val replacementQueue = if (allFirstProbed) ordered.filter { candidate ->
                            (firstPasses[candidate.fingerprint] ?: 0) >= 2 &&
                                candidate.fingerprint !in fallbackPasses
                        } else emptyList()
                        val slot = (firstQueue + retryQueue + replacementQueue).firstOrNull { candidate ->
                            candidate.fingerprint !in activeRows &&
                                !hasPublishableVideo(candidate) &&
                                playableTrack(candidate) != null &&
                                // No row can start a new probe in the final moments.
                                now + 750L < currentVideoEndMs()
                        }
                        if (slot == null) {
                            delay(100L)
                            continue
                        }
                        val fingerprint = slot.fingerprint
                        activeRows.add(fingerprint)
                        session.turboVideoReasons[fingerprint] = "running"
                        try {
                            val attemptNo = firstPasses[fingerprint] ?: 0
                            val rowStartedAt = session.turboVideoFirstProbeAtMs.getOrPut(fingerprint) {
                                SystemClock.elapsedRealtime()
                            }
                            val rowDeadline = minOf(
                                rowStartedAt + TURBO_AUTO_TOTAL_MS,
                                currentVideoEndMs(),
                            )
                            val remaining = rowDeadline - SystemClock.elapsedRealtime()
                            if (attemptNo < 2 && remaining > 0L) {
                                firstPasses[fingerprint] = attemptNo + 1
                                withTimeoutOrNull(remaining.coerceAtMost(TURBO_VIDEO_FIRST_ATTEMPT_MS)) {
                                    resolveVideoChunk(listOf(slot))
                                }
                                val latest = postedRows().firstOrNull { it.fingerprint == fingerprint }
                                session.turboVideoReasons[fingerprint] =
                                    if (latest != null && hasPublishableVideo(latest)) "verified_link"
                                    else "timeout_or_unverified"
                            } else {
                                firstPasses[fingerprint] = maxOf(attemptNo, 2)
                                fallbackPasses.add(fingerprint)
                                if (remaining > 0L) tryReplacement(fingerprint)
                                val latest = postedRows().firstOrNull { it.fingerprint == fingerprint }
                                session.turboVideoReasons[fingerprint] =
                                    if (latest == null) "cover_replaced"
                                    else if (hasPublishableVideo(latest)) "verified_link"
                                    else "no_verified_alternative"
                            }
                        } finally {
                            activeRows.remove(fingerprint)
                        }
                    }
                }
            }
            workers.forEach { it.join() }
        }

        // LAB27: video-first refill is deliberately separate from the fast
        // 10+40 poster publisher. It never triggers play() or synthetic taps.
        // Prepared substitutions stay invisible until the full batch is ready.
        val refillStartedAt = SystemClock.elapsedRealtime()
        val originalRows = postedRows()
        val missingRows = originalRows.filterNot(::hasPublishableVideo)
        if (missingRows.isNotEmpty() && isActive) {
            // Publish collected winners even at deadline; a timeout must not
            // discard the successful part of an atomic replacement wave.
            run {
                val anchorTitle = activeCriteria?.title.orEmpty()
                val owned = originalRows.mapTo(HashSet<String>()) {
                    DiscogsVersionSource.recordingIdentityKey(it)
                }
                val usedIds = originalRows.mapNotNullTo(HashSet<String>()) {
                    it.resolvedVideoId?.trim()?.takeIf(String::isNotBlank)
                }
                val options = session.turboReserve.toMutableList()
                // Single fresh discovery for ALL missing slots, not one expensive
                // remote query per poster. Use the native multi-source engine.
                if (options.count { !it.resolvedVideoId.isNullOrBlank() } < missingRows.size) {
                    val found = withTimeoutOrNull(TURBO_VIDEO_REFILL_DISCOVERY_MS) {
                        withContext(Dispatchers.IO) {
                            CoverDiscoverySources.discover(
                                title = anchorTitle,
                                originalArtist = resolvedOriginalArtist,
                                mode = DiscogsDirectMode.COVER,
                                aiConfig = foreignScoutConfig,
                                maxNetworkFanOut = if (playbackIsNormallyPlaying()) 1 else 3,
                                forceRefresh = true,
                            ).candidates
                        }
                    }.orEmpty()
                    options.addAll(found.filter {
                        TitleMeaningResolver.matchesBaseTitle(anchorTitle, it.title)
                    }.map {
                        DiscogsVersionSource.externalSeed(
                            candidate = it,
                            targetTitle = anchorTitle,
                            originalArtist = resolvedOriginalArtist,
                        )
                    })
                }
                val replacementCandidates = options
                    .distinctBy(DiscogsVersionSource::recordingIdentityKey)
                    .filter { candidate ->
                        !isRejected(candidate) && modeAcceptsSeed(candidate) &&
                            DiscogsVersionSource.recordingIdentityKey(candidate) !in owned &&
                            TitleMeaningResolver.matchesBaseTitle(anchorTitle, candidate.trackTitle) &&
                            playableTrack(candidate) != null
                    }
                val reserved = mutableSetOf<String>()
                val swaps = linkedMapOf<String, DiscogsVersionSeed>()
                val noDuplicateVideo = usedIds.toMutableSet()
                // Priority by closeness to the slot score, but NEVER enforce an
                // exact score or rearrange existing rows. Video is verified
                // before its own preview is used as the replacement poster.
                for (missing in missingRows) {
                    if (!isActive || SystemClock.elapsedRealtime() >=
                        refillStartedAt + TURBO_VIDEO_REFILL_MAX_MS) break
                    while (backgroundWorkBlocked() ||
                        playerConnection?.service?.isCoverAudioAtRisk() == true) {
                        if (!isActive || SystemClock.elapsedRealtime() >=
                            refillStartedAt + TURBO_VIDEO_REFILL_MAX_MS) break
                        delay(120L)
                    }
                    if (!isActive || SystemClock.elapsedRealtime() >=
                        refillStartedAt + TURBO_VIDEO_REFILL_MAX_MS) break
                    val originalSide = isDistinctOriginalVersion(missing)
                    val ranked = replacementCandidates.asSequence()
                        .filter { candidate ->
                            candidate.fingerprint !in reserved &&
                                isDistinctOriginalVersion(candidate) == originalSide
                        }
                        .sortedWith(
                            compareByDescending<DiscogsVersionSeed> {
                                !it.resolvedVideoId.isNullOrBlank()
                            }.thenBy {
                                kotlin.math.abs(it.confidenceScore - missing.confidenceScore)
                            }.thenByDescending { it.confidenceScore }
                        ).take(10).toList()
                    for (candidate in ranked) {
                        if (!isActive || SystemClock.elapsedRealtime() >=
                            refillStartedAt + TURBO_VIDEO_REFILL_MAX_MS) break
                        reserved.add(candidate.fingerprint)
                        val verified = withTimeoutOrNull(2_800L) {
                            lab21VerifyAlternative(candidate)
                        } ?: continue
                        val id = verified.resolvedVideoId?.trim().orEmpty()
                        if (id.isBlank() || id in noDuplicateVideo ||
                            !hasPublishableVideo(verified)) continue
                        val newPoster = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                        val fresh = verified.copy(
                            coverUrl = newPoster,
                            sourceNames = (verified.sourceNames +
                                "MusicLab LAB27 verified video").distinct(),
                        )
                        swaps[missing.fingerprint] = fresh
                        noDuplicateVideo.add(id)
                        owned.add(DiscogsVersionSource.recordingIdentityKey(fresh))
                        break
                    }
                }
                // SINGLE COMMIT: mutate both list snapshots once. All original
                // positions remain fixed, including when scores differ.
                if (swaps.isNotEmpty() && isActive) {
                    val snapshot = publishedOriginalSnapshots + publishedCoverSnapshots
                    val stillValid = swaps.filter { (fingerprint, _) ->
                        snapshot.any { it.fingerprint == fingerprint &&
                            !hasPublishableVideo(it) }
                    }
                    if (stillValid.isNotEmpty()) {
                        fun swap(row: DiscogsVersionSeed): DiscogsVersionSeed =
                            stillValid[row.fingerprint] ?: row
                        publishedOriginalSnapshots = publishedOriginalSnapshots.map(::swap)
                        publishedCoverSnapshots = publishedCoverSnapshots.map(::swap)
                        session.publishedOriginalSnapshots = publishedOriginalSnapshots
                        session.publishedCoverSnapshots = publishedCoverSnapshots
                        results = results.map(::swap)
                            .distinctBy(DiscogsVersionSource::recordingIdentityKey)
                        session.results = results
                        session.stableOrder = session.stableOrder.map {
                            stillValid[it]?.fingerprint ?: it
                        }
                        stillValid.forEach { (old, fresh) ->
                            session.turboSlotKeys[fresh.fingerprint] =
                                session.turboSlotKeys[old] ?: old
                            session.turboPosterPublishedAtMs[fresh.fingerprint] =
                                session.turboPosterPublishedAtMs[old]
                                    ?: SystemClock.elapsedRealtime()
                            session.turboFirstVisibleAtMs[fresh.fingerprint] =
                                session.turboFirstVisibleAtMs[old]
                                    ?: SystemClock.elapsedRealtime()
                            session.turboAutoClosedFingerprints.add(fresh.fingerprint)
                        }
                        turboAutoClosedTick++
                    }
                }
                android.util.Log.i(
                    "MusicLabLAB27Refill",
                    "missing=%d swapped=%d remaining=%d".format(
                        missingRows.size, swaps.size,
                        (publishedOriginalSnapshots + publishedCoverSnapshots)
                            .count { !hasPublishableVideo(it) },
                    ),
                )
            }
        }
        registerClock()
        postedRows().forEach { row ->
            session.turboAutoClosedFingerprints.add(row.fingerprint)
            session.turboVideoReasons.putIfAbsent(row.fingerprint, "deadline")
        }
        // Counts only: no private titles, URLs or credentials in diagnostics.
        val completedRows = postedRows()
        android.util.Log.i(
            "MusicLabLAB26Video",
            "rows=%d probed=%d linked=%d zero_attempt=%d".format(
                completedRows.size,
                completedRows.count { (firstPasses[it.fingerprint] ?: 0) > 0 },
                completedRows.count(::hasPublishableVideo),
                completedRows.count { (firstPasses[it.fingerprint] ?: 0) == 0 },
            ),
        )
        session.turboVideoComplete = true
        turboAutoClosedTick++
    }

    fun pauseCoverBackgroundForPlayback() {
        // LAB54: only the short playback-start critical lane owns the network.
        // Discovery/pagination survive because Cover must remain usable while music plays.
        backgroundPausedForPlayback = true
        // Cancel in-flight automatic provider calls, not just queued probes.
        turboAutoVideoJob?.cancel()
        turboAutoVideoJob = null
        verificationJob?.cancel()
        videoPreloadJob?.cancel()
        backgroundResumeJob?.cancel()
    }

    fun resumeCoverBackgroundAfterPlaybackBurst() {
        backgroundResumeJob?.cancel()
        // Turbo LAB05: once all cards are published, a Cover tap must NEVER
        // wake discovery, metadata verification, or a performance governor.
        if (isTurbo && mode == DiscogsDirectMode.COVER) {
            // LAB14: manual playback takes the critical lane only for startup,
            // then resumes the independent, bounded video-only worker.
            backgroundPausedForPlayback = false
            // Resume within the original bounded coverage window; never
            // restart its clock or compete with startup/rebuffering.
            if (!session.turboVideoComplete && session.turboAutoStartedAtMs > 0L &&
                SystemClock.elapsedRealtime() <
                    session.turboAutoStartedAtMs + TURBO_VIDEO_COVERAGE_MAX_MS) {
                turboSearchGeneration++
            }
            if (session.turboFinished) {
                playerConnection?.service?.setCoverPerformanceLoad(active = false, heavy = false)
                // LAB11: cached sessions remain idle on re-entry.
            }
            return
        }
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
        if (decisionSavingFingerprint != null) return
        if (status != AiBrainDecisionStatus.APPROVED &&
            status != AiBrainDecisionStatus.REJECTED) return

        // LAB17: the editorial action must work OFFLINE and cannot be held
        // hostage by optional Cloud endpoints. Store the user's decision first,
        // using exactly the same work-and-candidate identity as the UI filter.
        val approved = status == AiBrainDecisionStatus.APPROVED
        CoverPlaybackMemory.saveEditorialDecision(
            context,
            TitleMeaningResolver.workAnchorTitle(title),
            rejectionKey(seed),
            approved,
        )
        if (approved) {
            session.rejectedKeys.remove(rejectionKey(seed))
            session.approvedKeys.add(rejectionKey(seed))
            replaceSeed(
                seed.copy(
                    confidenceScore = 20,
                    confidenceReasons =
                        (seed.confidenceReasons + "Versione approvata manualmente").distinct(),
                    manuallyApproved = true,
                ),
            )
        } else {
            session.approvedKeys.remove(rejectionKey(seed))
            session.rejectedKeys.add(rejectionKey(seed))
            replaceSeed(
                seed.copy(
                    confidenceScore = 1,
                    confidenceReasons =
                        (seed.confidenceReasons + "Versione disapprovata manualmente").distinct(),
                    manuallyApproved = false,
                ),
            )
        }
        // The dialog and filtered list react immediately, regardless of
        // provider status. This never deletes Cloud data or changes the player.
        detailSeed = null
        Toast.makeText(
            context,
            if (approved) "Approvata: decisione salvata sul telefono."
            else "Disapprovata: esclusa dalle cover sul telefono.",
            Toast.LENGTH_LONG,
        ).show()

        // Preserve existing Cloud collaboration when available, but make it
        // strictly best-effort. Local decisions persist across app restarts.
        val config = foreignScoutConfig
        if (config != null && config.cloudEndpoint.isNotBlank() &&
            resolvedOriginalArtist.isNotBlank() && title.isNotBlank()) {
            decisionSavingFingerprint = seed.fingerprint
            scope.launch {
                val saved = try {
                    CloudMusicDiscovery.saveBrainDecision(
                        originalTitle = title,
                        originalArtist = resolvedOriginalArtist,
                        candidate = seedToBrainCandidate(seed),
                        status = status,
                        config = config,
                        categoryOverride =
                            if (mode == DiscogsDirectMode.ORIGINAL) {
                                cloudCategory(seed)
                            } else null,
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    false
                } finally {
                    decisionSavingFingerprint = null
                }
                if (!saved) {
                    Toast.makeText(
                        context,
                        "Decisione locale conservata. Cloud non sincronizzato.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    // LAB13: retain a genuine editorial image whenever present. Archive entries
    // may contain a video ID but no editorial coverUrl; without this fallback
    // they become permanent empty poster slots on every cached search.
    // The video thumbnail is NOT pinned into the editorial-artwork namespace.
    fun stableArtworkFor(seed: DiscogsVersionSeed): String? =
        if (mode == DiscogsDirectMode.COVER) {
            // LAB22 manual substitute shows its own verified video thumbnail,
            // never a persisted image belonging to an older recording.
            if (isTurbo && ("MusicLab LAB22 video poster" in seed.sourceNames ||
                    "MusicLab LAB23 video poster" in seed.sourceNames)) {
                seed.coverUrl
            } else CoverPlaybackMemory.editorialArtwork(context, seed.fingerprint, seed.coverUrl)
                // LAB14: previously archived songs may have only an older
                // MusicLab locally pinned image rather than editorial cover.
                ?: if ("Archivio Cloud" in seed.sourceNames) {
                    CoverPlaybackMemory.stableArtwork(context, seed.fingerprint, seed.coverUrl)
                } else null
                // Some Cloud records already hold a real YouTube poster URL,
                // which LAB10's editorial-only cache deliberately rejects.
                ?: seed.coverUrl?.takeIf { url ->
                    "Archivio Cloud" in seed.sourceNames &&
                        url.startsWith("https://", ignoreCase = true) &&
                        (url.contains("ytimg.com", ignoreCase = true) ||
                            url.contains("youtube.com", ignoreCase = true))
                }
                // Last resort: the playback video's own poster, not a second
                // full-resolution bitmap nor a new hidden web search.
                ?: seed.resolvedVideoId?.takeIf { id ->
                    id.matches(Regex("[A-Za-z0-9_-]{11}"))
                }?.let { id -> "https://i.ytimg.com/vi/$id/hqdefault.jpg" }
        } else {
            CoverPlaybackMemory.stableArtwork(context, seed.fingerprint, seed.coverUrl)
        }

    suspend fun playResolvedContext(selectedFingerprint: String) {
        val connection = playerConnection ?: return
        // Turbo LAB05: re-entry must play the immutable, previously certified
        // card, even if a transient catalog refresh changes the mutable pool.
        var selectedSeed =
            (if (isTurbo && mode == DiscogsDirectMode.COVER) {
                // The T+5 poster snapshot is intentionally immutable; during
                // T+5..10, the mutable recording may acquire a different ID.
                // Play only the new metadata-verified binding, never a stale
                // unverified ID retained by the original artwork card.
                // LAB13: the original-performer variants and the foreign
                // covers share the same verified player path. LAB12 checked
                // only publishedCoverSnapshots, leaving originals unplayable.
                val published = publishedOriginalSnapshots + publishedCoverSnapshots
                val admitted = published.any { it.fingerprint == selectedFingerprint }
                if (admitted) {
                    results.firstOrNull {
                        it.fingerprint == selectedFingerprint && hasPublishableVideo(it)
                    } ?: published.firstOrNull {
                        it.fingerprint == selectedFingerprint && hasPublishableVideo(it)
                    }
                } else null
            } else null)
                ?: (if (!isTurbo) {
                    orderedResults(results).firstOrNull {
                        it.fingerprint == selectedFingerprint && !it.resolvedVideoId.isNullOrBlank()
                    }
                } else null)
                ?: return
        var selectedId = selectedSeed.resolvedVideoId?.trim().orEmpty()
        if (selectedId.isBlank()) return
        if (isTurbo && mode == DiscogsDirectMode.COVER && !hasPublishableVideo(selectedSeed)) return

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
            CoverPlaybackMemory.pinEditorialVideoArtwork(context, selectedId, selectedArtwork)
        }
        val selectedItem =
            selectedSong?.toMediaMetadata()?.copy(thumbnailUrl = selectedArtwork)
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

        if (isTurbo && mode == DiscogsDirectMode.COVER &&
            connection.service.isPlayerReady.value) {
            // Turbo LAB05: a tapped verified recording goes directly through
            // the existing native playNow path.  playQueue asynchronously
            // filters and resolves a new queue; a filtered/late result could
            // silently keep the preceding song playing on re-entry.
            connection.playNow(selectedItem)
        } else {
            connection.playQueue(
                ListQueue(
                    title = queueTitle,
                    items = listOf(selectedItem),
                    startIndex = 0,
                ),
            )
        }
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
        // Global wall clock starts at the actual user search action, not when
        // verification begins after several network catalog requests.
        val turboCommandStartedAt = SystemClock.elapsedRealtime()
        val repeatSameWork = activeCriteria?.title?.equals(criteria.title, ignoreCase = true) == true
        if (isTurbo && repeatSameWork && session.turboFinished &&
            (session.publishedCoverSnapshots.isNotEmpty() || session.publishedOriginalSnapshots.isNotEmpty()) &&
            (session.publishedCoverSnapshots + session.publishedOriginalSnapshots).all(::hasPublishableVideo)) {
            // Only a fully linked, verified session may be replayed without refreshing.
            // Missing videos are *retryable*, never a permanently cached negative.
            loading = false
            sourceDiscoveryLoading = false
            return
        }
        session.turboFinished = false
        session.turboVideoComplete = false
        session.turboPosterPublishedAtMs.clear()
        session.turboSlotKeys.clear()
        session.turboAutoStartedAtMs = 0L
        session.turboVideoReasons.clear()
        session.turboVideoFirstProbeAtMs.clear()
        session.automaticVideoAttempts.clear()
        session.turboFirstVisibleAtMs.clear()
        session.turboAutoClosedFingerprints.clear()
        session.turboManualTriedSpareIds.clear()
        turboAutoClosedTick++
        turboSearchGeneration++
        turboVisibleJobs.values.forEach { it.cancel() }
        turboVisibleJobs.clear()
        turboReplacementClaims.clear()
        if (!repeatSameWork) session.turboReserve = emptyList()
        session.turboBurstStartedAtMs = if (isTurbo && mode == DiscogsDirectMode.COVER) turboCommandStartedAt else 0L
        session.turboArchiveMatches = 0
        val retainedResults = if (repeatSameWork) results else emptyList()
        val retainedOrder = if (repeatSameWork) session.stableOrder else emptyList()
        searchJob?.cancel()
        manualVideoSearchJob?.cancel()
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
        visibleLimit = if (isTurbo && mode == DiscogsDirectMode.COVER) TURBO_RESULT_QUOTA else pageSize
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
                // Turbo searching cannot downgrade any newly selected song.
                heavy = !isTurbo && !playbackIsNormallyPlaying(),
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
        session.visibleLimit = if (isTurbo && mode == DiscogsDirectMode.COVER) TURBO_RESULT_QUOTA else pageSize
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
            session.turboStreamReadyIds.clear()
            session.transientVideoRetries.clear()
            session.automaticVideoAttempts.clear()
        }
        session.originalWorkCredits = emptyList()

        if (isTurbo && mode == DiscogsDirectMode.COVER) {
            // Turbo LAB02: replace LAB01's staged discovery/publisher entirely.
            // No other worker owns this search. UI reveals ONLY the final sealed
            // verified snapshot, in one virtualized scroll, within five seconds.
            searchJob = scope.launch {
                val stopAt = turboCommandStartedAt + TURBO_BURST_DEADLINE_MS
                // Source callbacks retain completed findings even if the whole
                // aggregate discovery reaches its bounded deadline.
                // Exact duplicate callbacks must never consume another video-verifier turn.
                // A distinct source or richer relation remains independent evidence.
                val sourceEvidence = ConcurrentHashMap.newKeySet<CoverSourceCandidate>()
                val streamingCandidates = Channel<List<CoverSourceCandidate>>(Channel.UNLIMITED)
                val supplementalSeeds = Channel<List<DiscogsVersionSeed>>(Channel.UNLIMITED)
                val sourceStatus = ConcurrentHashMap<String, CoverSourceDiagnostic>()
                val sourceOriginalYear = AtomicReference<Int?>(null)
                val archiveOriginalArtist = AtomicReference<String?>(null)
                val cloudArchiveCount = java.util.concurrent.atomic.AtomicInteger(-1)
                val cloudMemoryCount = java.util.concurrent.atomic.AtomicInteger(-1)
                try {
                    listState.scrollToItem(0)
                    withTimeoutOrNull(
                        (stopAt - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                    ) {
                        coroutineScope {
                            // LAB07 Pollicino: the verifier consumes provider callbacks
                            // while slow catalog/cloud lanes are still running.
                            // Compose state stays on the owning UI dispatcher;
                            // user sees only the final atomic snapshot.
                            val selectedArchiveItem =
                                CloudMusicDiscovery.recentArchiveSelection(
                                    criteria.title, foreignScoutConfig?.cloudEndpoint.orEmpty(),
                                )
                            var quotaReached = false
                            val providerJobs = mutableListOf<Job>()
                            // LAB11: the initial block is atomically published
                            // when 50 candidates arrive, or at T+1.8s if at
                            // least 40 have arrived. Never reflow the block.
                            fun sealPostersOnce() {
                                if (publishedCoverSnapshots.isNotEmpty() ||
                                    publishedOriginalSnapshots.isNotEmpty() || activeCriteria != criteria) return
                                val ranked = turboRankedPool()
                                    .filter(::modeAcceptsSeed)
                                    .take(TURBO_RESULT_QUOTA)
                                // LAB27: publish the first ten together *as soon
                                // as* ten ranked documentary candidates exist.
                                // No artificial 1.2s minimum wait or video gate.
                                // Original performer versions remain ranked first.
                                if (ranked.size < TURBO_MIN_EARLY_POSTER_BLOCK) return
                                val firstBlock = ranked.take(TURBO_MIN_EARLY_POSTER_BLOCK)
                                publishedOriginalSnapshots = firstBlock.filter(::isDistinctOriginalVersion)
                                session.publishedOriginalSnapshots = publishedOriginalSnapshots
                                publishedCoverSnapshots = firstBlock.filterNot(::isDistinctOriginalVersion)
                                session.publishedCoverSnapshots = publishedCoverSnapshots
                                val sealedAt = SystemClock.elapsedRealtime()
                                firstBlock.forEachIndexed { index, seed ->
                                    session.turboPosterPublishedAtMs[seed.fingerprint] = sealedAt
                                    session.turboSlotKeys[seed.fingerprint] = "slot-$index"
                                }
                                visibleLimit = TURBO_RESULT_QUOTA
                                session.visibleLimit = TURBO_RESULT_QUOTA
                                publishedReadyLimit = firstBlock.size
                                session.publishedReadyLimit = firstBlock.size
                                rankingFrozen = true
                                session.rankingFrozen = true
                                originalSectionFrozen = true
                                session.originalSectionFrozen = true
                                // The first ten positions are immutable while
                                // metadata discovery prepares the next forty.
                                // Video bindings remain independently updateable.
                                // The global ten-second scope owns cancellation.
                                loading = false
                            }
                            // LAB27: the next 40 appear atomically whenever ready;
                            // never wait for a mandatory five-second boundary.
                            // Never insert before or rerank committed first rows.
                            fun sealSecondPostersOnce() {
                                if (activeCriteria != criteria ||
                                    publishedOriginalSnapshots.size + publishedCoverSnapshots.size !=
                                        TURBO_MIN_EARLY_POSTER_BLOCK) return
                                val committedKeys = (publishedOriginalSnapshots + publishedCoverSnapshots)
                                    .mapTo(HashSet<String>()) {
                                        DiscogsVersionSource.recordingIdentityKey(it)
                                    }
                                val next = turboRankedPool()
                                    .filter(::modeAcceptsSeed)
                                    .filterNot {
                                        DiscogsVersionSource.recordingIdentityKey(it) in committedKeys
                                    }
                                    .take(TURBO_POSTER_SECOND_BLOCK)
                                if (next.size < TURBO_POSTER_SECOND_BLOCK) return
                                // Append atomically; none of the first ten shifts.
                                val firstCount = publishedOriginalSnapshots.size +
                                    publishedCoverSnapshots.size
                                publishedCoverSnapshots = publishedCoverSnapshots + next
                                session.publishedCoverSnapshots = publishedCoverSnapshots
                                val shown = SystemClock.elapsedRealtime()
                                next.forEachIndexed { index, seed ->
                                    session.turboPosterPublishedAtMs[seed.fingerprint] = shown
                                    session.turboSlotKeys[seed.fingerprint] = "slot-${firstCount + index}"
                                }
                                publishedReadyLimit = TURBO_RESULT_QUOTA
                                session.publishedReadyLimit = TURBO_RESULT_QUOTA
                            }
                            fun stopAtFullQuota(): Boolean {
                                if (coverCandidatePool().count(::hasPublishableVideo) < TURBO_RESULT_QUOTA) return false
                                if (!quotaReached) {
                                    quotaReached = true
                                    // Stop just the producer runners; cancellation of
                                    // the parent here would discard our final snapshot.
                                    providerJobs.forEach { it.cancel() }
                                }
                                return true
                            }
                            // LAB16: metadata ingestion and 25+25 publication
                            // own a separate UI-dispatcher child job. A slow
                            // YouTube verification must NEVER delay a finished
                            // batch of real documentary poster candidates.
                            val posterCollector = launch {
                                while (isActive && SystemClock.elapsedRealtime() <
                                    stopAt - TURBO_THUMBNAIL_RESERVE_MS) {
                                    val early = withTimeoutOrNull(110L) {
                                        streamingCandidates.receive()
                                    }
                                    // LAB19: parse and normalize provider rows OFF
                                    // the Compose main thread; only final immutable
                                    // results are published on the UI dispatcher.
                                    val knownOriginalArtist = resolvedOriginalArtist
                                    val documentedSeeds = withContext(Dispatchers.Default) {
                                        early.orEmpty().asSequence()
                                            .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
                                            .distinctBy { it.title.lowercase() + "|" + it.artist.lowercase() }
                                            .take(TURBO_MAX_RANK_CANDIDATES)
                                            .map { item ->
                                                DiscogsVersionSource.externalSeed(
                                                    candidate = item,
                                                    targetTitle = criteria.title,
                                                    originalArtist = knownOriginalArtist,
                                                )
                                            }.toList()
                                    }
                                    val cloudSeedsNow = supplementalSeeds.tryReceive().getOrNull().orEmpty()
                                    val seeds = documentedSeeds + cloudSeedsNow
                                    if (seeds.isNotEmpty()) {
                                        results = mergePage(results, seeds, replace = false)
                                        session.results = results
                                        rebuildStableOrder()
                                        rankingFrozen = true
                                        originalSectionFrozen = true
                                        session.rankingFrozen = true
                                        session.originalSectionFrozen = true
                                    }
                                    // Even without a callback, Discogs can update the pool.
                                    sealPostersOnce()
                                    sealSecondPostersOnce()
                                }
                            }
                            // LAB21: removed legacy global streamingVerifier;
                            // visible rows own all automatic metadata verification.

                            // LAB20: share one validated archive conversion
                            // between an instant Archive-screen handoff and the
                            // asynchronous Cloud refresh. No extra search engine.
                            fun archiveSeeds(items: List<CloudArchiveItem>): List<DiscogsVersionSeed> =
                                items.mapNotNull { item ->
                                    val work = item.workTitle.ifBlank { item.title }
                                    if (!TitleMeaningResolver.matchesBaseTitle(criteria.title, work) &&
                                        !TitleMeaningResolver.matchesBaseTitle(criteria.title, item.title)
                                    ) return@mapNotNull null
                                    val category = when (item.category.lowercase()) {
                                        "live", "dal vivo" -> AiCoverCategory.LIVE
                                        "remix", "mix", "rework" -> AiCoverCategory.REMIX
                                        "foreign", "straniera", "adattamento" -> AiCoverCategory.FOREIGN
                                        else -> AiCoverCategory.COVER
                                    }
                                    DiscogsVersionSource.externalSeed(
                                        CoverSourceCandidate(
                                            title = item.title, artist = item.artist,
                                            sources = listOf("Archivio Cloud"),
                                            year = item.year, album = item.album,
                                            coverUrl = item.coverUrl, language = item.language,
                                            category = category,
                                            playbackVideoId = item.playbackVideoId,
                                            playbackVideoTitle = item.playbackVideoTitle,
                                            playbackVideoSource = item.playbackVideoSource,
                                            workRelationConfirmed = true,
                                            evidenceScore = (((item.sameWorkScore ?: 30) +
                                                (item.versionTypeScore ?: 30)) / 10).coerceIn(1, 20),
                                        ),
                                        targetTitle = criteria.title,
                                        originalArtist = resolvedOriginalArtist,
                                    )
                                }

                            // Already-selected archive records are published
                            // as soon as their own 1.2s poster boundary opens.
                            val earlyArchiveSeeds = archiveSeeds(listOfNotNull(selectedArchiveItem))
                            if (earlyArchiveSeeds.isNotEmpty()) supplementalSeeds.trySend(earlyArchiveSeeds)

                            val archiveDeferred = async(Dispatchers.IO) {
                                val items = withTimeoutOrNull(TURBO_SOURCE_WAIT_MS) {
                                    foreignScoutConfig?.let { config ->
                                        CloudMusicDiscovery.searchArchive(
                                            query = criteria.title,
                                            config = config,
                                            limit = 100,
                                        )
                                    }.orEmpty()
                                }.orEmpty()
                                cloudArchiveCount.set(items.size)
                                items.filter {
                                    it.originalArtist.isNotBlank() && (it.sameWorkScore ?: 0) >= 70
                                }.groupBy { it.originalArtist.trim() }
                                    .maxByOrNull { it.value.size }
                                    ?.key?.let { archiveOriginalArtist.set(it) }
                            val seeds = archiveSeeds(
                                (listOfNotNull(selectedArchiveItem) + items).distinctBy {
                                    listOf(it.title.lowercase(), it.artist.lowercase(), it.category.lowercase())
                                }
                            )
                                if (seeds.isNotEmpty()) supplementalSeeds.trySend(seeds)
                                seeds
                            }
                            val memoryDeferred = async(Dispatchers.IO) {
                                val memory = withTimeoutOrNull(TURBO_SOURCE_WAIT_MS) {
                                    foreignScoutConfig?.let { config ->
                                        // An incoming cover's artist is not always
                                        // the original work artist. Reuse the archive
                                        // attribution if it arrives promptly, before
                                        // deciding that Cloud memory is empty.
                                        val archiveArtist = withTimeoutOrNull(800L) {
                                            archiveDeferred.await()
                                            archiveOriginalArtist.get()
                                        }
                                        val memoryArtist = archiveArtist?.takeIf(String::isNotBlank)
                                            ?: resolvedOriginalArtist
                                        val cached = CloudMusicDiscovery.cachedMemoryState(
                                            context = context, title = criteria.title,
                                            artist = memoryArtist, config = config,
                                        )
                                        // Keep archive documentary candidates, but do not
                                        // let a seven-day snapshot containing only misses
                                        // block a fresh *read-only* Cloud discovery.
                                        cached?.takeIf { state ->
                                            val versions = state.discovery?.versions.orEmpty()
                                            val linked = versions.count { version ->
                                                !version.playbackVideoId.isNullOrBlank()
                                            }
                                            // A single confirmed original cannot freeze
                                            // forty-nine missing covers for seven days.
                                            versions.isNotEmpty() && linked * 2 >= versions.size
                                        } ?: CloudMusicDiscovery.discoverMemoryState(
                                            title = criteria.title, artist = memoryArtist,
                                            config = config, limit = 150, cacheContext = context,
                                        )
                                    }
                                }
                                cloudMemoryCount.set(memory?.discovery?.versions?.size ?: 0)
                                val known = memory?.discovery?.versions.orEmpty().map(::memoryCandidateToSeed)
                                if (known.isNotEmpty()) supplementalSeeds.trySend(known)
                                memory
                            }
                            val firstPageDeferred = async {
                                withTimeoutOrNull(TURBO_SOURCE_WAIT_MS) {
                                    loadPage(criteria, page = 1, replace = false, requestedSort = requestedSort)
                                } ?: false
                            }
                            val externalDeferred = async(Dispatchers.IO) {
                                // COVER.INFO needs two network hops: first the
                                // matching work, then its related recordings.
                                // Early callbacks are verified concurrently.
                                // The enclosing 5000ms deadline still rules all.
                                withTimeoutOrNull(TURBO_COVER_INFO_WAIT_MS) {
                                    CoverDiscoverySources.discover(
                                        title = criteria.title,
                                        originalArtist = resolvedOriginalArtist,
                                        mode = mode,
                                        aiConfig = foreignScoutConfig,
                                        maxNetworkFanOut = if (playbackIsNormallyPlaying() || playbackIsCritical()) 2 else 5,
                                        // LAB25: new automatic searches must not inherit a stale
                                        // 30-minute provider miss from a previous engine revision.
                                        forceRefresh = true,
                                        onEarlyVideoCandidates = { quick ->
                                            // Fast per-source Top-N before global
                                            // cross-provider 1–20 certification.
                                            val fresh = quick.sortedByDescending { it.evidenceScore }
                                                .take(24).filter { sourceEvidence.add(it) }
                                            if (fresh.isNotEmpty()) streamingCandidates.trySend(fresh)
                                            if (quick.isNotEmpty()) {
                                                sourceStatus["COVER.INFO"] = CoverSourceDiagnostic(
                                                    "COVER.INFO", true, quick.size,
                                                    "prima pagina acquisita · dettaglio in corso",
                                                )
                                            }
                                        },
                                        onSourceCompleted = { items, diagnostic, originalYear ->
                                            val fresh = items.sortedByDescending { it.evidenceScore }
                                                .take(24).filter { sourceEvidence.add(it) }
                                            if (fresh.isNotEmpty()) streamingCandidates.trySend(fresh)
                                            sourceStatus[diagnostic.name] = diagnostic
                                            if (originalYear != null) sourceOriginalYear.set(originalYear)
                                        },
                                    )
                                }
                            }

                            // Producers already launched together. The first
                            // 50 metadata-verified versions cancel remaining lanes
                            // immediately; source-complete still awaits normally
                            // when fewer versions were found before the deadline.
                            providerJobs.addAll(
                                listOf(archiveDeferred, memoryDeferred, firstPageDeferred, externalDeferred),
                            )
                            // A warm MusicLab cache can hit quota before all
                            // asynchronous runners have been registered.
                            if (quotaReached) providerJobs.forEach { it.cancel() }
                            val cloudSeeds = try {
                                archiveDeferred.await()
                            } catch (cancel: CancellationException) {
                                if (!quotaReached) throw cancel
                                emptyList()
                            }
                            val memoryState = try {
                                memoryDeferred.await()
                            } catch (cancel: CancellationException) {
                                if (!quotaReached) throw cancel
                                null
                            }
                            try {
                                firstPageDeferred.await()
                            } catch (cancel: CancellationException) {
                                if (!quotaReached) throw cancel
                            }
                            val external = try {
                                externalDeferred.await()
                            } catch (cancel: CancellationException) {
                                if (!quotaReached) throw cancel
                                null
                            }
                            // A completed cache hit returns no lane callbacks.
                            // Its diagnostic snapshot is still authoritative.
                            external?.diagnostics?.forEach { diagnostic ->
                                sourceStatus[diagnostic.name] = diagnostic
                            }

                            session.turboArchiveMatches = cloudSeeds.size
                            if (resolvedOriginalArtist.isBlank()) {
                                memoryState?.discovery?.original?.artist
                                    ?.takeIf(String::isNotBlank)?.let { resolvedOriginalArtist = it }
                            }
                            // A search may have started from another singer's
                            // cover. The archive's verified work identity takes
                            // precedence over the supplied performer's identity.
                            if (explicitArtistHint.isNullOrBlank()) {
                                (archiveOriginalArtist.get()
                                    ?: memoryState?.discovery?.original?.artist
                                        ?.takeIf(String::isNotBlank))
                                    ?.let { resolvedOriginalArtist = it }
                            }
                            val memorySeeds = memoryState?.discovery?.versions
                                .orEmpty().map(::memoryCandidateToSeed)
                            // Do not throw away already-completed providers if a
                            // different lane times out and the aggregate is null.
                            // A service may send one early callback and the same
                            // entries again in its final outcome. Merge each exact
                            // documentary observation just once.
                            val externalSeeds = (sourceEvidence.toList() +
                                external?.candidates.orEmpty()).distinct().map { source ->
                                DiscogsVersionSource.externalSeed(
                                    candidate = source,
                                    targetTitle = criteria.title,
                                    originalArtist = resolvedOriginalArtist,
                                )
                            }
                            var merged = mergePage(results, cloudSeeds + memorySeeds + externalSeeds, replace = false)
                            val workTitle = TitleMeaningResolver.workAnchorTitle(criteria.title)
                            // Original-work fallback when the user started
                            // from a COVER and no archive/memory attribution was
                            // verified: compare discographic issue years, NEVER
                            // YouTube upload dates. A unique earliest candidate
                            // requires at least two independent providers.
                            if (explicitArtistHint.isNullOrBlank() &&
                                archiveOriginalArtist.get().isNullOrBlank() &&
                                memoryState?.discovery?.original?.artist.isNullOrBlank()) {
                                val workYear = sourceOriginalYear.get()
                                    ?: external?.originalYear
                                val earliest = merged.filter { seed ->
                                    seed.year != null &&
                                        seed.kind == DiscogsVersionKind.STUDIO &&
                                        seed.sourceNames.distinct().size >= 2 &&
                                        TitleMeaningResolver.matchesBaseTitle(workTitle, seed.trackTitle)
                                }.sortedBy { it.year }
                                val first = earliest.firstOrNull()
                                val second = earliest.firstOrNull {
                                    !TitleMeaningResolver.sameArtist(it.artist, first?.artist.orEmpty())
                                }
                                if (first?.year != null &&
                                    (second?.year == null || first.year < second.year) &&
                                    (workYear == null || first.year <= workYear + 1)) {
                                    resolvedOriginalArtist = first.artist
                                }
                            }
                            // Use documentary original chronology even if another
                            // service times out. Unknown dates never become invented.
                            val originalYear = sourceOriginalYear.get()
                                ?: memoryState?.discovery?.original?.year
                                ?: external?.originalYear
                            merged = withContext(Dispatchers.Default) {
                                merged.map { seed ->
                                    DiscogsVersionSource.certifyForFrozenRanking(
                                        seed = seed, targetTitle = workTitle,
                                        originalArtist = resolvedOriginalArtist,
                                        originalYear = originalYear, originalCredits = emptyList(),
                                    )
                                }
                            }
                            if (activeCriteria != criteria) return@coroutineScope
                            results = merged
                            session.results = results
                            session.originalYear = originalYear
                            rebuildStableOrder()
                            rankingFrozen = true
                            session.rankingFrozen = true
                            originalSectionFrozen = true
                            session.originalSectionFrozen = true
                            session.sourceDiscoveryComplete = true
                            session.originalGateStartedAtMs = SystemClock.elapsedRealtime()
                            session.coverBatchStartedAtMs = SystemClock.elapsedRealtime()

                            // LAB04: inspect the entire scored evidence pool, not
                            // the first hundred releases. A first hundred that
                            // contains many artwork-only seeds must not starve
                            // lower-ranked but playable genuine cover recordings.
                            // Already verified IDs are reused without a new probe.
                            // All remote verification remains inside this single
                            // hard global deadline and never probes audio streams.
                            // LAB21: no competing legacy ten-second global
                            // video lookup. The 5+5+5 per-visible coordinator
                            // owns every automatic video search and cancellation.
                            // Prime Coil's existing image memory/disk caches, rather
                            // than creating 50 extra full-size bitmap copies.
                            val ready = turboRankedPool()
                                .filter(::hasPublishableVideo).take(TURBO_RESULT_QUOTA)
                            val thumbnails = ready.mapNotNull { seed ->
                                seed.resolvedVideoId?.takeIf(String::isNotBlank)?.let { id ->
                                    "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                                }
                            }.distinct()
                            // Thumbnail warming never extends the burst to 5s
                            // after we already reached the 50-video quota.
                            // When audio is playing, leave image IO to LazyColumn.
                            val imageBudget = minOf(
                                TURBO_THUMBNAIL_RESERVE_MS,
                                (stopAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
                            )
                            if (imageBudget > 0L && thumbnails.isNotEmpty() &&
                                !playbackIsNormallyPlaying() && !playbackIsCritical()) {
                                withTimeoutOrNull(imageBudget) {
                                    val gate = Semaphore(3)
                                    thumbnails.take(12).map { url ->
                                        async(Dispatchers.IO) {
                                            gate.withPermit {
                                                runCatching {
                                                    context.imageLoader.execute(
                                                        ImageRequest.Builder(context)
                                                            .data(url).size(Size(144, 144))
                                                            .memoryCachePolicy(CachePolicy.ENABLED)
                                                            .diskCachePolicy(CachePolicy.ENABLED)
                                                            .build()
                                                    )
                                                }
                                            }
                                        }
                                    }.awaitAll()
                                }
                            }
                            posterCollector.cancel()
                        }
                    }
                } finally {
                    // Stop each discovery lane first, retain every finished
                    // callback until the complete 5-second poster snapshot is
                    // constructed, THEN free its queue buffers.
                    // Only the sealed snapshot reaches UI; commit once after
                    // the five-second global deadline OR sooner at fifty valid videos.
                    if (isActive && activeCriteria == criteria) {
                        // A Cover exit cancels the burst; never seal a cancelled session.
                        // Freeze only after the whole burst; expose all prepared
                        // cards together, not one automatic page every 10 items.
                        // Preserve all score/date evidence in results.
                        // LAB08 two-phase publisher: collect every completed
                        // source callback, even if a slower sibling timed out.
                        // Source runners stop NOW, not after video lookups.
                        val bufferedSeeds = mutableListOf<DiscogsVersionSeed>()
                        while (true) {
                            val batch = supplementalSeeds.tryReceive().getOrNull() ?: break
                            bufferedSeeds += batch
                        }
                        val documentarySeeds = sourceEvidence.toList()
                            .map { item ->
                                DiscogsVersionSource.externalSeed(
                                    candidate = item,
                                    targetTitle = criteria.title,
                                    originalArtist = resolvedOriginalArtist,
                                )
                            }
                        if (bufferedSeeds.isNotEmpty() || documentarySeeds.isNotEmpty()) {
                            results = mergePage(results, bufferedSeeds + documentarySeeds, replace = false)
                            session.results = results
                            rebuildStableOrder()
                        }
                        // LAB09: prefer the user's 20/20 -> 1/20 ranking.
                        // Poster publication NEVER depends on a linked video.
                        // A real candidate with no artwork keeps its row as an
                        // explicit image-unavailable state rather than vanishing.
                        val finalRanked = turboRankedPool()
                            .filter(::modeAcceptsSeed)
                        if (publishedCoverSnapshots.isEmpty() && publishedOriginalSnapshots.isEmpty()) {
                            // Low-yield search: publish only real found candidates.
                            val completeBlock = finalRanked.take(TURBO_RESULT_QUOTA)
                            publishedOriginalSnapshots = completeBlock.filter(::isDistinctOriginalVersion)
                            session.publishedOriginalSnapshots = publishedOriginalSnapshots
                            publishedCoverSnapshots = completeBlock.filterNot(::isDistinctOriginalVersion)
                            session.publishedCoverSnapshots = publishedCoverSnapshots
                        } else {
                            val committedKeys = (publishedOriginalSnapshots + publishedCoverSnapshots)
                                .mapTo(HashSet<String>()) {
                                    DiscogsVersionSource.recordingIdentityKey(it)
                                }
                            // Append, never insert before visible positions 1..25.
                            // The physical row order is frozen even when a late
                            // original-performer source arrives in the second batch.
                            val secondBlock = finalRanked
                                .filterNot {
                                    DiscogsVersionSource.recordingIdentityKey(it) in committedKeys
                                }
                                .take(
                                    (TURBO_RESULT_QUOTA -
                                        publishedOriginalSnapshots.size -
                                        publishedCoverSnapshots.size).coerceAtLeast(0)
                                )
                            publishedCoverSnapshots = publishedCoverSnapshots + secondBlock
                            session.publishedCoverSnapshots = publishedCoverSnapshots
                        }
                        val secondSealedAt = SystemClock.elapsedRealtime()
                        (publishedOriginalSnapshots + publishedCoverSnapshots).forEachIndexed { index, seed ->
                            session.turboPosterPublishedAtMs.putIfAbsent(seed.fingerprint, secondSealedAt)
                            session.turboSlotKeys.putIfAbsent(seed.fingerprint, "slot-$index")
                        }
                        publishedReadyLimit = publishedOriginalSnapshots.size + publishedCoverSnapshots.size
                        session.publishedReadyLimit = publishedReadyLimit
                        // LAB13: discard the unselected documentary pool at the
                        // end of the burst. Only the 50 immutable cards remain
                        // in the screen session; no hidden search rankings should
                        // be recomputed during subsequent music playback.
                        val frozenIds = (publishedOriginalSnapshots + publishedCoverSnapshots)
                            .mapTo(HashSet<String>()) { it.fingerprint }
                        // LAB15: retain ranked reserves after 25+25 publication.
                        // LAB14: preserve ranked, off-screen documentary candidates
                        // as a bounded in-memory reserve for video-safe replacements.
                        // Not a new database. Never expose reserve cards to UI.
                        session.turboReserve = turboRankedPool()
                            .filterNot { it.fingerprint in frozenIds }
                            .take(TURBO_RESERVE_MAX)
                        val frozenResults = results.filter { it.fingerprint in frozenIds }
                        results = frozenResults
                        session.results = frozenResults
                        session.stableOrder = (publishedOriginalSnapshots + publishedCoverSnapshots)
                            .map { it.fingerprint }
                        visibleLimit = TURBO_RESULT_QUOTA
                        session.visibleLimit = TURBO_RESULT_QUOTA
                        resultPageIndex = 0
                        session.resultPageIndex = 0
                        rankingFrozen = true
                        session.rankingFrozen = true
                        originalSectionFrozen = true
                        session.originalSectionFrozen = true
                        session.sourceDiscoveryComplete = true
                        // LAB11: end of the sole ten-second Turbo execution.
                        session.turboFinished = true
                        // An empty snapshot is a transient result, never an
                        // immutable cache hit: another tap may retry the providers.
                        // Separate results of each consulted source: no longer
                        // report Discogs as if it were the only provider.
                        val externalNames = listOf(
                            "COVER.INFO", "MusicBrainz", "Apple/iTunes",
                            "Spotify", "LRCLIB", "AI Scout",
                        )
                        val completed = externalNames.map { name ->
                            sourceStatus[name] ?: CoverSourceDiagnostic(
                                name = name, available = false, found = 0,
                                note = if (name == "AI Scout") {
                                    "Turbo: Cloud editoriale in sola lettura"
                                } else {
                                    "tempo scaduto o servizio non disponibile"
                                },
                            )
                        }
                        val archiveCount = cloudArchiveCount.get()
                        val memoryCount = cloudMemoryCount.get()
                        val cloudDiagnostic = listOf(
                            CoverSourceDiagnostic(
                                "Archivio Cloud", archiveCount >= 0,
                                archiveCount.coerceAtLeast(0),
                                if (archiveCount >= 0) "ricerca completata" else "timeout / non configurato",
                            ),
                            CoverSourceDiagnostic(
                                "Memoria Cloud", memoryCount >= 0,
                                memoryCount.coerceAtLeast(0),
                                if (memoryCount >= 0) "ricerca completata" else "timeout / non configurato",
                            ),
                        )
                        sourceDiagnostics = sourceDiagnostics
                            .filter { it.name == "Discogs" } + cloudDiagnostic + completed
                        session.sourceDiagnostics = sourceDiagnostics
                        sourceDiscoveryLoading = false
                        loading = false
                        verificationJob?.cancel()
                        paginationJob?.cancel()
                        videoPreloadJob?.cancel()
                        backgroundResumeJob?.cancel()
                        CloudMusicDiscovery.cancelArchiveRequests()
                        // Full shutdown is separate from remaining on the Cover
                        // screen.  Audio reverts to its ordinary governor state.
                        playerConnection?.service?.setCoverPerformanceLoad(
                            active = false, heavy = false,
                        )
                        // LAB14: the poster producer stops at 10 seconds.
                        // A separate, lifecycle-owned VIDEO resolver continues
                        // against only the already sealed 50 positions.
                        // LAB21: video ownership remains with the per-visible 5+5+5 coordinator.
                    }
                    streamingCandidates.cancel()
                    supplementalSeeds.cancel()
                }

                // LAB09: NO second automatic publication or video-only prune.
                // All poster rows stay available for explicit tap-to-search.

            }
        } else {
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
            // Turbo reads the shared public archive by WORK TITLE, even when
            // original artist is not yet known. This is a read-only lookup.
            val turboArchiveDeferred = if (isTurbo && mode == DiscogsDirectMode.COVER) {
                async(Dispatchers.IO) {
                    foreignScoutConfig?.let { config ->
                        withTimeoutOrNull(TURBO_ARCHIVE_TIMEOUT_MS) {
                            CloudMusicDiscovery.searchArchive(
                                query = criteria.title,
                                config = config,
                                limit = 100,
                            )
                        }
                    }.orEmpty()
                }
            } else null
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

            val turboArchive = turboArchiveDeferred?.await().orEmpty()
            if (turboArchive.isNotEmpty() && activeCriteria == criteria) {
                val archiveSeeds = turboArchive.mapNotNull { item ->
                    val anchor = item.workTitle.ifBlank { item.title }
                    if (!TitleMeaningResolver.matchesBaseTitle(criteria.title, anchor) &&
                        !TitleMeaningResolver.matchesBaseTitle(criteria.title, item.title)) {
                        return@mapNotNull null
                    }
                    val category = when (item.category.lowercase()) {
                        "live", "dal vivo" -> AiCoverCategory.LIVE
                        "remix", "mix", "rework" -> AiCoverCategory.REMIX
                        "straniera", "foreign", "adattamento" -> AiCoverCategory.FOREIGN
                        else -> AiCoverCategory.COVER
                    }
                    val candidate = CoverSourceCandidate(
                        title = item.title,
                        artist = item.artist,
                        sources = listOf("Archivio Cloud"),
                        year = item.year,
                        album = item.album,
                        coverUrl = item.coverUrl,
                        language = item.language,
                        category = category,
                        playbackVideoId = item.playbackVideoId,
                        playbackVideoTitle = item.playbackVideoTitle,
                        playbackVideoSource = item.playbackVideoSource,
                        workRelationConfirmed = (item.sameWorkScore ?: 0) >= 70,
                        evidenceScore = (
                            ((item.sameWorkScore ?: 30) + (item.versionTypeScore ?: 30)) / 10
                        ).coerceIn(1, 20),
                    )
                    DiscogsVersionSource.externalSeed(
                        candidate = candidate,
                        targetTitle = criteria.title,
                        originalArtist = resolvedOriginalArtist,
                    )
                }
                session.turboArchiveMatches = archiveSeeds.size
                if (archiveSeeds.isNotEmpty()) {
                    results = mergePage(results, archiveSeeds, replace = false)
                    session.results = results
                    rebuildStableOrder()
                }
            }
            val cachedMemory = cachedMemoryDeferred.await()
            val memoryState =
                if (isTurbo) {
                    cachedMemory ?: withTimeoutOrNull(1_600L) { memoryDeferred.await() }
                } else {
                    cachedMemory ?: memoryDeferred.await()
                }

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

            // Turbo is a bounded snapshot, not a never-ending crawl. The
            // normal family retains its complete source waiting semantics.
            val firstPageLoaded =
                if (isTurbo) withTimeoutOrNull(4_500L) { firstPageDeferred.await() } ?: false
                else firstPageDeferred.await()

            var external =
                (if (isTurbo) {
                    withTimeoutOrNull(5_000L) {
                        runCatching { externalDeferred.await() }.getOrNull()
                    }
                } else {
                    runCatching { externalDeferred.await() }.getOrNull()
                }) ?: CoverSourceOutcome(emptyList(), emptyList())

            // A timed-out provider must not remain as a hidden background job.
            if (isTurbo) {
                if (!firstPageDeferred.isCompleted) firstPageDeferred.cancel()
                if (!externalDeferred.isCompleted) externalDeferred.cancel()
                if (!memoryDeferred.isCompleted) memoryDeferred.cancel()
            }
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

            // Starting from a cover may supply only the covering singer.
            // Reuse documentary discovery with no presumed original performer,
            // even if the plain work title is already identical.
            val turboOriginalUncertain =
                isTurbo && mode == DiscogsDirectMode.COVER &&
                    explicitArtistHint.isNullOrBlank() && !hasExplicitOriginalEvidence &&
                    memoryState?.discovery?.original?.artist.isNullOrBlank()
            if ((needsAnchorFallback && !isTurbo) || turboOriginalUncertain) {
                val fallback = try {
                    if (isTurbo) {
                        withTimeoutOrNull(1_500L) {
                            CoverDiscoverySources.discover(
                                title = workAnchor,
                                originalArtist = "",
                                mode = mode,
                                aiConfig = foreignScoutConfig,
                                maxNetworkFanOut = 1,
                            )
                        }
                    } else {
                        CoverDiscoverySources.discover(
                            title = workAnchor,
                            originalArtist = resolvedOriginalArtist,
                            mode = mode,
                            aiConfig = foreignScoutConfig,
                            maxNetworkFanOut = if (playbackIsNormallyPlaying()) 1 else 2,
                        )
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                }
                external = mergeDiscoveryOutcomes(
                    external, fallback ?: CoverSourceOutcome(emptyList(), emptyList()),
                )
                if (mode == DiscogsDirectMode.COVER && explicitArtistHint.isNullOrBlank()) {
                    consensusOriginalArtist(external.candidates)
                        ?.let { resolvedOriginalArtist = it }
                }
                if (!isTurbo && totalDiscogsResults == 0) {
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
                    rankPagesFetched < (if (isTurbo) 2 else DIRECT_COVER_RANK_MAX_SOURCE_PAGES) &&
                    results.size < (if (isTurbo) TURBO_MAX_RANK_CANDIDATES else DIRECT_COVER_RANK_TARGET) &&
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
                val detailSeeds = if (!isTurbo && discogsToken.isNotBlank()) {
                    results.filter { seed ->
                        seed.releaseId > 0 && !seed.discogsVerificationChecked
                    }.take(DIRECT_COVER_INITIAL_DETAIL_BUDGET)
                } else {
                    emptyList()
                }
                val (discogsCredits, verifiedRankSeeds) = coroutineScope {
                    val creditsDeferred = async(Dispatchers.IO) {
                        if (!isTurbo && discogsToken.isNotBlank() && resolvedOriginalArtist.isNotBlank()) {
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
        }
        searchJob?.invokeOnCompletion {
            scope.launch {
                loading = false
                sourceDiscoveryLoading = false
            }
        }
    }

    fun loadNextPage() {
        // All certified Turbo cards already share a single LazyColumn scroll.
        if (isTurbo && mode == DiscogsDirectMode.COVER) return
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

    // LAB22: The manual cover-swap action only appears after the original
    // viewport clock closes at 15 seconds. Reading the state tick ensures
    // Compose redraws the label when the automatic search ends.
    fun manualSwapReady(seed: DiscogsVersionSeed): Boolean {
        val version = turboAutoClosedTick
        return version >= 0 && seed.fingerprint in session.turboAutoClosedFingerprints &&
            !hasPublishableVideo(seed) &&
            session.turboFirstVisibleAtMs[seed.fingerprint]?.let {
                SystemClock.elapsedRealtime() - it >= TURBO_AUTO_TOTAL_MS
            } == true
    }

    // LAB21: manual tap chooses only an already verified alternative bundle.
    fun retryMissingVideo(seed: DiscogsVersionSeed) {
        if (isTurbo && mode == DiscogsDirectMode.COVER) {
            val current = (publishedCoverSnapshots + publishedOriginalSnapshots)
                .firstOrNull { it.fingerprint == seed.fingerprint } ?: return
            if (hasPublishableVideo(current)) return // Card tap already goes to playback.
            val firstSeen = session.turboFirstVisibleAtMs[current.fingerprint]
            if (firstSeen == null ||
                SystemClock.elapsedRealtime() - firstSeen < TURBO_AUTO_TOTAL_MS ||
                current.fingerprint !in session.turboAutoClosedFingerprints) {
                Toast.makeText(context, "Ricerca automatica 5+5+5 ancora attiva.", Toast.LENGTH_SHORT).show()
                return
            }
            if (manualVideoSearchJob?.isActive == true) {
                Toast.makeText(context, "Sto già cercando una nuova cover…", Toast.LENGTH_SHORT).show()
                return
            }

            // LAB22: manual action is a fresh, explicit user operation.
            // Verified memory first, then a bounded metadata lookup; if no
            // ready substitute exists, discover new cover recordings online.
            // Never search for another ID of the SAME failed performer.
            manualVideoSearchJob = scope.launch {
                resolvingFingerprint = current.fingerprint
                try {
                    // LAB23: the entire explicit retry, including cache,
                    // provider lookup and verification, has one hard 5s clock.
                    val finished = withTimeoutOrNull(5_000L) {
                    val anchor = activeCriteria?.title.orEmpty()
                    val chosenSide = isDistinctOriginalVersion(current)
                    val occupied = (publishedCoverSnapshots + publishedOriginalSnapshots)
                        .mapTo(HashSet<String>()) {
                            DiscogsVersionSource.recordingIdentityKey(it)
                        }
                    val usedIds = (publishedCoverSnapshots + publishedOriginalSnapshots)
                        .mapNotNullTo(HashSet<String>()) {
                            it.resolvedVideoId?.trim()?.takeIf(String::isNotBlank)
                        }
                    val tried = session.turboManualTriedSpareIds
                        .getOrPut(current.fingerprint) { linkedSetOf() }
                    fun validAlternative(candidate: DiscogsVersionSeed): Boolean =
                        candidate.fingerprint != current.fingerprint &&
                        candidate.fingerprint !in tried &&
                        !isRejected(candidate) &&
                        modeAcceptsSeed(candidate) &&
                        DiscogsVersionSource.recordingIdentityKey(candidate) !in occupied &&
                        isDistinctOriginalVersion(candidate) == chosenSide &&
                        playableTrack(candidate) != null &&
                        TitleMeaningResolver.matchesBaseTitle(anchor, candidate.trackTitle)

                    suspend fun adoptFrom(candidates: List<DiscogsVersionSeed>): Boolean {
                        val ranked = withContext(Dispatchers.Default) {
                            candidates.distinctBy(DiscogsVersionSource::recordingIdentityKey)
                                .filter(::validAlternative)
                                .sortedWith(
                                    compareByDescending<DiscogsVersionSeed> {
                                        hasPublishableVideo(it)
                                    }.thenByDescending { !it.resolvedVideoId.isNullOrBlank() }
                                        .thenByDescending { it.confidenceScore }
                                ).take(12)
                        }
                        for (candidate in ranked) {
                            if (!isActive) return false
                            tried.add(candidate.fingerprint)
                            val cachedId = CoverPlaybackMemory.verifiedVideo(
                                context, candidate.fingerprint,
                            ) ?: CoverPlaybackMemory.metadataVerifiedVideo(
                                context, candidate.fingerprint,
                            )
                            val fromCache = if (hasPublishableVideo(candidate)) candidate
                            else cachedId?.takeIf { it !in usedIds }?.let { id ->
                                DiscogsVersionSource.markVideoResolved(
                                    candidate, id,
                                    candidate.resolvedVideoTitle ?: candidate.trackTitle,
                                    "MusicLab LAB22 verified cache",
                                )
                            }
                            val verified = fromCache ?: withTimeoutOrNull(2_800L) {
                                lab21VerifyAlternative(candidate)
                            }
                            if (!isActive || verified == null || !hasPublishableVideo(verified))
                                continue
                            val videoId = verified.resolvedVideoId?.trim().orEmpty()
                            if (videoId.isBlank() || videoId in usedIds) continue
                            val thumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                            // Use the replacement video's own thumbnail, not
                            // a stale editorial cover of the discarded recording.
                            if (thumbnail == current.coverUrl) continue
                            val winner = verified.copy(
                                coverUrl = thumbnail,
                                sourceNames = (verified.sourceNames +
                                    "MusicLab LAB22 video poster").distinct(),
                            )
                            val latest = (publishedCoverSnapshots + publishedOriginalSnapshots)
                                .firstOrNull { it.fingerprint == current.fingerprint }
                                ?: return false
                            if (hasPublishableVideo(latest)) return false
                            // One atomic slot replacement: artist, video ID,
                            // track title, thumbnail and confidence stay together.
                            val change: (DiscogsVersionSeed) -> DiscogsVersionSeed = {
                                if (it.fingerprint == current.fingerprint) winner else it
                            }
                            if (publishedOriginalSnapshots.any {
                                    it.fingerprint == current.fingerprint
                                }) {
                                publishedOriginalSnapshots = publishedOriginalSnapshots.map(change)
                                session.publishedOriginalSnapshots = publishedOriginalSnapshots
                            } else {
                                publishedCoverSnapshots = publishedCoverSnapshots.map(change)
                                session.publishedCoverSnapshots = publishedCoverSnapshots
                            }
                            results = results.map(change)
                            session.results = results
                            session.stableOrder = session.stableOrder.map {
                                if (it == current.fingerprint) winner.fingerprint else it
                            }
                            session.turboReserve = session.turboReserve.filterNot {
                                it.fingerprint == winner.fingerprint
                            }
                            session.turboSlotKeys[winner.fingerprint] =
                                session.turboSlotKeys[current.fingerprint] ?: current.fingerprint
                            session.turboFirstVisibleAtMs[winner.fingerprint] = firstSeen
                            session.turboAutoClosedFingerprints.add(winner.fingerprint)
                            turboAutoClosedTick++
                            Toast.makeText(
                                context, "Cover e video sostituiti. Tocca per ascoltare.",
                                Toast.LENGTH_SHORT,
                            ).show()
                            return true
                        }
                        return false
                    }

                    // Phase M1: cached/preloaded verified alternatives: fastest path.
                    if (adoptFrom(session.turboReserve + turboRankedPool())) return@withTimeoutOrNull
                    // Phase M2: still use EXISTING discovered recordings whose
                    // video wasn't verified in the automatic fifteen seconds.
                    if (adoptFrom(results)) return@withTimeoutOrNull
                    // Phase M3: user explicitly requested network fallback.
                    // No background loop; each tap owns one bounded discovery.
                    val earlyCandidates = java.util.Collections.synchronizedList(
                        mutableListOf<CoverSourceCandidate>(),
                    )
                    val discovered = withTimeoutOrNull(2_500L) {
                        withContext(Dispatchers.IO) {
                            CoverDiscoverySources.discover(
                                title = anchor,
                                originalArtist = resolvedOriginalArtist,
                                mode = DiscogsDirectMode.COVER,
                                aiConfig = foreignScoutConfig,
                                maxNetworkFanOut = if (playbackIsNormallyPlaying()) 1 else 2,
                                // An explicit new tap MUST try fresh provider
                                // discovery, even if the last query was cached.
                                forceRefresh = true,
                                onEarlyVideoCandidates = { quick ->
                                    earlyCandidates.addAll(quick)
                                },
                            ).candidates
                        }
                    }.orEmpty() + synchronized(earlyCandidates) {
                        earlyCandidates.toList()
                    }
                    if (!isActive) return@withTimeoutOrNull
                    val fresh = withContext(Dispatchers.Default) {
                        discovered
                            .filter {
                                TitleMeaningResolver.matchesBaseTitle(anchor, it.title)
                            }
                            .map {
                                DiscogsVersionSource.externalSeed(
                                    candidate = it,
                                    targetTitle = anchor,
                                    originalArtist = resolvedOriginalArtist,
                                )
                            }
                    }
                    if (fresh.isNotEmpty()) {
                        session.turboReserve = (session.turboReserve + fresh)
                            .distinctBy(DiscogsVersionSource::recordingIdentityKey)
                            .take(TURBO_RESERVE_MAX)
                    }
                    if (!adoptFrom(fresh)) {
                        Toast.makeText(
                            context, "Nessuna nuova cover verificata. Tocca di nuovo per riprovare.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    } // hard 5s attempt
                    if (finished == null) {
                        Toast.makeText(context, "5 secondi terminati. Tocca per un nuovo tentativo.", Toast.LENGTH_SHORT).show()
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    Toast.makeText(context, "Ricerca non riuscita. Tocca di nuovo per riprovare.", Toast.LENGTH_SHORT).show()
                } finally {
                    if (resolvingFingerprint == current.fingerprint) resolvingFingerprint = null
                }
            }
            return
        }
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
        // LAB08 invariant: showing artwork at T+5 does not certify playback.
        // Never send a bare catalog ID to the native player while the
        // simultaneous metadata-verification race is still in flight.
        if (isTurbo && mode == DiscogsDirectMode.COVER) {
            val latest = results.firstOrNull { it.fingerprint == seed.fingerprint } ?: seed
            if (!hasPublishableVideo(latest)) {
                retryMissingVideo(seed)
                return
            }
        }
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
                (if (isTurbo && mode == DiscogsDirectMode.COVER) {
                    publishedCoverSnapshots.firstOrNull { it.fingerprint == seed.fingerprint }
                } else null)
                    ?: results.firstOrNull { it.fingerprint == seed.fingerprint }
                    ?: seed

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
                    resolveVideoChunk(listOf(currentSeed), playbackPriority = true)
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
            var ranked = (if (isTurbo) publishedCoverSnapshots else orderedResults(results))
                .filterNot(::isOriginalPerformerVersion)
                .filterNot(::isHiddenForCurrentCover)
                .filter(::hasPublishableVideo)
            var position = ranked.indexOfFirst { it.fingerprint == selectedFingerprint }
            if (position < 0) return@launch
            var next = ranked.getOrNull(position + direction)
            if (!isTurbo && next == null && direction > 0 && currentPage > 0 && currentPage < totalPages) {
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
        if (isTurbo && mode == DiscogsDirectMode.COVER) return@LaunchedEffect
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
        if (isTurbo && mode == DiscogsDirectMode.COVER) return@LaunchedEffect
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
    // LAB19: expensive per-card video scoring is memoized per published
    // snapshot, not recalculated for every player tick / scroll frame.
    // Video verification still updates the touched snapshot immutably.
    val visibleOriginalVersions = remember(
        mode, isTurbo, publishedOriginalSnapshots, turboStreamProofVersion,
        if (isTurbo) null else results,
    ) {
        if (mode == DiscogsDirectMode.COVER) publishedOriginalSnapshots
            .filterNot(::isHiddenForCurrentCover)
            .map(::videoCalibratedDisplaySeed)
        else emptyList()
    }
    val visibleTrueCovers = remember(
        mode, isTurbo, loading, publishedCoverSnapshots, visibleLimit,
        turboStreamProofVersion, if (isTurbo) null else results,
    ) {
        if (mode == DiscogsDirectMode.COVER && (!isTurbo || !loading)) {
            publishedCoverSnapshots
                .filterNot(::isHiddenForCurrentCover)
                .take(visibleLimit)
                .map(::videoCalibratedDisplaySeed)
        } else emptyList()
    }
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
        if (!session.initialized && initialTitle.isNotBlank()) {
            runSearch()
        } else if (isTurbo && mode == DiscogsDirectMode.COVER &&
            session.turboFinished && initialTitle.isNotBlank() &&
            (session.publishedCoverSnapshots + session.publishedOriginalSnapshots).any {
                !hasPublishableVideo(it)
            }) {
            // LAB25: a cold/reopened archive search with missing links is
            // incomplete. Revalidate at most once per 10 minutes per work.
            // Verified bindings, ranking decisions and Cloud data are not deleted.
            val now = SystemClock.elapsedRealtime()
            if (session.turboLastAutoRepairAtMs == 0L ||
                now - session.turboLastAutoRepairAtMs >= 600_000L) {
                session.turboLastAutoRepairAtMs = now
                runSearch()
            }
        }
    }

    // Fully verified sessions remain cache-only; incomplete sessions can repair.

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
                            if (isTurbo && mode == DiscogsDirectMode.COVER) {
                                // LAB03: local category changes must never drop
                                // the already prepared 50 cards or wake providers.
                                scope.launch { listState.scrollToItem(0) }
                            } else {
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
                            }
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
                                    if (isTurbo) {
                                        "Locandine: ${visibleTrueCovers.size + visibleOriginalVersions.size}/50 · " +
                                            "Video verificati: ${(visibleTrueCovers + visibleOriginalVersions).count(::hasPublishableVideo)} · " +
                                            if (loading) "Raccolta locandine · video 5+5+5 s per scheda visibile"
                                            else "Tocca una locandina senza video per cercarlo"
                                    } else {
                                        "Risultati certificati: ${publishPool.size} · " +
                                            "Cover in pagina: ${visibleTrueCovers.size}/$visibleLimit" +
                                            " · Video identificati: ${visibleTrueCovers.count(::hasVideoPreview)}"
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (!isTurbo && visibleTrueCovers.any { !hasVideoPreview(it) }) {
                                Text(
                                    "Video da associare: ${visibleTrueCovers.count { !hasVideoPreview(it) }} · ricerca senza precaricare audio",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!isTurbo && nextBlockPool.isNotEmpty()) {
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
                        if (isTurbo) {
                            when {
                                session.turboFinished ->
                                    "Nessuna locandina confermata. Riprova o cambia titolo."
                                else ->
                                    "Carico prima 25 locandine (obiettivo 5 secondi), poi altre 25 entro 10 secondi…"
                            }
                        } else if (!rankingFrozen) {
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
                        key = { seed -> if (isTurbo) "discogs_direct_original_${session.turboSlotKeys[seed.fingerprint] ?: seed.fingerprint}" else "discogs_direct_original_${seed.fingerprint}" },
                    ) { seed ->
                        DiscogsVersionCard(
                            seed = seed,
                            showVideoPreview = true,
                            videoReadyVerified = hasPublishableVideo(seed),
                            audioReadyVerified = hasTurboStreamReady(seed),
                            automaticVideoManaged = isTurbo,
                            manualSwitchAvailable = isTurbo && manualSwapReady(seed),
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
                        key = { seed -> if (isTurbo) "discogs_direct_cover_${session.turboSlotKeys[seed.fingerprint] ?: seed.fingerprint}" else "discogs_direct_cover_${seed.fingerprint}" },
                    ) { seed ->
                        DiscogsVersionCard(
                            seed = seed,
                            showVideoPreview = true,
                            videoReadyVerified = hasPublishableVideo(seed),
                            audioReadyVerified = hasTurboStreamReady(seed),
                            automaticVideoManaged = isTurbo,
                            manualSwitchAvailable = isTurbo && manualSwapReady(seed),
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
                !isTurbo &&
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
    videoReadyVerified: Boolean = false,
    audioReadyVerified: Boolean = false,
    automaticVideoManaged: Boolean = false,
    manualSwitchAvailable: Boolean = false,
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
    // LAB18: use a small, deterministic fallback chain only when an image
    // fails. Never fetch more artwork than the one visible card needs.
    // Editorial image stays first; video thumbnails do not overwrite its cache.
    val posterFallbacks = remember(stableArtworkUrl, seed.coverUrl, seed.resolvedVideoId) {
        buildList {
            stableArtworkUrl?.takeIf { it.startsWith("https://", ignoreCase = true) }?.let(::add)
            seed.coverUrl?.takeIf { it.startsWith("https://", ignoreCase = true) }?.let(::add)
            seed.resolvedVideoId?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                ?.let { id ->
                    add("https://i.ytimg.com/vi/$id/hqdefault.jpg")
                    add("https://i.ytimg.com/vi/$id/mqdefault.jpg")
                }
        }.distinct()
    }
    var posterIndex by remember(seed.fingerprint, posterFallbacks) {
        androidx.compose.runtime.mutableIntStateOf(0)
    }
    var posterUnavailable by remember(seed.fingerprint, posterFallbacks) {
        mutableStateOf(false)
    }
    val currentPosterUrl = posterFallbacks.getOrNull(posterIndex)
    val artworkRequest = remember(imageContext, currentPosterUrl, imagePixels) {
        ImageRequest.Builder(imageContext)
            .data(currentPosterUrl)
            .size(Size(imagePixels, imagePixels))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }
    val onArtworkError: (coil3.compose.AsyncImagePainter.State.Error) -> Unit = {
        if (posterIndex < posterFallbacks.lastIndex) posterIndex++
        else posterUnavailable = true
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
            if (currentPosterUrl.isNullOrBlank() || posterUnavailable) {
                // LAB10: missing editorial image is explicit, not a YouTube thumbnail.
                Surface(
                    modifier = Modifier.size(if (showVideoPreview) 144.dp else 76.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        Text("♫", style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (showVideoPreview) {
                // LAB10: one independently selected and permanently stable poster.
                AsyncImage(
                    model = artworkRequest,
                    onError = onArtworkError,
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
                    onError = onArtworkError,
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            when {
                                audioReadyVerified -> "Flusso audio trovato"
                                videoReadyVerified -> "Video trovato · audio non confermato"
                                automaticVideoManaged && manualSwitchAvailable ->
                                    "Video assente · Tocca per cambiare cover"
                                automaticVideoManaged -> "Ricerca automatica 5+5+5…"
                                seed.videoResolutionChecked -> "Video non trovato · Tocca per cercare"
                                else -> "Ricerca video automatica…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (videoReadyVerified) {
                                Color(0xFF2E7D32)
                            } else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).then(
                                if (!videoReadyVerified &&
                                    (if (automaticVideoManaged) manualSwitchAvailable
                                     else seed.videoResolutionChecked && seed.resolvedVideoId.isNullOrBlank())) {
                                    Modifier.clickable(onClick = onRetryVideo)
                                } else Modifier
                            ),
                        )
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
