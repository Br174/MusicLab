package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.metrolist.music.BuildConfig
import com.metrolist.music.constants.LastFMApiKeyKey
import com.metrolist.music.constants.LastFMSecretKey
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.lastfm.LastFM
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.MusicLabSectionPlayback
import com.metrolist.music.playback.MusicLabListPositionStore
import com.metrolist.music.playback.CoverPlaybackMemory
import com.metrolist.music.playback.queues.YouTubeQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

internal data class MusicLabChartTrack(
    val rank: Int,
    val title: String,
    val artist: String,
    val youtubeId: String? = null,
    val artwork: String? = null,
    val sourceArtworkTrusted: Boolean = true,
)

internal data class MusicLabChartResult(
    val source: String,
    val tracks: List<MusicLabChartTrack>,
)

internal object MusicLabChartsRepository {
    private val youtubeCountries = setOf(
        "AR", "AU", "AT", "BE", "BO", "BR", "CA", "CL", "CO", "CR",
        "CZ", "DK", "DO", "EC", "EG", "SV", "EE", "FI", "FR", "DE",
        "GT", "HN", "HK", "HU", "IS", "IN", "ID", "IE", "IL", "IT",
        "JP", "KE", "LU", "MY", "MX", "NL", "NZ", "NI", "NG", "NO",
        "PA", "PY", "PE", "PH", "PL", "PT", "RO", "RU", "SA", "RS",
        "SG", "ZA", "KR", "ES", "SE", "CH", "TW", "TZ", "TH", "TR",
        "UG", "GB", "UA", "AE", "UY", "US", "VN", "ZW"
    )

    /**
     * Source policy R29: Spotify/FIMI are NOT repurposed as a YouTube playback
     * chart. Spotify editorial APIs have restrictions and FIMI has no verified
     * reusable public API. Last.fm is attempted using the EXISTING API key
     * (non-commercial only), then genuine regional YouTube Music charts.
     * Every result retains its actual provider label and native rank.
     */
    suspend fun load(chart: ExploreChart): Result<MusicLabChartResult> = runCatching {
        val country = MusicLabSearchExploreCatalog.lastFmCountry(chart)
        val iso = MusicLabSearchExploreCatalog.countryCode(chart)

        // R30: the geo/global chart endpoints need only the API Key.
        // Account login/scrobbling still use LastFM.isInitialized() with the
        // Shared Secret; don't incorrectly gate chart access on both fields.
        var lastFmDiagnostic = "API Key Last.fm non configurata"
        if (LastFM.hasChartApiKey()) {
            val result = withTimeoutOrNull(5_000L) {
                LastFM.topTracksByRegion(country)
            }
            val top = result?.getOrNull()
            if (!top.isNullOrEmpty()) return@runCatching MusicLabChartResult(
                source = "Last.fm · brani popolari per Paese",
                tracks = top.take(50).map {
                    // Last.fm frequently supplies a successful HTTP image that is
                    // only its generic white-star placeholder. It must not
                    // override real album artwork from verified catalogues.
                    MusicLabChartTrack(it.rank, it.title, it.artist,
                        artwork = it.artwork, sourceArtworkTrusted = false)
                },
            )
            lastFmDiagnostic = when {
                result == null -> "Last.fm non ha risposto entro 5 secondi"
                result.isFailure -> "Last.fm ha respinto o non ha restituito i dati richiesti"
                else -> "Last.fm ha restituito una classifica vuota"
            }
        }

        // Chart localization is per-request, not global playback locale.
        // Only countries officially supported by YouTube charts can fall back.
        if (iso != null && iso in youtubeCountries) {
            val charts = withTimeoutOrNull(8_000L) {
                YouTube.getChartsPage(countryCode = iso).getOrNull()
            }
            val songs = charts?.sections.orEmpty().firstOrNull { section ->
                section.title.contains("Top songs", ignoreCase = true) &&
                    section.items.any { it is SongItem }
            }?.items?.filterIsInstance<SongItem>().orEmpty()
            if (songs.isNotEmpty()) return@runCatching MusicLabChartResult(
                source = "YouTube Music · classifiche per Paese",
                tracks = songs.take(50).mapIndexed { index, song ->
                    MusicLabChartTrack(
                        rank = song.chartPosition?.takeIf { it > 0 } ?: index + 1,
                        title = song.title,
                        artist = song.artists.firstOrNull()?.name.orEmpty(),
                        youtubeId = song.id,
                        artwork = song.thumbnail,
                    )
                },
            )
        }
        error("Classifica non disponibile per ${chart.title}. " +
            lastFmDiagnostic + ". Anche YouTube Music non ha restituito una classifica verificata.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicLabChartScreen(navController: NavController, title: String) {
    val chart = remember(title) {
        (MusicLabSearchExploreCatalog.featured + MusicLabSearchExploreCatalog.countries)
            .firstOrNull { it.title == title }
    }
    val context = LocalContext.current
    val player = LocalPlayerConnection.current
    // Reuse the exact artwork pinned by the currently visible mini-player.
    val playingMetadata = player?.mediaMetadata?.collectAsState()?.value
        ?.let { CoverPlaybackMemory.stablePlayerMetadata(context, it) }
    val miniPlayerBottomInset = LocalPlayerAwareWindowInsets.current
        .only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    val scope = rememberCoroutineScope()
    var isLoading by remember(title) { mutableStateOf(true) }
    var chartResult by remember(title) { mutableStateOf<MusicLabChartResult?>(null) }
    var loadError by remember(title) { mutableStateOf<String?>(null) }
    var resolvingSong by remember { mutableStateOf<String?>(null) }
    val listKey = "chart:" + title
    val initialPosition = remember(listKey) {
        MusicLabListPositionStore.attach(context)
        MusicLabListPositionStore.get(listKey)
    }
    val listState = remember(listKey) { LazyListState(initialPosition.index, initialPosition.offset) }
    DisposableEffect(listKey, listState) {
        onDispose {
            MusicLabListPositionStore.scroll(listKey,
                listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            MusicLabListPositionStore.flush(listKey)
        }
    }
    LaunchedEffect(listKey, listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) -> MusicLabListPositionStore.scroll(listKey, index, offset) }
    }

    LaunchedEffect(chart) {
        isLoading = true
        loadError = null
        chartResult = MusicLabEditorialSnapshot.loadChart(context, title)
        if (chart == null) {
            loadError = "Classifica sconosciuta"
        } else {
            // R30: bind the chart loader to the EXACT same locally stored keys
            // shown under Account > Last.fm + Discogs. Never log/send secrets.
            // Read at chart-opening time to include credentials saved after
            // application startup; no logout, reinstall or manual re-entry.
            runCatching {
                withContext(Dispatchers.IO) {
                    val saved = context.dataStore.data.first()
                    val apiKey = saved[LastFMApiKeyKey].orEmpty().trim()
                        .ifBlank { BuildConfig.LASTFM_API_KEY }
                    val secret = saved[LastFMSecretKey].orEmpty().trim()
                        .ifBlank { BuildConfig.LASTFM_SECRET }
                    LastFM.initialize(apiKey = apiKey, secret = secret)
                    MusicLabChartsRepository.load(chart).getOrThrow()
                }
            }.onSuccess {
                chartResult = it
                MusicLabEditorialSnapshot.saveChart(context, title, it)
            }.onFailure {
                if (chartResult == null) loadError = it.message ?: "Classifica non accessibile"
            }
        }
        isLoading = false
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(painter = painterResource(R.drawable.arrow_back),
                        contentDescription = "Indietro")
                }
            },
        )
    }) { insets ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                top = 12.dp, bottom = miniPlayerBottomInset + 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isLoading) item(key = "loading") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Text("  Verifica classifiche disponibili...", modifier = Modifier.padding(8.dp))
                }
            }
            loadError?.let { message ->
                item(key = "error") {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        Text("Spotify resta consultabile separatamente. Non viene mostrata una " +
                            "classifica inventata né vengono aperte altre app automaticamente.")
                    }
                }
            }
            chartResult?.let { result ->
                items(result.tracks, key = { "chart_${it.rank}_${it.title}_${it.artist}" }) { track ->
                    Row(modifier = Modifier.fillMaxWidth()
                        .background(if (MusicLabListPositionStore.get(listKey).selectedKey ==
                            track.rank.toString()) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent)
                        .clickable {
                        if (resolvingSong != null) return@clickable
                        resolvingSong = "${track.rank}"
                        scope.launch {
                            try {
                                val id = track.youtubeId
                                val savedId = if (id == null)
                                    MusicLabChartArtworkResolver.rememberedVideoId(context, track)
                                    else null
                                // Same per-song resolver for artwork and playback.
                                val song: SongItem? = if (id != null || savedId != null) null
                                    else withContext(Dispatchers.IO) {
                                        MusicLabChartArtworkResolver.resolveSong(context, track)
                                    }
                                val ytId = id ?: savedId ?: song?.id
                                if (ytId != null) {
                                    val selectedItem = song?.toMediaItem() ?: MediaMetadata(
                                        id = ytId,
                                        title = track.title,
                                        artists = listOf(MediaMetadata.Artist(id = null, name = track.artist)),
                                        duration = -1,
                                        thumbnailUrl = track.artwork,
                                    ).toMediaItem()
                                    val rows = result.tracks.map { ranked ->
                                        MusicLabSectionPlayback.Entry(
                                            key = ranked.rank.toString(),
                                            title = ranked.title,
                                            artist = ranked.artist,
                                        )
                                    }
                                    player?.let { connection ->
                                        MusicLabListPositionStore.select(listKey, track.rank.toString())
                                        MusicLabSectionPlayback.select(
                                            connection = connection,
                                            source = title,
                                            orderedEntries = rows,
                                            selectedIndex = result.tracks.indexOf(track),
                                            selected = selectedItem,
                                            findPlayable = { entry ->
                                                result.tracks.firstOrNull { it.rank.toString() == entry.key }
                                                    ?.let { nextTrack ->
                                                        val found = withContext(Dispatchers.IO) {
                                                            MusicLabChartArtworkResolver.resolveSong(context, nextTrack)
                                                        }
                                                        found?.toMediaItem() ?: nextTrack.youtubeId?.let { id ->
                                                            MediaMetadata(
                                                                id = id,
                                                                title = nextTrack.title,
                                                                artists = listOf(MediaMetadata.Artist(
                                                                    id = null, name = nextTrack.artist)),
                                                                duration = -1,
                                                                thumbnailUrl = nextTrack.artwork,
                                                            ).toMediaItem()
                                                        }
                                                    }
                                            },
                                        )
                                    }
                                } else loadError = "Video verificato non trovato per ${track.title}. " +
                                    "Il brano resta nella classifica."
                            } finally { resolvingSong = null }
                        }
                    }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MusicLabChartArtwork(
                            track,
                            nowPlayingArtwork = playingMetadata?.takeIf { current ->
                                MusicLabChartArtworkResolver.matchesPlayer(
                                    track, current.title, current.artists.map { it.name })
                            }?.thumbnailUrl,
                        )
                        Column(modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(track.artist, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Text("#${track.rank}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
