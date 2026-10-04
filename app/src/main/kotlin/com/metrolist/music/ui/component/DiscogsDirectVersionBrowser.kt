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
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val DIRECT_VERSION_PAGE_SIZE = 20
private const val DIRECT_VERSION_PREFETCH_DISTANCE = 8
private const val DIRECT_VERSION_BOTTOM_SAFE_DP = 260
private val DirectCoverGeminiApiKey = stringPreferencesKey("coverGeminiApiKey")

private enum class DirectVersionCategory {
    ALL,
    STUDIO,
    LIVE,
    REMIX,
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
    var foreignScoutComplete: Boolean = false,
    var originalWorkCredits: List<DiscogsCredit> = emptyList(),
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
    var foreignScoutLoading by remember(sessionKey) { mutableStateOf(false) }
    var detailSeed by remember(sessionKey) { mutableStateOf<DiscogsVersionSeed?>(null) }
    var paginationJob by remember(sessionKey) { mutableStateOf<Job?>(null) }
    val enrichedReleaseIds = remember(sessionKey) {
        session.results.filter { it.track != null }.mapTo(mutableSetOf()) { it.releaseId }
    }

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

    fun mergePage(
        current: List<DiscogsVersionSeed>,
        incoming: List<DiscogsVersionSeed>,
        replace: Boolean,
    ): List<DiscogsVersionSeed> {
        val merged = linkedMapOf<String, DiscogsVersionSeed>()
        if (!replace) {
            current.forEach { seed -> merged.putIfAbsent(seed.fingerprint, seed) }
        }
        incoming.forEach { seed ->
            val previous = merged[seed.fingerprint]
            if (previous == null) {
                merged[seed.fingerprint] = seed
            } else {
                val previousPrecision = previous.releaseDate?.length ?: 0
                val incomingPrecision = seed.releaseDate?.length ?: 0
                if (incomingPrecision > previousPrecision) {
                    merged[seed.fingerprint] = seed
                }
            }
        }
        return merged.values.toList()
    }

    suspend fun loadPage(
        criteria: DiscogsVersionSearchCriteria,
        page: Int,
        replace: Boolean,
        requestedSort: DirectVersionSort = sortMode,
    ): Boolean {
        val (discogsSort, discogsOrder) =
            when (requestedSort) {
                DirectVersionSort.RELEVANCE -> null to null
                DirectVersionSort.OLDEST -> "year" to "asc"
                DirectVersionSort.NEWEST -> "year" to "desc"
            }

        val pageResult = DiscogsVersionSource.loadVersionPage(
            token = discogsToken,
            mode = mode,
            criteria = criteria,
            originalArtist = lockedArtist,
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
        if (mode == DiscogsDirectMode.ORIGINAL && lockedArtist.isBlank()) {
            error = "Artista originale mancante."
            return
        }
        if (discogsToken.isBlank()) {
            error = "Inserisci il token Discogs in Impostazioni → Account → Last.fm + Discogs."
            return
        }

        paginationJob?.cancel()
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

        session.results = emptyList()
        session.currentPage = 0
        session.totalPages = 0
        session.totalDiscogsResults = 0
        session.activeCriteria = criteria
        session.selectedFingerprint = null
        session.listIndex = 0
        session.listOffset = 0
        session.foreignScoutComplete = false
        session.usedVideoIds.clear()
        foreignScoutLoading = false

        scope.launch {
            listState.scrollToItem(0)
            loadPage(
                criteria = criteria,
                page = 1,
                replace = true,
                requestedSort = requestedSort,
            )

            if (session.originalWorkCredits.isEmpty() && lockedArtist.isNotBlank()) {
                session.originalWorkCredits = DiscogsVersionSource.loadOriginalWorkCredits(
                    token = discogsToken,
                    title = criteria.title,
                    originalArtist = lockedArtist,
                )
                if (session.originalWorkCredits.isNotEmpty()) {
                    results = results.map { seed ->
                        DiscogsVersionSource.applySharedWorkCreditEvidence(
                            seed = seed,
                            originalCredits = session.originalWorkCredits,
                        )
                    }
                    session.results = results
                }
            }

            loading = false

            if (
                mode == DiscogsDirectMode.COVER &&
                foreignScoutConfig != null &&
                !session.foreignScoutComplete
            ) {
                foreignScoutLoading = true
                runCatching {
                    GeminiCoverVerification.discover(
                        originalTitle = criteria.title,
                        originalArtist = lockedArtist,
                        config = foreignScoutConfig,
                    )
                }.getOrDefault(emptyList()).forEach { reference ->
                    val discovered = DiscogsVersionSource.loadReferenceSeeds(
                        token = discogsToken,
                        referenceTitle = reference.title,
                        referenceArtist = reference.artist,
                        originalArtist = lockedArtist,
                        adaptedTitle = reference.translatedOrAdaptedTitle,
                    )
                    if (discovered.isNotEmpty()) {
                        results = mergePage(results, discovered, replace = false)
                        session.results = results
                    }
                }
                session.foreignScoutComplete = true
                foreignScoutLoading = false
            }
        }
    }

    fun loadNextPage() {
        val criteria = activeCriteria ?: return
        if (loading || loadingMore || currentPage <= 0 || currentPage >= totalPages) return
        if (discogsToken.isBlank()) return

        loadingMore = true
        paginationError = null
        paginationJob?.cancel()
        paginationJob = scope.launch {
            try {
                loadPage(criteria, currentPage + 1, replace = false)
            } finally {
                loadingMore = false
            }
        }
    }

    fun play(seed: DiscogsVersionSeed) {
        selectedFingerprint = seed.fingerprint
        session.selectedFingerprint = seed.fingerprint
        resolvingFingerprint = seed.fingerprint
        playerConnection?.beginPlaybackPriorityBurst("discogs-direct-version")

        scope.launch {
            val preResolvedId = seed.resolvedVideoId
            if (!preResolvedId.isNullOrBlank()) {
                val readySong = runCatching {
                    YouTube.queue(videoIds = listOf(preResolvedId)).getOrNull()?.firstOrNull()
                }.getOrNull()
                if (readySong != null) {
                    resolvingFingerprint = null
                    playerConnection?.playNow(readySong.toMediaItem())
                    return@launch
                }
            }

            val playableSeed =
                if (seed.track != null) {
                    seed
                } else {
                    DiscogsVersionSource.resolveSeedForPlayback(
                        token = discogsToken,
                        seed = seed,
                        targetTitle = seed.trackTitle,
                        mode = mode,
                        originalArtist = lockedArtist,
                    )
                }

            val track = playableSeed?.track
            if (track == null) {
                resolvingFingerprint = null
                Toast.makeText(context, "Traccia Discogs non disponibile.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val resolved = CompilationTrackResolver.resolveTrack(
                track = track,
                discogsVideos = playableSeed.videos,
                fastFirst = true,
                excludedVideoIds = session.usedVideoIds.toSet(),
            )
            resolvingFingerprint = null
            if (resolved == null) {
                Toast.makeText(context, "Video/audio unico non trovato per questa versione.", Toast.LENGTH_SHORT).show()
            } else {
                session.usedVideoIds += resolved.song.id
                val prepared = DiscogsVersionSource.markVideoResolved(
                    seed = playableSeed,
                    videoId = resolved.song.id,
                    videoTitle = resolved.song.title,
                    source = resolved.source,
                )
                results = results.map { current ->
                    if (current.releaseId == seed.releaseId) prepared else current
                }
                session.results = results
                playerConnection?.playNow(resolved.song.toMediaItem())
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

    val categoryResults =
        results.filter { seed ->
            when (category) {
                DirectVersionCategory.ALL -> true
                DirectVersionCategory.STUDIO ->
                    seed.kind == DiscogsVersionKind.STUDIO || seed.kind == DiscogsVersionKind.ACOUSTIC
                DirectVersionCategory.LIVE -> seed.kind == DiscogsVersionKind.LIVE
                DirectVersionCategory.REMIX -> seed.kind == DiscogsVersionKind.REMIX
            }
        }

    val visibleResults =
        when (sortMode) {
            DirectVersionSort.RELEVANCE ->
                categoryResults.sortedWith(
                    compareByDescending<DiscogsVersionSeed> { it.confidenceScore }
                        .thenBy { it.year ?: Int.MAX_VALUE }
                        .thenBy { it.artist.lowercase() },
                )
            DirectVersionSort.OLDEST ->
                categoryResults.sortedWith(
                    compareBy<DiscogsVersionSeed> { it.year ?: Int.MAX_VALUE }
                        .thenByDescending { it.confidenceScore },
                )
            DirectVersionSort.NEWEST ->
                categoryResults.sortedWith(
                    compareByDescending<DiscogsVersionSeed> { it.year ?: Int.MIN_VALUE }
                        .thenByDescending { it.confidenceScore },
                )
        }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collectLatest { (index, offset) ->
            session.listIndex = index
            session.listOffset = offset
        }
    }

    LaunchedEffect(listState, activeCriteria, currentPage, totalPages, visibleResults.size) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collectLatest { lastVisible ->
            if (
                activeCriteria != null &&
                currentPage > 0 &&
                currentPage < totalPages &&
                visibleResults.isNotEmpty() &&
                lastVisible >= 0 &&
                visibleResults.size - lastVisible <= DIRECT_VERSION_PREFETCH_DISTANCE
            ) {
                loadNextPage()
            }
        }
    }

    LaunchedEffect(
        activeCriteria,
        currentPage,
        totalPages,
        results.size,
        loading,
        loadingMore,
    ) {
        if (
            activeCriteria != null &&
            currentPage > 0 &&
            currentPage < totalPages &&
            visibleResults.isEmpty() &&
            !loading &&
            !loadingMore
        ) {
            delay(350)
            loadNextPage()
        }
    }

    LaunchedEffect(listState, visibleResults.map { it.releaseId }) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                visibleResults.getOrNull(info.index)?.releaseId
            }.distinct()
        }.collectLatest { visibleIds ->
            visibleIds.forEach { releaseId ->
                if (releaseId in enrichedReleaseIds || discogsToken.isBlank()) return@forEach
                val seed = results.firstOrNull { it.releaseId == releaseId } ?: return@forEach
                enrichedReleaseIds += releaseId
                var enriched = DiscogsVersionSource.enrichSeedMetadata(
                    token = discogsToken,
                    seed = seed,
                    targetTitle = seed.trackTitle,
                    mode = mode,
                    originalArtist = lockedArtist,
                )
                if (session.originalWorkCredits.isNotEmpty()) {
                    enriched = DiscogsVersionSource.applySharedWorkCreditEvidence(
                        seed = enriched,
                        originalCredits = session.originalWorkCredits,
                    )
                }

                val resolvedTrack = enriched.track
                if (resolvedTrack != null && enriched.resolvedVideoId.isNullOrBlank()) {
                    val resolved = CompilationTrackResolver.resolveTrack(
                        track = resolvedTrack,
                        discogsVideos = enriched.videos,
                        fastFirst = true,
                        excludedVideoIds = session.usedVideoIds.toSet(),
                    )
                    if (resolved != null && session.usedVideoIds.add(resolved.song.id)) {
                        enriched = DiscogsVersionSource.markVideoResolved(
                            seed = enriched,
                            videoId = resolved.song.id,
                            videoTitle = resolved.song.title,
                            source = resolved.source,
                        )
                    }
                }

                val replaced = results.map { current ->
                    if (current.releaseId == releaseId) enriched else current
                }
                results = mergePage(emptyList(), replaced, replace = true)
                session.results = results
                delay(1_100)
            }
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
                        text = if (mode == DiscogsDirectMode.COVER) "Cover · Discogs" else "Originali · Discogs",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { navController.popBackStack() }) {
                        Text("Chiudi")
                    }
                }

                if (mode == DiscogsDirectMode.ORIGINAL) {
                    Text(
                        text = "Artista fisso: $lockedArtist",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else if (lockedArtist.isNotBlank()) {
                    Text(
                        text = "Escludo l'interprete originale: $lockedArtist",
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
                            scope.launch { listState.scrollToItem(0) }
                        },
                    )
                }

                DirectSortSelector(
                    selected = sortMode,
                    onSelected = { selected ->
                        if (selected != sortMode) {
                            runSearch(selected)
                        }
                    },
                )

                Button(
                    onClick = { runSearch() },
                    enabled = !loading && discogsToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (loading) "Ricerca Discogs…" else "Cerca su Discogs")
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                if (activeCriteria != null && currentPage > 0) {
                    Text(
                        text = "Versioni uniche caricate: ${results.size} · Release Discogs: $totalDiscogsResults · Pagina $currentPage/$totalPages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (foreignScoutLoading) {
                        Text(
                            "Cerco anche adattamenti e versioni in altre lingue…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                        "Cerca direttamente nel database Discogs. Nessuna selezione AI.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            if (!loading && activeCriteria != null && visibleResults.isEmpty() && currentPage >= totalPages && currentPage > 0) {
                item(key = "discogs_direct_no_results_${mode.name}") {
                    Text(
                        "Nessuna versione Discogs trovata con questi filtri.",
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
                    "discogs_direct_${mode.name}_${index}_${seed.releaseId}_${seed.fingerprint.hashCode()}"
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

            if (loadingMore || (activeCriteria != null && currentPage > 0 && currentPage < totalPages)) {
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
                            Text("Carico altre schede Discogs…")
                        } else {
                            TextButton(onClick = ::loadNextPage) {
                                Text("Carica altre schede")
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
                !loading &&
                !loadingMore
            ) {
                item(key = "discogs_direct_end_${mode.name}") {
                    Text(
                        "Tutte le pagine Discogs disponibili sono state caricate.",
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
            onDismiss = { detailSeed = null },
        )
    }

}

@Composable
private fun DiscogsVersionDetailsDialog(
    seed: DiscogsVersionSeed,
    onDismiss: () -> Unit,
) {
    val creditsText =
        if (seed.credits.isEmpty()) {
            "Crediti: non disponibili"
        } else {
            seed.credits
                .groupBy { it.role }
                .entries
                .joinToString("\n") { (role, credits) ->
                    "$role: " + credits.joinToString(", ") { it.name }
                }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Chiudi")
            }
        },
        title = { Text("Dettagli versione") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Titolo: ${seed.trackTitle}")
                Text("Interprete: ${seed.artist}")
                Text("Pubblicazione: ${seed.releaseTitle}")
                Text("Tipo: ${seed.kind.name.lowercase()}")
                Text("Affidabilità: ${seed.confidenceScore}/10")
                Text("Data: ${seed.displayDate ?: "non disponibile"}")
                Text("Paese: ${seed.country ?: "non disponibile"}")
                Text("Formato: ${seed.formats.joinToString().ifBlank { "non disponibile" }}")
                Text("Etichetta: ${seed.labels.joinToString().ifBlank { "non disponibile" }}")
                Text("Lingua/adattamento: ${seed.language ?: "non disponibile"}")
                Text("Discogs Release: ${seed.releaseId}")
                Text("Discogs Master: ${seed.masterId ?: "non disponibile"}")
                Text("Video: ${seed.resolvedVideoTitle ?: "in verifica / non disponibile"}")
                Text("Fonte video: ${seed.resolvedVideoSource ?: "non disponibile"}")
                Text(creditsText)
                if (seed.confidenceReasons.isNotEmpty()) {
                    Text("Motivi punteggio: " + seed.confidenceReasons.joinToString(" · "))
                }
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
    val studio = results.count { it.kind == DiscogsVersionKind.STUDIO || it.kind == DiscogsVersionKind.ACOUSTIC }
    val live = results.count { it.kind == DiscogsVersionKind.LIVE }
    val remix = results.count { it.kind == DiscogsVersionKind.REMIX }

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
                            if (seed.resolvedVideoId.isNullOrBlank()) "Video in verifica…" else "Tocca per riprodurre",
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
