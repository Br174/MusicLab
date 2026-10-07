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
    val rejectedKeys: MutableSet<String> = linkedSetOf(),
    val approvedKeys: MutableSet<String> = linkedSetOf(),
    val usedVideoIds: MutableSet<String> = linkedSetOf(),
    val knownVideoBindings: MutableMap<String, Triple<String, String, String>> = linkedMapOf(),
    val preparedVideoSongs: MutableMap<String, SongItem> = ConcurrentHashMap(),
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
        mutableStateOf(if (mode == DiscogsDirectMode.COVER) minOf(session.visibleLimit, pageSize) else session.visibleLimit)
    }
    var resultPageIndex by remember(sessionKey) { mutableStateOf(session.resultPageIndex) }
    var searchJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var paginationJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var videoPreloadJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var verificationJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var backgroundResumeJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var playbackLaunchJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var backgroundPausedForPlayback by remember(sessionKey) { mutableStateOf(false) }
    var decisionSavingFingerprint by remember(sessionKey) { mutableStateOf<String?>(null) }
    var closeSwipeDistance by remember(sessionKey) { mutableStateOf(0f) }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = session.listIndex,
        initialFirstVisibleItemScrollOffset = session.listOffset,
    )

    DisposableEffect(sessionKey) {
        onDispose {
            // LAB49 lifecycle barrier: leaving Cover/Originali must terminate every
            // job owned by this screen. Underlying Discogs/Cloud/COVER.INFO calls are
            // now coroutine-cancellable too, so this is a real network stop.
            playbackLaunchJob?.cancel()
            searchJob?.cancel()
            paginationJob?.cancel()
            verificationJob?.cancel()
            videoPreloadJob?.cancel()
            backgroundResumeJob?.cancel()
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
        session.category = category
        session.sortMode = sortMode
    }

    fun buildCriteria(): DiscogsVersionSearchCriteria =
        DiscogsVersionSearchCriteria(
            title = TitleMeaningResolver.stripTrailingArtistHint(title),
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
        return CoverSourceOutcome(candidates, diagnostics)
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

        fun rankedSeed(seed: DiscogsVersionSeed): DiscogsVersionSeed {
            val samePerformerAsOriginal =
                mode == DiscogsDirectMode.COVER &&
                    resolvedOriginalArtist.isNotBlank() &&
                    TitleMeaningResolver.sameArtist(seed.artist, resolvedOriginalArtist)
            return if (isRejected(seed) || samePerformerAsOriginal) {
                seed.copy(
                    confidenceScore = 1,
                    confidenceReasons =
                        (
                            seed.confidenceReasons +
                                if (samePerformerAsOriginal) {
                                    "Stesso interprete dell'originale: mantenuta in fondo a 1/20"
                                } else {
                                    "Filtro negativo: mantenuta in graduatoria a 1/20"
                                }
                            ).distinct(),
                )
            } else {
                seed
            }
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
        if (mode == DiscogsDirectMode.COVER) return true
        val workTitle = TitleMeaningResolver.workAnchorTitle(title)
        val originalArtistOk =
            resolvedOriginalArtist.isNotBlank() &&
                TitleMeaningResolver.sameArtist(seed.artist, resolvedOriginalArtist)
        val titleOk =
            workTitle.isBlank() ||
                TitleMeaningResolver.matchesBaseTitle(
                    targetTitle = workTitle,
                    value = seed.trackTitle,
                    artistAliases = setOf(resolvedOriginalArtist).filter(String::isNotBlank).toSet(),
                )
        return originalArtistOk && titleOk
    }

    fun sortedPool(source: List<DiscogsVersionSeed>): List<DiscogsVersionSeed> {
        val displayable =
            source.filter(DiscogsVersionSource::isDisplayableDirectSeed)
                .filter(::modeAcceptsSeed)
        val effectiveSortMode =
            if (mode == DiscogsDirectMode.COVER) DirectVersionSort.RELEVANCE else sortMode
        return when (effectiveSortMode) {
            DirectVersionSort.RELEVANCE ->
                displayable.sortedWith(
                    compareByDescending<DiscogsVersionSeed> { it.confidenceScore }
                        .thenByDescending { it.originalWorkReference }
                        .thenByDescending { it.sourceNames.distinct().size }
                        .thenByDescending { it.track != null }
                        .thenByDescending { !it.resolvedVideoId.isNullOrBlank() }
                        .thenBy { it.artist.lowercase() }
                        .thenBy { it.trackTitle.lowercase() },
                )
            DirectVersionSort.OLDEST ->
                displayable.sortedWith(
                    compareBy<DiscogsVersionSeed> { it.year ?: Int.MAX_VALUE }
                        .thenByDescending { it.confidenceScore },
                )
            DirectVersionSort.NEWEST ->
                displayable.sortedWith(
                    compareByDescending<DiscogsVersionSeed> { it.year ?: Int.MIN_VALUE }
                        .thenByDescending { it.confidenceScore },
                )
        }
    }

    fun rebuildStableOrder() {
        session.stableOrder = sortedPool(results).map { it.fingerprint }
    }

    fun syncStableOrder() {
        val currentIds = results.mapTo(linkedSetOf()) { it.fingerprint }
        val kept = session.stableOrder.filter { it in currentIds }
        val known = kept.toHashSet()
        val appended = results.map { it.fingerprint }.filter { known.add(it) }
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
        if (sortMode == DirectVersionSort.RELEVANCE && relevanceChanged) {
            rebuildStableOrder()
        } else {
            syncStableOrder()
        }
    }

    fun playableTrack(seed: DiscogsVersionSeed): DiscogsTrack? =
        seed.track ?: if (
            seed.releaseId <= 0 &&
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
        val jobs =
            chunk.map { seed ->
                launch {
                    val track = playableTrack(seed)
                    val internal =
                        if (track == null) null else knownMusicLabVideo(seed, track)

                    var resolvedSong = internal?.first
                    var resolvedSource = internal?.second

                    if (resolvedSong == null && track != null) {
                        val external =
                            CompilationTrackResolver.resolveTrack(
                                track = track,
                                discogsVideos = seed.videos,
                                fastFirst = true,
                                excludedVideoIds = session.usedVideoIds.toSet(),
                            )
                        resolvedSong = external?.song
                        resolvedSource = external?.source
                    }

                    val current = results.firstOrNull { it.fingerprint == seed.fingerprint } ?: return@launch
                    val currentTrack = playableTrack(current)
                    if (
                        resolvedSong != null &&
                        resolvedSong!!.id in session.usedVideoIds &&
                        currentTrack != null
                    ) {
                        val retry =
                            CompilationTrackResolver.resolveTrack(
                                track = currentTrack,
                                discogsVideos = current.videos,
                                fastFirst = true,
                                excludedVideoIds = session.usedVideoIds.toSet(),
                            )
                        resolvedSong = retry?.song
                        resolvedSource = retry?.source
                    }

                    resolvedSong?.let(::rememberPreparedVideoSong)

                    val updated =
                        if (resolvedSong == null || !session.usedVideoIds.add(resolvedSong!!.id)) {
                            DiscogsVersionSource.markVideoUnavailable(current)
                        } else {
                            DiscogsVersionSource.markVideoResolved(
                                seed = current,
                                videoId = resolvedSong!!.id,
                                videoTitle = resolvedSong!!.title,
                                source = resolvedSource ?: "MusicLab",
                            )
                        }
                    replaceSeed(updated)
                    if (!updated.resolvedVideoId.isNullOrBlank()) persistCloudPlaybackBinding(updated)
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

    fun currentVideoPagePool(): List<DiscogsVersionSeed> {
        val ranked = videoPriorityPool(results).filterNot(::isHiddenForCurrentCover)
        if (mode != DiscogsDirectMode.COVER) {
            return ranked.take(visibleLimit.coerceAtLeast(pageSize))
        }
        val start = resultPageIndex.coerceAtLeast(0) * pageSize
        return ranked.drop(start).take(pageSize)
    }

    fun readyVideoCount(): Int =
        currentVideoPagePool().count { !it.resolvedVideoId.isNullOrBlank() }

    suspend fun resolveNextVideoBatch(limit: Int = DIRECT_VIDEO_BATCH_SIZE) {
        val pagePool = currentVideoPagePool()
        val targetReady = pagePool.size
        val missingReady = (targetReady - readyVideoCount()).coerceAtLeast(0)
        if (missingReady == 0) return

        // LAB54: resolve exactly the current global-ranking page.
        val batch =
            pagePool
                .filter { seed ->
                    DiscogsVersionSource.isDisplayableDirectSeed(seed) &&
                        playableTrack(seed) != null &&
                        !seed.videoResolutionChecked
                }
                .take(limit.coerceAtMost(missingReady.coerceAtLeast(1)))
        if (batch.isEmpty()) return

        batch.chunked(DIRECT_VIDEO_PARALLELISM).forEach { chunk ->
            resolveVideoChunk(chunk)
        }
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
        if (backgroundWorkBlocked()) return
        videoPreloadJob =
            scope.launch {
                // LAB50: this job is allowed to exist only while playback does not own
                // the lane. Automatic effects may call the scheduler again after a
                // cancellation, therefore the persistent barrier is checked both here
                // and inside the loop.
                if (backgroundWorkBlocked()) return@launch
                if (!playbackIsNormallyPlaying()) warmResolvedVideoMetadata()
                while (true) {
                    if (backgroundWorkBlocked()) break
                    val workWindow = currentVideoPagePool()
                    val targetReady = workWindow.size
                    if (targetReady == 0 || readyVideoCount() >= targetReady) break

                    val pendingBefore =
                        workWindow.count { seed ->
                            DiscogsVersionSource.isDisplayableDirectSeed(seed) &&
                                playableTrack(seed) != null &&
                                !seed.videoResolutionChecked
                        }
                    if (pendingBefore == 0) break

                    val readyBefore = readyVideoCount()
                    resolveNextVideoBatch(
                        limit = if (playbackIsNormallyPlaying()) {
                            DIRECT_COVER_PLAYBACK_BATCH_SIZE
                        } else {
                            DIRECT_VIDEO_BATCH_SIZE
                        },
                    )
                    if (!playbackIsNormallyPlaying()) warmResolvedVideoMetadata()
                    val readyAfter = readyVideoCount()

                    val anyPending =
                        currentVideoPagePool().any { seed ->
                            DiscogsVersionSource.isDisplayableDirectSeed(seed) &&
                                playableTrack(seed) != null &&
                                !seed.videoResolutionChecked
                        }
                    if (readyAfter == readyBefore && !anyPending) break
                    delay(if (playbackIsNormallyPlaying()) 260 else 120)
                }
                if (!playbackIsNormallyPlaying()) warmResolvedVideoMetadata(limit = pageSize)
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
                        currentVideoPagePool()
                            .firstOrNull { seed ->
                                !isHiddenForCurrentCover(seed) &&
                                    seed.releaseId > 0 &&
                                    seed.track == null &&
                                    !seed.discogsVerificationChecked
                            } ?: break

                    val updated =
                        DiscogsVersionSource.enrichSeedMetadata(
                            token = discogsToken,
                            seed = pending,
                            targetTitle = title,
                            mode = mode,
                            originalArtist = resolvedOriginalArtist,
                        )
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
                // Normal playback is allowed. The Cover resolver restarts in its
                // one-at-a-time playback lane instead of waiting for song end.
                backgroundPausedForPlayback = false
                scheduleDiscogsVerification()
                scheduleVideoPreload()
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
        val selectedSeed =
            orderedResults(results)
                .firstOrNull { it.fingerprint == selectedFingerprint && !it.resolvedVideoId.isNullOrBlank() }
                ?: return
        val selectedId = selectedSeed.resolvedVideoId?.trim().orEmpty()
        if (selectedId.isBlank()) return

        // LAB50 fast lane: once the exact playable video id is already verified,
        // tapping Play must not make another YouTube.queue metadata request. Reuse a
        // prepared SongItem when available; otherwise build the minimum player
        // metadata locally from the verified row. MusicService resolves the stream
        // from mediaId, so the network work that matters starts in the Player lane.
        val selectedItem =
            session.preparedVideoSongs[selectedId]?.toMediaItem()
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
            if (mode == DiscogsDirectMode.COVER) {
                "Cover · $title"
            } else {
                "Originali · $title"
            }

        connection.playQueue(
            ListQueue(
                title = queueTitle,
                items = listOf(selectedItem),
                startIndex = 0,
            ),
        )

        // LAB50 deliberately ends here. The former delayed "prepare next item"
        // hydration kept a second YouTube metadata request alive after every tap and
        // could accumulate work across consecutive songs. The selected song is the
        // only playback-critical item; later discovery resumes only after playback
        // is genuinely idle.
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

        results = mergePage(results, pageResult.items, replace)
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
        syncStableOrder()

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
        searchJob?.cancel()
        paginationJob?.cancel()
        verificationJob?.cancel()
        videoPreloadJob?.cancel()
        loading = true
        loadingMore = false
        error = null
        paginationError = null
        results = emptyList()
        currentPage = 0
        totalPages = 0
        totalDiscogsResults = 0
        activeCriteria = criteria
        selectedFingerprint = null
        visibleLimit = pageSize
        resultPageIndex = 0
        sourceDiagnostics = emptyList()
        sourceDiscoveryLoading = true

        session.results = emptyList()
        session.currentPage = 0
        session.totalPages = 0
        session.totalDiscogsResults = 0
        session.activeCriteria = criteria
        session.selectedFingerprint = null
        session.listIndex = 0
        session.listOffset = 0
        session.stableOrder = emptyList()
        session.visibleLimit = pageSize
        session.resultPageIndex = 0
        session.sourceDiagnostics = emptyList()
        session.sourceDiscoveryComplete = false
        session.usedVideoIds.clear()
        session.knownVideoBindings.clear()
        session.preparedVideoSongs.clear()
        session.originalWorkCredits = emptyList()

        searchJob =
            scope.launch {
                listState.scrollToItem(0)

            val memoryState =
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
                rebuildStableOrder()
                // Cloud-first playback must not wait for Discogs pagination.
                scheduleVideoPreload()
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

            loading = false

            val externalDeferred =
                async(kotlinx.coroutines.Dispatchers.IO) {
                    CoverDiscoverySources.discover(
                        title = criteria.title,
                        originalArtist = resolvedOriginalArtist,
                        mode = mode,
                        aiConfig = foreignScoutConfig,
                    )
                }

            val firstPageLoaded =
                loadPage(
                    criteria = criteria,
                    page = 1,
                    replace = false,
                    requestedSort = requestedSort,
                )

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
                scheduleVideoPreload()
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

            rebuildStableOrder()
            session.visibleLimit = visibleLimit

            if (firstPageLoaded) {
                scheduleDiscogsVerification()
            }
            // Provider/cloud video resolution is independent from Discogs page success.
            scheduleVideoPreload()
        }
        searchJob?.invokeOnCompletion {
            scope.launch {
                loading = false
                sourceDiscoveryLoading = false
            }
        }
    }

    fun moveToResultPage(targetPage: Int) {
        if (mode != DiscogsDirectMode.COVER) return
        val poolSize = orderedResults(results).count { !isHiddenForCurrentCover(it) }
        val pageCount = if (poolSize == 0) 0 else (poolSize + pageSize - 1) / pageSize
        if (targetPage !in 0 until pageCount) return
        resultPageIndex = targetPage
        session.resultPageIndex = targetPage
        verificationJob?.cancel()
        videoPreloadJob?.cancel()
        scope.launch { listState.scrollToItem(0) }
        scheduleDiscogsVerification()
        scheduleVideoPreload()
    }

    fun loadNextPage() {
        val criteria = activeCriteria ?: return
        if (loading || loadingMore) return

        if (mode == DiscogsDirectMode.COVER) {
            val poolSize = orderedResults(results).count { !isHiddenForCurrentCover(it) }
            val pageCount = if (poolSize == 0) 0 else (poolSize + pageSize - 1) / pageSize
            if (resultPageIndex + 1 < pageCount) {
                moveToResultPage(resultPageIndex + 1)
                return
            }
            if (backgroundWorkBlocked() || currentPage <= 0 || currentPage >= totalPages) return

            loadingMore = true
            paginationError = null
            paginationJob?.cancel()
            paginationJob =
                scope.launch {
                    try {
                        val oldPoolSize = orderedResults(results).count { !isHiddenForCurrentCover(it) }
                        val loaded = loadPage(criteria, currentPage + 1, replace = false)
                        if (loaded) {
                            val newPoolSize = orderedResults(results).count { !isHiddenForCurrentCover(it) }
                            if (newPoolSize > oldPoolSize) moveToResultPage(resultPageIndex + 1)
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

    fun loadPreviousPage() {
        if (mode == DiscogsDirectMode.COVER && resultPageIndex > 0) {
            moveToResultPage(resultPageIndex - 1)
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

    LaunchedEffect(visibleLimit, sourceDiagnostics) {
        session.visibleLimit = visibleLimit
        session.sourceDiagnostics = sourceDiagnostics
    }

    val orderedPool = orderedResults(results)
    val navigablePool =
        if (mode == DiscogsDirectMode.COVER) {
            orderedPool.filterNot(::isHiddenForCurrentCover)
        } else {
            orderedPool
        }
    val resultPageCount =
        if (mode == DiscogsDirectMode.COVER && navigablePool.isNotEmpty()) {
            (navigablePool.size + pageSize - 1) / pageSize
        } else if (mode == DiscogsDirectMode.COVER) {
            0
        } else {
            1
        }
    val safeResultPageIndex =
        if (resultPageCount == 0) 0 else resultPageIndex.coerceIn(0, resultPageCount - 1)
    val resultPageStart = safeResultPageIndex * pageSize
    val resultPagePool =
        if (mode == DiscogsDirectMode.COVER) {
            navigablePool.drop(resultPageStart).take(pageSize)
        } else {
            navigablePool
        }
    // Cover cards still publish only with their resolved video thumbnail, but page
    // membership comes from the full global score list, never from readiness.
    val readyPool =
        if (mode == DiscogsDirectMode.COVER) {
            resultPagePool.filter { !it.resolvedVideoId.isNullOrBlank() }
        } else {
            resultPagePool
        }
    val visibleResults =
        if (mode == DiscogsDirectMode.COVER) readyPool else readyPool.take(visibleLimit)

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collectLatest { (index, offset) ->
            session.listIndex = index
            session.listOffset = offset
        }
    }

    LaunchedEffect(resultPageCount, category) {
        if (mode == DiscogsDirectMode.COVER) {
            val clamped = if (resultPageCount == 0) 0 else resultPageIndex.coerceIn(0, resultPageCount - 1)
            if (clamped != resultPageIndex) {
                resultPageIndex = clamped
                session.resultPageIndex = clamped
                scope.launch { listState.scrollToItem(0) }
            }
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
                    .pointerInput(sessionKey) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dragAmount ->
                                closeSwipeDistance =
                                    (closeSwipeDistance + dragAmount).coerceAtLeast(0f)
                            },
                            onDragEnd = {
                                if (closeSwipeDistance >= 110f) navController.popBackStack()
                                closeSwipeDistance = 0f
                            },
                            onDragCancel = { closeSwipeDistance = 0f },
                        )
                    },
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                    OutlinedButton(
                        onClick = { filtersExpanded = !filtersExpanded },
                    ) {
                        Text(if (filtersExpanded) "⌃" else "⌄")
                    }
                }

                if (filtersExpanded) {
                    DirectCategorySelector(
                        selected = category,
                        results = results,
                        onSelected = { selected ->
                            category = selected
                            session.category = selected
                            visibleLimit = pageSize
                            session.visibleLimit = visibleLimit
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

                if (mode != DiscogsDirectMode.COVER) {
                    DirectSortSelector(
                        selected = sortMode,
                        onSelected = { selected ->
                            if (selected != sortMode) {
                                sortMode = selected
                                session.sortMode = selected
                                rebuildStableOrder()
                                visibleLimit = pageSize
                                session.visibleLimit = visibleLimit
                                resultPageIndex = 0
                                session.resultPageIndex = 0
                                scope.launch { listState.scrollToItem(0) }
                            }
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { runSearch() },
                        enabled = !loading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (loading) "Ricerca…" else "Cerca versioni")
                    }
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
                        val firstNumber = if (resultPagePool.isEmpty()) 0 else resultPageStart + 1
                        val lastNumber = resultPageStart + resultPagePool.size
                        Text(
                            text =
                                "Risultati raccolti: ${navigablePool.size} · " +
                                    "Pagina ${safeResultPageIndex + 1}/${resultPageCount.coerceAtLeast(1)} · " +
                                    "Risultati $firstNumber–$lastNumber",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (readyPool.size < resultPagePool.size) {
                            Text(
                                "Preparo i video della pagina: ${readyPool.size}/${resultPagePool.size} pronti",
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

            if (!loading && activeCriteria != null && visibleResults.isEmpty() && currentPage >= totalPages && currentPage > 0) {
                item(key = "discogs_direct_no_results_${mode.name}") {
                    Text(
                        "Nessun candidato disponibile con questo filtro. Provo le altre pagine/fonti quando disponibili.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            items(
                count = visibleResults.size,
                key = { index ->
                    val seed = visibleResults[index]
                    "discogs_direct_${mode.name}_${seed.fingerprint}"
                },
            ) { index ->
                val seed = visibleResults[index]
                DiscogsVersionCard(
                    seed = seed,
                    showVideoPreview = mode == DiscogsDirectMode.COVER,
                    selected = seed.fingerprint == selectedFingerprint,
                    resolving = seed.fingerprint == resolvingFingerprint,
                    onPlay = { play(seed) },
                    onRetryVideo = { retryMissingVideo(seed) },
                    onDetails = { detailSeed = seed },
                )
            }

            if (
                loadingMore ||
                (
                    activeCriteria != null &&
                        currentPage > 0 &&
                        (readyPool.size > visibleLimit || currentPage < totalPages)
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
                            Text("Preparo il prossimo blocco da $pageSize video…")
                        } else {
                            TextButton(onClick = ::loadNextPage) {
                                Text("Carica i prossimi $pageSize")
                            }
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
                activeCriteria != null &&
                currentPage > 0 &&
                currentPage >= totalPages &&
                readyPool.size <= visibleLimit &&
                !loading &&
                !loadingMore
            ) {
                item(key = "discogs_direct_end_${mode.name}") {
                    Text(
                        "Fine dei risultati attualmente raccolti. Nessun candidato è stato eliminato automaticamente.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                    )
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
                Text(
                    if (approved) "Stato: Approvata" else "Stato: Non approvata",
                    color =
                        if (approved) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Titolo: ${seed.trackTitle}",
                    modifier = Modifier.clickable { onSearch(seed.trackTitle) },
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Interprete: ${seed.artist}",
                    modifier = Modifier.clickable { onSearch(seed.artist) },
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Pubblicazione: ${seed.releaseTitle}",
                    modifier =
                        Modifier.clickable(enabled = seed.releaseTitle.isNotBlank()) {
                            onSearch(seed.releaseTitle)
                        },
                )
                Text("Tipo: ${seed.kind.name.lowercase()}")
                Text("Affidabilità: ${seed.confidenceScore}/20")
                Text("Data: ${seed.displayDate ?: "non disponibile"}")
                Text("Paese: ${seed.country ?: "non disponibile"}")
                Text("Formato: ${seed.formats.joinToString().ifBlank { "non disponibile" }}")
                Text("Etichetta: ${seed.labels.joinToString().ifBlank { "non disponibile" }}")
                Text("Lingua/adattamento: ${seed.language ?: "non disponibile"}")
                Text("Fonti: ${seed.sourceNames.joinToString().ifBlank { "non disponibile" }}")
                Text(
                    "Stato evidenza: " +
                        when {
                            seed.track != null -> "tracklist Discogs verificata"
                            seed.releaseId > 0 && seed.discogsVerificationChecked ->
                                "Discogs controllato, corrispondenza non confermata"
                            seed.releaseId > 0 -> "candidato Discogs in verifica"
                            else -> "candidato da fonte esterna"
                        },
                )
                if (seed.releaseId > 0) {
                    Text("Discogs Release: ${seed.releaseId}")
                    Text("Discogs Master: ${seed.masterId ?: "non disponibile"}")
                }
                Text("Video: ${seed.resolvedVideoTitle ?: "in verifica / non disponibile"}")
                Text("Fonte video: ${seed.resolvedVideoSource ?: "non disponibile"}")

                if (seed.credits.isEmpty()) {
                    Text("Crediti: non disponibili")
                } else {
                    Text("Crediti:", fontWeight = FontWeight.SemiBold)
                    seed.credits
                        .distinctBy { "${it.name}|${it.role}" }
                        .forEach { credit ->
                            Text(
                                "${credit.role}: ${credit.name}",
                                modifier = Modifier.clickable { onSearch(credit.name) },
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                }

                if (seed.confidenceReasons.isNotEmpty()) {
                    Text("Motivi punteggio: " + seed.confidenceReasons.joinToString(" · "))
                }
                Text(
                    "Tocca titolo, interprete, pubblicazione o un autore per cercarlo in MusicLab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Chiudi")
            }
        },
        title = { Text("Fonti della ricerca") },
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
                    "Le fonti propongono o rafforzano candidati. Nessuna fonte e nessuna AI elimina automaticamente un risultato.",
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
    val studio =
        results.count {
            it.language.isNullOrBlank() &&
                (it.kind == DiscogsVersionKind.STUDIO || it.kind == DiscogsVersionKind.ACOUSTIC)
        }
    val live = results.count { it.language.isNullOrBlank() && it.kind == DiscogsVersionKind.LIVE }
    val remix = results.count { it.language.isNullOrBlank() && it.kind == DiscogsVersionKind.REMIX }
    val foreign = results.count { !it.language.isNullOrBlank() }

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
        DirectChip("Mix · $remix", selected == DirectVersionCategory.REMIX) {
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
    selected: Boolean,
    resolving: Boolean,
    onPlay: () -> Unit,
    onRetryVideo: () -> Unit,
    onDetails: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
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
            modifier = Modifier.fillMaxWidth().padding(10.dp),
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
                    seed.releaseTitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Data pubblicazione: ${seed.displayDate ?: "non disponibile"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Affidabilità: ${seed.confidenceScore}/20",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                seed.language?.takeIf(String::isNotBlank)?.let { language ->
                    Text(
                        "Lingua/adattamento: $language",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                seed.resolvedVideoTitle?.takeIf(String::isNotBlank)?.let { videoTitle ->
                    Text(
                        "Video pronto: $videoTitle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val details = buildList {
                    add(
                        when (seed.kind) {
                            DiscogsVersionKind.STUDIO -> "Studio"
                            DiscogsVersionKind.LIVE -> "Live"
                            DiscogsVersionKind.REMIX -> "Remix"
                            DiscogsVersionKind.ACOUSTIC -> "Acoustic"
                        },
                    )
                    seed.country?.takeIf(String::isNotBlank)?.let(::add)
                    seed.formats.firstOrNull()?.takeIf(String::isNotBlank)?.let(::add)
                    seed.labels.firstOrNull()?.takeIf(String::isNotBlank)?.let(::add)
                }.joinToString(" · ")

                if (details.isNotBlank()) {
                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

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
