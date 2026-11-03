package com.metrolist.music.ui.screens.search

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.MusicLabListPositionStore
import com.metrolist.music.playback.MusicLabSectionMemory
import com.metrolist.music.playback.MusicLabSectionPlayback
import com.metrolist.music.playback.MusicLabSectionWorkCheckpoint
import com.metrolist.music.playback.MusicLabSectionWorkPolicy
import com.metrolist.music.ui.component.MusicLabFastHitArtwork
import com.metrolist.music.ui.component.MusicLabFastPath
import com.metrolist.music.ui.component.MusicLabTrackRecovery
import com.metrolist.music.utils.SearchRoutes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

internal enum class MusicLabHistoricalChartMode(val title: String, val isAlbum: Boolean, val annual: Boolean) {
    WEEKLY_SINGLES("Top settimanali singoli", false, false),
    WEEKLY_ALBUMS("Top settimanali album", true, false),
    ANNUAL_SINGLES("Top annuali singoli", false, true),
    ANNUAL_ALBUMS("Top annuali album", true, true),
}

@Composable
internal fun MusicLabHistoricalChartModeMenu(
    selected: MusicLabHistoricalChartMode,
    onModeChange: (MusicLabHistoricalChartMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected.title + "  ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MusicLabHistoricalChartMode.entries.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.title, fontWeight =
                        if (category == selected) FontWeight.Bold else FontWeight.Normal) },
                    onClick = {
                        expanded = false
                        onModeChange(category)
                    },
                )
            }
        }
    }
}

/** The route stays unchanged. Weekly singles retains its verified R46 parser and playback. */
@Composable
internal fun MusicLabHistoricalHitParadeScreen(navController: NavController) {
    val context = LocalContext.current
    var mode by remember {
        mutableStateOf(runCatching {
            MusicLabHistoricalChartMode.valueOf(
                MusicLabSectionMemory.text(context, "hitparade", "chart_mode",
                    MusicLabHistoricalChartMode.WEEKLY_SINGLES.name))
        }.getOrDefault(MusicLabHistoricalChartMode.WEEKLY_SINGLES))
    }
    val onModeChange: (MusicLabHistoricalChartMode) -> Unit = {
        mode = it
        MusicLabSectionMemory.putText(context, "hitparade", "chart_mode", it.name)
    }
    if (mode == MusicLabHistoricalChartMode.WEEKLY_SINGLES) {
        MusicLabHistoricalHitParadeWeeklySinglesScreen(navController, mode, onModeChange)
    } else {
        MusicLabHistoricalExtraChartsScreen(navController, mode, onModeChange)
    }
}

internal data class HistoricalExtraRow(
    val rank: Int, val title: String, val artist: String, val artwork: String? = null,
)
internal data class HistoricalExtraResult(
    val sourceUrl: String, val actualDate: Long, val rows: List<HistoricalExtraRow>,
)

/** R47: only explicit first-party HPI archives. No invented year, week or album. */
internal object MusicLabHistoricalExtraRepository {
    private const val DOMAIN = "https://hitparadeitalia.it"
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
    private val weekHref = Regex("(?i)^https?://(?:www\\.)?hitparadeitalia\\.it/hp_weeka/(\\d{4})/hpa(\\d{8})\\.html$")
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { isLenient = false }

    private fun download(url: String): String {
        val uri = java.net.URI(url)
        require(uri.scheme == "https" && uri.host in setOf("hitparadeitalia.it",
            "www.hitparadeitalia.it") && !url.contains("..")) { "Fonte non autorizzata" }
        val request = Request.Builder().url(url)
            .header("User-Agent", "MusicLab-HitParade/1.1")
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Archivio non disponibile (HTTP " + response.code + ")")
            response.body?.string() ?: error("Pagina originale vuota")
        }
    }

    internal fun annualUrl(mode: MusicLabHistoricalChartMode, year: Int): String {
        require(mode.annual)
        require(year in (if (mode.isAlbum) 1959 else 1947)..Calendar.getInstance().get(Calendar.YEAR))
        return if (mode.isAlbum) "$DOMAIN/hp_yenda/lpe$year.htm"
            else "$DOMAIN/hp_yends/hpe$year.htm"
    }

    internal fun weeklyAlbumIndexUrl(year: Int): String {
        require(year in 1959..2001)
        return "$DOMAIN/hp_weeka/$year/index.html"
    }

    internal fun publishedAlbumWeeks(html: String, year: Int): List<HistoricalWeek> {
        val doc = Jsoup.parse(html, weeklyAlbumIndexUrl(year))
        return doc.select("a[href]").mapNotNull { a ->
            val url = a.absUrl("href").substringBefore('?').substringBefore('#')
            val match = weekHref.matchEntire(url) ?: return@mapNotNull null
            if (match.groupValues[1] != year.toString()) return@mapNotNull null
            val date = synchronized(dayFormat) {
                runCatching { dayFormat.parse(match.groupValues[2]) }.getOrNull()
            } ?: return@mapNotNull null
            HistoricalWeek(date.time, url)
        }.distinctBy { it.millis }.sortedBy { it.millis }
    }

    private fun sourceArtwork(element: org.jsoup.nodes.Element): String? {
        val img = element.selectFirst("img[src]") ?: return null
        val url = img.absUrl("src")
        return url.takeIf { it.startsWith("https://") &&
            java.net.URI(it).host?.endsWith("hitparadeitalia.it") == true }
    }

    /** 1947+ annual singles: ordered list, title - artist [weekly peak]. */
    internal fun parseAnnualSingles(html: String): List<HistoricalExtraRow> {
        val doc = Jsoup.parse(html)
        val rankPattern = Regex("^\\s*(\\d{1,3})\\s*[.\\-]\\s*")
        val peak = Regex("\\s*\\[#\\d+[^\\]]*\\]\\s*$")
        val candidates = doc.select("ol li").ifEmpty {
            doc.select("li").filter { it.text().matches(Regex("^\\s*\\d+[.]\\s+.*")) }
        }
        return candidates.mapIndexedNotNull { index, li ->
            var headline = li.text().trim()
            val explicit = rankPattern.find(headline)
            val rank = explicit?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
            headline = headline.replaceFirst(rankPattern, "").replace(peak, "").trim()
            val separator = headline.lastIndexOf(" - ")
            if (separator <= 0) return@mapIndexedNotNull null
            val title = headline.substring(0, separator).trim()
            val artist = headline.substring(separator + 3).trim()
            if (rank !in 1..200 || title.isBlank() || artist.isBlank()) return@mapIndexedNotNull null
            HistoricalExtraRow(rank, title, artist, sourceArtwork(li))
        }.distinctBy { it.rank }.sortedBy { it.rank }
    }

    /** Annual albums: real rank/album/artist table; never treat tracklist as rows. */
    internal fun parseAnnualAlbums(html: String): List<HistoricalExtraRow> {
        val doc = Jsoup.parse(html)
        val result = mutableListOf<HistoricalExtraRow>()
        for (table in doc.select("table")) {
            val head = table.select("tr").firstOrNull {
                val t = it.text().lowercase(Locale.ROOT)
                t.contains("album") && (t.contains("artist") || t.contains("interprete"))
            } ?: continue
            val headers = head.select("th, td").map { it.text().trim().lowercase(Locale.ROOT) }
            val titleCol = headers.indexOfFirst { it.contains("album") }
            val artistCol = headers.indexOfFirst { it.contains("artist") || it.contains("interprete") }
            if (titleCol < 0 || artistCol < 0) continue
            for (row in table.select("tr")) {
                val cells = row.select("> td")
                if (cells.size <= maxOf(titleCol, artistCol)) continue
                val rank = cells.firstOrNull()?.text()?.trim()?.toIntOrNull() ?: continue
                val title = cells[titleCol].ownText().trim().ifBlank {
                    cells[titleCol].text().substringBefore("[+").trim()
                }
                val artist = cells[artistCol].text().trim()
                if (rank !in 1..200 || title.isBlank() || artist.isBlank()) continue
                result.add(HistoricalExtraRow(rank, title, artist, sourceArtwork(cells[titleCol])))
            }
            if (result.isNotEmpty()) break
        }
        return result.distinctBy { it.rank }.sortedBy { it.rank }
    }

    /** Weekly album archives have <h5> ranks and separate title/artist blocks. */
    internal fun parseWeeklyAlbums(html: String): List<HistoricalExtraRow> {
        val doc = Jsoup.parse(html)
        return doc.select("li").mapNotNull { li ->
            val rank = li.selectFirst("h5")?.text()?.trim()?.toIntOrNull()
                ?: return@mapNotNull null
            if (rank !in 1..200) return@mapNotNull null
            val titleNode = li.selectFirst(
                ".album-dettagli-titolo, .disco-dettagli-titolo, .brano-dettagli-titolo")
            val artistNode = li.selectFirst(
                ".album-dettagli-artista, .disco-dettagli-artista, .brano-dettagli-artista")
            val lines = li.wholeText().lines().map { it.trim() }.filter { it.isNotEmpty() }
            val afterRank = lines.dropWhile { it != rank.toString() }.drop(1)
            val title = titleNode?.text()?.trim() ?: afterRank.firstOrNull().orEmpty()
            val artist = artistNode?.text()?.trim() ?: afterRank.getOrNull(1).orEmpty()
            if (title.isBlank() || artist.isBlank() || title.startsWith("P:")) return@mapNotNull null
            HistoricalExtraRow(rank, title, artist, sourceArtwork(li))
        }.distinctBy { it.rank }.sortedBy { it.rank }
    }

    suspend fun load(mode: MusicLabHistoricalChartMode, whenMillis: Long): HistoricalExtraResult =
        withContext(Dispatchers.IO) {
            val day = Calendar.getInstance().apply { timeInMillis = whenMillis }
            val year = day.get(Calendar.YEAR)
            if (mode.annual) {
                val url = annualUrl(mode, year)
                val html = download(url)
                val rows = if (mode.isAlbum) parseAnnualAlbums(html) else parseAnnualSingles(html)
                if (rows.isEmpty()) error("Classifica " + year + " non disponibile in formato leggibile")
                return@withContext HistoricalExtraResult(url, whenMillis, rows)
            }
            require(mode == MusicLabHistoricalChartMode.WEEKLY_ALBUMS)
            val index = weeklyAlbumIndexUrl(year)
            val weeks = publishedAlbumWeeks(download(index), year)
            if (weeks.isEmpty()) error("Nessuna settimana album pubblicata per il " + year)
            val target = MusicLabHistoricalHitParade.saturdayOf(day).timeInMillis
            val available = weeks.lastOrNull { it.millis <= target } ?: weeks.first()
            val rows = parseWeeklyAlbums(download(available.url))
            if (rows.isEmpty()) error("Classifica album non leggibile: " + available.url.substringAfterLast('/'))
            HistoricalExtraResult(available.url, available.millis, rows)
        }

    fun loadSaved(context: android.content.Context, key: String): HistoricalExtraResult? =
        runCatching {
            val o = MusicLabSectionMemory.readState(context, "hitparade:extra:$key")
            val a = o.optJSONArray("rows") ?: return@runCatching null
            val rows = (0 until a.length()).mapNotNull { i ->
                val row = a.optJSONObject(i) ?: return@mapNotNull null
                val rank = row.optInt("rank")
                val title = row.optString("title")
                val artist = row.optString("artist")
                if (rank !in 1..200 || title.isBlank() || artist.isBlank()) return@mapNotNull null
                HistoricalExtraRow(rank, title, artist,
                    row.optString("image").takeIf(String::isNotBlank))
            }
            if (rows.isEmpty()) null else HistoricalExtraResult(
                o.getString("url"), o.getLong("when"), rows)
        }.getOrNull()

    fun save(context: android.content.Context, key: String, chart: HistoricalExtraResult) {
        MusicLabSectionMemory.writeState(context, "hitparade:extra:$key", JSONObject().apply {
            put("url", chart.sourceUrl)
            put("when", chart.actualDate)
            put("rows", JSONArray().apply {
                chart.rows.take(200).forEach { item ->
                    put(JSONObject().apply {
                        put("rank", item.rank)
                        put("title", item.title)
                        put("artist", item.artist)
                        put("image", item.artwork.orEmpty())
                    })
                }
            })
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicLabHistoricalExtraChartsScreen(
    navController: NavController,
    mode: MusicLabHistoricalChartMode,
    onModeChange: (MusicLabHistoricalChartMode) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = LocalPlayerConnection.current
    val defaultYear = if (mode == MusicLabHistoricalChartMode.WEEKLY_ALBUMS) 1995 else 1995
    val initialDate = remember(mode) {
        Calendar.getInstance().apply {
            set(defaultYear, Calendar.JUNE, 3, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val section = "hitparade-extra:" + mode.name
    var selectedMillis by remember(mode) {
        mutableStateOf(MusicLabSectionMemory.number(
            context, section, "date", initialDate))
    }
    val key = mode.name + ":" + (if (mode.annual)
        Calendar.getInstance().apply { timeInMillis = selectedMillis }.get(Calendar.YEAR)
        else selectedMillis)
    var chart by remember(key) {
        mutableStateOf(MusicLabHistoricalExtraRepository.loadSaved(context, key))
    }
    var loading by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var resolvingRank by remember(key) { mutableStateOf<Int?>(null) }
    var tapSerial by remember(key) { mutableStateOf(0L) }
    val selectedKeys by MusicLabListPositionStore.selectedKeys.collectAsStateWithLifecycle()
    val positionKey = "hitparade:extra:position:" + key
    val initialPosition = remember(positionKey) {
        MusicLabListPositionStore.attach(context)
        MusicLabListPositionStore.get(positionKey)
    }
    val listState = remember(positionKey) {
        LazyListState(initialPosition.index, initialPosition.offset)
    }
    var restorePending by remember(positionKey) {
        mutableStateOf(initialPosition.index > 0 || initialPosition.offset > 0)
    }
    LaunchedEffect(positionKey, chart?.rows?.size) {
        if (restorePending && chart != null && chart!!.rows.size + 2 > initialPosition.index) {
            listState.scrollToItem(initialPosition.index, initialPosition.offset)
            restorePending = false
        }
    }
    DisposableEffect(positionKey, listState) {
        onDispose {
            if (!restorePending) {
                MusicLabListPositionStore.scroll(positionKey,
                    listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                MusicLabListPositionStore.flush(positionKey)
            }
        }
    }
    LaunchedEffect(positionKey, listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) ->
                if (!restorePending) MusicLabListPositionStore.scroll(positionKey, index, offset)
            }
    }
    LaunchedEffect(mode, key) {
        MusicLabSectionMemory.putNumber(context, section, "date", selectedMillis)
        val cache = MusicLabHistoricalExtraRepository.loadSaved(context, key)
        chart = cache
        val checkpoint = MusicLabSectionWorkCheckpoint.read(
            context, "hitparade-extra", key)
        if (!MusicLabSectionWorkPolicy.shouldLoad(cache != null, checkpoint.phase)) return@LaunchedEffect
        loading = cache == null
        error = null
        if (checkpoint.phase == MusicLabSectionWorkCheckpoint.Phase.COMPLETE)
            MusicLabSectionWorkCheckpoint.reset(context, "hitparade-extra", key)
        if (!MusicLabSectionWorkCheckpoint.begin(context, "hitparade-extra", key)) {
            loading = false
            return@LaunchedEffect
        }
        try {
            val result = MusicLabHistoricalExtraRepository.load(mode, selectedMillis)
            chart = result
            MusicLabHistoricalExtraRepository.save(context, key, result)
            MusicLabSectionWorkCheckpoint.complete(context, "hitparade-extra", key, result.rows.size)
        } catch (cancel: CancellationException) {
            MusicLabSectionWorkCheckpoint.standby(context, "hitparade-extra", key)
            throw cancel
        } catch (e: Exception) {
            MusicLabSectionWorkCheckpoint.standby(context, "hitparade-extra", key)
            if (chart == null) error = e.message ?: "Classifica non disponibile"
        } finally { loading = false }
    }
    val formatter = remember { SimpleDateFormat("dd MMMM yyyy", Locale.ITALIAN) }
    val bottomPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom)
        .asPaddingValues().calculateBottomPadding()
    fun shift(delta: Int) {
        val c = Calendar.getInstance().apply { timeInMillis = selectedMillis }
        c.add(if (mode.annual) Calendar.YEAR else Calendar.DAY_OF_MONTH,
            if (mode.annual) delta else 7 * delta)
        val requestedYear = c.get(Calendar.YEAR)
        val minYear = if (mode == MusicLabHistoricalChartMode.ANNUAL_SINGLES) 1947 else 1959
        val maxYear = if (mode == MusicLabHistoricalChartMode.WEEKLY_ALBUMS) 2001
            else Calendar.getInstance().get(Calendar.YEAR)
        if (requestedYear in minYear..maxYear) selectedMillis = c.timeInMillis
    }
    fun selectDate() {
        val date = Calendar.getInstance().apply { timeInMillis = selectedMillis }
        val picker = DatePickerDialog(context, { _, year, month, day ->
            selectedMillis = Calendar.getInstance().apply {
                set(year, if (mode.annual) Calendar.JANUARY else month,
                    if (mode.annual) 1 else day, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH))
        picker.datePicker.minDate = Calendar.getInstance().apply {
            set(if (mode == MusicLabHistoricalChartMode.ANNUAL_SINGLES) 1947 else 1959,
                Calendar.JANUARY, 1)
        }.timeInMillis
        picker.datePicker.maxDate = if (mode == MusicLabHistoricalChartMode.WEEKLY_ALBUMS) {
            Calendar.getInstance().apply {
                set(2001, Calendar.DECEMBER, 31)
            }.timeInMillis
        } else System.currentTimeMillis()
        picker.show()
    }
    Scaffold(topBar = {
        TopAppBar(title = {
            MusicLabHistoricalHeaderTitle(mode,
                chart?.actualDate ?: selectedMillis)
        }, navigationIcon = {
            IconButton(onClick = { navController.navigateUp() }) {
                Icon(painter = painterResource(R.drawable.arrow_back),
                    contentDescription = "Indietro")
            }
        })
    }) { insets ->
        if (chart == null && restorePending) {
            Box(Modifier.fillMaxSize().padding(insets)) {
                if (loading) CircularProgressIndicator()
            }
        } else LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp,
                bottom = bottomPadding + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "category_and_date") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MusicLabHistoricalChartModeMenu(mode, onModeChange)
                    Text(if (mode.annual) "Seleziona l'anno della classifica"
                        else "Seleziona una settimana con album pubblicati")
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { shift(-1) }) { Text("‹") }
                        OutlinedButton(onClick = ::selectDate, modifier = Modifier.weight(1f)) {
                            Text(if (mode.annual)
                                Calendar.getInstance().apply { timeInMillis = selectedMillis }
                                    .get(Calendar.YEAR).toString()
                                else formatter.format(java.util.Date(selectedMillis)))
                        }
                        OutlinedButton(onClick = { shift(1) }) { Text("›") }
                    }
                    chart?.let { current ->
                        Text(mode.title + " · " + current.rows.size + " risultati",
                            fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse(current.sourceUrl)))
                        }) { Text("Fonte: Hit Parade Italia · Visualizza originale") }
                    }
                    if (mode == MusicLabHistoricalChartMode.WEEKLY_ALBUMS) {
                        Text("Archivio album settimanali: anni effettivamente pubblicati fino al 2001.",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (loading) item(key = "loading") {
                Row { CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(" Caricamento della classifica") }
            }
            error?.let { message -> item(key = "error") {
                Text(message, color = MaterialTheme.colorScheme.error)
            } }
            chart?.let { current ->
                items(current.rows, key = { "hpi_extra_" + it.rank }) { row ->
                    // When a source record repeats the album title as artist,
                    // use the catalogue-verified artist without changing chart ranks.
                    val artworkRevision by MusicLabHitParadeDiscArtwork.updates.collectAsStateWithLifecycle()
                    val displayArtist = if (mode.isAlbum)
                        MusicLabHitParadeDiscArtwork.correctedArtist(context, row.title, row.artist)
                            ?: row.artist else row.artist
                    Row(modifier = Modifier.fillMaxWidth()
                        .background(if (selectedKeys[positionKey] == row.rank.toString())
                            MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable {
                            MusicLabListPositionStore.select(positionKey, row.rank.toString())
                            if (mode.isAlbum) {
                                navController.navigate(
                                    SearchRoutes.resultRoute(row.title + " " + displayArtist))
                            } else {
                                val serial = ++tapSerial
                                resolvingRank = row.rank
                                scope.launch {
                                    try {
                                        val song = MusicLabHitParadeVideoResolver.find(
                                            context, row.title, row.artist)
                                        if (serial != tapSerial) return@launch
                                        if (song != null) player?.let { connection ->
                                            connection.beginPlaybackPriorityBurst("historical-annual-tap")
                                            val ordered = current.rows.map { r ->
                                                MusicLabSectionPlayback.Entry(
                                                    r.rank.toString(), r.title, r.artist)
                                            }
                                            MusicLabHitParadeDiscArtwork.registerPlaybackRows(
                                                positionKey, ordered, connection)
                                            MusicLabSectionPlayback.select(connection,
                                                source = mode.title,
                                                orderedEntries = ordered,
                                                selectedIndex = current.rows.indexOfFirst {
                                                    it.rank == row.rank },
                                                selected = MusicLabHitParadeDiscArtwork.playableMediaItem(
                                                    context, row.title, row.artist, song),
                                                findPlayable = { entry ->
                                                    MusicLabHitParadeVideoResolver.find(context,
                                                        entry.title, entry.artist)?.let { playable ->
                                                        MusicLabHitParadeDiscArtwork.playableMediaItem(
                                                            context, entry.title, entry.artist, playable)
                                                    }
                                                },
                                                selectionKey = positionKey)
                                        } else {
                                            error = "Registrazione non trovata: " + row.title
                                        }
                                    } catch (_: Exception) {
                                        if (serial == tapSerial) error = "Ascolto non disponibile"
                                    } finally {
                                        if (serial == tapSerial) resolvingRank = null
                                    }
                                }
                            }
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(row.rank.toString(), style = MaterialTheme.typography.titleMedium)
                        MusicLabHitParadeDiscCover(
                            title = row.title,
                            artist = row.artist,
                            album = mode.isAlbum,
                            sourceArtwork = row.artwork)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.title, fontWeight = FontWeight.SemiBold)
                            Text(displayArtist, style = MaterialTheme.typography.bodySmall)
                        }
                        if (resolvingRank == row.rank) CircularProgressIndicator(
                            modifier = Modifier.size(20.dp))
                        if (!mode.isAlbum) MusicLabHitParadeVideoMenu(row.title, row.artist)
                    }
                }
            }
            item(key = "attribution") {
                Text("Classifiche originali: Hit Parade Italia. Album e singoli " +
                    "sono identificati separatamente; nessun dato inventato.",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
