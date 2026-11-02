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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
)

private object CompilationSessionStore {
    var query: String = ""
    var year: Int? = null
    var genre: String? = null
    var style: String? = null
    var country: String? = null
    var language: String? = null

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
    // Null (loading) and a genuinely absent token must not be confused.
    val tokenFlow = remember(context) {
        context.dataStore.data.map { CompilationTokenState(true, it[DiscogsTokenKey].orEmpty().trim()) }
    }
    val tokenState by tokenFlow.collectAsStateWithLifecycle(initialValue = CompilationTokenState())
    val discogsToken = tokenState.token

    var query by remember { mutableStateOf(CompilationSessionStore.query) }
    var trackRequest by remember { mutableStateOf(CompilationNavigationBridge.takePendingTrack()) }
    var year by remember { mutableStateOf(CompilationSessionStore.year) }
    var genre by remember { mutableStateOf(CompilationSessionStore.genre) }
    var style by remember { mutableStateOf(CompilationSessionStore.style) }
    var country by remember { mutableStateOf(CompilationSessionStore.country) }
    var language by remember { mutableStateOf(CompilationSessionStore.language) }

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
    val resultListState = rememberLazyListState(
        initialFirstVisibleItemIndex = CompilationSessionStore.listIndex,
        initialFirstVisibleItemScrollOffset = CompilationSessionStore.listOffset,
    )
    var resolvingAll by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var filtersExpanded by remember { mutableStateOf(CompilationSessionStore.filtersExpanded) }
    var selectedTrackIndex by remember { mutableStateOf(CompilationSessionStore.selectedTrackIndex) }
    var paginationJob by remember { mutableStateOf<Job?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var fullAudioLookupJob by remember { mutableStateOf<Job?>(null) }
    var nextTrackWarmJob by remember { mutableStateOf<Job?>(null) }
    var fastTrackPlayJob by remember { mutableStateOf<Job?>(null) }
    var progressiveAlbumJob by remember { mutableStateOf<Job?>(null) }
    var searchGeneration by remember { mutableStateOf(0L) }
    var activeSearchToken by remember { mutableStateOf("") }
    var historyOpen by remember { mutableStateOf(false) }
    var searchHistory by remember { mutableStateOf(emptyList<CompilationSearchCriteria>()) }

    LaunchedEffect(Unit) {
        searchHistory = withContext(Dispatchers.IO) { CompilationSearchHistoryStore.load(context) }
    }

    LaunchedEffect(query, year, genre, style, country, language) {
        CompilationSessionStore.query = query
        CompilationSessionStore.year = year
        CompilationSessionStore.genre = genre
        CompilationSessionStore.style = style
        CompilationSessionStore.country = country
        CompilationSessionStore.language = language
        CompilationSessionStore.filtersExpanded = filtersExpanded
    }

    LaunchedEffect(resultListState) {
        snapshotFlow {
            resultListState.firstVisibleItemIndex to resultListState.firstVisibleItemScrollOffset
        }.collectLatest { (index, offset) ->
            CompilationSessionStore.listIndex = index
            CompilationSessionStore.listOffset = offset
        }
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
    // Results are painted progressively as each real Discogs release is checked.
    suspend fun verifyContainingTrack(
        candidates: List<DiscogsCompilationSummary>,
        criteria: CompilationSearchCriteria,
        replaceResults: Boolean,
        token: String,
        generation: Long,
    ): List<DiscogsCompilationSummary> {
        val wantedTitle = criteria.trackTitle ?: return candidates
        val accepted = mutableListOf<DiscogsCompilationSummary>()
        for (candidate in candidates) {
            if (generation != searchGeneration) break
            val release = DiscogsClient.getRelease(token, candidate.id, includeMasterVideos = false)
                .getOrNull() ?: continue
            if (generation != searchGeneration) break
            if (release.tracks.any { track ->
                    CompilationTrackMatchPolicy.matches(track, wantedTitle, criteria.trackArtist.orEmpty())
                }
            ) {
                accepted += candidate
                results = if (replaceResults) accepted.toList()
                          else (results + candidate).distinctBy { it.id }
                CompilationSessionStore.results = results
            }
        }
        return accepted
    }

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
            query = if (criteria.trackTitle != null) "" else criteria.query,
            trackTitle = criteria.trackTitle,
            year = criteria.year,
            genre = criteria.genre,
            style = criteria.style,
            country = criteria.country,
            page = page,
            perPage = if (criteria.trackTitle != null || criteria.language != null) 25 else 100,
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

        val visibleItems = filterByLanguage(pageResult.items, criteria.language, token, generation)
        if (generation != searchGeneration) return false
        val verifiedItems = if (criteria.trackTitle != null) {
            verifyContainingTrack(visibleItems, criteria, replaceResults, token, generation)
        } else visibleItems
        if (generation != searchGeneration) return false
        results = if (replaceResults) {
            verifiedItems
        } else {
            (results + verifiedItems).distinctBy { it.id }
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

    fun runSearch(requestedCriteria: CompilationSearchCriteria? = null) {
        val criteria = requestedCriteria ?: CompilationSearchCriteria(
            query = query.trim(),
            year = year,
            genre = genre,
            style = style,
            country = country,
            language = language,
            trackTitle = trackRequest?.title?.let(CompilationSearchTitlePolicy::clean),
            trackArtist = trackRequest?.artist,
        )

        // Invalidate and remove the old list BEFORE reading credentials. Neither
        // a missing credential nor a late response may keep the old search alive.
        searchGeneration += 1
        val generation = searchGeneration
        paginationJob?.cancel()
        searchJob?.cancel()
        fullAudioLookupJob?.cancel()
        nextTrackWarmJob?.cancel()
        progressiveAlbumJob?.cancel()
        loading = true
        loadingMore = false
        error = null
        paginationError = null
        detail = null
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
                val succeeded = loadPage(criteria, page = 1, replaceResults = true,
                    token = token, generation = generation)
                if (!succeeded && generation == searchGeneration) {
                    activeSearch = null
                    CompilationSessionStore.activeSearch = null
                }
                if (succeeded && generation == searchGeneration) {
                    val updated = withContext(Dispatchers.IO) {
                        CompilationSearchHistoryStore.save(context, criteria)
                        CompilationSearchHistoryStore.load(context)
                    }
                    if (generation == searchGeneration) searchHistory = updated
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (generation == searchGeneration)
                    error = failure.message ?: "Impossibile caricare le compilation."
            } finally {
                if (generation == searchGeneration) loading = false
            }
        }
    }

    // A menu-to-Compilation action is a new search, never a continuation of
    // the previous year/filter results. Keep the original artist evidence.
    LaunchedEffect(trackRequest?.title, trackRequest?.artist) {
        trackRequest?.let { target ->
            val cleanTitle = CompilationSearchTitlePolicy.clean(target.title)
            query = cleanTitle
            year = null
            genre = null
            style = null
            country = null
            language = null
            runSearch(CompilationSearchCriteria(
                query = cleanTitle, year = null, genre = null, style = null,
                country = null, language = null,
                trackTitle = cleanTitle, trackArtist = target.artist.trim(),
            ))
        }
    }

    fun loadNextPage() {
        val criteria = activeSearch ?: return
        if (loading || loadingMore || currentPage <= 0 || currentPage >= totalPages) return

        loadingMore = true
        paginationError = null
        paginationJob?.cancel()
        paginationJob = scope.launch {
            try {
                loadPage(
                    criteria = criteria,
                    page = currentPage + 1,
                    replaceResults = false,
                    token = activeSearchToken,
                    generation = searchGeneration,
                )
            } finally {
                loadingMore = false
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
        paginationJob?.cancel()
        loadingMore = false
        selectedTrackIndex = null
        CompilationSessionStore.selectedTrackIndex = null
        detailLoading = true
        error = null
        fullAudio = null
        resolvedTracks.clear()
        scope.launch {
            DiscogsClient.getRelease(discogsToken, summary.id)
                .onSuccess { loaded ->
                    detail = loaded
                    CompilationSessionStore.detail = loaded
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
        }
        return found
    }

    // Warm just the first two tracks while the user reads the tracklist.
    // This is cancellable and never blocks rendering the Discogs album cover.
    LaunchedEffect(detail?.id) {
        val current = detail ?: return@LaunchedEffect
        delay(180L)
        for (index in 0 until minOf(MusicLabFastPathPolicy.COMPILATION_WARM_TRACKS, current.tracks.size)) {
            if (detail?.id != current.id ||
                playerConnection?.isPlaybackPriorityBurstActive() == true) break
            resolveTrack(index, current)
        }
    }

    fun playTrack(index: Int, current: DiscogsCompilationDetail) {
        selectedTrackIndex = index
        CompilationSessionStore.selectedTrackIndex = index

        paginationJob?.cancel()
        loadingMore = false
        fullAudioLookupJob?.cancel()
        nextTrackWarmJob?.cancel()
        progressiveAlbumJob?.cancel()
        fastTrackPlayJob?.cancel()
        playerConnection?.beginPlaybackPriorityBurst("compilation-track-tap")

        fastTrackPlayJob = scope.launch {
            // The last selection wins even when an older query finishes late.
            val found = resolveTrack(index, current)
            if (selectedTrackIndex != index || detail?.id != current.id) return@launch
            if (found != null) {
                playerConnection?.playNow(found.song.toMediaItem())
                nextTrackWarmJob = scope.launch {
                    while (playerConnection?.isPlaybackPriorityBurstActive() == true) {
                        delay(180)
                    }
                    delay(350)
                    if (selectedTrackIndex != index || detail?.id != current.id) return@launch
                    val nextIndex = index + 1
                    val next = resolveTrack(nextIndex, current)
                    if (next != null && selectedTrackIndex == index &&
                        detail?.id == current.id) {
                        playerConnection?.playNext(next.song.toMediaItem())
                    }
                    if (fullAudio == null && detail?.id == current.id) {
                        scheduleFullAudioLookup(current, delayMs = 2_000L)
                    }
                }
            } else {
                Toast.makeText(context, "Brano non trovato per ora.", Toast.LENGTH_SHORT).show()
                if (fullAudio == null) scheduleFullAudioLookup(current, delayMs = 1_000L)
            }
        }
    }

    fun resolveAllAndPlay(current: DiscogsCompilationDetail) {
        if (resolvingAll) return
        fastTrackPlayJob?.cancel()
        nextTrackWarmJob?.cancel()
        selectedTrackIndex = null
        CompilationSessionStore.selectedTrackIndex = null
        resolvingAll = true
        // Start the first playable track immediately. Append later tracks in
        // original Discogs order as they resolve; never wait for the full album.
        progressiveAlbumJob = scope.launch {
            var started = false
            val admittedIds = linkedSetOf<String>()
            try {
                for (index in current.tracks.indices) {
                    if (!MusicLabFastPathPolicy.mayContinueCompilation(detail?.id, current.id, selectedTrackIndex)) break
                    val found = resolveTrack(index, current) ?: continue
                    if (!MusicLabFastPathPolicy.mayContinueCompilation(detail?.id, current.id, selectedTrackIndex)) break
                    val item = found.song.toMediaItem()
                    if (!started) {
                        playerConnection?.beginPlaybackPriorityBurst("compilation-first-audio")
                        playerConnection?.playQueue(
                            ListQueue(title = current.title, items = listOf(item), startIndex = 0)
                        )
                        admittedIds.add(item.mediaId)
                        started = true
                        // Allow the initial one-item queue to commit before appending.
                        delay(120L)
                    } else {
                        val currentId = playerConnection?.player?.currentMediaItem?.mediaId
                        if (!MusicLabFastPathPolicy.mayAppendResolvedSong(currentId, admittedIds, item.mediaId)) break
                        admittedIds.add(item.mediaId)
                        playerConnection?.addToQueue(item)
                    }
                }
                if (!started && detail?.id == current.id) {
                    Toast.makeText(context, "Nessun brano riproducibile trovato.",
                        Toast.LENGTH_SHORT).show()
                }
            } finally {
                resolvingAll = false
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
                resolvingAll = resolvingAll,
                saving = saving,
                selectedTrackIndex = selectedTrackIndex,
                onPlayFull = {
                    playerConnection?.beginPlaybackPriorityBurst("compilation-full-audio")
                    fullAudio?.song?.let { playerConnection?.playNow(it.toMediaItem()) }
                },
                onPlayAll = { resolveAllAndPlay(detail!!) },
                onSave = { saveCompilation(detail!!) },
                onTrackClick = { index -> playTrack(index, detail!!) },
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
                                            onValueChange = { query = it; trackRequest = null },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.onSurface,
                                            ),
                                            modifier = Modifier.weight(1f),
                                            decorationBox = { innerTextField ->
                                                Box(contentAlignment = Alignment.CenterStart) {
                                                    if (query.isEmpty()) {
                                                        Text(
                                                            "Cerca compilation",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1,
                                                        )
                                                    }
                                                    innerTextField()
                                                }
                                            },
                                        )
                                        TextButton(
                                            onClick = { runSearch() },
                                            enabled = !loading,
                                        ) {
                                            Text(if (loading) "…" else "Cerca")
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
                                    CompilationFilterDropdown(
                                        label = "Genere",
                                        selected = genre ?: "Tutto",
                                        options = compilationGenres,
                                        modifier = Modifier.weight(1f),
                                        onSelect = { genre = it.takeUnless { value -> value == "Tutto" } },
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CompilationFilterDropdown(
                                        label = "Stile",
                                        selected = style ?: "Tutto",
                                        options = compilationStyles,
                                        modifier = Modifier.weight(1f),
                                        onSelect = { style = it.takeUnless { value -> value == "Tutto" } },
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
                                    Text(if (loading) "Ricerca…" else "Cerca compilation")
                                }
                            }

                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                            if (activeSearch != null && (totalDiscogsResults > 0 || results.isNotEmpty())) {
                                val counterText =
                                    if (activeSearch?.language == null) {
                                        if (activeSearch?.trackTitle != null) {
                                            "Compilation verificate: ${results.size} · ricerca in corso nelle pagine Discogs ($currentPage/$totalPages)"
                                        } else "Caricate ${results.size} / $totalDiscogsResults · pagina $currentPage/$totalPages"
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
                        } else if (!loading && activeSearch != null && totalDiscogsResults == 0) {
                            item {
                                Text(
                                    "Nessuna compilation trovata con questi criteri.",
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
                                            "Fine risultati Discogs · ${results.size} compilation visibili con il filtro Lingua."
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
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
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
    resolvingAll: Boolean,
    saving: Boolean,
    selectedTrackIndex: Int?,
    onPlayFull: () -> Unit,
    onPlayAll: () -> Unit,
    onSave: () -> Unit,
    onTrackClick: (Int) -> Unit,
    onDiscogsClick: () -> Unit,
) {
    LazyColumn(
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
                    model = detail.coverUrl,
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
                    onClick = onPlayAll,
                    enabled = !resolvingAll,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (resolvingAll) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (resolvingAll) {
                            "Cerco i brani… ${resolvedTracks.size}/${detail.tracks.size}"
                        } else {
                            "▶ Riproduci tutto per brani"
                        },
                    )
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
                    "$label: $selected",
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
