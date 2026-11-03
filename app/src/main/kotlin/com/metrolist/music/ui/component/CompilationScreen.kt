package com.metrolist.music.ui.component

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.constants.DiscogsTokenKey
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.discogs.CompilationFullAudio
import com.metrolist.music.discogs.CompilationLanguageHeuristics
import com.metrolist.music.discogs.CompilationResolvedTrack
import com.metrolist.music.discogs.CompilationTrackResolver
import com.metrolist.music.discogs.CompilationTrackMatchPolicy
import com.metrolist.music.discogs.DiscogsClient
import com.metrolist.music.discogs.DiscogsCompilationDetail
import com.metrolist.music.discogs.DiscogsCompilationSummary
import com.metrolist.music.discogs.DiscogsSearchMode
import com.metrolist.music.discogs.DiscogsReleaseCategory
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.MusicLabSectionWorkPolicy
import com.metrolist.music.playback.MusicLabSectionWorkCheckpoint
import com.metrolist.music.playback.MusicLabSectionPlayback
import com.metrolist.music.playback.MusicLabListPositionStore
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.Year

private data class CompilationTokenState(val loaded: Boolean = false, val token: String = "")

internal data class CompilationSearchCriteria(
    val query: String,
    val year: Int?,
    val genre: String?,
    val style: String?,
    val country: String?,
    val language: String?,
    val trackTitle: String? = null,
    val trackArtist: String? = null,
    val mode: DiscogsSearchMode = DiscogsSearchMode.COMPILATION,
    val lookupMode: CompilationLookupMode = CompilationLookupMode.TITLE_ARTIST,
)

private object CompilationSessionStore {
    var query: String = ""
    var year: Int? = null
    var genre: String? = null
    var style: String? = null
    var country: String? = null
    var language: String? = null
    var mode: DiscogsSearchMode = DiscogsSearchMode.COMPILATION
    var lookupMode: CompilationLookupMode = CompilationLookupMode.TITLE_ARTIST

    var results: List<DiscogsCompilationSummary> = emptyList()
    var currentPage: Int = 0
    var totalPages: Int = 0
    var totalDiscogsResults: Int = 0
    var activeSearch: CompilationSearchCriteria? = null

    var detail: DiscogsCompilationDetail? = null
    var fullAudio: CompilationFullAudio? = null
    val resolvedTracks: MutableMap<Int, CompilationResolvedTrack> = linkedMapOf()

    var listIndex: Int = 0
    var listOffset: Int = 0
    var filtersExpanded: Boolean = true
    var selectedTrackIndex: Int? = null
    var selectedReleaseId: Int? = null

    fun resetForNewSearch() {
        activeSearch = null
        results = emptyList()
        currentPage = 0
        totalPages = 0
        totalDiscogsResults = 0
        detail = null
        fullAudio = null
        resolvedTracks.clear()
        listIndex = 0
        listOffset = 0
        selectedTrackIndex = null
    }
}

@Composable
internal fun CompilationScreen(
    navController: NavHostController,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val playerConnection = LocalPlayerConnection.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    val restored = remember(context) {
        MusicLabListPositionStore.attach(context)
        MusicLabCompilationSnapshot.load(context).also { saved ->
            if (CompilationSessionStore.results.isEmpty() &&
                CompilationSessionStore.activeSearch == null) {
                CompilationSessionStore.query = saved.query
                CompilationSessionStore.year = saved.year
                CompilationSessionStore.genre = saved.genre
                CompilationSessionStore.style = saved.style
                CompilationSessionStore.country = saved.country
                CompilationSessionStore.language = saved.language
                CompilationSessionStore.mode = saved.mode
                CompilationSessionStore.lookupMode = saved.lookupMode
                CompilationSessionStore.results = saved.results
                CompilationSessionStore.currentPage = saved.page
                CompilationSessionStore.totalPages = saved.pages
                CompilationSessionStore.totalDiscogsResults = saved.total
                CompilationSessionStore.filtersExpanded = saved.filtersExpanded
                CompilationSessionStore.selectedReleaseId = saved.selectedRelease
                CompilationSessionStore.selectedTrackIndex = saved.selectedTrack
                if (saved.page > 0 && saved.results.isNotEmpty()) {
                    CompilationSessionStore.activeSearch = CompilationSearchCriteria(
                        saved.query, saved.year, saved.genre, saved.style,
                        saved.country, saved.language,
                        trackTitle = saved.trackTitle,
                        trackArtist = saved.trackArtist,
                        mode = saved.mode,
                        lookupMode = saved.lookupMode)
                }
            }
        }
    }
    // Null (loading) and a genuinely absent token must not be confused.
    val tokenFlow = remember(context) {
        context.dataStore.data.map { CompilationTokenState(true, it[DiscogsTokenKey].orEmpty().trim()) }
    }
    val tokenState by tokenFlow.collectAsStateWithLifecycle(initialValue = CompilationTokenState())
    val discogsToken = tokenState.token

    var query by remember { mutableStateOf(CompilationSessionStore.query) }
    var trackRequest by remember { mutableStateOf<CompilationTrackRequest?>(null) }
    val pendingPlayerRequest by CompilationNavigationBridge.requests.collectAsStateWithLifecycle()
    LaunchedEffect(pendingPlayerRequest?.requestId) {
        if (pendingPlayerRequest != null)
            trackRequest = CompilationNavigationBridge.takePendingTrack()
    }
    var year by remember { mutableStateOf(CompilationSessionStore.year) }
    var genre by remember { mutableStateOf(CompilationSessionStore.genre) }
    var style by remember { mutableStateOf(CompilationSessionStore.style) }
    var country by remember { mutableStateOf(CompilationSessionStore.country) }
    var language by remember { mutableStateOf(CompilationSessionStore.language) }
    var mode by remember { mutableStateOf(CompilationSessionStore.mode) }
    var lookupMode by remember { mutableStateOf(CompilationSessionStore.lookupMode) }
    var sourceTitle by remember { mutableStateOf(restored.originalTitle) }
    var sourceArtist by remember { mutableStateOf(restored.originalArtist) }
    var searchModesOpen by remember { mutableStateOf(false) }
    var releaseTypesOpen by remember { mutableStateOf(false) }

    var results by remember { mutableStateOf(CompilationSessionStore.results) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var paginationError by remember { mutableStateOf<String?>(null) }
    var currentPage by remember { mutableStateOf(CompilationSessionStore.currentPage) }
    var totalPages by remember { mutableStateOf(CompilationSessionStore.totalPages) }
    var totalDiscogsResults by remember { mutableStateOf(CompilationSessionStore.totalDiscogsResults) }
    var activeSearch by remember { mutableStateOf(CompilationSessionStore.activeSearch) }

    var detail by remember { mutableStateOf(CompilationSessionStore.detail) }
    var detailLoading by remember { mutableStateOf(false) }
    var fullAudio by remember { mutableStateOf(CompilationSessionStore.fullAudio) }
    val resolvedTracks = remember {
        mutableStateMapOf<Int, CompilationResolvedTrack>().apply {
            putAll(CompilationSessionStore.resolvedTracks)
        }
    }
    // Same visible-row FastPath prewarm as Hit Parade. Reserve native search
    // capacity for the user's next tap instead of flooding all 32 tracks.
    val visibleWarmGate = remember { Semaphore(MusicLabFastPathPolicy.COMPILATION_VISIBLE_WARM_PARALLELISM) }
    val savedPosition = remember(context) {
        MusicLabListPositionStore.get("compilation:results")
    }
    val resultListState = remember {
        LazyListState(savedPosition.index, savedPosition.offset)
    }
    var saving by remember { mutableStateOf(false) }
    var filtersExpanded by remember { mutableStateOf(CompilationSessionStore.filtersExpanded) }
    var selectedTrackIndex by remember { mutableStateOf(CompilationSessionStore.selectedTrackIndex) }
    // A newly searched song must stay highlighted even if an older
    // playback snapshot points to a different song in the same album.
    var awaitingSongSelection by remember {
        mutableStateOf(restored.trackTitle != null && restored.openRelease != null)
    }
    val currentSectionContext by MusicLabSectionPlayback.active.collectAsStateWithLifecycle()
    val selectedKeys by MusicLabListPositionStore.selectedKeys.collectAsStateWithLifecycle()
    LaunchedEffect(currentSectionContext?.index, currentSectionContext?.revision,
        detail?.id, awaitingSongSelection) {
        if (awaitingSongSelection) return@LaunchedEffect
        val state = currentSectionContext ?: return@LaunchedEffect
        val currentDetail = detail ?: return@LaunchedEffect
        if (state.selectionKey == "compilation:detail:" + currentDetail.id &&
            state.index in currentDetail.tracks.indices) {
            selectedTrackIndex = state.index
            CompilationSessionStore.selectedTrackIndex = state.index
        }
    }
    var selectedReleaseId by remember { mutableStateOf(CompilationSessionStore.selectedReleaseId) }
    var openReleaseId by remember { mutableStateOf(restored.openRelease) }
    var paginationJob by remember { mutableStateOf<Job?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var fullAudioLookupJob by remember { mutableStateOf<Job?>(null) }
    var nextTrackWarmJob by remember { mutableStateOf<Job?>(null) }
    var fastTrackPlayJob by remember { mutableStateOf<Job?>(null) }
    var searchGeneration by remember { mutableStateOf(0L) }
    var activeSearchToken by remember { mutableStateOf("") }
    var historyOpen by remember { mutableStateOf(false) }
    var searchHistory by remember { mutableStateOf(emptyList<CompilationSearchCriteria>()) }

    LaunchedEffect(Unit) {
        searchHistory = withContext(Dispatchers.IO) { CompilationSearchHistoryStore.load(context) }
    }

    LaunchedEffect(query, year, genre, style, country, language, mode, lookupMode) {
        CompilationSessionStore.query = query
        CompilationSessionStore.year = year
        CompilationSessionStore.genre = genre
        CompilationSessionStore.style = style
        CompilationSessionStore.country = country
        CompilationSessionStore.language = language
        CompilationSessionStore.mode = mode
        CompilationSessionStore.lookupMode = lookupMode
        CompilationSessionStore.filtersExpanded = filtersExpanded
    }

    LaunchedEffect(resultListState) {
        snapshotFlow {
            resultListState.firstVisibleItemIndex to resultListState.firstVisibleItemScrollOffset
        }.collectLatest { (index, offset) ->
            CompilationSessionStore.listIndex = index
            CompilationSessionStore.listOffset = offset
            MusicLabListPositionStore.scroll("compilation:results", index, offset)
        }
    }
    val latestCompilationSnapshot = rememberUpdatedState(MusicLabCompilationSnapshot.Saved(
        query = query, year = year, genre = genre, style = style,
        country = country, language = language, filtersExpanded = filtersExpanded, mode = mode,
        lookupMode = lookupMode, originalTitle = sourceTitle, originalArtist = sourceArtist,
        trackTitle = activeSearch?.trackTitle, trackArtist = activeSearch?.trackArtist,
        results = results, page = currentPage, pages = totalPages,
        total = totalDiscogsResults, selectedRelease = selectedReleaseId,
        selectedTrack = selectedTrackIndex, openRelease = openReleaseId,
    ))
    val latestOpenCompilation = rememberUpdatedState(detail)
    DisposableEffect(resultListState) {
        onDispose {
            MusicLabListPositionStore.scroll("compilation:results",
                resultListState.firstVisibleItemIndex,
                resultListState.firstVisibleItemScrollOffset)
            MusicLabListPositionStore.flush("compilation:results")
            // Read the latest committed Compose state, not the initial lambda
            // closure from the first composition. A quick tab switch must
            // never roll back the user's latest search and page.
            MusicLabCompilationSnapshot.save(context, latestCompilationSnapshot.value)
            latestOpenCompilation.value?.let { current ->
                MusicLabCompilationResolvedCache.save(context, current, resolvedTracks)
                if (resolvedTracks.size < current.tracks.size)
                    MusicLabSectionWorkCheckpoint.standby(context, "compilation:tracks",
                        current.id.toString())
            }
        }
    }
    LaunchedEffect(query, year, genre, style, country, language, mode, lookupMode,
        sourceTitle, sourceArtist, filtersExpanded,
        activeSearch, results, currentPage, totalPages, totalDiscogsResults, selectedReleaseId,
        selectedTrackIndex, openReleaseId) {
        // Coalesce edits and list pagination, never persist the Discogs token.
        delay(350)
        MusicLabCompilationSnapshot.save(context, MusicLabCompilationSnapshot.Saved(
            query = query, year = year, genre = genre, style = style,
            country = country, language = language, filtersExpanded = filtersExpanded, mode = mode,
            lookupMode = lookupMode, originalTitle = sourceTitle, originalArtist = sourceArtist,
            trackTitle = activeSearch?.trackTitle, trackArtist = activeSearch?.trackArtist,
            results = results, page = currentPage, pages = totalPages,
            total = totalDiscogsResults, selectedRelease = selectedReleaseId,
            selectedTrack = selectedTrackIndex, openRelease = openReleaseId,
        ))
    }
    LaunchedEffect(openReleaseId, tokenState.loaded, discogsToken) {
        val id = openReleaseId ?: return@LaunchedEffect
        if (!tokenState.loaded || discogsToken.isBlank() || detail != null) return@LaunchedEffect
        detailLoading = true
        DiscogsClient.getRelease(discogsToken, id)
            .onSuccess { if (openReleaseId == id) {
                detail = it
                CompilationSessionStore.detail = it
                MusicLabCompilationCoverBridge.remember(context, it.id, it.coverUrl)
                if (selectedTrackIndex == null) {
                    val wanted = activeSearch
                    selectedTrackIndex = it.tracks.indexOfFirst { track ->
                        wanted?.trackTitle != null && CompilationLightSearchPolicy.accepts(
                            track, wanted.trackTitle, wanted.trackArtist.orEmpty(), wanted.lookupMode)
                    }.takeIf { it >= 0 }
                    CompilationSessionStore.selectedTrackIndex = selectedTrackIndex
                }
            } }
            .onFailure { if (openReleaseId == id) {
                error = it.message ?: "Impossibile riaprire questa compilation."
            } }
        detailLoading = false
    }

    suspend fun filterByLanguage(
        items: List<DiscogsCompilationSummary>,
        requestedLanguage: String?,
        token: String,
        generation: Long,
    ): List<DiscogsCompilationSummary> {
        if (requestedLanguage == null || items.isEmpty()) return items
        return withContext(Dispatchers.IO) {
            items.filter { summary ->
                if (generation != searchGeneration) return@filter false
                val candidate = DiscogsClient.getRelease(token, summary.id).getOrNull()
                candidate == null ||
                    CompilationLanguageHeuristics.matchesOrUnknown(requestedLanguage, candidate.tracks)
            }
        }
    }

    // Verify the actual release tracklist before showing it as containing the song.
    // Only the Player -> Compilation route needs tracklist confirmation.
    // Generic/manual R39 search remains broad, without this additional network check.
    /**
     * R52 title-only search must be backed by an actual Discogs tracklist.
     * Fetch lightweight release data only for candidates of the indexed
     * Discogs "track" search. Never accept a release just because its album
     * title contains the song title; never infer unverified chart metadata.
     */
    suspend fun verifyIndexedTrackCandidates(
        candidates: List<DiscogsCompilationSummary>,
        criteria: CompilationSearchCriteria,
        token: String,
        generation: Long,
        onVerified: (DiscogsCompilationSummary) -> Unit,
    ): List<DiscogsCompilationSummary> = coroutineScope {
        val song = criteria.trackTitle ?: return@coroutineScope candidates
        // R55: small bounded concurrency; Discogs still owns its HTTP rate limits.
        // Reveal each real tracklist match immediately instead of waiting for the page.
        val permits = Semaphore(3)
        val matchedIds = mutableSetOf<Int>()
        val jobs = candidates.map { candidate ->
            launch {
                permits.withPermit {
                    if (generation != searchGeneration) return@withPermit
                    // Starting audio pauses lower-priority release inspections.
                    while (playerConnection?.isPlaybackPriorityBurstActive() == true) {
                        delay(120L)
                        if (generation != searchGeneration) return@withPermit
                    }
                    val release = DiscogsClient.getRelease(token, candidate.id,
                        includeMasterVideos = false).getOrNull() ?: return@withPermit
                    if (generation != searchGeneration) return@withPermit
                    val formatLabels = release.formatDescriptions.ifEmpty { candidate.formats }
                    if (!criteria.mode.accepts(formatLabels)) return@withPermit
                    if (release.tracks.none { track ->
                            CompilationLightSearchPolicy.accepts(
                                track, song, criteria.trackArtist.orEmpty(), criteria.lookupMode)
                        }) return@withPermit
                    if (criteria.language != null &&
                        !CompilationLanguageHeuristics.matchesOrUnknown(
                            criteria.language, release.tracks)) return@withPermit
                    matchedIds.add(candidate.id)
                    onVerified(candidate)
                }
            }
        }
        jobs.forEach { it.join() }
        candidates.filter { it.id in matchedIds }.distinctBy { it.id }
    }

    // Results are painted progressively as each real Discogs release is checked.
    suspend fun loadPage(
        criteria: CompilationSearchCriteria,
        page: Int,
        replaceResults: Boolean,
        token: String,
        generation: Long,
    ): Boolean {
        if (generation != searchGeneration) return false
        val response = DiscogsClient.searchCompilations(
            token = token,
            // R39: Restore R36's broad Discogs 'q' search. Never use the
            // narrow Discogs 'track' parameter supplied by R37/R38.
            query = criteria.query,
            year = criteria.year,
            genre = criteria.genre,
            style = criteria.style,
            country = criteria.country,
            page = page,
            perPage = if (criteria.trackTitle != null) 12
                else if (criteria.language == null) 100 else 25,
            trackTitle = criteria.trackTitle,
            mode = criteria.mode,
            includeSingles = false, // All lookup strategies honor the selected publication formats.
        )

        if (generation != searchGeneration) return false
        val pageResult = response.getOrElse {
            if (replaceResults) {
                error = it.message ?: "Errore durante la ricerca Discogs."
            } else {
                paginationError = it.message ?: "Errore nel caricamento della pagina successiva."
            }
            return false
        }

        // Save the cursor as soon as Discogs returns it; verification may continue.
        currentPage = pageResult.page
        totalPages = pageResult.pages
        totalDiscogsResults = pageResult.totalItems
        val visibleItems = if (criteria.trackTitle != null)
            verifyIndexedTrackCandidates(pageResult.items, criteria, token, generation) { confirmed ->
                if (generation == searchGeneration) {
                    results = (results + confirmed).distinctBy { it.id }
                    CompilationSessionStore.results = results
                    CompilationSessionStore.currentPage = currentPage
                    CompilationSessionStore.totalPages = totalPages
                    CompilationSessionStore.totalDiscogsResults = totalDiscogsResults
                }
            }
        else filterByLanguage(pageResult.items, criteria.language, token, generation)
        if (generation != searchGeneration) return false
        // R36 behavior: show all releases returned by Discogs. No mandatory
        // tracklist/artist matching; keep the R38 stale-request generation gate.
        if (generation != searchGeneration) return false
        results = if (replaceResults && criteria.trackTitle == null) {
            visibleItems
        } else {
            (results + visibleItems).distinctBy { it.id }
        }
        currentPage = pageResult.page
        totalPages = pageResult.pages
        totalDiscogsResults = pageResult.totalItems

        CompilationSessionStore.results = results
        CompilationSessionStore.currentPage = currentPage
        CompilationSessionStore.totalPages = totalPages
        CompilationSessionStore.totalDiscogsResults = totalDiscogsResults
        CompilationSessionStore.activeSearch = criteria

        paginationError = null
        return true
    }

    fun searchIdentity(criteria: CompilationSearchCriteria): String =
        listOf(criteria.query, criteria.year?.toString().orEmpty(),
            criteria.genre.orEmpty(), criteria.style.orEmpty(),
            criteria.country.orEmpty(), criteria.language.orEmpty(),
            criteria.mode.name, criteria.lookupMode.name, criteria.trackTitle.orEmpty(),
            criteria.trackArtist.orEmpty()).joinToString("|")

    fun runSearch(requestedCriteria: CompilationSearchCriteria? = null,
                  resumeInterrupted: Boolean = false) {
        val criteria = requestedCriteria ?: CompilationSearchCriteria(
            query = query.trim(),
            year = year,
            genre = genre,
            style = style,
            country = country,
            language = language,
            mode = mode,
            // All three strategies use Discogs indexed track titles, NEVER
            // matches against the album / compilation release title.
            trackTitle = CompilationLightSearchPolicy.indexTitle(
                sourceTitle.ifBlank { query }, sourceArtist),
            trackArtist = sourceArtist.takeIf(String::isNotBlank),
            lookupMode = lookupMode,
        )

        val workKey = searchIdentity(criteria)
        if (!resumeInterrupted)
            MusicLabSectionWorkCheckpoint.reset(context, "compilation:search", workKey)
        MusicLabSectionWorkCheckpoint.advance(context, "compilation:search", workKey, 0, 1)
        // Invalidate and remove the old list BEFORE reading credentials. Neither
        // a missing credential nor a late response may keep the old search alive.
        searchGeneration += 1
        val generation = searchGeneration
        paginationJob?.cancel()
        searchJob?.cancel()
        fullAudioLookupJob?.cancel()
        nextTrackWarmJob?.cancel()
        loading = true
        loadingMore = false
        error = null
        paginationError = null
        detail = null
        openReleaseId = null
        fullAudio = null
        resolvedTracks.clear()
        CompilationSessionStore.resetForNewSearch()
        activeSearchToken = ""
        results = emptyList()
        currentPage = 0
        totalPages = 0
        totalDiscogsResults = 0
        activeSearch = null
        searchJob = scope.launch {
            try {
                // Read the persisted value, not the initial empty Compose placeholder.
                val token = withContext(Dispatchers.IO) {
                    context.dataStore.data.first()[DiscogsTokenKey].orEmpty().trim()
                }
                if (generation != searchGeneration) return@launch
                if (token.isBlank()) {
                    error = "Token Discogs non salvato. Apri Token e API nelle impostazioni."
                    return@launch
                }
                activeSearchToken = token
                activeSearch = criteria
                CompilationSessionStore.activeSearch = criteria
                resultListState.scrollToItem(0)
                var succeeded = loadPage(criteria, page = 1, replaceResults = true,
                    token = token, generation = generation)
                // R54: all searches use only candidate releases with a matched
                // tracklist row. Cover is deliberately lightweight / unverified.
                // User controls the exact source query through Cerca,
                // or chooses the Discogs track index through Titolo.
                if (!succeeded && generation == searchGeneration) {
                    activeSearch = null
                    CompilationSessionStore.activeSearch = null
                    MusicLabSectionWorkCheckpoint.standby(context, "compilation:search", workKey)
                } else if (succeeded && generation == searchGeneration) {
                    MusicLabSectionWorkCheckpoint.complete(context, "compilation:search",
                        workKey, results.size)
                }
                if (succeeded && generation == searchGeneration) {
                    val updated = withContext(Dispatchers.IO) {
                        CompilationSearchHistoryStore.save(context, activeSearch ?: criteria)
                        CompilationSearchHistoryStore.load(context)
                    }
                    if (generation == searchGeneration) searchHistory = updated
                }
            } catch (cancel: CancellationException) {
                MusicLabSectionWorkCheckpoint.standby(context, "compilation:search", workKey)
                throw cancel
            } catch (failure: Exception) {
                MusicLabSectionWorkCheckpoint.standby(context, "compilation:search", workKey)
                if (generation == searchGeneration)
                    error = failure.message ?: "Impossibile caricare le compilation."
            } finally {
                if (generation == searchGeneration) loading = false
            }
        }
    }

    // R54: first player poster search uses ONLY canonical locandina metadata.
    // No YouTube video title, no new player side effects and no hidden mode hop.
    LaunchedEffect(trackRequest?.requestId) {
        val target = trackRequest ?: return@LaunchedEffect
        sourceTitle = target.title
        sourceArtist = target.artist
        lookupMode = CompilationLookupMode.TITLE_ARTIST
        val title = CompilationLightSearchPolicy.indexTitle(target.title, target.artist)
        val visible = listOf(target.title, target.artist)
            .filter(String::isNotBlank).joinToString(" - ")
        query = visible
        year = null
        genre = null
        style = null
        country = null
        language = null
        mode = DiscogsSearchMode.COMPILATION
        runSearch(CompilationSearchCriteria(
            query = visible, year = null, genre = null, style = null,
            country = null, language = null, mode = DiscogsSearchMode.COMPILATION,
            trackTitle = title, trackArtist = target.artist.takeIf(String::isNotBlank),
            lookupMode = CompilationLookupMode.TITLE_ARTIST,
        ))
    }

    fun loadNextPage() {
        val criteria = activeSearch ?: return
        if (loading || loadingMore || currentPage <= 0 || currentPage >= totalPages) return

        val next = currentPage + 1
        val pageKey = searchIdentity(criteria) + "|page:$next"
        MusicLabSectionWorkCheckpoint.advance(context, "compilation:pagination", pageKey,
            currentPage, next)
        loadingMore = true
        paginationError = null
        paginationJob?.cancel()
        paginationJob = scope.launch {
            try {
                if (loadPage(
                    criteria = criteria,
                    page = next,
                    replaceResults = false,
                    token = activeSearchToken,
                    generation = searchGeneration,
                )) {
                    MusicLabSectionWorkCheckpoint.complete(context, "compilation:pagination",
                        pageKey, results.size)
                } else MusicLabSectionWorkCheckpoint.standby(context,
                    "compilation:pagination", pageKey)
            } catch (cancel: CancellationException) {
                MusicLabSectionWorkCheckpoint.standby(context, "compilation:pagination", pageKey)
                throw cancel
            } finally {
                loadingMore = false
            }
        }
    }

    // A page interrupted by a screen switch resumes once, using the saved
    // filters and cursor. Completed pages stay cached and do not restart.
    LaunchedEffect(tokenState.loaded, discogsToken, trackRequest?.requestId) {
        // A just-arrived poster title deliberately waits for the two buttons.
        if (trackRequest != null) return@LaunchedEffect
        if (!tokenState.loaded || discogsToken.isBlank()) return@LaunchedEffect
        val criteria = activeSearch
        if (criteria != null && currentPage > 0) {
            activeSearchToken = discogsToken
            val pageKey = searchIdentity(criteria) + "|page:${currentPage + 1}"
            if (MusicLabSectionWorkCheckpoint.read(context,
                    "compilation:pagination", pageKey).phase !=
                MusicLabSectionWorkCheckpoint.Phase.COMPLETE &&
                MusicLabSectionWorkCheckpoint.read(context,
                    "compilation:pagination", pageKey).total > 0) {
                loadNextPage()
            }
        } else if (query.isNotBlank() && results.isEmpty() && !loading) {
            val restoreCriteria = CompilationSearchCriteria(
                query.trim(), year, genre, style, country, language,
                trackTitle = restored.trackTitle,
                trackArtist = restored.trackArtist,
                mode = mode,
                lookupMode = restored.lookupMode)
            val workKey = searchIdentity(restoreCriteria)
            val saved = MusicLabSectionWorkCheckpoint.read(context,
                "compilation:search", workKey)
            if (MusicLabSectionWorkPolicy.shouldResumeInterrupted(
                    saved.phase, saved.total > 0, results.isNotEmpty())) {
                runSearch(restoreCriteria, resumeInterrupted = true)
            }
        }
    }

    LaunchedEffect(resultListState, activeSearch, currentPage, totalPages, results.size) {
        snapshotFlow {
            resultListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collectLatest { lastVisible ->
            if (
                activeSearch != null &&
                currentPage > 0 &&
                currentPage < totalPages &&
                results.isNotEmpty() &&
                lastVisible >= 0 &&
                results.size - lastVisible <= 12
            ) {
                loadNextPage()
            }
        }
    }

    fun openCompilation(summary: DiscogsCompilationSummary) {
        // Preselection is visual, never a playback event.
        awaitingSongSelection = activeSearch?.trackTitle != null
        // The selected poster and its scroll position survive the detail screen.
        selectedReleaseId = summary.id
        openReleaseId = summary.id
        CompilationSessionStore.selectedReleaseId = summary.id
        CompilationSessionStore.listIndex = resultListState.firstVisibleItemIndex
        CompilationSessionStore.listOffset = resultListState.firstVisibleItemScrollOffset
        paginationJob?.cancel()
        loadingMore = false
        selectedTrackIndex = null
        CompilationSessionStore.selectedTrackIndex = null
        detailLoading = true
        error = null
        fullAudio = null
        resolvedTracks.clear()
        CompilationSessionStore.resolvedTracks.clear()
        scope.launch {
            MusicLabCompilationCoverBridge.remember(context, summary.id,
                summary.coverUrl ?: summary.thumbnailUrl)
            DiscogsClient.getRelease(discogsToken, summary.id)
                .onSuccess { loaded ->
                    // Original Discogs release sleeve wins over any video poster.
                    MusicLabCompilationCoverBridge.remember(context, loaded.id,
                        loaded.coverUrl ?: summary.coverUrl ?: summary.thumbnailUrl)
                    detail = loaded
                    CompilationSessionStore.detail = loaded
                    val wanted = activeSearch
                    selectedTrackIndex = loaded.tracks.indexOfFirst { track ->
                        wanted?.trackTitle != null && CompilationLightSearchPolicy.accepts(
                            track, wanted.trackTitle, wanted.trackArtist.orEmpty(), wanted.lookupMode)
                    }.takeIf { it >= 0 }
                    CompilationSessionStore.selectedTrackIndex = selectedTrackIndex
                    detailLoading = false
                }
                .onFailure {
                    error = it.message ?: "Impossibile aprire la compilation."
                    detailLoading = false
                }
        }
    }

    fun scheduleFullAudioLookup(
        current: DiscogsCompilationDetail,
        delayMs: Long = 1_500L,
    ) {
        if (fullAudio != null) return
        fullAudioLookupJob?.cancel()
        fullAudioLookupJob = scope.launch {
            delay(delayMs)
            while (playerConnection?.isPlaybackPriorityBurstActive() == true) {
                delay(180)
            }
            val found = CompilationTrackResolver.findFullAudio(current)
            if (detail?.id == current.id) {
                fullAudio = found
                CompilationSessionStore.fullAudio = found
            }
        }
    }

    LaunchedEffect(detail?.id) {
        val current = detail ?: return@LaunchedEffect
        if (fullAudio == null) scheduleFullAudioLookup(current)
    }

    suspend fun resolveTrack(index: Int, current: DiscogsCompilationDetail): CompilationResolvedTrack? {
        resolvedTracks[index]?.let { return it }
        val track = current.tracks.getOrNull(index) ?: return null
        // R36: reuse the exact native search-result object before invoking the
        // deeper Discogs/video fallback. Cover search remains untouched.
        val fast = MusicLabFastPath.find(
            track.title, track.artists.joinToString(" ")
        ) { candidate -> CompilationTrackResolver.isHardCompatible(track, candidate) }
        val found = fast?.let {
            CompilationResolvedTrack(track, it, "MusicLab FastPath", false)
        } ?: CompilationTrackResolver.resolveTrack(track, current.videos)
        if (found != null && detail?.id == current.id) {
            resolvedTracks[index] = found
            CompilationSessionStore.resolvedTracks[index] = found
            MusicLabCompilationResolvedCache.save(context, current, resolvedTracks)
            MusicLabSectionWorkCheckpoint.advance(context, "compilation:tracks",
                current.id.toString(), resolvedTracks.size, current.tracks.size)
            if (resolvedTracks.size >= current.tracks.size)
                MusicLabSectionWorkCheckpoint.complete(context, "compilation:tracks",
                    current.id.toString(), resolvedTracks.size)
        }
        return found
    }

    // Restore previously verified SongItems, including after Android process
    // death. No audio is started; wrong-album indices cannot be restored.
    LaunchedEffect(detail?.id) {
        val current = detail ?: return@LaunchedEffect
        val cached = withContext(Dispatchers.IO) {
            MusicLabCompilationResolvedCache.load(context, current)
        }
        if (detail?.id == current.id) {
            cached.forEach { (index, entry) ->
                if (resolvedTracks[index] == null) {
                    resolvedTracks[index] = entry
                    CompilationSessionStore.resolvedTracks[index] = entry
                }
            }
        }
    }

    // Exactly as in Hit Parade: prepare only the rows entering the viewport,
    // share native MusicLab FastPath results, and never start playback here.
    // No slow Discogs/YouTube fallback runs during a passive scroll; the
    // already-existing resolveTrack() performs that only on explicit selection.
    suspend fun warmVisibleTrack(index: Int, current: DiscogsCompilationDetail) {
        if (detail?.id != current.id || resolvedTracks.containsKey(index)) return
        val track = current.tracks.getOrNull(index) ?: return
        visibleWarmGate.withPermit {
            while (detail?.id == current.id &&
                playerConnection?.isPlaybackPriorityBurstActive() == true) {
                delay(160L)
            }
            if (detail?.id != current.id || resolvedTracks.containsKey(index)) return@withPermit
            val song = MusicLabFastPath.find(
                track.title, track.artists.joinToString(" ")
            ) { candidate -> CompilationTrackResolver.isHardCompatible(track, candidate) }
            if (song != null && detail?.id == current.id &&
                !resolvedTracks.containsKey(index)) {
                val found = CompilationResolvedTrack(track, song, "MusicLab FastPath", false)
                resolvedTracks[index] = found
                CompilationSessionStore.resolvedTracks[index] = found
                MusicLabCompilationResolvedCache.save(context, current, resolvedTracks)
                MusicLabSectionWorkCheckpoint.advance(context, "compilation:tracks",
                    current.id.toString(), resolvedTracks.size, current.tracks.size)
                if (resolvedTracks.size >= current.tracks.size)
                    MusicLabSectionWorkCheckpoint.complete(context, "compilation:tracks",
                        current.id.toString(), resolvedTracks.size)
            }
        }
    }

    // Keep the original first-two head start even before the first rows
    // become visible. All other tracks warm only as the user scrolls.
    LaunchedEffect(detail?.id) {
        val current = detail ?: return@LaunchedEffect
        delay(180L)
        for (index in 0 until minOf(MusicLabFastPathPolicy.COMPILATION_WARM_TRACKS, current.tracks.size)) {
            if (detail?.id != current.id) break
            warmVisibleTrack(index, current)
        }
    }

    fun playTrack(index: Int, current: DiscogsCompilationDetail) {
        awaitingSongSelection = false
        selectedTrackIndex = index
        CompilationSessionStore.selectedTrackIndex = index

        paginationJob?.cancel()
        loadingMore = false
        fullAudioLookupJob?.cancel()
        nextTrackWarmJob?.cancel()
        fastTrackPlayJob?.cancel()
        playerConnection?.beginPlaybackPriorityBurst("compilation-track-tap")

        fastTrackPlayJob = scope.launch {
            // The last selection wins even when an older query finishes late.
            val found = resolveTrack(index, current)
            if (selectedTrackIndex != index || detail?.id != current.id) return@launch
            if (found != null) {
                playerConnection?.let { connection ->
                    MusicLabSectionPlayback.select(
                        connection = connection,
                        source = "Compilation · " + current.title,
                        orderedEntries = current.tracks.mapIndexed { trackIndex, track ->
                            MusicLabSectionPlayback.Entry(
                                key = trackIndex.toString(),
                                title = track.title,
                                artist = track.artists.joinToString(", "),
                            )
                        },
                        selectedIndex = index,
                        selected = found.song.toMediaItem(),
                        selectionKey = "compilation:detail:" + current.id,
                        findPlayable = { entry ->
                            entry.key.toIntOrNull()
                                ?.takeIf { it in current.tracks.indices }
                                ?.let { trackIndex ->
                                    resolveTrack(trackIndex, current)?.song?.toMediaItem()
                                }
                        },
                    )
                }
                if (fullAudio == null && detail?.id == current.id)
                    scheduleFullAudioLookup(current, delayMs = 2_000L)
            } else {
                Toast.makeText(context, "Brano non trovato per ora.", Toast.LENGTH_SHORT).show()
                if (fullAudio == null) scheduleFullAudioLookup(current, delayMs = 1_000L)
            }
        }
    }

    fun saveCompilation(current: DiscogsCompilationDetail) {
        if (saving) return
        saving = true
        scope.launch {
            current.tracks.indices.forEach { index -> resolveTrack(index, current) }
            val songs = current.tracks.indices.mapNotNull(resolvedTracks::get)
            if (songs.isEmpty()) {
                saving = false
                Toast.makeText(context, "Non ci sono ancora brani da salvare.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            withContext(Dispatchers.IO) {
                database.withTransaction {
                    val playlistId = "discogs_" + current.id
                    val old = playlistBlocking(playlistId)
                    val entity = old?.playlist?.copy(
                        name = current.title,
                        thumbnailUrl = current.coverUrl,
                        bookmarkedAt = old.playlist.bookmarkedAt ?: LocalDateTime.now(),
                        lastUpdateTime = LocalDateTime.now(),
                    ) ?: PlaylistEntity(
                        id = playlistId,
                        name = current.title,
                        thumbnailUrl = current.coverUrl,
                        bookmarkedAt = LocalDateTime.now(),
                        isEditable = true,
                    )

                    if (old == null) insert(entity) else update(entity)
                    songs.forEach { insert(it.song.toMediaMetadata()) }

                    val playlist = playlistBlocking(playlistId)
                    if (playlist != null) {
                        val ids = songs.map { it.song.id }
                        val duplicates = playlistDuplicates(playlistId, ids).toSet()
                        val newSongs = songs
                            .filterNot { it.song.id in duplicates }
                            .map { it.song.id to it.song.setVideoId }
                        addSongsToPlaylist(playlist, newSongs)
                    }
                }
            }
            saving = false
            Toast.makeText(
                context,
                "Compilation salvata: ${songs.size}/${current.tracks.size} brani trovati.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    if (detail != null) {
                        detail = null
                        openReleaseId = null
                        fullAudio = null
                        resolvedTracks.clear()
                        CompilationSessionStore.detail = null
                        CompilationSessionStore.fullAudio = null
                        CompilationSessionStore.resolvedTracks.clear()
                    } else {
                        navController.popBackStack()
                    }
                },
            ) {
                Text("← Indietro")
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Compilation",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        when {
            !tokenState.loaded -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            discogsToken.isBlank() -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Token Discogs mancante",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("Apri il menu account → Token e API e inserisci il Personal Access Token Discogs.")
                    Button(onClick = { navController.popBackStack() }) {
                        Text("Torna indietro")
                    }
                }
            }

            detail != null -> CompilationDetailContent(
                detail = detail!!,
                resolvedTracks = resolvedTracks,
                fullAudio = fullAudio,
                saving = saving,
                selectedTrackIndex = selectedTrackIndex ?: detail?.let { current ->
                    selectedKeys["compilation:detail:" + current.id]?.toIntOrNull()
                },
                onPlayFull = {
                    playerConnection?.beginPlaybackPriorityBurst("compilation-full-audio")
                    fullAudio?.song?.let { playerConnection?.playNow(it.toMediaItem()) }
                },
                onSave = { saveCompilation(detail!!) },
                onTrackClick = { index -> playTrack(index, detail!!) },
                onVisibleTrack = { index -> warmVisibleTrack(index, detail!!) },
                onDiscogsClick = { uriHandler.openUri(detail!!.discogsUrl) },
            )

            detailLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            else -> {
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
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.weight(1f).height(54.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize().padding(start = 14.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        BasicTextField(
                                            value = query,
                                            onValueChange = {
                                                query = it
                                                trackRequest = null
                                                sourceTitle = ""
                                                sourceArtist = ""
                                            },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.onSurface,
                                            ),
                                            modifier = Modifier.weight(1f),
                                            decorationBox = { innerTextField ->
                                                Box(contentAlignment = Alignment.CenterStart) {
                                                    if (query.isEmpty()) {
                                                        Text(
                                                            "Cerca album o compilation",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1,
                                                        )
                                                    }
                                                    innerTextField()
                                                }
                                            },
                                        )
                                        Box {
                                            TextButton(
                                                onClick = { searchModesOpen = true },
                                                enabled = query.isNotBlank(),
                                            ) {
                                                Text(lookupMode.label, maxLines = 1)
                                                Text("⌄")
                                            }
                                            DropdownMenu(
                                                expanded = searchModesOpen,
                                                onDismissRequest = { searchModesOpen = false },
                                            ) {
                                                CompilationLookupMode.entries.forEach { selected ->
                                                    DropdownMenuItem(
                                                        text = { Text(selected.label) },
                                                        onClick = {
                                                            searchModesOpen = false
                                                            lookupMode = selected
                                                            val title = CompilationLightSearchPolicy.indexTitle(
                                                                sourceTitle.ifBlank { query }, sourceArtist)
                                                            if (title.isNotBlank()) {
                                                                val label = if (selected == CompilationLookupMode.TITLE_ARTIST)
                                                                    listOf(title, sourceArtist)
                                                                        .filter(String::isNotBlank).joinToString(" - ")
                                                                else title
                                                                query = label
                                                                runSearch(CompilationSearchCriteria(
                                                                    query = label,
                                                                    year = year, genre = genre, style = style,
                                                                    country = country, language = language,
                                                                    mode = mode,
                                                                    trackTitle = title,
                                                                    trackArtist = sourceArtist.takeIf(String::isNotBlank),
                                                                    lookupMode = selected,
                                                                ))
                                                            }
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                Box {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.size(48.dp).clickable { historyOpen = true },
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                painter = painterResource(R.drawable.history),
                                                contentDescription = "Cronologia ricerche Compilation",
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }
                                    }
                                    DropdownMenu(expanded = historyOpen,
                                        onDismissRequest = { historyOpen = false }) {
                                        if (searchHistory.isEmpty()) {
                                            DropdownMenuItem(text = { Text("Nessuna ricerca salvata") },
                                                onClick = { historyOpen = false })
                                        }
                                        searchHistory.forEach { previous ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(previous.trackTitle
                                                            ?: previous.query.ifBlank {
                                                                "Compilation " + (previous.year?.toString() ?: "con filtri")
                                                            })
                                                        Text(previous.trackArtist ?: listOfNotNull(
                                                            previous.year?.toString(), previous.genre,
                                                            previous.style, previous.country,
                                                        ).joinToString(" · "),
                                                            style = MaterialTheme.typography.bodySmall)
                                                    }
                                                },
                                                onClick = {
                                                    historyOpen = false
                                                    query = previous.query
                                                    year = previous.year
                                                    genre = previous.genre
                                                    style = previous.style
                                                    country = previous.country
                                                    language = previous.language
                                                    mode = previous.mode
                                                    lookupMode = previous.lookupMode
                                                    sourceTitle = previous.trackTitle.orEmpty()
                                                    sourceArtist = previous.trackArtist.orEmpty()
                                                    // The history entry supplies the exact criteria.
                                                    // Avoid the auto-navigation effect launching a second search.
                                                    trackRequest = null
                                                    runSearch(previous)
                                                },
                                            )
                                        }
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.size(54.dp).clickable {
                                        filtersExpanded = !filtersExpanded
                                        CompilationSessionStore.filtersExpanded = filtersExpanded
                                    },
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            if (filtersExpanded) "⌃" else "⌄",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                    }
                                }
                            }

                            if (filtersExpanded) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CompilationFilterDropdown(
                                        label = "Anno",
                                        selected = year?.toString() ?: "Tutto",
                                        options = listOf("Tutto") + (Year.now().value downTo 1900).map(Int::toString),
                                        modifier = Modifier.weight(1f),
                                        onSelect = { year = it.takeUnless { value -> value == "Tutto" }?.toIntOrNull() },
                                    )
                                    Box(modifier = Modifier.weight(1f)) {
                                        OutlinedButton(
                                            onClick = { releaseTypesOpen = true },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(mode.label, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f))
                                            Text("⌄")
                                        }
                                        DropdownMenu(
                                            expanded = releaseTypesOpen,
                                            onDismissRequest = { releaseTypesOpen = false },
                                        ) {
                                            DiscogsReleaseCategory.entries.forEach { category ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Checkbox(
                                                                checked = mode.includes(category),
                                                                onCheckedChange = null,
                                                            )
                                                            Text(category.label)
                                                        }
                                                    },
                                                    onClick = { mode = mode.toggle(category) },
                                                    enabled = !(mode.includes(category) &&
                                                        mode.selected.size == 1),
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CompilationFilterDropdown(
                                        label = "Stile",
                                        selected = CompilationUnifiedStyleFilter.label(genre, style),
                                        options = CompilationUnifiedStyleFilter.options(
                                            compilationGenres, compilationStyles),
                                        modifier = Modifier.weight(1f),
                                        onSelect = {
                                            val picked = CompilationUnifiedStyleFilter.parse(it)
                                            genre = picked.first
                                            style = picked.second
                                        },
                                    )
                                    CompilationFilterDropdown(
                                        label = "Nazione",
                                        selected = country ?: "Tutto",
                                        options = compilationCountries,
                                        modifier = Modifier.weight(1f),
                                        onSelect = { country = it.takeUnless { value -> value == "Tutto" } },
                                    )
                                }

                                CompilationFilterDropdown(
                                    label = "Lingua",
                                    selected = language ?: "Tutte",
                                    options = compilationLanguages,
                                    modifier = Modifier.fillMaxWidth(),
                                    onSelect = { language = it.takeUnless { value -> value == "Tutte" } },
                                )

                                Button(
                                    onClick = { runSearch() },
                                    enabled = !loading,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (loading) "Ricerca…" else "Cerca in " + mode.label)
                                }
                            }

                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                            if (activeSearch != null && (totalDiscogsResults > 0 || results.isNotEmpty())) {
                                val counterText =
                                    if (activeSearch?.trackTitle != null) {
                                        "Canzoni verificate in ${results.size} dischi · " +
                                            "Discogs $totalDiscogsResults candidati · pagina $currentPage/$totalPages"
                                    } else if (activeSearch?.language == null) {
                                        "Caricate ${results.size} / $totalDiscogsResults · pagina $currentPage/$totalPages"
                                    } else {
                                        "Visibili ${results.size} · Discogs $totalDiscogsResults · pagina $currentPage/$totalPages"
                                    }
                                Text(
                                    counterText,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    LazyColumn(
                        state = resultListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 176.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (!loading && activeSearch == null && results.isEmpty()) {
                            item {
                                Text(
                                    "Scrivi nella barra sopra oppure apri i filtri con ⌄.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        } else if (!loading && activeSearch != null && results.isEmpty()
                            && currentPage > 0 && totalDiscogsResults > 0) {
                            item {
                                Text(
                                    "Nessuna canzone verificata in questa pagina. Può cercare nei dischi successivi.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        } else if (!loading && activeSearch != null && totalDiscogsResults == 0) {
                            item {
                                Text(
                                    "Nessun risultato per ${activeSearch?.mode?.label?.lowercase() ?: "la categoria"} con questi criteri.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }

                        items(
                            items = results,
                            key = { "discogs_release_${it.id}" },
                        ) { item ->
                            CompilationResultCard(
                                summary = item,
                                selected = item.id == selectedReleaseId,
                                onClick = { openCompilation(item) },
                            )
                        }

                        if (activeSearch != null && currentPage > 0 && currentPage < totalPages) {
                            item(key = "discogs_load_more_${currentPage + 1}") {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (activeSearch?.trackTitle != null) {
                                        TextButton(onClick = ::loadNextPage,
                                            enabled = !loadingMore) {
                                            Text(if (loadingMore) "Verifico le tracklist…"
                                                else "Cerca nei dischi successivi")
                                        }
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            "Carico altri risultati… ${results.size}/$totalDiscogsResults",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                                paginationError?.let {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            it,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        TextButton(onClick = ::loadNextPage) {
                                            Text("Riprova")
                                        }
                                    }
                                }
                            }
                        } else if (
                            activeSearch != null &&
                            currentPage > 0 &&
                            currentPage >= totalPages &&
                            !loading &&
                            !loadingMore
                        ) {
                            item(key = "discogs_all_loaded") {
                                Text(
                                    text =
                                        if (activeSearch?.language == null) {
                                            "Tutti i risultati Discogs disponibili sono stati caricati: ${results.size}."
                                        } else {
                                            "Fine risultati Discogs · ${results.size} dischi visibili con il filtro Lingua."
                                        },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompilationResultCard(
    summary: DiscogsCompilationSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 5.dp else 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = summary.coverUrl ?: summary.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(92.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    summary.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(summary.year?.toString(), summary.country).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val genreStyle = (summary.genres + summary.styles).distinct().take(3).joinToString(" · ")
                if (genreStyle.isNotBlank()) {
                    Text(
                        genreStyle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                summary.labels.firstOrNull()?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompilationDetailContent(
    detail: DiscogsCompilationDetail,
    resolvedTracks: Map<Int, CompilationResolvedTrack>,
    fullAudio: CompilationFullAudio?,
    saving: Boolean,
    selectedTrackIndex: Int?,
    onPlayFull: () -> Unit,
    onSave: () -> Unit,
    onTrackClick: (Int) -> Unit,
    onVisibleTrack: suspend (Int) -> Unit,
    onDiscogsClick: () -> Unit,
) {
    // Two preceding LazyColumn items (artwork/header + divider) precede tracks.
    // Scrolling is purely visual: it must never call playTrack().
    val detailListState = remember(detail.id) { LazyListState() }
    LaunchedEffect(detail.id, selectedTrackIndex) {
        val highlighted = selectedTrackIndex
        if (highlighted != null && highlighted in detail.tracks.indices)
            detailListState.scrollToItem(highlighted + 2)
    }
    LazyColumn(
        state = detailListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 176.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AsyncImage(
                    model = detail.coverUrl ?: MusicLabCompilationCoverBridge.artwork(
                        LocalContext.current, detail.id),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(220.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    detail.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                val meta = listOfNotNull(
                    detail.year?.toString(),
                    detail.country,
                    detail.labels.firstOrNull(),
                ).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta)
                val taxonomy = (detail.genres + detail.styles).distinct().joinToString(" · ")
                if (taxonomy.isNotBlank()) {
                    Text(
                        taxonomy,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))

                if (fullAudio != null) {
                    Button(
                        onClick = onPlayFull,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("▶ Riproduci compilation completa")
                    }
                    Text(
                        fullAudio.source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedButton(
                    onClick = onSave,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (saving) "Salvataggio…" else "💾 Salva compilation")
                }

                Text(
                    "${resolvedTracks.size}/${detail.tracks.size} brani già individuati · ${detail.videos.size} video Discogs disponibili",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )

                TextButton(onClick = onDiscogsClick) {
                    Text("Dati forniti da Discogs · Apri sorgente")
                }
            }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }

        itemsIndexed(
            items = detail.tracks,
            key = { index, track -> "${detail.id}_${index}_${track.position}_${track.title}" },
        ) { index, track ->
            LaunchedEffect(detail.id, index) {
                if (resolvedTracks[index] == null) onVisibleTrack(index)
            }
            val resolved = resolvedTracks[index]
            val selected = index == selectedTrackIndex
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clickable { onTrackClick(index) },
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = 8.dp,
                        vertical = 10.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                Text(
                    track.position.ifBlank { (index + 1).toString() },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(38.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val artist = track.artists.joinToString(", ").ifBlank { "Artista non indicato" }
                    val source = resolved?.source ?: "Tocca per trovare e riprodurre"
                    Text(
                        "$artist · $source",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (resolved != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                track.durationText?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CompilationFilterDropdown(
    label: String,
    selected: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(50.dp).clickable { expanded = true },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (label.isBlank()) selected else "$label: $selected",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text("⌄", color = MaterialTheme.colorScheme.primary)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

private val compilationGenres = listOf(
    "Tutto",
    "Blues",
    "Brass & Military",
    "Children's",
    "Classical",
    "Electronic",
    "Folk, World, & Country",
    "Funk / Soul",
    "Hip Hop",
    "Jazz",
    "Latin",
    "Non-Music",
    "Pop",
    "Reggae",
    "Rock",
    "Stage & Screen",
)

private val compilationStyles = listOf(
    "Tutto",
    "Disco",
    "Italo-Disco",
    "Synth-pop",
    "Pop Rock",
    "Rock & Roll",
    "Soul",
    "Funk",
    "House",
    "Techno",
    "Trance",
    "Dance-pop",
    "Ballad",
    "Chanson",
    "Easy Listening",
    "Soundtrack",
    "Vocal",
    "Schlager",
)

private val compilationCountries = listOf(
    "Tutto",
    "Italy",
    "US",
    "UK",
    "France",
    "Germany",
    "Spain",
    "Netherlands",
    "Belgium",
    "Canada",
    "Australia",
    "Japan",
    "Brazil",
    "Portugal",
    "Greece",
    "Sweden",
    "Switzerland",
    "Austria",
    "Europe",
)

private val compilationLanguages = listOf(
    "Tutte",
    "Italiano",
    "Inglese",
    "Francese",
    "Spagnolo",
    "Tedesco",
    "Portoghese",
)
