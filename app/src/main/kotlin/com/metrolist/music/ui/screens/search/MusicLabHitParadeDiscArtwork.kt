package com.metrolist.music.ui.screens.search

import android.content.Context
import java.lang.ref.WeakReference
import androidx.media3.common.MediaItem
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.SongItem
import com.metrolist.spotify.Spotify
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.MusicLabSectionPlayback
import com.metrolist.music.playback.MusicLabArtworkAudioGate
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.ui.component.MusicLabFastPath
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * R50: one shared, durable DISCOGRAPHIC artwork authority for HPI weekly/annual
 * singles and albums. YouTube thumbnails are temporary visual fallbacks and
 * NEVER stored as verified record sleeves.
 */
internal object MusicLabHitParadeDiscArtwork {
    internal data class RecordIdentity(val title: String, val artist: String)
    internal data class Disc(val url: String, val artist: String?)
    private const val PREFS = "musiclab_hitparade_disc_artwork_r50"
    private val covers = ConcurrentHashMap<String, String>()
    private val playbackRows = ConcurrentHashMap<String, RecordIdentity>()
    @Volatile private var attachedPlayer: WeakReference<PlayerConnection>? = null
    private val albumArtists = ConcurrentHashMap<String, String>()
    private val rejected = ConcurrentHashMap.newKeySet<String>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val gate = Semaphore(3)
    private val updatesFlow = MutableStateFlow(0L)
    val updates = updatesFlow

    internal fun norm(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036f]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim().replace(Regex("\\s+"), " ")

    private fun key(title: String, artist: String, album: Boolean): String =
        (if (album) "album|" else "single|") + norm(title) + "|" + norm(artist)

    internal fun sameRecord(title: String, artist: String,
                            candidateTitle: String, candidateArtist: String): Boolean {
        val t = norm(title)
        val other = norm(candidateTitle)
        if (t.isEmpty() || !(t == other || other.startsWith(t + " "))) return false
        val a = norm(artist)
        val b = norm(candidateArtist)
        return a.isNotEmpty() && b.isNotEmpty() &&
            (a == b || (a.length >= 4 && b.length >= 4 &&
                (a.contains(b) || b.contains(a))))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun publish() { updatesFlow.value = updatesFlow.value + 1L }

    /** A deliberately selected DIFFERENT recording may use a different sleeve. */
    fun identity(context: Context, title: String, artist: String): RecordIdentity {
        val choice = MusicLabHitParadeVideoMemory.get(context, title, artist)
        if (choice?.manual != true) return RecordIdentity(title, artist)
        return if (sameRecord(title, artist, choice.title, choice.artist))
            RecordIdentity(title, artist)
        else RecordIdentity(choice.title.ifBlank { title },
            choice.artist.ifBlank { artist })
    }

    fun saved(context: Context, title: String, artist: String, album: Boolean): String? {
        val k = key(title, artist, album)
        val url = covers[k] ?: prefs(context).getString("art:$k", null)
        return url?.takeIf { it.startsWith("https://") && it !in rejected }?.also {
            covers[k] = it
        }
    }

    fun correctedArtist(context: Context, title: String, artist: String): String? {
        if (norm(title) != norm(artist)) return null
        val k = key(title, artist, album = true)
        return albumArtists[k] ?: prefs(context).getString("artist:$k", null)?.also {
            albumArtists[k] = it
        }
    }

    private fun rememberDisc(context: Context, title: String, artist: String,
                             album: Boolean, disc: Disc) {
        val k = key(title, artist, album)
        if (!disc.url.startsWith("https://") || disc.url in rejected) return
        covers[k] = disc.url
        val edit = prefs(context).edit().putString("art:$k", disc.url)
        if (album && norm(title) == norm(artist) && !disc.artist.isNullOrBlank() &&
            norm(disc.artist) != norm(artist)) {
            albumArtists[k] = disc.artist
            edit.putString("artist:$k", disc.artist)
        }
        edit.apply()
        publish()
        if (!album) synchronizeCurrentPlayer(context)
    }

    fun reject(context: Context, title: String, artist: String, album: Boolean, url: String) {
        rejected.add(url)
        val k = key(title, artist, album)
        if (covers[k] == url) covers.remove(k)
        if (prefs(context).getString("art:$k", null) == url) {
            prefs(context).edit().remove("art:$k").apply()
        }
        retryAfter.remove(k)
        publish()
    }

    fun registerPlaybackRows(
        selectionKey: String, entries: List<MusicLabSectionPlayback.Entry>,
        player: PlayerConnection,
    ) {
        attachedPlayer = WeakReference(player)
        entries.forEach { entry ->
            playbackRows[selectionKey + "|" + entry.key] =
                RecordIdentity(entry.title, entry.artist)
        }
    }

    private fun synchronizeCurrentPlayer(context: Context) {
        val connection = attachedPlayer?.get() ?: return
        val section = MusicLabSectionPlayback.active.value ?: return
        if (section.mediaId != connection.mediaMetadata.value?.id ||
            (!section.source.startsWith("Hit Parade") &&
                section.source != "Top annuali singoli")) return
        val selectionKey = section.selectionKey ?: return
        val selectedKey = section.selectedKey ?: return
        val row = playbackRows[selectionKey + "|" + selectedKey] ?: return
        val selectedVideo = MusicLabHitParadeVideoMemory.get(context, row.title, row.artist)
        if (selectedVideo?.manual == true && selectedVideo.videoId != section.mediaId) return
        val image = artwork(context, row.title, row.artist, album = false) ?: return
        val current = connection.mediaMetadata.value ?: return
        if (current.thumbnailUrl != image)
            connection.mediaMetadata.value = current.copy(thumbnailUrl = image)
    }

    fun onVideoChanged() { publish() }

    private fun safeImage(url: String?): String? =
        url?.takeIf { it.startsWith("https://") && it !in rejected }

    internal fun eligibleAlbum(title: String, artist: String, foundTitle: String,
                              foundArtist: String): Boolean {
        val wanted = norm(title)
        val actual = norm(foundTitle)
        if (wanted.isEmpty() || actual != wanted) return false
        // R47 historic album parser occasionally returns album name twice.
        // In that case require an exact album TITLE, not an unverified artist.
        return norm(artist) == wanted || sameRecord(title, artist, foundTitle, foundArtist)
    }

    private suspend fun spotifyAlbum(title: String, artist: String): Disc? =
        withTimeoutOrNull(3_000L) {
            val query = if (norm(title) == norm(artist)) title else "$title $artist"
            val albums = Spotify.search(query, types = listOf("album"), limit = 12)
                .getOrNull()?.albums?.items.orEmpty()
            albums.firstNotNullOfOrNull { candidate ->
                val performer = candidate.artists.joinToString(" ") { it.name }
                if (!eligibleAlbum(title, artist, candidate.name, performer)) null
                else candidate.images.firstNotNullOfOrNull { safeImage(it.url) }
                    ?.let { Disc(it, performer) }
            }
        }

    private suspend fun appleAlbum(title: String, artist: String): Disc? =
        withTimeoutOrNull(4_000L) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val terms = if (norm(title) == norm(artist)) title else "$title $artist"
                    val query = URLEncoder.encode(terms, "UTF-8")
                    val address = URL("https://itunes.apple.com/search?term=" + query +
                        "&entity=album&media=music&limit=20&country=IT")
                    val conn = address.openConnection() as HttpURLConnection
                    conn.connectTimeout = 2300
                    conn.readTimeout = 2300
                    try {
                        if (conn.responseCode !in 200..299) return@runCatching null
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val results = json.optJSONArray("results") ?: return@runCatching null
                        for (index in 0 until results.length()) {
                            val candidate = results.optJSONObject(index) ?: continue
                            val name = candidate.optString("collectionName")
                            val singer = candidate.optString("artistName")
                            if (!eligibleAlbum(title, artist, name, singer)) continue
                            val image = safeImage(candidate.optString("artworkUrl100"))
                                ?.replace("100x100bb", "600x600bb")
                            if (image != null) return@runCatching Disc(image, singer)
                        }
                        null
                    } finally { conn.disconnect() }
                }.getOrNull()
            }
        }

    private suspend fun spotifySong(title: String, artist: String): Disc? =
        withTimeoutOrNull(2_600L) {
            Spotify.search("$title $artist", types = listOf("track"), limit = 12)
                .getOrNull()?.tracks?.items.orEmpty().firstNotNullOfOrNull { candidate ->
                    val performer = candidate.artists.joinToString(" ") { it.name }
                    if (!sameRecord(title, artist, candidate.name, performer)) null
                    else candidate.album?.images?.firstNotNullOfOrNull { safeImage(it.url) }
                        ?.let { Disc(it, performer) }
                }
        }

    private suspend fun appleSong(title: String, artist: String): Disc? =
        withTimeoutOrNull(4_000L) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val query = URLEncoder.encode("$title $artist", "UTF-8")
                    val address = URL("https://itunes.apple.com/search?term=" + query +
                        "&entity=song&media=music&limit=15&country=IT")
                    val conn = address.openConnection() as HttpURLConnection
                    conn.connectTimeout = 2300
                    conn.readTimeout = 2300
                    try {
                        if (conn.responseCode !in 200..299) return@runCatching null
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val results = json.optJSONArray("results") ?: return@runCatching null
                        for (i in 0 until results.length()) {
                            val c = results.optJSONObject(i) ?: continue
                            if (!sameRecord(title, artist,
                                    c.optString("trackName"), c.optString("artistName"))) continue
                            val cover = safeImage(c.optString("artworkUrl100"))
                                ?.replace("100x100bb", "600x600bb")
                            if (cover != null) return@runCatching Disc(cover, c.optString("artistName"))
                        }
                        null
                    } finally { conn.disconnect() }
                }.getOrNull()
            }
        }

    private suspend fun songDisc(title: String, artist: String): Disc? = coroutineScope {
        val spotify = async(Dispatchers.IO) { spotifySong(title, artist) }
        val apple = async(Dispatchers.IO) { appleSong(title, artist) }
        spotify.await() ?: apple.await()
    }

    suspend fun resolve(context: Context, title: String, artist: String,
                        album: Boolean, sourceArtwork: String? = null): String? {
        val original = if (album) RecordIdentity(title, artist) else identity(context, title, artist)
        saved(context, original.title, original.artist, album)?.let { return it }
        val k = key(original.title, original.artist, album)
        if ((retryAfter[k] ?: 0L) > System.currentTimeMillis()) return null
        // Historical charts remain instantly visible. Only visible rows start
        // this bounded lookup; changing section cancels Compose's producer.
        return gate.withPermit {
            saved(context, original.title, original.artist, album)?.let { return@withPermit it }
            val verified = safeImage(sourceArtwork)
                ?.takeUnless { MusicLabLightArtwork.isVideoPoster(it) }
                ?.let { Disc(it, null) }
                ?: MusicLabArtworkAudioGate.optionalLookup {
                    if (album) coroutineScope {
                        val spotify = async(Dispatchers.IO) {
                            spotifyAlbum(original.title, original.artist)
                        }
                        val apple = async(Dispatchers.IO) {
                            appleAlbum(original.title, original.artist)
                        }
                        spotify.await() ?: apple.await()
                    } else songDisc(original.title, original.artist)
                }
            val enriched = verified ?: MusicLabLightArtwork.recover(
                context, original.title, original.artist, album = album
            )?.let { Disc(it, null) }
            if (enriched != null) {
                rememberDisc(context, original.title, original.artist, album, enriched)
                enriched.url
            } else {
                retryAfter[k] = System.currentTimeMillis() + 20_000L
                null
            }
        }
    }

    fun fallback(context: Context, title: String, artist: String): String? {
        val choice = MusicLabHitParadeVideoMemory.get(context, title, artist)
        safeImage(choice?.thumbnail)?.let { return it }
        return safeImage(MusicLabFastPath.peek(title, artist) { candidate ->
            MusicLabChartArtworkResolver.sameRecordingLabel(title, artist, candidate)
        }?.thumbnail)
    }

    fun artwork(context: Context, title: String, artist: String, album: Boolean): String? {
        val chosen = if (album) RecordIdentity(title, artist) else identity(context, title, artist)
        return saved(context, chosen.title, chosen.artist, album)
            ?: MusicLabLightArtwork.saved(context, chosen.title, chosen.artist, album)
            ?: if (album) null else fallback(context, title, artist)
    }

    /**
     * R58: a previously verified or manually chosen video ID is already a
     * playable Media3 identity. Never block a repeat tap on YouTube.queue()
     * just to reconstruct its SongItem metadata. Preserve the original sleeve
     * and keep the user's exact pinned recording.
     */
    fun rememberedPlayableMediaItem(context: Context, title: String, artist: String): MediaItem? {
        val choice = MusicLabHitParadeVideoMemory.get(context, title, artist) ?: return null
        return MediaMetadata(
            id = choice.videoId,
            title = choice.title.ifBlank { title },
            artists = listOf(MediaMetadata.Artist(id = null,
                name = choice.artist.ifBlank { artist })),
            duration = -1,
            thumbnailUrl = artwork(context, title, artist, album = false) ?: choice.thumbnail,
        ).toMediaItem()
    }

    /** Seed both native artwork metadata and the list from the same verified URL. */
    fun playableMediaItem(context: Context, title: String, artist: String,
                          song: SongItem): MediaItem {
        val sleeve = artwork(context, title, artist, album = false)
        return if (sleeve == null) song.toMediaItem()
        else song.toMediaMetadata().copy(thumbnailUrl = sleeve).toMediaItem()
    }

    fun currentPlayerArtwork(context: Context, mediaId: String): String? {
        val snapshot = MusicLabSectionPlayback.active.value ?: return null
        if (snapshot.mediaId != mediaId) return null
        if (!snapshot.source.startsWith("Hit Parade") &&
            snapshot.source != "Top annuali singoli") return null
        val row = playbackRows[(snapshot.selectionKey ?: return null) +
            "|" + (snapshot.selectedKey ?: return null)] ?: return null
        val selectedVideo = MusicLabHitParadeVideoMemory.get(context, row.title, row.artist)
        if (selectedVideo?.manual == true && selectedVideo.videoId != mediaId) return null
        return artwork(context, row.title, row.artist, album = false)
    }

    /**
     * The native player continues the SAME media stream: only Compose metadata
     * shown by MiniPlayer/expanded Player receives the shared disc sleeve.
     * No replaceMediaItem, prepare, seek or playback restart.
     */
    @Composable
    fun playerMetadata(original: MediaMetadata?): MediaMetadata? {
        val context = LocalContext.current
        val revision by updates.collectAsStateWithLifecycle()
        val extraRevision by MusicLabLightArtwork.revisions.collectAsStateWithLifecycle()
        val observedSection by MusicLabSectionPlayback.active.collectAsStateWithLifecycle()
        val section = observedSection
        if (original == null || section == null) return original
        if (section.mediaId != original.id ||
            (!section.source.startsWith("Hit Parade") &&
                section.source != "Top annuali singoli")) return original
        val position = section.selectionKey ?: return original
        val rank = section.selectedKey ?: return original
        val active = playbackRows[position + "|" + rank] ?: return original
        // Consume revision so a newly loaded disc updates both player surfaces.
        val image = artwork(context, active.title, active.artist, album = false)
        return if (image != null && image != original.thumbnailUrl)
            original.copy(thumbnailUrl = image) else original
    }
}

@Composable
internal fun MusicLabHitParadeDiscCover(
    title: String,
    artist: String,
    album: Boolean,
    sourceArtwork: String? = null,
    retrySignal: Int = 0,
) {
    val context = LocalContext.current
    val revision by MusicLabHitParadeDiscArtwork.updates.collectAsStateWithLifecycle()
    val extraRevision by MusicLabLightArtwork.revisions.collectAsStateWithLifecycle()
    val identity = remember(title, artist, album, revision) {
        if (album) MusicLabHitParadeDiscArtwork.RecordIdentity(title, artist)
        else MusicLabHitParadeDiscArtwork.identity(context, title, artist)
    }
    var rejectedUrl by remember(identity.title, identity.artist, album) {
        mutableStateOf<String?>(null)
    }
    val disc by produceState(
        initialValue = MusicLabHitParadeDiscArtwork.saved(
            context, identity.title, identity.artist, album),
        key1 = identity, key2 = album, key3 = listOf(sourceArtwork, rejectedUrl, retrySignal, extraRevision),
    ) {
        value = MusicLabHitParadeDiscArtwork.saved(
            context, identity.title, identity.artist, album)
        if (retrySignal > 0) {
            MusicLabLightArtwork.recover(
                context, identity.title, identity.artist, album = album, manual = true
            )?.let { value = it }
        }
        if (value == null) {
            value = MusicLabHitParadeDiscArtwork.resolve(
                context, title, artist, album, sourceArtwork)
        }
    }
    val videoFallback by produceState<String?>(
        initialValue = sourceArtwork?.takeIf { MusicLabLightArtwork.isVideoPoster(it) }
            ?: if (!album) MusicLabHitParadeDiscArtwork.fallback(context, title, artist) else null,
        key1 = title, key2 = artist, key3 = album,
    ) {
        if (value == null && !album) {
            value = MusicLabFastPath.findArtwork(title, artist) { candidate ->
                MusicLabChartArtworkResolver.sameRecordingLabel(title, artist, candidate)
            }?.thumbnail
        }
    }
    val image = disc?.takeUnless { it == rejectedUrl }
        ?: MusicLabLightArtwork.saved(context, identity.title, identity.artist, album)
        ?: videoFallback?.takeUnless { it == rejectedUrl }
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center) {
        if (!image.isNullOrBlank()) {
            AsyncImage(
                model = image,
                contentDescription = (if (album) "Copertina album " else "Copertina singolo ") + title,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
                onError = {
                    rejectedUrl = image
                    MusicLabHitParadeDiscArtwork.reject(
                        context, identity.title, identity.artist, album, image)
                    MusicLabLightArtwork.reject(
                        context, identity.title, identity.artist, album, image)
                },
            )
        } else {
            Text("♫", color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }
    }
}
