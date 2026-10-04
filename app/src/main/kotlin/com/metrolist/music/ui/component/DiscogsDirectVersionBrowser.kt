package com.metrolist.music.ui.component

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
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
import java.util.concurrent.ConcurrentHashMap

private const val DIRECT_VERSION_PAGE_SIZE = 20
private const val DIRECT_VERSION_PREFETCH_DISTANCE = 4
private const val DIRECT_VERSION_BOTTOM_SAFE_DP = 260
private const val DIRECT_VIDEO_BATCH_SIZE = 10
private const val DIRECT_VIDEO_PARALLELISM = 3
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
    var sourceDiagnostics: List<CoverSourceDiagnostic> = emptyList(),
    var sourceDiscoveryComplete: Boolean = false,
    val rejectedKeys: MutableSet<String> = linkedSetOf(),
    val usedVideoIds: MutableSet<String> = linkedSetOf(),
)

private object DirectVersionSessionStore {
    private val sessions = ConcurrentHashMap<String, DirectVersionSession>()

    fun get(
        key: String,
        initialTitle: String,
    ): DirectVersionSession {
        if (sessions.size > 16 && !sessions.containsKey(key)) {
            sessions.keys.firstOrNull()?.let(sessions::remove)
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
    var visibleLimit by remember(sessionKey) { mutableStateOf(session.visibleLimit) }
    var paginationJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var videoPreloadJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var verificationJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    var decisionSavingFingerprint by remember(sessionKey) { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = session.listIndex,
        initialFirstVisibleItemScrollOffset = session.listOffset,
    )

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
            title = title.trim(),
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

    fun memoryCandidateToSeed(candidate: AiCoverCandidate): DiscogsVersionSeed {
        val score =
            when (candidate.brainStatus) {
                AiBrainDecisionStatus.APPROVED -> 10
                AiBrainDecisionStatus.PROBABLE -> 8
                AiBrainDecisionStatus.UNCERTAIN -> 4
                AiBrainDecisionStatus.REJECTED -> 1
                null -> 4
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
                seed.confidenceScore >= 9 -> "very_strong"
                seed.confidenceScore >= 7 -> "strong"
                seed.confidenceScore >= 4 -> "medium"
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
            sameWorkScore = (seed.confidenceScore * 10).coerceIn(10, 100),
            versionTypeScore = (seed.confidenceScore * 10).coerceIn(10, 100),
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

    fun mergePage(
        current: List<DiscogsVersionSeed>,
        incoming: List<DiscogsVersionSeed>,
        replace: Boolean,
    ): List<DiscogsVersionSeed> {
        val merged = linkedMapOf<String, DiscogsVersionSeed>()
        if (!replace) {
            current.filterNot(::isRejected).forEach { seed ->
                merged[DiscogsVersionSource.identityKey(seed)] = seed
            }
        }
        incoming.filterNot(::isRejected).forEach { seed ->
            val key = DiscogsVersionSource.identityKey(seed)
            val previous = merged[key]
            merged[key] =
                if (previous == null) {
                    seed
                } else {
                    DiscogsVersionSource.mergeEvidence(previous, seed)
                }
        }
        return merged.values.toList()
    }

    fun sortedPool(source: List<DiscogsVersionSeed>): List<DiscogsVersionSeed> {
        val displayable = source.filter(DiscogsVersionSource::isDisplayableDirectSeed)
        return when (sortMode) {
            DirectVersionSort.RELEVANCE ->
                displayable.sortedWith(
                    compareByDescending<DiscogsVersionSeed> { it.confidenceScore }
                        .thenBy { it.year ?: Int.MAX_VALUE }
                        .thenBy { it.artist.lowercase() },
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
        val ordered =
            buildList {
                session.stableOrder.forEach { fingerprint ->
                    byId[fingerprint]?.let(::add)
                }
                source.forEach { seed ->
                    if (none { it.fingerprint == seed.fingerprint }) add(seed)
                }
            }.filter(DiscogsVersionSource::isDisplayableDirectSeed)

        return ordered.filter { seed ->
            when (category) {
                DirectVersionCategory.ALL -> true
                DirectVersionCategory.STUDIO ->
                    seed.language.isNullOrBlank() &&
                        (seed.kind == DiscogsVersionKind.STUDIO || seed.kind == DiscogsVersionKind.ACOUSTIC)
                DirectVersionCategory.LIVE ->
                    seed.language.isNullOrBlank() && seed.kind == DiscogsVersionKind.LIVE
                DirectVersionCategory.REMIX ->
                    seed.language.isNullOrBlank() && seed.kind == DiscogsVersionKind.REMIX
                DirectVersionCategory.FOREIGN -> !seed.language.isNullOrBlank()
            }
        }
    }

    fun replaceSeed(updated: DiscogsVersionSeed) {
        results = results.map { current ->
            val same =
                current.fingerprint == updated.fingerprint ||
                    (current.releaseId > 0 && current.releaseId == updated.releaseId) ||
                    DiscogsVersionSource.identityKey(current) == DiscogsVersionSource.identityKey(updated)
            if (same) {
                DiscogsVersionSource.mergeEvidence(current, updated)
            } else {
                current
            }
        }
        session.results = results
        syncStableOrder()
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


    suspend fun resolveVideoChunk(chunk: List<DiscogsVersionSeed>) = coroutineScope {
        if (chunk.isEmpty()) return@coroutineScope
        val excludedSnapshot = session.usedVideoIds.toSet()
        val attempts =
            chunk.map { seed ->
                async {
                    val track = playableTrack(seed)
                    seed.fingerprint to if (track == null) {
                        null
                    } else {
                        CompilationTrackResolver.resolveTrack(
                            track = track,
                            discogsVideos = seed.videos,
                            fastFirst = true,
                            excludedVideoIds = excludedSnapshot,
                        )
                    }
                }
            }.awaitAll()

        attempts.forEach { (fingerprint, firstAttempt) ->
            val current = results.firstOrNull { it.fingerprint == fingerprint } ?: return@forEach
            val track = playableTrack(current)
            var resolved = firstAttempt
            if (
                resolved != null &&
                resolved.song.id in session.usedVideoIds &&
                track != null
            ) {
                resolved = CompilationTrackResolver.resolveTrack(
                    track = track,
                    discogsVideos = current.videos,
                    fastFirst = true,
                    excludedVideoIds = session.usedVideoIds.toSet(),
                )
            }

            val updated =
                if (resolved == null || !session.usedVideoIds.add(resolved.song.id)) {
                    DiscogsVersionSource.markVideoUnavailable(current)
                } else {
                    DiscogsVersionSource.markVideoResolved(
                        seed = current,
                        videoId = resolved.song.id,
                        videoTitle = resolved.song.title,
                        source = resolved.source,
                    )
                }
            replaceSeed(updated)
        }
    }

    suspend fun resolveNextVideoBatch(limit: Int = DIRECT_VIDEO_BATCH_SIZE) {
        val batch =
            results
                .filter { seed ->
                    DiscogsVersionSource.isDisplayableDirectSeed(seed) &&
                        playableTrack(seed) != null &&
                        !seed.videoResolutionChecked
                }
                .take(limit)
        if (batch.isEmpty()) return

        batch.chunked(DIRECT_VIDEO_PARALLELISM).forEach { chunk ->
            resolveVideoChunk(chunk)
        }
    }

    fun scheduleVideoPreload() {
        if (videoPreloadJob?.isActive == true) return
        videoPreloadJob =
            scope.launch {
                while (true) {
                    val pendingBefore =
                        results.count { seed ->
                            DiscogsVersionSource.isDisplayableDirectSeed(seed) &&
                                playableTrack(seed) != null &&
                                !seed.videoResolutionChecked
                        }
                    if (pendingBefore == 0) break

                    resolveNextVideoBatch()

                    val pendingAfter =
                        results.count { seed ->
                            DiscogsVersionSource.isDisplayableDirectSeed(seed) &&
                                playableTrack(seed) != null &&
                                !seed.videoResolutionChecked
                        }
                    if (pendingAfter >= pendingBefore) break
                    delay(120)
                }
            }
    }

    fun scheduleDiscogsVerification() {
        if (verificationJob?.isActive == true || discogsToken.isBlank()) return
        verificationJob =
            scope.launch {
                while (true) {
                    val pending =
                        results.firstOrNull { seed ->
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
                    delay(80)
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
                session.rejectedKeys += rejectionKey(seed)
                results = results.filterNot { rejectionKey(it) in session.rejectedKeys }
                session.results = results
                detailSeed = null
                rebuildStableOrder()
                Toast.makeText(
                    context,
                    "Disapprovata: memorizzata e nascosta dalle ricerche future.",
                    Toast.LENGTH_LONG,
                ).show()
            } else if (status == AiBrainDecisionStatus.APPROVED) {
                val approved =
                    seed.copy(
                        confidenceScore = 10,
                        confidenceReasons =
                            (seed.confidenceReasons + "Approvata manualmente e salvata nel cloud").distinct(),
                        sourceNames = (seed.sourceNames + "Archivio Cloud").distinct(),
                    )
                replaceSeed(approved)
                detailSeed = results.firstOrNull { it.fingerprint == approved.fingerprint } ?: approved
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
        val ordered = orderedResults(results)
        val readySeeds =
            ordered.filter { seed ->
                !seed.resolvedVideoId.isNullOrBlank()
            }
        val selectedSeed =
            readySeeds.firstOrNull { it.fingerprint == selectedFingerprint } ?: return

        val ids = readySeeds.mapNotNull { it.resolvedVideoId }.distinct()
        val songs =
            ids.chunked(40).flatMap { chunk ->
                runCatching {
                    YouTube.queue(videoIds = chunk).getOrNull().orEmpty()
                }.getOrDefault(emptyList())
            }
        val songsById = songs.associateBy { it.id }
        val queueEntries =
            readySeeds.mapNotNull { seed ->
                val id = seed.resolvedVideoId ?: return@mapNotNull null
                songsById[id]?.let { song -> seed.fingerprint to song.toMediaItem() }
            }

        val startIndex = queueEntries.indexOfFirst { it.first == selectedSeed.fingerprint }
        if (startIndex < 0) return

        val queueTitle =
            if (mode == DiscogsDirectMode.COVER) {
                "Cover · $title"
            } else {
                "Originali · $title"
            }
        connection.playQueue(
            ListQueue(
                title = queueTitle,
                items = queueEntries.map { it.second },
                startIndex = startIndex,
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
            perPage = DIRECT_VERSION_PAGE_SIZE,
            sort = discogsSort,
            sortOrder = discogsOrder,
        ).getOrElse { failure ->
            val message = failure.message ?: "Errore Discogs"
            if (replace) error = message else paginationError = message
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
        val criteria = buildCriteria()
        if (criteria.title.isBlank()) {
            error = "Inserisci il titolo del brano."
            return
        }
        if (resolvedOriginalArtist.isBlank()) {
            error = "Interprete originale di riferimento mancante."
            return
        }
        if (discogsToken.isBlank()) {
            error = "Inserisci il token Discogs in Impostazioni → Account → Last.fm + Discogs."
            return
        }

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
        visibleLimit = DIRECT_VERSION_PAGE_SIZE
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
        session.visibleLimit = DIRECT_VERSION_PAGE_SIZE
        session.sourceDiagnostics = emptyList()
        session.sourceDiscoveryComplete = false
        session.usedVideoIds.clear()
        session.originalWorkCredits = emptyList()

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

            memoryState
                ?.discovery
                ?.original
                ?.artist
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { resolvedOriginalArtist = it }

            session.rejectedKeys.clear()
            session.rejectedKeys.addAll(memoryState?.rejectedKeys.orEmpty())

            val memoryVersions =
                memoryState
                    ?.discovery
                    ?.versions
                    .orEmpty()
                    .filterNot { candidate ->
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
                        ) in session.rejectedKeys
                    }

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
                            "cloud-first · ${session.rejectedKeys.size} disapprovate escluse"
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

            val external =
                runCatching { externalDeferred.await() }
                    .getOrElse { CoverSourceOutcome(emptyList(), emptyList()) }

            if (external.candidates.isNotEmpty()) {
                val externalSeeds = external.candidates.map(DiscogsVersionSource::externalSeed)
                results = mergePage(results, externalSeeds, replace = false)
                session.results = results
                scheduleVideoPreload()
            }
            sourceDiagnostics =
                sourceDiagnostics.filter { diagnostic ->
                    diagnostic.name == "Discogs" || diagnostic.name == "Archivio Cloud"
                } + external.diagnostics
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
    }

    fun loadNextPage() {
        val criteria = activeCriteria ?: return
        if (loading || loadingMore || discogsToken.isBlank()) return

        val target = visibleLimit + DIRECT_VERSION_PAGE_SIZE
        val currentlyAvailable = orderedResults(results).size
        if (currentlyAvailable >= target) {
            visibleLimit = target
            session.visibleLimit = visibleLimit
            return
        }
        if (currentPage <= 0 || currentPage >= totalPages) {
            visibleLimit = target
            session.visibleLimit = visibleLimit
            return
        }

        loadingMore = true
        paginationError = null
        paginationJob?.cancel()
        paginationJob =
            scope.launch {
                try {
                    var available = orderedResults(results).size
                    var page = currentPage
                    while (available < target && page < totalPages) {
                        page += 1
                        val loaded = loadPage(criteria, page, replace = false)
                        if (!loaded) break
                        available = orderedResults(results).size
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

    fun play(seed: DiscogsVersionSeed) {
        session.listIndex = listState.firstVisibleItemIndex
        session.listOffset = listState.firstVisibleItemScrollOffset
        selectedFingerprint = seed.fingerprint
        session.selectedFingerprint = seed.fingerprint
        resolvingFingerprint = seed.fingerprint
        playerConnection?.beginPlaybackPriorityBurst("musiclab-version")
        scheduleVideoPreload()

        scope.launch {
            var currentSeed =
                results.firstOrNull { it.fingerprint == seed.fingerprint } ?: seed

            if (
                currentSeed.releaseId > 0 &&
                currentSeed.track == null &&
                !currentSeed.discogsVerificationChecked
            ) {
                val verified =
                    DiscogsVersionSource.enrichSeedMetadata(
                        token = discogsToken,
                        seed = currentSeed,
                        targetTitle = title,
                        mode = mode,
                        originalArtist = resolvedOriginalArtist,
                    )
                replaceSeed(verified)
                currentSeed =
                    results.firstOrNull { it.fingerprint == seed.fingerprint } ?: verified
            }

            if (!currentSeed.resolvedVideoId.isNullOrBlank()) {
                resolvingFingerprint = null
                playResolvedContext(currentSeed.fingerprint)
                return@launch
            }

            val track = playableTrack(currentSeed)
            if (track == null) {
                resolvingFingerprint = null
                Toast.makeText(
                    context,
                    "Candidato conservato, ma non ancora riproducibile.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            val resolved = CompilationTrackResolver.resolveTrack(
                track = track,
                discogsVideos = currentSeed.videos,
                fastFirst = !currentSeed.videoResolutionChecked,
                excludedVideoIds = session.usedVideoIds.toSet(),
            )
            resolvingFingerprint = null
            if (resolved == null || !session.usedVideoIds.add(resolved.song.id)) {
                replaceSeed(DiscogsVersionSource.markVideoUnavailable(currentSeed))
                Toast.makeText(context, "Video/audio unico non trovato per questa versione.", Toast.LENGTH_SHORT).show()
            } else {
                val prepared = DiscogsVersionSource.markVideoResolved(
                    seed = currentSeed,
                    videoId = resolved.song.id,
                    videoTitle = resolved.song.title,
                    source = resolved.source,
                )
                replaceSeed(prepared)
                playResolvedContext(prepared.fingerprint)
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

    LaunchedEffect(visibleLimit, sourceDiagnostics) {
        session.visibleLimit = visibleLimit
        session.sourceDiagnostics = sourceDiagnostics
    }

    val orderedPool = orderedResults(results)
    val visibleResults = orderedPool.take(visibleLimit)

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collectLatest { (index, offset) ->
            session.listIndex = index
            session.listOffset = offset
        }
    }

    LaunchedEffect(listState, activeCriteria, currentPage, totalPages, visibleResults.size, visibleLimit) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collectLatest { lastVisible ->
            if (
                activeCriteria != null &&
                visibleResults.isNotEmpty() &&
                lastVisible >= 0 &&
                visibleResults.size - lastVisible <= DIRECT_VERSION_PREFETCH_DISTANCE &&
                !loading &&
                !loadingMore &&
                (orderedPool.size > visibleLimit || currentPage < totalPages)
            ) {
                loadNextPage()
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
            activeCriteria != null &&
            orderedPool.size < visibleLimit &&
            currentPage > 0 &&
            currentPage < totalPages &&
            !loading &&
            !loadingMore
        ) {
            delay(250)
            loadNextPage()
        }
    }


    LaunchedEffect(sessionKey, discogsToken) {
        if (
            !session.initialized &&
            initialTitle.isNotBlank() &&
            discogsToken.isNotBlank()
        ) {
            runSearch()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
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
                        Text("Chiudi")
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
                            visibleLimit = DIRECT_VERSION_PAGE_SIZE
                            session.visibleLimit = visibleLimit
                            scope.launch { listState.scrollToItem(0) }
                        },
                    )
                }

                DirectSortSelector(
                    selected = sortMode,
                    onSelected = { selected ->
                        if (selected != sortMode) {
                            sortMode = selected
                            session.sortMode = selected
                            rebuildStableOrder()
                            visibleLimit = DIRECT_VERSION_PAGE_SIZE
                            session.visibleLimit = visibleLimit
                            scope.launch { listState.scrollToItem(0) }
                        }
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { runSearch() },
                        enabled = !loading && discogsToken.isNotBlank(),
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

                if (activeCriteria != null && currentPage > 0) {
                    val logicalBlock =
                        ((visibleLimit.coerceAtLeast(1) - 1) / DIRECT_VERSION_PAGE_SIZE) + 1
                    Text(
                        text =
                            "Risultati raccolti: ${results.size} · Mostrati: ${visibleResults.size} · " +
                                "Blocco MusicLab $logicalBlock · 20 per blocco · " +
                                "Release Discogs disponibili: $totalDiscogsResults",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
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
                top = 8.dp,
                bottom = DIRECT_VERSION_BOTTOM_SAFE_DP.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!loading && activeCriteria == null && results.isEmpty()) {
                item(key = "discogs_direct_empty_${mode.name}") {
                    Text(
                        "Cerca cover/versioni: MusicLab raccoglie tutte le piste e le ordina da 10 a 1 senza scarti automatici.",
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
                    selected = seed.fingerprint == selectedFingerprint,
                    resolving = seed.fingerprint == resolvingFingerprint,
                    onPlay = { play(seed) },
                    onDetails = { detailSeed = seed },
                )
            }

            if (
                loadingMore ||
                (
                    activeCriteria != null &&
                        currentPage > 0 &&
                        (orderedPool.size > visibleLimit || currentPage < totalPages)
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
                            Text("Preparo il prossimo blocco da 20 risultati…")
                        } else {
                            TextButton(onClick = ::loadNextPage) {
                                Text("Carica i prossimi 20")
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
                orderedPool.size <= visibleLimit &&
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
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Chiudi")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onApprove,
                    enabled = !saving,
                ) {
                    Text(if (saving) "Salvo…" else "Approva")
                }
                TextButton(
                    onClick = onReject,
                    enabled = !saving,
                ) {
                    Text("Disapprova")
                }
            }
        },
        title = { Text("Dettagli versione") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Text("Affidabilità: ${seed.confidenceScore}/10")
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
        DirectChip("Punteggio 10→1", selected == DirectVersionSort.RELEVANCE) {
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
    selected: Boolean,
    resolving: Boolean,
    onPlay: () -> Unit,
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
            AsyncImage(
                model = seed.coverUrl,
                contentDescription = null,
                modifier = Modifier.size(76.dp),
                contentScale = ContentScale.Crop,
            )
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
                    "Affidabilità: ${seed.confidenceScore}/10",
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
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            when {
                                !seed.resolvedVideoId.isNullOrBlank() -> "Tocca per riprodurre"
                                seed.videoResolutionChecked -> "Video non trovato"
                                else -> "Video in verifica…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
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
