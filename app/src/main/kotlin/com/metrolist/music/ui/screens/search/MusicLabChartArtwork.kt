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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
    // Temporary misses are retried later, not treated as permanent absence.
    private val retryAfter = ConcurrentHashMap<String, Long>()
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

    /** Reuse the mini-player cover only for the same title and performer. */
    fun matchesPlayer(track: MusicLabChartTrack, title: String, artists: List<String>): Boolean {
        fun norm(value: String) = value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
        val wantedArtist = norm(track.artist)
        return norm(track.title) == norm(title) && wantedArtist.isNotEmpty() &&
            artists.any { artist ->
                val name = norm(artist)
                name.isNotEmpty() && (name.contains(wantedArtist) || wantedArtist.contains(name))
            }
    }

    suspend fun resolve(track: MusicLabChartTrack, ignoreSourceArtwork: Boolean = false): String? {
        if (!ignoreSourceArtwork) {
            track.artwork?.takeIf { it.startsWith("https://") }?.let { return it }
        }
        val id = key(track.title, track.artist)
        cache[id]?.let { return it }
        if ((retryAfter[id] ?: 0L) > System.currentTimeMillis()) return null

        return gate.withPermit {
            cache[id]?.let { return@withPermit it }
            if ((retryAfter[id] ?: 0L) > System.currentTimeMillis()) return@withPermit null
            val art = withTimeoutOrNull(3500L) {
                withContext(Dispatchers.IO) {
                    YouTube.searchSummary("${track.artist} ${track.title}", incognito = true)
                        .getOrNull()?.summaries?.flatMap { it.items }
                        ?.filterIsInstance<SongItem>()
                        ?.firstOrNull { sameRecordingLabel(track.title, track.artist, it) }
                        ?.thumbnail?.takeIf { it.startsWith("https://") }
                }
            }
            val fallback = if (art.isNullOrBlank()) withTimeoutOrNull(5000L) {
                withContext(Dispatchers.IO) {
                    YouTube.searchSummary("${track.title} ${track.artist} official audio", incognito = true)
                        .getOrNull()?.summaries?.flatMap { it.items }
                        ?.filterIsInstance<SongItem>()
                        ?.firstOrNull { sameRecordingLabel(track.title, track.artist, it) }
                        ?.thumbnail?.takeIf { it.startsWith("https://") }
                }
            } else null
            val result = art ?: fallback
            if (result.isNullOrBlank()) {
                retryAfter[id] = System.currentTimeMillis() + 20_000L
            } else {
                cache[id] = result
                retryAfter.remove(id)
            }
            result
        }
    }
}

@Composable
internal fun MusicLabChartArtwork(track: MusicLabChartTrack, nowPlayingArtwork: String? = null) {
    var sourceFailed by remember(track.title, track.artist, track.artwork) { mutableStateOf(false) }
    var rejectedUrl by remember(track.title, track.artist) { mutableStateOf<String?>(null) }
    val image by produceState<String?>(
        initialValue = nowPlayingArtwork ?: track.artwork,
        key1 = track,
        key2 = nowPlayingArtwork,
        key3 = sourceFailed,
    ) {
        value = nowPlayingArtwork?.takeIf { it.startsWith("https://") }
            ?: MusicLabChartArtworkResolver.resolve(track, ignoreSourceArtwork = sourceFailed)
    }
    Box(
        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (!image.isNullOrBlank() && image != rejectedUrl) {
            AsyncImage(
                model = image, contentDescription = "Copertina: ${track.title}",
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
                onError = {
                    rejectedUrl = image
                    if (!sourceFailed && image == track.artwork) sourceFailed = true
                },
            )
        } else {
            Text("♫", color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 27.sp, fontWeight = FontWeight.Bold)
        }
    }
}
