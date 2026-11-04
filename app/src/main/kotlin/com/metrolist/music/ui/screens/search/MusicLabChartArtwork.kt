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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.spotify.Spotify
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
        if (!MusicLabLightArtwork.isVideoPoster(song.thumbnail))
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


    /**
     * The Last.fm chart API can return a *valid HTTPS* placeholder graphic
     * (grey box with white star). Coil successfully renders it, therefore
     * onError never fires. For charts sourced from Last.fm the source thumbnail
     * is untrusted and catalogue/album covers are resolved independently.
     *
     * Reuse MusicLab's already-available Spotify metadata when authenticated,
     * with the public Apple catalogue as a no-login fallback. Neither lookup
     * touches playback, queue, ranking, or the chart's original track list.
     */
    private fun matchingCatalogueTrack(
        requested: MusicLabChartTrack, title: String, artist: String,
    ): Boolean {
        val wantTitle = norm(requested.title).replace(
            Regex("\\s+(feat|featuring|ft)\\s+.*$"), ""
        ).trim()
        val candidateTitle = norm(title).replace(
            Regex("\\s+(feat|featuring|ft)\\s+.*$"), ""
        ).trim()
        if (titleScore(wantTitle, candidateTitle) < 5) return false
        val wantedArtist = norm(requested.artist)
        val candidateArtist = norm(artist)
        return wantedArtist.isNotBlank() && candidateArtist.isNotBlank() &&
            (wantedArtist == candidateArtist ||
                (wantedArtist.length >= 4 && candidateArtist.length >= 4 &&
                    (candidateArtist.contains(wantedArtist) ||
                        wantedArtist.contains(candidateArtist))))
    }

    private suspend fun spotifyArtwork(track: MusicLabChartTrack): String? =
        withTimeoutOrNull(2600L) {
            val found = Spotify.search(
                query = track.title + " " + track.artist,
                types = listOf("track"),
                limit = 10,
            ).getOrNull()?.tracks?.items.orEmpty()
            found.firstOrNull { candidate ->
                matchingCatalogueTrack(
                    track, candidate.name,
                    candidate.artists.joinToString(" ") { it.name },
                )
            }?.album?.images?.firstNotNullOfOrNull { allowedImage(it.url) }
        }

    private suspend fun appleArtwork(track: MusicLabChartTrack): String? =
        withTimeoutOrNull(3200L) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val query = URLEncoder.encode(
                        track.title + " " + track.artist, "UTF-8",
                    )
                    val url = URL(
                        "https://itunes.apple.com/search?term=" + query +
                            "&entity=song&media=music&limit=12&country=IT"
                    )
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 2200
                    connection.readTimeout = 2200
                    connection.setRequestProperty("Accept", "application/json")
                    try {
                        if (connection.responseCode !in 200..299) return@runCatching null
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val results = JSONObject(response).optJSONArray("results")
                            ?: return@runCatching null
                        for (index in 0 until results.length()) {
                            val item = results.optJSONObject(index) ?: continue
                            if (!matchingCatalogueTrack(
                                track, item.optString("trackName"),
                                item.optString("artistName"),
                            )) continue
                            val image = allowedImage(item.optString("artworkUrl100"))
                            if (image != null) {
                                return@runCatching image.replace(
                                    "100x100bb", "600x600bb"
                                )
                            }
                        }
                        null
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
            }
        }

    private suspend fun catalogueArtwork(track: MusicLabChartTrack): String? =
        coroutineScope {
            // Both existing metadata sources run off-main, concurrently.
            // Bound per-row requests with the same semaphore as YouTube.
            val spotify = async(Dispatchers.IO) { spotifyArtwork(track) }
            val apple = async(Dispatchers.IO) { appleArtwork(track) }
            spotify.await() ?: apple.await()
        }

    suspend fun resolve(context: Context, track: MusicLabChartTrack,
                        ignoreSourceArtwork: Boolean = false,
                        rejectedUrl: String? = null): String? {
        MusicLabLightArtwork.saved(context, track.title, track.artist, false)
            ?.takeUnless { it == rejectedUrl }?.let { return it }
        if (!ignoreSourceArtwork) {
            if (track.sourceArtworkTrusted) {
                allowedImage(track.artwork)
                    ?.takeUnless { it == rejectedUrl || MusicLabLightArtwork.isVideoPoster(it) }
                    ?.let { return it }
            }
        }
        val id = key(track)
        artworkCache[id]?.takeUnless { it == rejectedUrl || MusicLabLightArtwork.isVideoPoster(it) }?.let { return it }
        val stored = withContext(Dispatchers.IO) {
            prefs(context).getString("art:" + id, null)
        }
        allowedImage(stored)?.takeUnless { it == rejectedUrl || MusicLabLightArtwork.isVideoPoster(it) }?.let {
            artworkCache[id] = it
            return it
        }
        // The initial list loads artwork without any click or player event.
        // Never persist the generic Last.fm star as a successful cover.
        val fromCatalogue = gate.withPermit { catalogueArtwork(track) }
            ?.takeUnless { it == rejectedUrl }
        if (fromCatalogue != null) {
            rememberArtwork(context, track, fromCatalogue)
            return fromCatalogue
        }
        val song = resolveSong(context, track)
        val fromYouTube = allowedImage(song?.thumbnail)?.takeUnless { it == rejectedUrl }
        if (fromYouTube != null && !MusicLabLightArtwork.isVideoPoster(fromYouTube))
            rememberArtwork(context, track, fromYouTube)
        return fromYouTube
    }
}

@Composable
internal fun MusicLabChartArtwork(track: MusicLabChartTrack, nowPlayingArtwork: String? = null, retrySignal: Int = 0) {
    val context = LocalContext.current
    val extraRevision by MusicLabLightArtwork.revisions.collectAsStateWithLifecycle()
    var sourceFailed by remember(track.title, track.artist, track.artwork) { mutableStateOf(false) }
    var rejectedUrl by remember(track.title, track.artist) { mutableStateOf<String?>(null) }
    val image by produceState<String?>(
        initialValue = MusicLabLightArtwork.saved(context, track.title, track.artist, false)
            ?: track.artwork.takeIf { track.sourceArtworkTrusted },
        key1 = track,
        key2 = nowPlayingArtwork,
        key3 = listOf(sourceFailed, rejectedUrl, retrySignal, extraRevision),
    ) {
        // Native MusicLab artwork is displayed immediately, without awaiting any extra provider.
        value = MusicLabLightArtwork.saved(context, track.title, track.artist, false)
            ?: track.artwork.takeIf { track.sourceArtworkTrusted && !sourceFailed && it != rejectedUrl }
            ?: nowPlayingArtwork?.takeIf { it != rejectedUrl }
        if (nowPlayingArtwork != null && nowPlayingArtwork != rejectedUrl &&
            !MusicLabLightArtwork.isVideoPoster(nowPlayingArtwork)) {
            MusicLabChartArtworkResolver.rememberArtwork(context, track, nowPlayingArtwork)
        }
        if (value == null || MusicLabLightArtwork.isVideoPoster(value)) {
            MusicLabChartArtworkResolver.resolve(context, track, sourceFailed, rejectedUrl)
                ?.let { if (value == null || !MusicLabLightArtwork.isVideoPoster(it)) value = it }
        }
        // Only the uncovered rows use extra sources. No audio or chart-load coupling.
        if (retrySignal > 0 || value == null || MusicLabLightArtwork.isVideoPoster(value)) {
            MusicLabLightArtwork.recover(
                context, track.title, track.artist, manual = retrySignal > 0,
            )?.let { value = it }
        }
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
        val shownImage = image
        if (!shownImage.isNullOrBlank() && shownImage != rejectedUrl) {
            AsyncImage(
                model = shownImage, contentDescription = "Copertina: " + track.title,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
                onError = {
                    val failed = shownImage
                    rejectedUrl = failed
                    MusicLabChartArtworkResolver.forgetBrokenArtwork(context, track, failed)
                    MusicLabLightArtwork.reject(context, track.title, track.artist, false, failed)
                    if (failed == track.artwork) sourceFailed = true
                },
            )
        } else {
            Text("♫", color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 27.sp, fontWeight = FontWeight.Bold)
        }
    }
}
