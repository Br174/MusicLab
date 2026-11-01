package com.metrolist.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.ui.screens.search.MusicLabChartArtwork
import com.metrolist.music.ui.screens.search.MusicLabChartArtworkResolver
import com.metrolist.music.ui.screens.search.MusicLabChartTrack
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * MusicLab R36: share native MusicLab search-result metadata between
 * Hit Parade artwork, Hit Parade playback and Compilation track playback.
 * Neither chart sourcing nor Cover discovery is routed through this class.
 */
internal object MusicLabFastPath {
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(4)
    private val pages = ConcurrentHashMap<String, List<SongItem>>()
    private val flights = ConcurrentHashMap<String, Deferred<List<SongItem>>>()

    private fun key(title: String, artist: String): String =
        artist.trim().lowercase(Locale.ROOT) + "|" + title.trim().lowercase(Locale.ROOT)

    private suspend fun page(
        cacheKey: String,
        provider: suspend () -> List<SongItem>,
    ): List<SongItem> {
        pages[cacheKey]?.let { return it }
        val pending = flights.computeIfAbsent(cacheKey) {
            worker.async {
                gate.withPermit { withTimeoutOrNull(3_600L) { provider() }.orEmpty() }
            }
        }
        return try {
            pending.await().also { found ->
                if (found.isNotEmpty()) {
                    if (pages.size >= 800) pages.clear()
                    pages[cacheKey] = found
                }
            }
        } finally {
            if (pending.isCompleted) flights.remove(cacheKey, pending)
        }
    }

    /** One native YouTube Music search query is enough for most known songs. */
    suspend fun find(
        title: String,
        artist: String,
        compatible: (SongItem) -> Boolean,
    ): SongItem? {
        if (title.isBlank()) return null
        val searchTerm = listOf(artist.trim(), title.trim())
            .filter(String::isNotBlank).joinToString(" ")
        val id = key(title, artist)
        val summary = page("summary|" + id) {
            YouTube.searchSummary(searchTerm, incognito = true).getOrNull()
                ?.summaries?.flatMap { it.items }?.filterIsInstance<SongItem>().orEmpty()
        }
        summary.firstOrNull(compatible)?.let { return it }
        val songs = page("songs|" + id) {
            YouTube.search(searchTerm, YouTube.SearchFilter.FILTER_SONG)
                .getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
        }
        return songs.firstOrNull(compatible)
    }

    fun peek(
        title: String,
        artist: String,
        compatible: (SongItem) -> Boolean,
    ): SongItem? {
        val id = key(title, artist)
        return (pages["summary|" + id].orEmpty() + pages["songs|" + id].orEmpty())
            .firstOrNull(compatible)
    }
}

private data class FastHitArtwork(val loaded: Boolean, val url: String?)

/**
 * Visible Hit Parade rows share their verified SongItem with subsequent Play.
 * Original chart pages, rankings, and their legacy artwork fallback stay intact.
 */
@Composable
internal fun MusicLabFastHitArtwork(track: MusicLabChartTrack) {
    val context = LocalContext.current
    val compatible: (SongItem) -> Boolean = remember(track.title, track.artist) {
        { candidate -> MusicLabChartArtworkResolver.sameRecordingLabel(
            track.title, track.artist, candidate
        ) }
    }
    val cached = remember(track.title, track.artist) {
        MusicLabFastPath.peek(track.title, track.artist, compatible)
    }
    val state by produceState(
        initialValue = FastHitArtwork(cached != null, cached?.thumbnail),
        key1 = track.title,
        key2 = track.artist,
    ) {
        val song = MusicLabFastPath.find(track.title, track.artist, compatible)
        if (song != null) {
            MusicLabChartArtworkResolver.rememberArtwork(context, track, song.thumbnail)
        }
        value = FastHitArtwork(true, song?.thumbnail)
    }
    var failed by remember(track.title, track.artist) { mutableStateOf(false) }
    when {
        state.url?.startsWith("https://") == true && !failed -> AsyncImage(
            model = state.url,
            contentDescription = "Copertina: " + track.title,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
            onError = { failed = true },
        )
        state.loaded -> MusicLabChartArtwork(track)
        else -> Box(
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text("♫", color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 27.sp, fontWeight = FontWeight.Bold)
        }
    }
}
