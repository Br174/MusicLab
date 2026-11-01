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
import androidx.compose.foundation.layout.only
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
    private val modernLink = Regex("^$BASE/(\\d{4})/hps(\\d{8})\\.html$", RegexOption.IGNORE_CASE)
    private val classicLink = Regex("^$BASE/(\\d{2})/hp(\\d{6})\\.htm$", RegexOption.IGNORE_CASE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(9, TimeUnit.SECONDS).build()
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { isLenient = false }

    /** The site's 1981-2017 years use hpw_YEAR.htm, not YEAR/index.html. */
    internal fun indexUrl(year: Int): String =
        if (year in 1981..2017) "$BASE/hpw_$year.htm" else "$BASE/$year/index.html"

    internal fun saturdayOf(day: Calendar): Calendar = (day.clone() as Calendar).apply {
        val offset = (Calendar.SATURDAY - get(Calendar.DAY_OF_WEEK) + 7) % 7
        add(Calendar.DAY_OF_MONTH, offset)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun download(url: String): String {
        require(url.startsWith("$BASE/") && !url.contains(".."))
        val request = Request.Builder().url(url).header("User-Agent", "MusicLab-HitParade/1.1").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Fonte non disponibile (HTTP " + response.code + ")")
            response.body?.string() ?: error("Pagina vuota")
        }
    }

    /** Resolve actual first-party links instead of synthesizing invalid weeks. */
    internal fun weeksFromIndex(html: String, year: Int): List<HistoricalWeek> {
        val doc = Jsoup.parse(html, indexUrl(year))
        return doc.select("a[href]").mapNotNull { link ->
            val url = link.absUrl("href").substringBefore('#').substringBefore('?')
            val fresh = modernLink.matchEntire(url)
            val old = classicLink.matchEntire(url)
            val yearShort = (year % 100).toString().padStart(2, '0')
            val dateKey = when {
                fresh != null && fresh.groupValues[1] == year.toString() &&
                    fresh.groupValues[2].startsWith(year.toString()) ->
                    fresh.groupValues[2]
                old != null && old.groupValues[1] == yearShort &&
                    old.groupValues[2].startsWith(yearShort) ->
                    year.toString() + old.groupValues[2].takeLast(4)
                else -> return@mapNotNull null
            }
            val day = synchronized(dateFormat) {
                runCatching { dateFormat.parse(dateKey) }.getOrNull()
            } ?: return@mapNotNull null
            HistoricalWeek(day.time, url)
        }.distinctBy { it.millis }.sortedBy { it.millis }
    }

    /** Top20/50/100 tables from 1981-2017 and list entries from newer archives. */
    internal fun parseSongs(html: String): List<HistoricalHit> {
        val doc = Jsoup.parse(html)
        val tableSongs = doc.select("table.piccolo tr").mapNotNull { row ->
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.size < 4) return@mapNotNull null
            val rank = cells[0].text().trim().toIntOrNull() ?: return@mapNotNull null
            if (rank !in 1..200) return@mapNotNull null
            val title = cells[2].text().trim()
            val artist = cells[3].text().trim()
            if (title.isEmpty() || artist.isEmpty()) return@mapNotNull null
            HistoricalHit(rank, title, artist, null, null, cells[1].text().trim())
        }
        if (tableSongs.isNotEmpty()) return tableSongs.distinctBy { it.rank }.sortedBy { it.rank }

        return doc.select("li").mapNotNull { row ->
            val rank = row.selectFirst("h5")?.text()?.trim()?.toIntOrNull()
                ?: return@mapNotNull null
            if (rank !in 1..200) return@mapNotNull null
            // Heading's own text excludes copyright, writers, label and statistics.
            val heading = row.ownText().replace('\u00a0', ' ').trim()
            val trackList = row.select("p").firstOrNull {
                it.text().contains("Tracklist:", ignoreCase = true)
            }?.selectFirst("b")?.text().orEmpty()
            val sides = trackList.split('\\').map { it.trim() }.filter { it.isNotEmpty() }
            val chartedSide = sides.firstOrNull { heading.contains(it, ignoreCase = true) }
                ?: sides.firstOrNull()
            val title: String
            val artist: String
            if (!chartedSide.isNullOrBlank()) {
                // Identify the side actually ranked, not automatically side A.
                title = chartedSide
                // Some chart templates wrap the headline in a link, which
                // disappears from Element.ownText(). Fall back to row.text().
                val readable = if (heading.indexOf(title, ignoreCase = true) >= 0)
                    heading else row.text()
                val index = readable.indexOf(title, ignoreCase = true)
                if (index < 0) return@mapNotNull null
                artist = readable.substring(index + title.length).substringBefore('(')
                    .trim().replace(Regex("\\s+"), " ")
            } else {
                // On Top200 pages the YouTube-linked title is followed by a
                // separate interpreter paragraph, not by an A/B side tracklist.
                title = row.select("a[href]").firstOrNull { link ->
                    val href = link.attr("href")
                    link.text().isNotBlank() && (
                        href.contains("youtube", ignoreCase = true) ||
                        href.contains("youtu.be", ignoreCase = true))
                }?.text()?.trim().orEmpty()
                artist = row.select("p").map { it.text().trim() }.firstOrNull {
                    it.isNotBlank() && !it.startsWith("P:", ignoreCase = true) &&
                        !it.contains("MAX:", ignoreCase = true) && it != rank.toString()
                }.orEmpty()
            }
            if (title.isBlank() || artist.isBlank() || artist.startsWith("P:"))
                return@mapNotNull null
            val content = row.text()
            fun field(name: String): String? =
                Regex("\\b$name:\\s*([^|\\s]+)", RegexOption.IGNORE_CASE)
                    .find(content)?.groupValues?.get(1)
            HistoricalHit(rank, title, artist, field("MAX"), field("NS"), field("Pre"))
        }.distinctBy { it.rank }.sortedBy { it.rank }
    }

    suspend fun load(day: Calendar): HistoricalChart = withContext(Dispatchers.IO) {
        val year = day.get(Calendar.YEAR)
        require(year in 1959..Calendar.getInstance().get(Calendar.YEAR)) {
            "L'archivio settimanale inizia nel 1959"
        }
        val weeks = weeksFromIndex(download(indexUrl(year)), year)
        if (weeks.isEmpty()) error("Nessuna settimana pubblicata per l'anno " + year)
        val target = saturdayOf(day).timeInMillis
        val selectedWeek = weeks.lastOrNull { it.millis <= target } ?: weeks.first()
        val tracks = parseSongs(download(selectedWeek.url))
        if (tracks.isEmpty()) error("Formato storico non riconosciuto per questa settimana")
        HistoricalChart(selectedWeek, tracks)
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
