package com.metrolist.music.ui.component

import android.widget.Toast
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import androidx.media3.common.Player
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
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
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.CancellationException
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
private const val DIRECT_COVER_PAGE_SIZE = 10
private const val DIRECT_VERSION_SOURCE_FETCH_SIZE = 30
private const val DIRECT_VERSION_PREFETCH_DISTANCE = 2
private const val DIRECT_VERSION_BOTTOM_SAFE_DP = 260
private const val DIRECT_VIDEO_BATCH_SIZE = 5
private const val DIRECT_COVER_RANK_MAX_SOURCE_PAGES = 5
private const val DIRECT_COVER_RANK_TARGET = 120
private const val DIRECT_COVER_PLAYBACK_BATCH_SIZE = 1
private const val DIRECT_VIDEO_PARALLELISM = 3
private const val DIRECT_BACKGROUND_PREFETCH_AHEAD = 20
private const val DIRECT_PREPARED_VIDEO_CACHE_LIMIT = 48
private const val DIRECT_TAPPED_ROW_RESOLVE_TIMEOUT_MS = 2_500L
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
    var visibleLimit: Int = DIRECT_VERSION_PAGE_SIZE,
    var resultPageIndex: Int = 0,
    var sourceDiagnostics: List<CoverSourceDiagnostic> = emptyList(),
    var sourceDiscoveryComplete: Boolean = false,
    var originalYear: Int? = null,
    var rankingFrozen: Boolean = false,
    var publishedReadyLimit: Int = 0,
    var publishedOriginalSnapshots: List<DiscogsVersionSeed> = emptyList(),
    var publishedCoverSnapshots: List<DiscogsVersionSeed> = emptyList(),
    var originalSectionFrozen: Boolean = false,
    val rejectedKeys: MutableSet<String> = linkedSetOf(),
    val approvedKeys: MutableSet<String> = linkedSetOf(),
    val usedVideoIds: MutableSet<String> = linkedSetOf(),
    val knownVideoBindings: MutableMap<String, Triple<String, String, String>> = linkedMapOf(),
    val preparedVideoSongs: MutableMap<String, SongItem> = ConcurrentHashMap(),
    val playReadyVideoIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    val transientVideoRetries: MutableMap<String, Int> = ConcurrentHashMap(),
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
        if (!rankingFrozen && sortMode == DirectVersionSort.RELEVANCE && relevanceChanged) {
            rebuildStableOrder()
        } else {
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
        val connection = playerConnection ?: return@coroutineScope

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
                        val streamProbe =
                            withTimeoutOrNull(6_000L) {
                                try {
                                    connection.service.getStreamUrl(candidate.id)
                                } catch (cancel: CancellationException) {
                                    throw cancel // LAB58: exit Cloud/Cover must cancel active requests.
                                } catch (_: Exception) {
                                    transientTimeout = true
                                    null
                                }
                            }
                        if (streamProbe == null) transientTimeout = true
                        if (streamProbe == null) return null
                        rememberPreparedVideoSong(candidate)
                        session.playReadyVideoIds += candidate.id
                        return candidate to (source ?: "MusicLab")
                    }

                    var verified: Pair<SongItem, String>? = null
                    val existingId = current.resolvedVideoId?.trim().orEmpty()

                    if (existingId.isNotBlank()) {
                        val existingSong =
                            session.preparedVideoSongs[existingId]
                                ?: withTimeoutOrNull(2_200L) {
                                    YouTube.queue(videoIds = listOf(existingId)).getOrNull()?.firstOrNull()
                                }
                        verified = verifyCandidateSong(existingSong, current.resolvedVideoSource)
                        if (verified == null) session.playReadyVideoIds.remove(existingId)
                    }

                    if (verified == null) {
                        val excluded =
                            session.usedVideoIds
                                .filterNot { it == existingId }
                                .toSet()
                        val resolved =
                            withTimeoutOrNull(10_000L) {
                                try {
                                    CompilationTrackResolver.resolveTrack(
                                        track = track,
                                        discogsVideos = current.videos,
                                        fastFirst = true,
                                        excludedVideoIds = excluded,
                                    )
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    transientTimeout = true
                                    null
                                }
                            }
                        if (resolved == null) transientTimeout = true
                        verified = verifyCandidateSong(resolved?.song, resolved?.source)
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
                        persistCloudPlaybackBinding(updated)
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

    fun isPlayReady(seed: DiscogsVersionSeed): Boolean {
        val id = seed.resolvedVideoId?.trim().orEmpty()
        return id.isNotBlank() &&
            id in session.playReadyVideoIds &&
            session.preparedVideoSongs.containsKey(id)
    }

    fun originalCandidatePool(): List<DiscogsVersionSeed> =
        orderedResults(results)
            .filterNot(::isHiddenForCurrentCover)
            .filter(::isOriginalPerformerVersion)
            .filterNot { it.videoResolutionChecked && it.resolvedVideoId.isNullOrBlank() }

    fun coverCandidatePool(): List<DiscogsVersionSeed> =
        orderedResults(results)
            .filterNot(::isHiddenForCurrentCover)
            .filterNot(::isOriginalPerformerVersion)
            .filterNot { it.videoResolutionChecked && it.resolvedVideoId.isNullOrBlank() }

    fun remainingCoverPool(): List<DiscogsVersionSeed> {
        val committed = publishedCoverSnapshots.mapTo(HashSet<String>()) { it.fingerprint }
        return coverCandidatePool().filterNot { it.fingerprint in committed }
    }

    fun visibleCoverPool(): List<DiscogsVersionSeed> =
        publishedCoverSnapshots + remainingCoverPool()
            .take((visibleLimit - publishedCoverSnapshots.size).coerceAtLeast(0))

    fun videoPreparationPool(): List<DiscogsVersionSeed> {
        if (mode != DiscogsDirectMode.COVER) {
            return orderedResults(results).take(visibleLimit.coerceAtLeast(pageSize))
        }
        // LAB58: originals never consume ten-Cover page capacity.
        // Pending covers extend into the NEXT page for ranked replacements.
        val committedOriginals = publishedOriginalSnapshots.mapTo(HashSet<String>()) { it.fingerprint }
        val pendingOriginals = originalCandidatePool()
            .filterNot { it.fingerprint in committedOriginals }
            .take(DIRECT_VIDEO_BATCH_SIZE)
        val target = visibleLimit.coerceAtLeast(pageSize) + pageSize
        val pendingCovers = remainingCoverPool().take(
            (target - publishedCoverSnapshots.size).coerceAtLeast(0),
        )
        // LAB59: cover search owns the first video slots. Originals cannot
        // starve a list containing hundreds of real cover candidates.
        return pendingCovers + pendingOriginals
    }

    fun preparationReadyVideoCount(): Int =
        videoPreparationPool().count(::isPlayReady)

    fun readyVideoCount(): Int =
        if (mode == DiscogsDirectMode.COVER) {
            visibleCoverPool().count(::isPlayReady)
        } else {
            orderedResults(results).count { !it.resolvedVideoId.isNullOrBlank() }
        }

    suspend fun publishReadyBatches() {
        if (mode != DiscogsDirectMode.COVER || !rankingFrozen) return

        // LAB59: originals and covers publish independently.  A single slow
        // original never blocks even the first visible cover video.
        if (!originalSectionFrozen) {
            val originals = originalCandidatePool().filter(::hasVideoPreview)
            publishedOriginalSnapshots = originals.toList()
            session.publishedOriginalSnapshots = publishedOriginalSnapshots
            originalSectionFrozen = true
            session.originalSectionFrozen = true
        }

        while (publishedCoverSnapshots.size < visibleLimit) {
            val requested = minOf(
                DIRECT_VIDEO_BATCH_SIZE,
                visibleLimit - publishedCoverSnapshots.size,
            )
            // LAB59: collect actual video thumbnails from the scored queue.
            // Unknown/unplayable rows never hold the first five hostage.
            val pending = remainingCoverPool()
            val group = pending.filter(::hasVideoPreview).take(requested)
            if (group.isEmpty()) break

            // LAB58: atomic group commit. Already shown cards never reorder.
            publishedCoverSnapshots = publishedCoverSnapshots + group
            session.publishedCoverSnapshots = publishedCoverSnapshots
            publishedReadyLimit = publishedCoverSnapshots.size
            session.publishedReadyLimit = publishedReadyLimit
            delay(80)
        }
    }

    suspend fun resolveNextVideoBatch(limit: Int = DIRECT_VIDEO_BATCH_SIZE) {
        // Do not let malformed/discographically incomplete seeds permanently
        // block the 5+5 queue; keep their ranking evidence for later inspection.
        videoPreparationPool()
            .filter { !isPlayReady(it) && playableTrack(it) == null }
            .take(limit)
            .forEach { replaceSeed(DiscogsVersionSource.markVideoUnavailable(it)) }

        val batch =
            videoPreparationPool()
                .filter { seed ->
                    DiscogsVersionSource.isDisplayableDirectSeed(seed) &&
                        playableTrack(seed) != null &&
                        !isPlayReady(seed)
                }
                .take(limit)
        if (batch.isEmpty()) {
            publishReadyBatches()
            return
        }
        batch.chunked(DIRECT_VIDEO_PARALLELISM).forEach { chunk ->
            resolveVideoChunk(chunk)
        }
        publishReadyBatches()
    }

    suspend fun warmResolvedVideoMetadata(limit: Int = 12) {
        val ids =
            orderedResults(results)
                .take(visibleLimit.coerceAtLeast(pageSize))
                .mapNotNull { it.resolvedVideoId }
                .distinct()
                .filterNot { session.preparedVideoSongs.containsKey(it) }
                .take(limit)
        if (ids.isEmpty()) return

        ids.chunked(12).forEach { chunk ->
            val songs =
                withTimeoutOrNull(4_500L) {
                    YouTube.queue(videoIds = chunk).getOrNull().orEmpty()
                }.orEmpty()
            songs.forEach(::rememberPreparedVideoSong)
        }
    }

    fun playbackIsNormallyPlaying(): Boolean =
        playerConnection?.isEffectivelyPlaying?.value == true

    fun playbackIsCritical(): Boolean =
        playerConnection?.isPlaybackPriorityBurstActive() == true ||
            playerConnection?.playbackState?.value == Player.STATE_BUFFERING

    fun backgroundWorkBlocked(): Boolean = backgroundPausedForPlayback || playbackIsCritical()

    fun scheduleVideoPreload() {
        if (videoPreloadJob?.isActive == true) return
        if (playerConnection == null) return
        // LAB59: no background streaming probes compete with music playback.
        if (playbackIsNormallyPlaying()) return
        if (backgroundWorkBlocked()) return
        if (mode == DiscogsDirectMode.COVER && !rankingFrozen) return

        videoPreloadJob =
            scope.launch {
                if (mode == DiscogsDirectMode.COVER) {
                    playerConnection?.service?.setCoverPerformanceLoad(active = true, heavy = true)
                }
                try {
                    if (backgroundWorkBlocked()) return@launch
                    if (!playbackIsNormallyPlaying()) warmResolvedVideoMetadata()

                    while (true) {
                        if (backgroundWorkBlocked() || playbackIsNormallyPlaying()) break
                        val workWindow = videoPreparationPool()
                        if (workWindow.isEmpty()) break
                        if (workWindow.all(::isPlayReady)) break

                        val readyBefore = preparationReadyVideoCount()
                        resolveNextVideoBatch(
                            limit =
                                if (playbackIsNormallyPlaying()) {
                                    DIRECT_COVER_PLAYBACK_BATCH_SIZE
                                } else {
                                    DIRECT_VIDEO_BATCH_SIZE
                                },
                        )
                        if (!playbackIsNormallyPlaying()) warmResolvedVideoMetadata()
                        val readyAfter = preparationReadyVideoCount()

                        val anyPending =
                            videoPreparationPool().any { seed ->
                                DiscogsVersionSource.isDisplayableDirectSeed(seed) &&
                                    playableTrack(seed) != null &&
                                    !isPlayReady(seed)
                            }
                        if (readyAfter == readyBefore && !anyPending) break
                        delay(if (playbackIsNormallyPlaying()) 260 else 120)
                    }

                    if (!playbackIsNormallyPlaying()) {
                        warmResolvedVideoMetadata(limit = pageSize)
                    }
                    publishReadyBatches()
                } finally {
                    if (mode == DiscogsDirectMode.COVER) {
                        playerConnection?.service?.setCoverPerformanceLoad(active = true, heavy = false)
                    }
                }
            }
    }

    fun scheduleDiscogsVerification() {
        if (verificationJob?.isActive == true) return
        if (backgroundWorkBlocked()) return
        verificationJob =
            scope.launch {
                while (true) {
                    if (backgroundWorkBlocked()) break
                    val pending =
                        videoPreparationPool()
                            .firstOrNull { seed ->
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
                    if (updated.track != null) {
                        scheduleVideoPreload()
                    }
                    delay(if (playbackIsNormallyPlaying()) 300 else 120)
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
                if (!playbackIsNormallyPlaying()) {
                    scheduleDiscogsVerification()
                    scheduleVideoPreload()
                }
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

    suspend fun playResolvedContext(selectedFingerprint: String) {
        val connection = playerConnection ?: return
        var selectedSeed =
            orderedResults(results)
                .firstOrNull { it.fingerprint == selectedFingerprint && !it.resolvedVideoId.isNullOrBlank() }
                ?: return
        var selectedId = selectedSeed.resolvedVideoId?.trim().orEmpty()
        if (selectedId.isBlank()) return

        var selectedSong = session.preparedVideoSongs[selectedId]
        val track = playableTrack(selectedSeed)
        val sourceHint = selectedSeed.resolvedVideoSource.orEmpty().lowercase()
        val alreadyPlayReady =
            selectedId in session.playReadyVideoIds && selectedSong != null
        val needsDirectStreamProbe =
            !alreadyPlayReady &&
                (
                    selectedSong == null ||
                        "archivio" in sourceHint ||
                        "cloud" in sourceHint ||
                        "cover.info" in sourceHint ||
                        "discogs" in sourceHint
                    )

        // LAB55: metadata presence is not enough for inherited/direct bindings.
        // Probe the actual stream before handing a stale Cloud/COVER.INFO/Discogs id
        // to Player; resolver-produced YT Music ids retain the LAB50 fast lane.
        var inheritedStreamPlayable = true
        if (needsDirectStreamProbe) {
            if (selectedSong == null) {
                selectedSong =
                    withTimeoutOrNull(1_800L) {
                        YouTube.queue(videoIds = listOf(selectedId)).getOrNull()?.firstOrNull()
                    }?.takeIf { song ->
                        track == null || CompilationTrackResolver.isHardCompatible(track, song)
                    }?.also(::rememberPreparedVideoSong)
            }
            inheritedStreamPlayable =
                selectedSong != null &&
                    withTimeoutOrNull(3_500L) {
                        connection.service.getStreamUrl(selectedId)
                    } != null
        }

        if (!inheritedStreamPlayable && track != null) {
            session.usedVideoIds += selectedId
            session.knownVideoBindings.remove(DiscogsVersionSource.recordingIdentityKey(selectedSeed))
            val stale =
                DiscogsVersionSource.markVideoUnavailable(selectedSeed).copy(
                    videoResolutionChecked = false,
                )
            replaceSeed(stale)

            val alternate =
                CompilationTrackResolver.resolveTrack(
                    track = track,
                    discogsVideos = selectedSeed.videos,
                    fastFirst = true,
                    excludedVideoIds = session.usedVideoIds.toSet(),
                )
            val alternateSong = alternate?.song
            val alternatePlayable =
                alternateSong != null &&
                    withTimeoutOrNull(3_500L) {
                        connection.service.getStreamUrl(alternateSong.id)
                    } != null

            if (alternateSong != null && alternatePlayable && session.usedVideoIds.add(alternateSong.id)) {
                rememberPreparedVideoSong(alternateSong)
                val refreshed =
                    DiscogsVersionSource.markVideoResolved(
                        seed = results.firstOrNull { it.fingerprint == selectedFingerprint } ?: stale,
                        videoId = alternateSong.id,
                        videoTitle = alternateSong.title,
                        source = alternate.source,
                    )
                replaceSeed(refreshed)
                rememberKnownVideoBinding(refreshed)
                selectedSeed = refreshed
                selectedId = alternateSong.id
                selectedSong = alternateSong
            } else {
                Toast.makeText(
                    context,
                    "Sorgente non riproducibile: cerco automaticamente un altro video.",
                    Toast.LENGTH_SHORT,
                ).show()
                scheduleVideoPreload()
                return
            }
        } else if (selectedSong == null && track != null) {
            selectedSong =
                withTimeoutOrNull(1_800L) {
                    YouTube.queue(videoIds = listOf(selectedId)).getOrNull()?.firstOrNull()
                }?.takeIf { song -> CompilationTrackResolver.isHardCompatible(track, song) }
                    ?.also(::rememberPreparedVideoSong)
        }

        val selectedItem =
            selectedSong?.toMediaItem()
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
                    thumbnailUrl = selectedSeed.coverUrl,
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
                val enriched =
                    DiscogsVersionSource.enrichSeedsForRanking(
                        token = discogsToken,
                        seeds = pageResult.items,
                        targetTitle = TitleMeaningResolver.workAnchorTitle(criteria.title),
                        mode = mode,
                        originalArtist = resolvedOriginalArtist,
                    )
                enriched.map { seed ->
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
        if (mode == DiscogsDirectMode.COVER) {
            playerConnection?.service?.setCoverPerformanceLoad(active = true, heavy = true)
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
        if (!repeatSameWork) {
            session.usedVideoIds.clear()
            session.knownVideoBindings.clear()
            session.preparedVideoSongs.clear()
            session.playReadyVideoIds.clear()
            session.transientVideoRetries.clear()
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
            val memoryDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                foreignScoutConfig?.let { config ->
                    runCatching {
                        CloudMusicDiscovery.discoverMemoryState(
                            title = criteria.title,
                            artist = resolvedOriginalArtist,
                            config = config,
                            mode = if (mode == DiscogsDirectMode.ORIGINAL) "originals" else "cover",
                            limit = 150,
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
                    )
                }

            val memoryState = memoryDeferred.await()

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
                results =
                    mergePage(
                        current = emptyList(),
                        incoming = memoryVersions.map(::memoryCandidateToSeed),
                        replace = true,
                    )
                session.results = results
                // LAB57: keep cloud candidates internal until metadata
                // certification is complete and the rank is frozen.
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
            sourceDiagnostics = listOf(memoryDiagnostic)
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

                val discogsCredits =
                    if (discogsToken.isNotBlank() && resolvedOriginalArtist.isNotBlank()) {
                        withTimeoutOrNull(5_500L) {
                            DiscogsVersionSource.loadOriginalWorkCredits(
                                token = discogsToken,
                                title = workTitle,
                                originalArtist = resolvedOriginalArtist,
                            )
                        }.orEmpty()
                    } else {
                        emptyList()
                    }

                session.originalWorkCredits =
                    (external.workCredits + discogsCredits)
                        .distinctBy { credit ->
                            credit.name.lowercase() + "|" + credit.role.lowercase()
                        }

                val rankingEnriched =
                    if (discogsToken.isNotBlank()) {
                        withTimeoutOrNull(11_000L) {
                            DiscogsVersionSource.enrichSeedsForRanking(
                                token = discogsToken,
                                seeds = results,
                                targetTitle = workTitle,
                                mode = mode,
                                originalArtist = resolvedOriginalArtist,
                            )
                        } ?: results
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
                rebuildStableOrder()

                rankingFrozen = true
                session.rankingFrozen = true
                publishedReadyLimit = 0
                session.publishedReadyLimit = 0
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

            if (firstPageLoaded) {
                scheduleDiscogsVerification()
            }
            scheduleVideoPreload()
        }
        searchJob?.invokeOnCompletion {
            scope.launch {
                loading = false
                sourceDiscoveryLoading = false
            }
        }
    }

    fun loadNextPage() {
        val criteria = activeCriteria ?: return
        if (loading || loadingMore) return

        if (mode == DiscogsDirectMode.COVER) {
            val poolSize = coverCandidatePool().size
            val target = visibleLimit + pageSize
            if (poolSize >= target || currentPage <= 0 || currentPage >= totalPages) {
                if (poolSize <= publishedCoverSnapshots.size) return
                visibleLimit = target
                session.visibleLimit = visibleLimit
                verificationJob?.cancel()
                videoPreloadJob?.cancel()
                scope.launch { publishReadyBatches() }
                scheduleDiscogsVerification()
                scheduleVideoPreload()
                return
            }

            if (backgroundWorkBlocked() || currentPage <= 0 || currentPage >= totalPages) return
            loadingMore = true
            paginationError = null
            paginationJob?.cancel()
            paginationJob =
                scope.launch {
                    playerConnection?.service?.setCoverPerformanceLoad(active = true, heavy = true)
                    try {
                        val loaded = loadPage(criteria, currentPage + 1, replace = false)
                        if (loaded) {
                            visibleLimit = visibleLimit + pageSize
                            session.visibleLimit = visibleLimit
                            verificationJob?.cancel()
                            videoPreloadJob?.cancel()
                            publishReadyBatches()
                            scheduleDiscogsVerification()
                            scheduleVideoPreload()
                        }
                    } finally {
                        loadingMore = false
                        playerConnection?.service?.setCoverPerformanceLoad(active = true, heavy = false)
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
            scheduleDiscogsVerification()
            scheduleVideoPreload()
            return
        }
        if (currentPage <= 0 || currentPage >= totalPages) {
            visibleLimit = target
            session.visibleLimit = visibleLimit
            scheduleDiscogsVerification()
            scheduleVideoPreload()
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
                    scheduleDiscogsVerification()
                    scheduleVideoPreload()
                } finally {
                    loadingMore = false
                }
            }
    }

    fun retryMissingVideo(seed: DiscogsVersionSeed) {
        val track = playableTrack(seed)
        if (track == null) {
            Toast.makeText(context, "Dati versione insufficienti per la ricerca video.", Toast.LENGTH_SHORT).show()
            return
        }

        resolvingFingerprint = seed.fingerprint
        results =
            results.map { current ->
                if (current.fingerprint == seed.fingerprint) {
                    current.copy(videoResolutionChecked = false)
                } else {
                    current
                }
            }
        session.results = results

        scope.launch {
            var foundSong: SongItem? = null
            var foundSource: String? = null
            var providerTrackHint: DiscogsTrack? = null
            val excluded = session.usedVideoIds.toSet()

            suspend fun acceptVideo(
                videoId: String?,
                sourceName: String,
                compatibilityTrack: DiscogsTrack = track,
            ): Boolean {
                val id = videoId?.trim().orEmpty()
                if (id.isBlank() || id in excluded) return false
                val song =
                    session.preparedVideoSongs[id]
                        ?: withTimeoutOrNull(2_500L) {
                            YouTube.queue(videoIds = listOf(id)).getOrNull()?.firstOrNull()
                        }?.also(::rememberPreparedVideoSong)
                        ?: return false
                if (!CompilationTrackResolver.isHardCompatible(compatibilityTrack, song)) return false
                foundSong = song
                foundSource = sourceName
                return true
            }

            // Lane 1: MusicLab cloud/internal memory for this exact musical version.
            foreignScoutConfig?.let { config ->
                val memory =
                    runCatching {
                        CloudMusicDiscovery.discoverMemoryState(
                            title = TitleMeaningResolver.workAnchorTitle(title),
                            artist = resolvedOriginalArtist,
                            config = config,
                            mode = if (mode == DiscogsDirectMode.ORIGINAL) "originals" else "cover",
                            limit = 150,
                        )
                    }.getOrNull()

                for (candidate in memory?.discovery?.versions.orEmpty()) {
                    if (
                        !TitleMeaningResolver.sameArtist(candidate.artist, seed.artist) ||
                        !TitleMeaningResolver.matchesBaseTitle(seed.trackTitle, candidate.title)
                    ) {
                        continue
                    }
                    if (acceptVideo(candidate.playbackVideoId, candidate.playbackVideoSource ?: "Archivio MusicLab")) {
                        break
                    }
                }
            }

            // Lane 2: COVER.INFO / Spotify / iTunes / other music sources can either
            // provide a direct binding or a better exact-version query for the video lane.
            if (foundSong == null) {
                val discovery =
                    runCatching {
                        CoverDiscoverySources.discover(
                            title = seed.trackTitle,
                            originalArtist = resolvedOriginalArtist,
                            mode = mode,
                            aiConfig = foreignScoutConfig,
                        )
                    }.getOrElse { CoverSourceOutcome(emptyList(), emptyList()) }

                val matching =
                    discovery.candidates
                        .filter { candidate ->
                            TitleMeaningResolver.sameArtist(candidate.artist, seed.artist) &&
                                TitleMeaningResolver.matchesBaseTitle(seed.trackTitle, candidate.title)
                        }
                        .sortedWith(
                            compareByDescending<CoverSourceCandidate> { it.evidenceScore }
                                .thenByDescending { it.sources.distinct().size }
                                .thenByDescending { it.durationSeconds != null },
                        )

                for (candidate in matching) {
                    val hintedTrack =
                        DiscogsTrack(
                            position = "",
                            title = candidate.title,
                            artists = listOf(candidate.artist),
                            durationText = null,
                            durationSeconds = candidate.durationSeconds ?: seed.durationSeconds,
                        )
                    if (
                        acceptVideo(
                            candidate.playbackVideoId,
                            candidate.playbackVideoSource ?: candidate.sources.joinToString(" + "),
                            hintedTrack,
                        )
                    ) {
                        break
                    }
                    if (providerTrackHint == null) providerTrackHint = hintedTrack
                }
            }

            // Lane 3: deep YouTube lookup. If Spotify/iTunes/etc. clarified the exact
            // version, use that query first, then fall back to the original row metadata.
            if (foundSong == null) {
                val deepTracks =
                    listOfNotNull(providerTrackHint, track)
                        .distinctBy { candidateTrack ->
                            candidateTrack.title.lowercase() + "|" +
                                candidateTrack.artists.joinToString("|").lowercase()
                        }

                for ((index, deepTrack) in deepTracks.withIndex()) {
                    val deep =
                        CompilationTrackResolver.resolveTrack(
                            track = deepTrack,
                            discogsVideos = seed.videos,
                            fastFirst = false,
                            excludedVideoIds = session.usedVideoIds.toSet(),
                        )
                    if (deep != null) {
                        foundSong = deep.song
                        foundSource =
                            if (index == 0 && providerTrackHint != null) {
                                "Ricerca approfondita da fonti musicali · ${deep.source}"
                            } else {
                                "Ricerca approfondita · ${deep.source}"
                            }
                        break
                    }
                }
            }

            val current = results.firstOrNull { it.fingerprint == seed.fingerprint } ?: seed
            val updated =
                if (foundSong != null && session.usedVideoIds.add(foundSong!!.id)) {
                    rememberPreparedVideoSong(foundSong!!)
                    DiscogsVersionSource.markVideoResolved(
                        seed = current,
                        videoId = foundSong!!.id,
                        videoTitle = foundSong!!.title,
                        source = foundSource ?: "Ricerca MusicLab",
                    )
                } else {
                    DiscogsVersionSource.markVideoUnavailable(current)
                }

            replaceSeed(updated)
            resolvingFingerprint = null
            if (!updated.resolvedVideoId.isNullOrBlank()) {
                persistCloudPlaybackBinding(updated)
                Toast.makeText(context, "Video trovato.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Nessun video compatibile trovato.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun play(seed: DiscogsVersionSeed) {
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

            if (
                currentSeed.resolvedVideoId.isNullOrBlank() &&
                !currentSeed.videoResolutionChecked
            ) {
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

    LaunchedEffect(visibleLimit, sourceDiagnostics, rankingFrozen, publishedReadyLimit) {
        session.visibleLimit = visibleLimit
        session.sourceDiagnostics = sourceDiagnostics
        session.rankingFrozen = rankingFrozen
        session.publishedReadyLimit = publishedReadyLimit
    }

    val orderedPool = orderedResults(results)
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
                .filterNot { it.videoResolutionChecked && it.resolvedVideoId.isNullOrBlank() }
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
    // The published snapshots are immutable, so an unavailable late result or
    // new year/credit cannot reorder any video already seen on screen.
    val visibleOriginalVersions =
        if (mode == DiscogsDirectMode.COVER) {
            val committed = publishedOriginalSnapshots.mapTo(HashSet<String>()) { it.fingerprint }
            sortGroup(
                publishedOriginalSnapshots +
                    navigablePool.filter(::isOriginalPerformerVersion)
                        .filter(::hasVideoPreview).filterNot { it.fingerprint in committed },
                sortMode,
            )
        } else emptyList()
    val visibleTrueCovers =
        if (mode == DiscogsDirectMode.COVER) {
            val committed = publishedCoverSnapshots.mapTo(HashSet<String>()) { it.fingerprint }
            val earlyVideos = publishPool.filter(::hasVideoPreview)
                .filterNot { it.fingerprint in committed }
                .take((visibleLimit - publishedCoverSnapshots.size).coerceAtLeast(0))
            // LAB59: render already-discovered video thumbnails while the
            // remaining stream probes run off the user-visible critical path.
            sortGroup((publishedCoverSnapshots + earlyVideos).take(visibleLimit), sortMode)
        } else emptyList()
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
            publishPool.size < visibleLimit + pageSize &&
            currentPage > 0 &&
            currentPage < totalPages &&
            !loading &&
            !loadingMore &&
            paginationJob?.isActive != true &&
            sourcePrefetchedForVisibleLimit != visibleLimit &&
            !backgroundWorkBlocked()
        ) {
            sourcePrefetchedForVisibleLimit = visibleLimit
            delay(220)
            paginationJob =
                scope.launch {
                    try {
                        loadPage(criteria, currentPage + 1, replace = false)
                    } finally {
                        scheduleDiscogsVerification()
                        scheduleVideoPreload()
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
                            resultPageIndex = 0
                            session.resultPageIndex = 0
                            verificationJob?.cancel()
                            videoPreloadJob?.cancel()
                            scope.launch { listState.scrollToItem(0) }
                            scheduleDiscogsVerification()
                            scheduleVideoPreload()
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
                                        "Pubblicati play-ready: ${visibleResults.size}/${visibleMembershipPool.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (readyPool.size < visibleMembershipPool.size) {
                                Text(
                                    "Blocco corrente 5+5: ${readyPool.size}/${visibleMembershipPool.size} play-ready",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (nextBlockPool.isNotEmpty()) {
                                Text(
                                    "Prossimi ${nextBlockPool.size} già in preparazione: $nextBlockReady/${nextBlockPool.size} play-ready",
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
                            "Preparo il primo blocco di 5 cover già riproducibili…"
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
                visibleTrueCovers.size >= visibleLimit &&
                (
                    publishPool.size > visibleLimit ||
                        (currentPage > 0 && currentPage < totalPages)
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
    showConfidence: Boolean,
    selected: Boolean,
    resolving: Boolean,
    onPlay: () -> Unit,
    onRetryVideo: () -> Unit,
    onDetails: () -> Unit,
) {
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
                // LAB53 Cover rule: the video thumbnail itself is the play target.
                // No separate album artwork and no play icon are layered on top.
                AsyncImage(
                    model = videoId.takeIf(String::isNotBlank)
                        ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
                    contentDescription = seed.resolvedVideoTitle ?: "Video cover",
                    modifier =
                        Modifier
                            .size(144.dp)
                            .clickable(onClick = onPlay),
                    contentScale = ContentScale.Crop,
                )
            } else {
                AsyncImage(
                    model = seed.coverUrl,
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
                            "Cerco l'audio…",
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
