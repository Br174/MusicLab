package com.metrolist.music.ui.screens.search

import android.content.Context
import com.metrolist.music.constants.DiscogsTokenKey
import com.metrolist.music.constants.LastFMApiKeyKey
import com.metrolist.music.discogs.DiscogsClient
import com.metrolist.music.playback.MusicLabArtworkAudioGate
import com.metrolist.music.utils.dataStore
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Supplemental ARTWORK ONLY. Never owns MusicLab search, chart rows, video
 * resolution or playback. Native YouTube Music/Spotify metadata stays first.
 * Used only for missing discographic sleeves, or an explicit user retry.
 */
internal object MusicLabLightArtwork {
    private const val PREFS = "musiclab_section_verified_artwork_r58"
    private val extras = ConcurrentHashMap<String, String>()
    private val autoMissUntil = ConcurrentHashMap<String, Long>()
    private val rejected = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(2)
    private val revisionFlow = MutableStateFlow(0L)
    val revisions = revisionFlow

    private fun normalized(text: String) =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036f]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            .replace(Regex("\\s+"), " ")

    private fun key(title: String, artist: String, album: Boolean) =
        (if (album) "album|" else "song|") + normalized(artist) + "|" + normalized(title)

    fun isVideoPoster(url: String?): Boolean {
        val text = url?.lowercase(Locale.ROOT).orEmpty()
        return text.contains("ytimg.com/vi/") ||
            text.contains("youtube.com/vi/") || text.contains("img.youtube.com/vi/")
    }

    private fun safe(url: String?): String? = url?.takeIf {
        it.startsWith("https://") && it.length > 14 && it !in rejected &&
            !isVideoPoster(it) && !it.contains("2a96cbd8b46e442fc41c2b86b821562f")
    }

    fun saved(context: Context, title: String, artist: String, album: Boolean): String? {
        val k = key(title, artist, album)
        return safe(extras[k] ?: context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(k, null))?.also {
            extras[k] = it
        }
    }

    private fun store(context: Context, title: String, artist: String, album: Boolean, url: String) {
        val image = safe(url) ?: return
        val k = key(title, artist, album)
        extras[k] = image
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(k, image).apply()
        autoMissUntil.remove(k)
        revisionFlow.value += 1L
    }

    fun reject(context: Context, title: String, artist: String, album: Boolean, url: String) {
        rejected.add(url)
        val k = key(title, artist, album)
        extras.remove(k, url)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(k, null) == url) prefs.edit().remove(k).apply()
        autoMissUntil.remove(k)
        revisionFlow.value += 1L
    }

    /** One bounded lookup per visible missing sleeve, never on the playback path. */
    suspend fun recover(
        context: Context, title: String, artist: String, album: Boolean = false,
        manual: Boolean = false,
    ): String? {
        if (title.isBlank() || artist.isBlank()) return null
        // A video without usable musical credits should retain its YouTube
        // image instead of exhausting catalogue/provider budgets.
        if (!manual && (title.count { it.isLetter() } < 2 ||
                artist.count { it.isLetter() } < 2)) return null
        if (!manual) saved(context, title, artist, album)?.let { return it }
        val k = key(title, artist, album)
        if (!manual && System.currentTimeMillis() < (autoMissUntil[k] ?: 0L)) return null
        // Audio startup suspends this optional task; it does not trigger
        // an artificial missing-artwork cooldown or another player lookup.
        return gate.withPermit {
            if (!manual) saved(context, title, artist, album)?.let { return@withPermit it }
            val result = MusicLabArtworkAudioGate.optionalLookup {
                withTimeoutOrNull(5_500L) {
                    // Discogs and Last.fm are secondary to native metadata.
                    discogs(context, title, artist, album)
                        ?: lastFm(context, title, artist, album)
                }
            }
            if (result != null) {
                store(context, title, artist, album, result)
            } else if (!manual && MusicLabArtworkAudioGate.mayStartOptionalArtwork()) {
                autoMissUntil[k] = System.currentTimeMillis() + 120_000L
            }
            result
        }
    }

    private suspend fun discogs(
        context: Context, title: String, artist: String, album: Boolean,
    ): String? {
        val token = context.dataStore.data.first()[DiscogsTokenKey].orEmpty().trim()
        if (token.isBlank()) return null
        val releases = withTimeoutOrNull(3_500L) {
            DiscogsClient.searchReleases(
                token = token,
                artist = artist,
                releaseTitle = title,
                perPage = 12,
                sort = null,
                sortOrder = null,
            ).getOrNull()?.items.orEmpty()
        }.orEmpty()
        val targetTitle = normalized(title)
        val targetArtist = normalized(artist)
        return releases.firstNotNullOfOrNull { release ->
            // Discogs search titles usually have "artist - release" format.
            // Reject a different album by the same performer.
            val parts = release.title.split(Regex("\\s+[-–]\\s+"), limit = 2)
            val label = normalized(parts.last())
            val credit = normalized(if (parts.size == 2) parts.first() else artist)
            val titleMatches = label == targetTitle ||
                (album && label.startsWith("$targetTitle "))
            if (titleMatches && (credit == targetArtist ||
                    credit.contains(targetArtist) || targetArtist.contains(credit))) {
                safe(release.coverUrl ?: release.thumbnailUrl)
            } else null
        }
    }

    private suspend fun lastFm(
        context: Context, title: String, artist: String, album: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        val key = context.dataStore.data.first()[LastFMApiKeyKey].orEmpty().trim()
        if (key.isBlank()) return@withContext null
        withTimeoutOrNull(2_100L) {
            runCatching {
                fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
                val method = if (album) "album.getinfo" else "track.getinfo"
                val queryName = if (album) "album" else "track"
                val address = "https://ws.audioscrobbler.com/2.0/?method=$method" +
                    "&api_key=${enc(key)}&artist=${enc(artist)}&$queryName=${enc(title)}&format=json"
                val conn = URL(address).openConnection() as HttpURLConnection
                conn.connectTimeout = 1_600
                conn.readTimeout = 1_600
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    val data = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val record = data.optJSONObject(if (album) "album" else "track")
                        ?: return@runCatching null
                    if (normalized(record.optString("name")) != normalized(title))
                        return@runCatching null
                    val artistValue = record.opt("artist")
                    val foundArtist = when (artistValue) {
                        is JSONObject -> artistValue.optString("name")
                        is String -> artistValue
                        else -> ""
                    }
                    if (normalized(foundArtist) != normalized(artist))
                        return@runCatching null
                    val sleeve = if (album) record else record.optJSONObject("album")
                    val images = sleeve?.optJSONArray("image") ?: return@runCatching null
                    (images.length() - 1 downTo 0).firstNotNullOfOrNull { index ->
                        safe(images.optJSONObject(index)?.optString("#text"))
                    }
                } finally { conn.disconnect() }
            }.getOrNull()
        }
    }
}
