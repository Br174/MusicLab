package com.metrolist.music.ui.screens.search

import android.content.Context
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.playback.MusicLabSectionMemory
import com.metrolist.music.ui.component.MusicLabFastPath
import com.metrolist.music.ui.component.MusicLabTrackRecovery
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * R48: one durable recording identity for historic weekly and annual singles.
 * Chart dates and ranks are NOT keys: the same song reuses the choice in other weeks.
 * Explicit user choices cannot be replaced by any automatic discovery.
 */
internal object MusicLabHitParadeVideoMemory {
    data class Choice(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnail: String?,
        val manual: Boolean,
    )

    private const val SECTION = "hitparade-video-choice-r48"

    internal fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036f]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim().replace(Regex("\\s+"), " ")

    internal fun key(title: String, artist: String): String =
        normalize(title) + "|" + normalize(artist)

    internal fun validVideoId(id: String): Boolean =
        id.matches(Regex("^[A-Za-z0-9_-]{11}$"))

    internal fun shouldSaveAutomatic(existing: Choice?): Boolean =
        existing?.manual != true

    fun get(context: Context, title: String, artist: String): Choice? {
        if (normalize(title).isBlank() || normalize(artist).isBlank()) return null
        val data = MusicLabSectionMemory.readState(context, SECTION + ":" + key(title, artist))
        val id = data.optString("videoId")
        if (!validVideoId(id)) return null
        return Choice(
            videoId = id,
            title = data.optString("title"),
            artist = data.optString("artist"),
            thumbnail = data.optString("thumbnail").takeIf(String::isNotBlank),
            manual = data.optBoolean("manual", false),
        )
    }

    fun save(context: Context, title: String, artist: String, choice: Choice): Boolean {
        if (!validVideoId(choice.videoId) ||
            normalize(title).isBlank() || normalize(artist).isBlank()) return false
        if (!choice.manual && !shouldSaveAutomatic(get(context, title, artist))) return false
        MusicLabSectionMemory.writeState(context, SECTION + ":" + key(title, artist),
            JSONObject().apply {
                put("videoId", choice.videoId)
                put("title", choice.title)
                put("artist", choice.artist)
                put("thumbnail", choice.thumbnail.orEmpty())
                put("manual", choice.manual)
            })
        return true
    }

    fun clear(context: Context, title: String, artist: String) {
        MusicLabSectionMemory.eraseSection(context, SECTION + ":" + key(title, artist))
    }
}

/**
 * Priority: explicitly chosen video -> previously verified automatic video ->
 * MusicLab FastPath -> recovery -> existing artwork resolver -> targeted video search.
 *
 * The historical chart parser, its ranking and the global player are untouched.
 * No broad eager lookup: a failed tap retries while off-screen searches stay in standby.
 */
internal object MusicLabHitParadeVideoResolver {
    private val live = ConcurrentHashMap<String, SongItem>()

    suspend fun find(context: Context, title: String, artist: String): SongItem? {
        val cacheKey = MusicLabHitParadeVideoMemory.key(title, artist)
        val pinned = MusicLabHitParadeVideoMemory.get(context, title, artist)
        if (pinned != null) {
            live[cacheKey]?.takeIf { it.id == pinned.videoId }?.let { return it }
            val restored = withContext(Dispatchers.IO) {
                withTimeoutOrNull(4_000L) {
                    YouTube.queue(listOf(pinned.videoId)).getOrNull()
                        ?.firstOrNull { it.id == pinned.videoId }
                }
            }
            if (restored != null) {
                live[cacheKey] = restored
                return restored
            }
            // Never silently substitute an explicitly selected, temporarily
            // unavailable video. The user can replace/remove it from the menu.
            if (pinned.manual) return null
            MusicLabHitParadeVideoMemory.clear(context, title, artist)
            live.remove(cacheKey)
        }

        val compatible: (SongItem) -> Boolean = { item ->
            MusicLabChartArtworkResolver.sameRecordingLabel(title, artist, item)
        }
        val verified =
            MusicLabFastPath.find(title, artist, compatible)
                ?: MusicLabTrackRecovery.find(title, artist, compatible)
                ?: MusicLabChartArtworkResolver.resolveSong(
                    context, MusicLabChartTrack(0, title, artist))
                ?: deeperSearch(title, artist, compatible)
        if (verified != null && MusicLabHitParadeVideoMemory.save(
                context, title, artist,
                MusicLabHitParadeVideoMemory.Choice(
                    verified.id, verified.title,
                    verified.artists.joinToString(", ") { it.name },
                    verified.thumbnail, manual = false,
                ))) {
            live[cacheKey] = verified
        }
        return verified
    }

    /**
     * Additional evidence only after ordinary finders miss. Search genuine
     * YouTube Music video/song results and apply the same strict title/artist
     * compatibility rule; never invent a chart entry or play a random cover.
     */
    private suspend fun deeperSearch(
        title: String,
        artist: String,
        compatible: (SongItem) -> Boolean,
    ): SongItem? = withContext(Dispatchers.IO) {
        val queries = listOf(
            artist.trim() + " " + title.trim() + " official",
            title.trim() + " " + artist.trim() + " original",
            artist.trim() + " " + title.trim(),
        ).distinct()
        for (query in queries) {
            val candidates = withTimeoutOrNull(3_500L) {
                YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO).getOrNull()
                    ?.items?.filterIsInstance<SongItem>().orEmpty()
            }.orEmpty()
            candidates.firstOrNull(compatible)?.let { return@withContext it }
        }
        null
    }

    fun userSelected(context: Context, title: String, artist: String,
        videoId: String, videoTitle: String, videoArtist: String, thumbnail: String?
    ): Boolean {
        val saved = MusicLabHitParadeVideoMemory.save(context, title, artist,
            MusicLabHitParadeVideoMemory.Choice(
                videoId, videoTitle, videoArtist, thumbnail, manual = true))
        if (saved) live.remove(MusicLabHitParadeVideoMemory.key(title, artist))
        return saved
    }

    fun removeSelection(context: Context, title: String, artist: String) {
        MusicLabHitParadeVideoMemory.clear(context, title, artist)
        live.remove(MusicLabHitParadeVideoMemory.key(title, artist))
    }
}
