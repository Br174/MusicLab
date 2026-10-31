package com.metrolist.music.ui.screens.search

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * R27: each chart/genre row owns its own artwork and playable-song identity.
 * Playback is an optional source of verified artwork, never the live UI image model.
 * Positive lookups are shared and persisted; misses remain retryable.
 */
internal object MusicLabChartArtworkResolver {
    private const val PREFS = "musiclab_chart_artwork_r27"
    private val artworkCache = ConcurrentHashMap<String, String>()
    private val songCache = ConcurrentHashMap<String, SongItem>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val gate = Semaphore(3)

    private fun norm(input: String): String =
        Normalizer.normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036f]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim().replace(Regex("\\s+"), " ")

    private fun key(track: MusicLabChartTrack): String =
        norm(track.artist) + "|" + norm(track.title)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun allowedImage(url: String?): String? =
        url?.takeIf { it.startsWith("https://") && it.length > 12 }

    private fun titleScore(wanted: String, proposed: String): Int {
        if (wanted.isBlank() || proposed.isBlank()) return 0
        if (wanted == proposed) return 8
        val extra = if (proposed.startsWith(wanted + " ")) proposed.removePrefix(wanted + " ") else ""
        if (extra.isNotBlank() && Regex("^(official|audio|video|lyrics?|lyric video|visualizer|remaster|remastered|radio edit|hd|hq|mv)(\\s|$)")
                .containsMatchIn(extra)) return 7
        val want = wanted.split(' ').filter { it.length > 1 }.toSet()
        val got = proposed.split(' ').filter { it.length > 1 }.toSet()
        if (want.size >= 2 && got.isNotEmpty() && want.intersect(got).size >=
            kotlin.math.ceil(want.size * 0.8).toInt() && want.intersect(got).size >= 2) return 5
        return 0
    }

    private fun score(track: MusicLabChartTrack, song: SongItem): Int {
        val title = titleScore(norm(track.title), norm(song.title))
        if (title == 0) return 0
        val artist = norm(track.artist)
        if (artist.isEmpty()) return 0
        val artistMatches = song.artists.any { performer ->
            val candidate = norm(performer.name)
            candidate == artist ||
                (candidate.length >= 4 && artist.length >= 4 &&
                    (candidate.contains(artist) || artist.contains(candidate))) ||
                (artist.split(' ').size > 1 && artist.split(' ').all { it in candidate.split(' ') })
        }
        // Some official uploads show a label/channel instead of the performer.
        val artistInTitle = norm(song.title).contains(artist) && artist.length >= 4
        if (!artistMatches && !artistInTitle) return 0
        if (Regex("\\b(karaoke|instrumental|cover|reaction|tutorial)\\b")
                .containsMatchIn(norm(song.title))) return 0
        return title + (if (artistMatches) 4 else 1)
    }

    fun sameRecordingLabel(title: String, artist: String, candidate: SongItem): Boolean =
        score(MusicLabChartTrack(0, title, artist), candidate) >= 5

    fun matchesPlayer(track: MusicLabChartTrack, title: String, artists: List<String>): Boolean {
        val wantedArtist = norm(track.artist)
        return norm(track.title) == norm(title) && wantedArtist.isNotEmpty() &&
            artists.any { performer ->
                val name = norm(performer)
                name == wantedArtist ||
                    (name.length >= 4 && wantedArtist.length >= 4 &&
                        (name.contains(wantedArtist) || wantedArtist.contains(name)))
            }
    }

    fun rememberArtwork(context: Context, track: MusicLabChartTrack, url: String?) {
        val image = allowedImage(url) ?: return
        val id = key(track)
        artworkCache[id] = image
        prefs(context).edit().putString("art:" + id, image).apply()
        retryAfter.remove(id)
    }

    fun forgetBrokenArtwork(context: Context, track: MusicLabChartTrack, failed: String) {
        val id = key(track)
        artworkCache.remove(id, failed)
        if (prefs(context).getString("art:" + id, null) == failed) {
            prefs(context).edit().remove("art:" + id).apply()
        }
    }

    fun rememberedVideoId(context: Context, track: MusicLabChartTrack): String? =
        prefs(context).getString("video:" + key(track), null)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }

    private fun rememberSong(context: Context, track: MusicLabChartTrack, song: SongItem) {
        val id = key(track)
        songCache[id] = song
        prefs(context).edit().putString("video:" + id, song.id).apply()
        rememberArtwork(context, track, song.thumbnail)
    }

    /**
     * Try multiple song-oriented searches, then the existing YouTube Music
     * song endpoint. Match title and performer before admitting a candidate.
     */
    suspend fun resolveSong(context: Context, track: MusicLabChartTrack): SongItem? {
        val id = key(track)
        songCache[id]?.let { return it }
        if ((retryAfter[id] ?: 0L) > System.currentTimeMillis()) return null
        return gate.withPermit {
            songCache[id]?.let { return@withPermit it }
            if ((retryAfter[id] ?: 0L) > System.currentTimeMillis()) return@withPermit null
            val queries = listOf(
                track.artist + " " + track.title,
                track.title + " " + track.artist + " official audio",
            )
            var best: SongItem? = null
            for (query in queries) {
                val songs = withTimeoutOrNull(3500L) {
                    withContext(Dispatchers.IO) {
                        YouTube.searchSummary(query, incognito = true).getOrNull()
                            ?.summaries?.flatMap { it.items }?.filterIsInstance<SongItem>()
                            .orEmpty()
                    }
                }.orEmpty()
                val verified = songs.filter { sameRecordingLabel(track.title, track.artist, it) }
                best = verified.maxByOrNull { score(track, it) }?.takeIf { score(track, it) >= 5 }
                if (best != null) break
            }
            if (best == null) {
                val songs = withTimeoutOrNull(5000L) {
                    withContext(Dispatchers.IO) {
                        YouTube.search(track.title + " " + track.artist,
                            YouTube.SearchFilter.FILTER_SONG).getOrNull()
                            ?.items?.filterIsInstance<SongItem>().orEmpty()
                    }
                }.orEmpty()
                val verified = songs.filter { sameRecordingLabel(track.title, track.artist, it) }
                best = verified.maxByOrNull { score(track, it) }?.takeIf { score(track, it) >= 5 }
            }
            if (best == null) retryAfter[id] = System.currentTimeMillis() + 20_000L
            else rememberSong(context, track, best)
            best
        }
    }

    suspend fun resolve(context: Context, track: MusicLabChartTrack,
                        ignoreSourceArtwork: Boolean = false,
                        rejectedUrl: String? = null): String? {
        if (!ignoreSourceArtwork) {
            allowedImage(track.artwork)?.takeUnless { it == rejectedUrl }?.let { return it }
        }
        val id = key(track)
        artworkCache[id]?.takeUnless { it == rejectedUrl }?.let { return it }
        val stored = withContext(Dispatchers.IO) {
            prefs(context).getString("art:" + id, null)
        }
        allowedImage(stored)?.takeUnless { it == rejectedUrl }?.let {
            artworkCache[id] = it
            return it
        }
        val song = resolveSong(context, track)
        return allowedImage(song?.thumbnail)?.takeUnless { it == rejectedUrl }
    }
}

@Composable
internal fun MusicLabChartArtwork(track: MusicLabChartTrack, nowPlayingArtwork: String? = null) {
    val context = LocalContext.current
    var sourceFailed by remember(track.title, track.artist, track.artwork) { mutableStateOf(false) }
    var rejectedUrl by remember(track.title, track.artist) { mutableStateOf<String?>(null) }
    val image by produceState<String?>(
        initialValue = track.artwork,
        key1 = track,
        key2 = nowPlayingArtwork,
        key3 = sourceFailed,
        key4 = rejectedUrl,
    ) {
        // Seed the song-specific persistent store without binding the artwork
        // on screen to whichever item the player happens to be playing.
        if (nowPlayingArtwork != null && nowPlayingArtwork != rejectedUrl) {
            MusicLabChartArtworkResolver.rememberArtwork(context, track, nowPlayingArtwork)
        }
        value = MusicLabChartArtworkResolver.resolve(context, track, sourceFailed, rejectedUrl)
        if (value == null) {
            delay(20_000L)
            value = MusicLabChartArtworkResolver.resolve(context, track, sourceFailed, rejectedUrl)
        }
    }
    Box(
        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (!image.isNullOrBlank() && image != rejectedUrl) {
            AsyncImage(
                model = image, contentDescription = "Copertina: " + track.title,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
                onError = {
                    val failed = image
                    rejectedUrl = failed
                    MusicLabChartArtworkResolver.forgetBrokenArtwork(context, track, failed)
                    if (failed == track.artwork) sourceFailed = true
                },
            )
        } else {
            Text("♫", color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 27.sp, fontWeight = FontWeight.Bold)
        }
    }
}
