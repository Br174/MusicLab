package com.metrolist.music.ui.component

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.constants.DiscogsTokenKey
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.discogs.CompilationFullAudio
import com.metrolist.music.discogs.CompilationLanguageHeuristics
import com.metrolist.music.discogs.CompilationResolvedTrack
import com.metrolist.music.discogs.CompilationTrackResolver
import com.metrolist.music.discogs.DiscogsClient
import com.metrolist.music.discogs.DiscogsCompilationDetail
import com.metrolist.music.discogs.DiscogsCompilationSummary
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.Year

private data class CompilationSearchCriteria(
    val query: String,
    val year: Int?,
    val genre: String?,
    val style: String?,
    val country: String?,
    val language: String?,
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

    fun resetForNewSearch() {
        results = emptyList()
        currentPage = 0
        totalPages = 0
        totalDiscogsResults = 0
        detail = null
        fullAudio = null
        resolvedTracks.clear()
        listIndex = 0
        listOffset = 0
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
    val discogsToken by rememberPreference(DiscogsTokenKey, "")

    var query by remember { mutableStateOf(CompilationSessionStore.query) }
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

    LaunchedEffect(query, year, genre, style, country, language) {
        CompilationSessionStore.query = query
        CompilationSessionStore.year = year
        CompilationSessionStore.genre = genre
        CompilationSessionStore.style = style
        CompilationSessionStore.country = country
        CompilationSessionStore.language = language
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
    ): List<DiscogsCompilationSummary> {
        if (requestedLanguage == null || items.isEmpty()) return items
        return withContext(Dispatchers.IO) {
            items.filter { summary ->
                val candidate = DiscogsClient.getRelease(discogsToken, summary.id).getOrNull()
                candidate == null ||
                    CompilationLanguageHeuristics.matchesOrUnknown(requestedLanguage, candidate.tracks)
            }
        }
    }

    suspend fun loadPage(
        criteria: CompilationSearchCriteria,
        page: Int,
        replaceResults: Boolean,
    ): Boolean {
        val response = DiscogsClient.searchCompilations(
            token = discogsToken,
            query = criteria.query,
            year = criteria.year,
            genre = criteria.genre,
            style = criteria.style,
            country = criteria.country,
            page = page,
            perPage = if (criteria.language == null) 100 else 25,
        )

        val pageResult = response.getOrElse {
            if (replaceResults) {
                error = it.message ?: "Errore durante la ricerca Discogs."
            } else {
                paginationError = it.message ?: "Errore nel caricamento della pagina successiva."
            }
            return false
        }

        val visibleItems = filterByLanguage(pageResult.items, criteria.language)
        results = if (replaceResults) {
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

    fun runSearch() {
        if (discogsToken.isBlank()) {
            error = "Inserisci prima il Personal Access Token Discogs in Token e API."
            return
        }

        val criteria = CompilationSearchCriteria(
            query = query.trim(),
            year = year,
            genre = genre,
            style = style,
            country = country,
            language = language,
        )

        loading = true
        loadingMore = false
        error = null
        paginationError = null
        detail = null
        resolvedTracks.clear()
        CompilationSessionStore.resetForNewSearch()
        results = emptyList()
        currentPage = 0
        totalPages = 0
        totalDiscogsResults = 0
        activeSearch = criteria
        CompilationSessionStore.activeSearch = criteria

        scope.launch {
            resultListState.scrollToItem(0)
            loadPage(criteria, page = 1, replaceResults = true)
            loading = false
        }
    }

    fun loadNextPage() {
        val criteria = activeSearch ?: return
        if (loading || loadingMore || currentPage <= 0 || currentPage >= totalPages) return

        loadingMore = true
        paginationError = null
        scope.launch {
            loadPage(
                criteria = criteria,
                page = currentPage + 1,
                replaceResults = false,
            )
            loadingMore = false
        }
    }

    fun openCompilation(summary: DiscogsCompilationSummary) {
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

    LaunchedEffect(detail?.id) {
        val current = detail ?: return@LaunchedEffect
        fullAudio = CompilationTrackResolver.findFullAudio(current)
        CompilationSessionStore.fullAudio = fullAudio
    }

    suspend fun resolveTrack(index: Int, current: DiscogsCompilationDetail): CompilationResolvedTrack? {
        resolvedTracks[index]?.let { return it }
        val track = current.tracks.getOrNull(index) ?: return null
        val found = CompilationTrackResolver.resolveTrack(track, current.videos)
        if (found != null) {
            resolvedTracks[index] = found
            CompilationSessionStore.resolvedTracks[index] = found
        }
        return found
    }

    fun playTrack(index: Int, current: DiscogsCompilationDetail) {
        scope.launch {
            val found = resolveTrack(index, current)
            if (found != null) {
                playerConnection?.playNow(found.song.toMediaItem())
            } else {
                Toast.makeText(context, "Brano non trovato per ora.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun resolveAllAndPlay(current: DiscogsCompilationDetail) {
        if (resolvingAll) return
        resolvingAll = true
        scope.launch {
            val songs = mutableListOf<CompilationResolvedTrack>()
            current.tracks.indices.forEach { index ->
                resolveTrack(index, current)?.let(songs::add)
            }
            resolvingAll = false
            if (songs.isEmpty()) {
                Toast.makeText(context, "Nessun brano riproducibile trovato.", Toast.LENGTH_SHORT).show()
            } else {
                playerConnection?.playQueue(
                    ListQueue(
                        title = current.title,
                        items = songs.map { it.song.toMediaItem() },
                        startIndex = 0,
                    ),
                )
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
                onPlayFull = {
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
                LazyColumn(
                    state = resultListState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text("Cerca compilation, titolo, artista, brano o etichetta") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )

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

                            Text(
                                text = "La lingua è stimata da MusicLab; Anno, Genere, Stile e Nazione usano i dati Discogs.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Button(
                                onClick = ::runSearch,
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (loading) "Ricerca…" else "Cerca compilation")
                            }

                            error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }

                            if (activeSearch != null && (totalDiscogsResults > 0 || results.isNotEmpty())) {
                                val counterText =
                                    if (activeSearch?.language == null) {
                                        "Compilation caricate: ${results.size} / Totale Discogs: $totalDiscogsResults"
                                    } else {
                                        "Compilation visibili: ${results.size} · Totale Discogs prima del filtro Lingua: $totalDiscogsResults"
                                    }
                                Text(
                                    counterText,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (totalPages > 0) {
                                    Text(
                                        "Pagina $currentPage di $totalPages",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (!loading && activeSearch == null && results.isEmpty()) {
                                Text(
                                    "Imposta i filtri o scrivi qualcosa e premi Cerca compilation.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (!loading && activeSearch != null && totalDiscogsResults == 0) {
                                Text(
                                    "Nessuna compilation trovata con questi criteri.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                            LaunchedEffect(activeSearch, currentPage, totalPages) {
                                loadNextPage()
                            }
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

                    item { Spacer(Modifier.height(24.dp)) }
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
    onPlayFull: () -> Unit,
    onPlayAll: () -> Unit,
    onSave: () -> Unit,
    onTrackClick: (Int) -> Unit,
    onDiscogsClick: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onTrackClick(index) }.padding(
                    horizontal = 16.dp,
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
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "$label: $selected",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
