package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * R31: lazily restore artwork using the chart's own metadata first.
 * For Last.fm entries without an image, reuse MusicLab's existing YouTube
 * search, limited to visible rows. Never block the player or chart list.
 */
internal object MusicLabChartArtworkResolver {
    private val cache = ConcurrentHashMap<String, String>()
    private val unavailable = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(3)

    private fun key(title: String, artist: String): String =
        artist.trim().lowercase(Locale.ROOT) + "|" + title.trim().lowercase(Locale.ROOT)

    fun sameRecordingLabel(title: String, artist: String, candidate: SongItem): Boolean {
        fun normalized(value: String) =
            value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
        val candidateTitle = normalized(candidate.title)
        val wantedTitle = normalized(title)
        if (wantedTitle.isEmpty() || candidateTitle != wantedTitle) return false
        return candidate.artists.any { performer ->
            val a = normalized(performer.name)
            val b = normalized(artist)
            a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))
        }
    }

    suspend fun resolve(track: MusicLabChartTrack): String? {
        track.artwork?.takeIf { it.startsWith("https://") }?.let { return it }
        val id = key(track.title, track.artist)
        cache[id]?.let { return it }
        if (id in unavailable) return null

        return gate.withPermit {
            cache[id]?.let { return@withPermit it }
            if (id in unavailable) return@withPermit null
            val art = withTimeoutOrNull(3500L) {
                withContext(Dispatchers.IO) {
                    YouTube.searchSummary("${track.artist} ${track.title}", incognito = true)
                        .getOrNull()?.summaries?.flatMap { it.items }
                        ?.filterIsInstance<SongItem>()
                        ?.firstOrNull { sameRecordingLabel(track.title, track.artist, it) }
                        ?.thumbnail?.takeIf { it.startsWith("https://") }
                }
            }
            if (art.isNullOrBlank()) unavailable.add(id) else cache[id] = art
            art
        }
    }
}

@Composable
internal fun MusicLabChartArtwork(track: MusicLabChartTrack) {
    val image by produceState<String?>(initialValue = track.artwork,
        key1 = track.title, key2 = track.artist, key3 = track.artwork) {
        value = MusicLabChartArtworkResolver.resolve(track)
    }
    Box(
        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (!image.isNullOrBlank()) {
            AsyncImage(
                model = image, contentDescription = "Copertina: ${track.title}",
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Text("♫", color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 27.sp, fontWeight = FontWeight.Bold)
        }
    }
}
