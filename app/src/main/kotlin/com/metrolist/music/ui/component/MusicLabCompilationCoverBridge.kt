package com.metrolist.music.ui.component

import android.content.Context
import androidx.media3.common.MediaItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.MusicLabSectionPlayback
import java.util.concurrent.ConcurrentHashMap

/**
 * Discogs is the release artwork authority for its actual album or compilation.
 * The list, opened detail, mini and full player use the identical sleeve.
 * Metadata-only projection: no prepare/seek/playback side effects.
 */
internal object MusicLabCompilationCoverBridge {
    private const val PREFS = "musiclab_discogs_release_covers_r52"
    private val covers = ConcurrentHashMap<Int, String>()

    private fun safe(url: String?): String? =
        url?.trim()?.takeIf {
            it.startsWith("https://") &&
                !it.contains("ytimg.com", ignoreCase = true) &&
                !it.contains("youtube.com", ignoreCase = true)
        }

    fun remember(context: Context, releaseId: Int, url: String?) {
        val image = safe(url) ?: return
        covers[releaseId] = image
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("release:$releaseId", image).apply()
    }

    fun artwork(context: Context, releaseId: Int): String? =
        covers[releaseId] ?: safe(context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("release:$releaseId", null))?.also { covers[releaseId] = it }

    fun currentPlayerArtwork(context: Context, mediaId: String): String? {
        val snapshot = MusicLabSectionPlayback.active.value ?: return null
        if (!snapshot.source.startsWith("Compilation · ") ||
            snapshot.mediaId != mediaId) return null
        val key = snapshot.selectionKey ?: return null
        if (!key.startsWith("compilation:detail:")) return null
        val releaseId = key.removePrefix("compilation:detail:").toIntOrNull() ?: return null
        return artwork(context, releaseId)
    }

    fun mediaItem(song: SongItem, image: String?): MediaItem =
        if (safe(image) == null) song.toMediaItem()
        else song.toMediaMetadata().copy(thumbnailUrl = safe(image)).toMediaItem()
}
