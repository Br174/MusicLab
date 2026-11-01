package com.metrolist.music.ui.screens.search

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.YouTubeQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Historical chart data has a separate owner. It never overwrites the live charts. */
internal data class HistoricalHit(val rank: Int, val title: String, val artist: String,
    val peak: String?, val weeks: String?, val previous: String?)
internal data class HistoricalWeek(val millis: Long, val url: String)
internal data class HistoricalChart(val week: HistoricalWeek, val songs: List<HistoricalHit>)

internal object MusicLabHistoricalHitParade {
    private const val BASE = "https://www.hitparadeitalia.it/hp_weeks"
    private val weekPattern = Regex("hps(\\d{8})\\.html", RegexOption.IGNORE_CASE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(9, TimeUnit.SECONDS).build()
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { isLenient = false }

    internal fun saturdayOf(day: Calendar): Calendar = (day.clone() as Calendar).apply {
        val offset = (Calendar.SATURDAY - get(Calendar.DAY_OF_WEEK) + 7) % 7
        add(Calendar.DAY_OF_MONTH, offset)
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun download(url: String): String {
        require(url.startsWith("$BASE/") && !url.contains(".."))
        val request = Request.Builder().url(url).header("User-Agent", "MusicLab-HitParade/1.0").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Fonte non disponibile (HTTP ${response.code})")
            response.body?.string() ?: error("Pagina vuota")
        }
    }

    internal fun weeksFromIndex(html: String, year: Int): List<HistoricalWeek> {
        val doc = Jsoup.parse(html, "$BASE/$year/index.html")
        return doc.select("a[href]").mapNotNull { link ->
            val url = link.absUrl("href")
            val name = weekPattern.find(url)?.groupValues?.get(1) ?: return@mapNotNull null
            if (!url.startsWith("$BASE/$year/") || !name.startsWith(year.toString())) return@mapNotNull null
            val day = synchronized(dateFormat) { runCatching { dateFormat.parse(name) }.getOrNull() }
                ?: return@mapNotNull null
            HistoricalWeek(day.time, url)
        }.distinctBy { it.millis }.sortedBy { it.millis }
    }

    internal fun parseSongs(html: String): List<HistoricalHit> {
        val doc = Jsoup.parse(html)
        val entries = doc.select("li").mapNotNull { row ->
            val rank = row.selectFirst("h5")?.text()?.trim()?.toIntOrNull()
                ?: return@mapNotNull null
            if (rank !in 1..200) return@mapNotNull null
            // The site's 1959-2024 templates place a tracklist in the record
            // detail paragraph. That first side gives the exact song title.
            val title = row.select("p b").lastOrNull()?.text()
                ?.substringBefore('\\')?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val text = row.text()
            val at = text.indexOf(title, ignoreCase = true)
            if (at < 0) return@mapNotNull null
            val afterTitle = text.substring(at + title.length)
            val artist = afterTitle.substringBefore('(').trim()
                .replace(Regex("\\s+"), " ")
            if (artist.isEmpty() || artist.startsWith("P:")) return@mapNotNull null
            fun field(label: String): String? =
                Regex("\\b$label:\\s*([^|\\s]+)", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.get(1)
            HistoricalHit(rank, title, artist, field("MAX"), field("NS"), field("Pre"))
        }.distinctBy { it.rank }.sortedBy { it.rank }
        return entries
    }

    suspend fun load(day: Calendar): HistoricalChart = withContext(Dispatchers.IO) {
        val year = day.get(Calendar.YEAR)
        require(year in 1959..Calendar.getInstance().get(Calendar.YEAR)) {
            "L'archivio settimanale inizia nel 1959"
        }
        val index = download("$BASE/$year/index.html")
        val weeks = weeksFromIndex(index, year)
        if (weeks.isEmpty()) error("Nessuna settimana pubblicata nell'indice del $year")
        val target = saturdayOf(day).timeInMillis
        val chosen = weeks.firstOrNull { it.millis >= target } ?: weeks.last()
        val tracks = parseSongs(download(chosen.url))
        if (tracks.isEmpty()) error("Formato storico non ancora riconosciuto per questa settimana")
        HistoricalChart(chosen, tracks)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicLabHistoricalHitParadeScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = LocalPlayerConnection.current
    val bottomPadding = LocalPlayerAwareWindowInsets.current
        .only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    var selected by remember {
        mutableStateOf(Calendar.getInstance().apply {
            set(1980, Calendar.SEPTEMBER, 27, 12, 0, 0)
        })
    }
    var chart by remember { mutableStateOf<HistoricalChart?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf<Int?>(null) }
    val formatter = remember { SimpleDateFormat("dd MMMM yyyy", Locale.ITALIAN) }

    fun shiftWeek(delta: Int) {
        selected = (selected.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, 7 * delta)
        }
    }
    fun openCalendar() {
        val picker = DatePickerDialog(context, { _, year, month, day ->
            selected = Calendar.getInstance().apply {
                set(year, month, day, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH),
            selected.get(Calendar.DAY_OF_MONTH))
        picker.datePicker.minDate = Calendar.getInstance().apply { set(1959, 0, 1) }.timeInMillis
        picker.datePicker.maxDate = System.currentTimeMillis()
        picker.show()
    }

    LaunchedEffect(selected.timeInMillis) {
        loading = true
        chart = null
        error = null
        runCatching { MusicLabHistoricalHitParade.load(selected) }
            .onSuccess { chart = it }
            .onFailure { error = it.message ?: "Impossibile caricare la classifica" }
        loading = false
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Hit Parade Italiana") }, navigationIcon = {
            IconButton(onClick = { navController.navigateUp() }) {
                Icon(painter = painterResource(R.drawable.arrow_back),
                    contentDescription = "Indietro")
            }
        })
    }) { insets ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                top = 10.dp, bottom = bottomPadding + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "date_controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Scegli una data per riscoprire le canzoni in classifica",
                        style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { shiftWeek(-1) }) { Text("‹") }
                        OutlinedButton(onClick = ::openCalendar, modifier = Modifier.weight(1f)) {
                            Text(formatter.format(selected.time), maxLines = 1)
                        }
                        OutlinedButton(onClick = { shiftWeek(1) },
                            enabled = selected.timeInMillis < System.currentTimeMillis() - 7L * 86400000L) {
                            Text("›")
                        }
                    }
                    chart?.let { result ->
                        Text("Settimana del ${formatter.format(result.week.millis)} · " +
                            "${result.songs.size} brani",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse(result.week.url)))
                        }) { Text("Fonte: Hit Parade Italia · Visualizza l'originale") }
                    }
                }
            }
            if (loading) item(key = "loading") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("  Caricamento della settimana...", modifier = Modifier.padding(8.dp))
                }
            }
            error?.let { problem -> item(key = "error") {
                Column {
                    Text(problem, color = MaterialTheme.colorScheme.error)
                    Text("Nessuna classifica inventata. Riprovare con un'altra data.")
                }
            } }
            chart?.let { result ->
                items(result.songs, key = { "hitparade_${it.rank}" }) { song ->
                    Row(modifier = Modifier.fillMaxWidth().clickable {
                        if (resolving != null) return@clickable
                        resolving = song.rank
                        scope.launch {
                            try {
                                val match = withContext(Dispatchers.IO) {
                                    YouTube.searchSummary("${song.artist} ${song.title}", incognito = true)
                                        .getOrNull()?.summaries?.flatMap { it.items }
                                        ?.filterIsInstance<SongItem>().orEmpty()
                                        .firstOrNull { item ->
                                            MusicLabChartArtworkResolver.sameRecordingLabel(
                                                song.title, song.artist, item)
                                        }
                                }
                                if (match != null) {
                                    player?.playQueue(YouTubeQueue(
                                        endpoint = WatchEndpoint(videoId = match.id),
                                        preloadItem = match.toMediaMetadata()))
                                } else {
                                    error = "Registrazione originale non trovata: ${song.title}. " +
                                        "Il brano rimane visibile."
                                }
                            } catch (_: Exception) {
                                error = "Ascolto non disponibile. La classifica resta consultabile."
                            } finally {
                                resolving = null
                            }
                        }
                    }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${song.rank}", style = MaterialTheme.typography.titleMedium)
                        MusicLabChartArtwork(MusicLabChartTrack(
                            rank = song.rank, title = song.title, artist = song.artist))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(song.title, fontWeight = FontWeight.SemiBold)
                            Text(song.artist, style = MaterialTheme.typography.bodySmall)
                            if (song.peak != null || song.weeks != null) {
                                Text("Massimo: ${song.peak ?: "?"} · settimane: ${song.weeks ?: "?"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (resolving == song.rank)
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }
            }
            item(key = "attribution") {
                Text("Dati storici: Hit Parade Italia. Copertine e ascolto " +
                    "sono risolti separatamente dai servizi musicali già presenti.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
