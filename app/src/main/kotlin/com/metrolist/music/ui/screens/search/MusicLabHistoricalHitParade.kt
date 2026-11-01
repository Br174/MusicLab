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

    /** Validate chart identity separately from artwork and player availability. */
    private fun validSong(title: String, artist: String): Boolean =
        title.isNotBlank() && artist.any { it.isLetter() } &&
            !title.startsWith("P:", ignoreCase = true) &&
            !artist.startsWith("P:", ignoreCase = true) &&
            !artist.contains("MAX:", ignoreCase = true)

    private fun splitOldTop10(text: String): Pair<String, String>? {
        // The 1981-1984 archive uses both "Title-Artist" and "Title - Artist".
        val separator = text.lastIndexOf('-')
        if (separator < 1 || separator >= text.lastIndex) return null
        val title = text.substring(0, separator).trim()
        val artist = text.substring(separator + 1).trim()
        return if (validSong(title, artist)) title to artist else null
    }

    private fun chartHeading(row: org.jsoup.nodes.Element): String {
        // Avoid confusing the h5 rank, numeric chart movement and its links
        // with the song headline. Collect only siblings before the first detail <p>.
        val result = StringBuilder()
        var pastRank = false
        for (node in row.childNodes()) {
            if (node is org.jsoup.nodes.Element && node.selectFirst("h5") != null) {
                pastRank = true
                continue
            }
            if (!pastRank) continue
            if (node is org.jsoup.nodes.Element && node.tagName() == "p") break
            when (node) {
                is org.jsoup.nodes.TextNode -> result.append(node.getWholeText())
                is org.jsoup.nodes.Element -> result.append(node.text())
            }
        }
        return result.toString().replace('\u00a0', ' ').trim()
    }

    /**
     * Parse the original weekly markup rather than a repaired DOM: the source
     * uses invalid <p><h5> and unclosed <li> nodes, which are reparented by
     * HTML parsers. Real-page fixtures guard every generation (1959..2026).
     */
    private fun parseOriginalListSource(html: String): List<HistoricalHit> {
        val blocks = Regex("(?is)<li\\b[^>]*>(.*?)(?=<li\\b|</ul>)")
        val ranks = Regex("(?is)<h5[^>]*>\\s*(\\d{1,3})\\s*</h5>")
        val paragraphs = Regex("(?is)<p\\b[^>]*>(.*?)</p>")
        val tracklist = Regex("(?is)Tracklist\\s*:\\s*<b[^>]*>(.*?)</b>")
        val movement = Regex("^[+-]?\\d{1,3}(?=\\p{L})")
        val result = mutableListOf<HistoricalHit>()
        for (match in blocks.findAll(html)) {
            val block = match.groupValues[1]
            val rank = ranks.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (rank !in 1..200) continue
            val afterRank = block.substringAfter("</h5>", "")
            if (afterRank.isBlank()) continue
            val headlineMarkup = afterRank.substringBefore("<p")
            val heading = Jsoup.parseBodyFragment(headlineMarkup).text()
                .replace('\u00a0', ' ').trim()
            val cleanHeading = heading.replace(movement, "").trim()
            val track = tracklist.find(block)?.groupValues?.get(1).orEmpty()
            val sides = Jsoup.parseBodyFragment(track).text()
                .split('|').flatMap { it.split('\\') }.map { it.trim() }
                .filter { it.isNotBlank() }
            val title: String
            val artist: String
            if (sides.isNotEmpty()) {
                // The actual chart heading comes first, then the performer.
                // Match it against all A/B sides with punctuation and minor
                // spelling tolerance, but display the original heading title.
                val matchTitle = matchLegacyHeading(cleanHeading, sides) ?: continue
                title = matchTitle.first
                artist = matchTitle.second
            } else {
                // Top200 modern: heading text and the FIRST following <p>
                // contain title and performer. Never use movement or N.S.
                title = cleanHeading
                val artistParagraph = paragraphs.find(afterRank)?.groupValues?.get(1)
                    ?: continue
                artist = Jsoup.parseBodyFragment(artistParagraph).text().trim()
            }
            if (!validSong(title, artist)) continue
            fun stat(name: String): String? =
                Regex("\\b$name:\\s*([^|\\s]+)", RegexOption.IGNORE_CASE)
                    .find(Jsoup.parseBodyFragment(block).text())?.groupValues?.get(1)
            result.add(HistoricalHit(rank, title, artist, stat("MAX"), stat("NS"), stat("Pre")))
        }
        return result.distinctBy { it.rank }.sortedBy { it.rank }
    }

    private fun normalizeLegacy(s: String) =
        s.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun editDistance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                curr[j + 1] = minOf(
                    curr[j] + 1, prev[j + 1] + 1,
                    prev[j] + if (a[i] == b[j]) 0 else 1)
            }
            for (k in prev.indices) prev[k] = curr[k]
        }
        return prev[b.length]
    }

    private fun matchLegacyHeading(heading: String, sides: List<String>): Pair<String, String>? {
        val map = heading.indices.filter { heading[it].isLetterOrDigit() }
        val normalizedHeading = normalizeLegacy(heading)
        if (map.isEmpty()) return null
        var best: Pair<String, String>? = null
        var bestDistance = Int.MAX_VALUE
        for (side in sides) {
            val normalizedSide = normalizeLegacy(side)
            if (normalizedSide.length < 2) continue
            for (len in (normalizedSide.length - 2).coerceAtLeast(1)..
                (normalizedSide.length + 2).coerceAtMost(normalizedHeading.length)) {
                val distance = editDistance(
                    normalizedHeading.take(len), normalizedSide)
                val tolerance = if (normalizedSide.length >= 12) 2 else 1
                if (distance > tolerance || distance >= bestDistance) continue
                val endIndex = map[len - 1] + 1
                val title = heading.substring(0, endIndex).trim()
                val artist = heading.substring(endIndex).trim()
                if (!validSong(title, artist)) continue
                best = title to artist
                bestDistance = distance
            }
        }
        return best
    }

    /** Columns are located by their original header cells, never by position. */
    private fun parseOriginalTableSource(html: String): List<HistoricalHit> {
        val rows = Regex("(?is)<tr\\b[^>]*>(.*?)</tr>").findAll(html).map { match ->
            Regex("(?is)<td\\b[^>]*>(.*?)</td>").findAll(match.groupValues[1])
                .map { Jsoup.parseBodyFragment(it.groupValues[1]).text().trim() }.toList()
        }.toList()
        val headers = rows.firstOrNull { row ->
            row.any { it.equals("Titolo", ignoreCase = true) } &&
                row.any { it.equals("Interprete", ignoreCase = true) }
        } ?: return emptyList()
        val titleCol = headers.indexOfFirst { it.equals("Titolo", ignoreCase = true) }
        val artistCol = headers.indexOfFirst { it.equals("Interprete", ignoreCase = true) }
        val peakCol = headers.indexOfFirst { it.equals("Peak", ignoreCase = true) }
        val weeksCol = headers.indexOfFirst { it.equals("n.s.", ignoreCase = true) }
        val songs = rows.mapNotNull { cells ->
            if (cells.size <= maxOf(titleCol, artistCol)) return@mapNotNull null
            val rank = cells[0].toIntOrNull() ?: return@mapNotNull null
            val title = cells[titleCol]
            val artist = cells[artistCol]
            if (rank !in 1..200 || !validSong(title, artist)) return@mapNotNull null
            fun c(index: Int) = cells.getOrNull(index)?.takeIf { it.isNotBlank() }
            HistoricalHit(rank, title, artist, c(peakCol), c(weeksCol), c(1))
        }
        return songs.distinctBy { it.rank }.sortedBy { it.rank }
    }

    /** Each supported source layout has a different structured parser. */
    internal fun parseSongs(html: String): List<HistoricalHit> {
        // First use the source layout. Malformed legacy HTML may be
        // reparented unpredictably by DOM parsers, hiding actual track fields.
        val ranked = parseOriginalListSource(html)
        if (ranked.isNotEmpty()) return ranked
        val table = parseOriginalTableSource(html)
        if (table.isNotEmpty()) return table
        val doc = Jsoup.parse(html)

        // Tables have different column counts. 1985-2016: 4 columns;
        // 2017: 11 columns. Read the heading names, never fixed offsets.
        val tableRows = doc.select("table").firstNotNullOfOrNull { table ->
            val rows = table.select("tr")
            val names = rows.firstOrNull()?.select("th, td")?.map { it.text().trim() }
                ?: return@firstNotNullOfOrNull null
            val titleCol = names.indexOfFirst { it.equals("Titolo", ignoreCase = true) }
            val artistCol = names.indexOfFirst { it.equals("Interprete", ignoreCase = true) }
            if (titleCol < 0 || artistCol < 0) return@firstNotNullOfOrNull null
            val previousCol = if (names.size > 1) 1 else -1
            val weeksCol = names.indexOfFirst {
                it.equals("n.s.", ignoreCase = true) ||
                    it.equals("Settimane", ignoreCase = true)
            }
            val peakCol = names.indexOfFirst {
                it.equals("Peak", ignoreCase = true) ||
                    it.equals("Max", ignoreCase = true)
            }
            rows.drop(1).mapNotNull { row ->
                val cells = row.children().filter { it.tagName() in listOf("td", "th") }
                if (cells.size <= maxOf(titleCol, artistCol)) return@mapNotNull null
                val rank = cells.firstOrNull()?.text()?.trim()?.toIntOrNull()
                    ?: return@mapNotNull null
                if (rank !in 1..200) return@mapNotNull null
                val title = cells[titleCol].text().trim()
                val artist = cells[artistCol].text().trim()
                if (!validSong(title, artist)) return@mapNotNull null
                fun column(index: Int): String? = if (index in cells.indices) {
                    cells[index].text().trim().takeIf { it.isNotEmpty() }
                } else null
                HistoricalHit(rank, title, artist, column(peakCol),
                    column(weeksCol), column(previousCol))
            }
        }
        if (!tableRows.isNullOrEmpty()) {
            return tableRows.distinctBy { it.rank }.sortedBy { it.rank }
        }

        // 1981/1982 legacy Top10: numbered <ol><li>Title-Artist</li></ol>.
        val oldOrdered = doc.select("ol").firstOrNull { it.select("li").isNotEmpty() }
        if (oldOrdered != null) {
            val ordered = oldOrdered.select("li").mapIndexedNotNull { i, element ->
                val pair = splitOldTop10(element.ownText().ifBlank { element.text() })
                    ?: return@mapIndexedNotNull null
                HistoricalHit(i + 1, pair.first, pair.second, null, null, null)
            }
            if (ordered.isNotEmpty()) return ordered
        }

        // 1983/1984 Top10: plain paragraph separated by <br>, not an <li> list.
        val top10Heading = Regex("HitParadeItalia\\s*-\\s*Top\\s*10", RegexOption.IGNORE_CASE)
        val top10 = doc.select("p").firstOrNull { top10Heading.containsMatchIn(it.text()) }
        if (top10 != null) {
            val lines = top10.html()
                .split(Regex("(?i)<br\\s*/?>|\\r?\\n"))
                .map { Jsoup.parseBodyFragment(it).text().trim() }
                .filter { it.isNotBlank() && !top10Heading.containsMatchIn(it) }
            val songs = lines.mapNotNull { line ->
                splitOldTop10(line)?.let { it.first to it.second }
            }.take(10).mapIndexed { index, pair ->
                HistoricalHit(index + 1, pair.first, pair.second, null, null, null)
            }
            if (songs.isNotEmpty()) return songs
        }

        // 1959-1980 Top50 uses disc side / tracklist and a linked headline.
        // 2018+ Top200 uses a linked headline plus a separate artist <p>.
        return doc.select("li").mapNotNull { row ->
            val rank = row.selectFirst("h5")?.text()?.trim()?.toIntOrNull()
                ?: return@mapNotNull null
            if (rank !in 1..200) return@mapNotNull null
            // Jsoup places the chart headline directly on the <li> itself for
            // 1959-1980 and 2018+; walking children from <h5> can lose it
            // when the heading element is hoisted out of its malformed <p>.
            // Keep the linked-headline fallback for other historical variants.
            val ownHeading = row.ownText().replace('\u00a0', ' ').trim()
            val heading = if (ownHeading.any(Char::isLetter)) ownHeading
                else chartHeading(row)
            val trackList = row.select("p").firstOrNull {
                it.text().contains("Tracklist:", ignoreCase = true)
            }?.selectFirst("b")?.text().orEmpty()
            val sides = trackList.split('\\').flatMap { it.split('|') }
                .map { it.trim() }.filter { it.isNotBlank() }
            val title: String
            val artist: String
            if (sides.isNotEmpty()) {
                // Select the disc side actually listed in the ranking, not side A.
                val side = sides.sortedByDescending { it.length }.firstOrNull {
                    heading.contains(it, ignoreCase = true)
                }
                val headline = if (side == null) row.text() else heading
                val chosen = side ?: sides.firstOrNull {
                    headline.contains(it, ignoreCase = true)
                } ?: return@mapNotNull null
                val index = headline.indexOf(chosen, ignoreCase = true)
                if (index < 0) return@mapNotNull null
                title = chosen
                artist = headline.substring(index + chosen.length).substringBefore('(')
                    .trim().replace(Regex("\\s+"), " ")
            } else {
                // The movement number is directly prefixed to the headline,
                // e.g. "9Movin' to the sun"; never treat that digit as title.
                val withoutDelta = heading.replace(
                    Regex("^[+-]?\\d{1,3}(?=\\p{L})"), "").trim()
                val linkTitle = row.select("a[href]").map { it.text().trim() }
                    .firstOrNull { it.any(Char::isLetter) && !it.contains("MAX:") }
                title = if (withoutDelta.any { it.isLetter() }) withoutDelta
                    else linkTitle.orEmpty()
                artist = row.select("p").firstOrNull {
                    it.selectFirst("h5") == null &&
                        it.text().any(Char::isLetter) &&
                        !it.text().contains("MAX:", ignoreCase = true)
                }?.text()?.trim().orEmpty()
            }
            if (!validSong(title, artist)) return@mapNotNull null
            val details = row.text()
            fun field(label: String): String? =
                Regex("\\b$label:\\s*([^|\\s]+)", RegexOption.IGNORE_CASE)
                    .find(details)?.groupValues?.get(1)
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
