package com.metrolist.music.playback

import android.content.Context
import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import org.json.JSONObject

/** R45: manual recording selection for every song, using the existing R41 local memory. */
internal object MusicLabManualVersionStore {
    data class Choice(val videoId: String, val title: String, val artist: String, val thumbnail: String?)
    private const val SECTION = "manual-recording-version-r45"
    private fun valid(id: String) = id.matches(Regex("^[a-zA-Z0-9_-]{11}$"))
    fun canonicalId(context: Context, id: String): String =
        MusicLabSectionMemory.text(context, SECTION, "alias:$id", id).ifBlank { id }

    fun get(context: Context, id: String): Choice? {
        val root = canonicalId(context, id)
        val json = MusicLabSectionMemory.readState(context, "$SECTION:$root")
        val videoId = json.optString("videoId")
        if (!valid(videoId)) return null
        return Choice(videoId, json.optString("title"), json.optString("artist"),
            json.optString("thumbnail").takeIf(String::isNotBlank))
    }

    fun save(context: Context, id: String, choice: Choice) {
        if (!valid(choice.videoId) || id.isBlank()) return
        val root = canonicalId(context, id)
        get(context, root)?.let { old ->
            MusicLabSectionMemory.putText(context, SECTION, "alias:${old.videoId}", "")
        }
        MusicLabSectionMemory.writeState(context, "$SECTION:$root", JSONObject().apply {
            put("videoId", choice.videoId)
            put("title", choice.title)
            put("artist", choice.artist)
            put("thumbnail", choice.thumbnail.orEmpty())
        })
        MusicLabSectionMemory.putText(context, SECTION, "alias:${choice.videoId}", root)
    }

    fun clear(context: Context, id: String) {
        val root = canonicalId(context, id)
        get(context, root)?.let {
            MusicLabSectionMemory.putText(context, SECTION, "alias:${it.videoId}", "")
        }
        MusicLabSectionMemory.eraseSection(context, "$SECTION:$root")
    }

    /** Replace only the audio source identity; never clear or reorder the native queue. */
    fun apply(context: Context, item: MediaItem): MediaItem {
        val choice = get(context, item.mediaId) ?: return item
        if (choice.videoId == item.mediaId) return item
        val original = item.metadata
        return if (original != null) {
            original.copy(
                id = choice.videoId,
                title = choice.title.ifBlank { original.title },
                artists = choice.artist.takeIf { it.isNotBlank() }?.let {
                    listOf(MediaMetadata.Artist(id = null, name = it))
                } ?: original.artists,
                thumbnailUrl = choice.thumbnail ?: original.thumbnailUrl,
            ).toMediaItem()
        } else {
            item.buildUpon().setMediaId(choice.videoId).setUri(choice.videoId)
                .setCustomCacheKey(choice.videoId).build()
        }
    }
}
