/**
 * Universal manual YouTube source override.
 * Keeps the original MusicLab media id/metadata while resolving audio from another YouTube video.
 */
package com.metrolist.music.playback

import org.json.JSONObject

data class YouTubeMatchOverride(
    val videoId: String,
    val title: String = "",
    val artist: String = "",
    val thumbnail: String? = null,
)

object YouTubeMatchOverrides {
    @Volatile private var cachedRaw: String? = null
    @Volatile private var cachedMap: Map<String, YouTubeMatchOverride> = emptyMap()

    fun decode(value: String?): MutableMap<String, YouTubeMatchOverride> {
        if (value.isNullOrBlank()) return mutableMapOf()
        val snapshot = if (value == cachedRaw) cachedMap else parse(value).also {
            cachedRaw = value
            cachedMap = it
        }
        return LinkedHashMap(snapshot)
    }

    private fun parse(value: String): Map<String, YouTubeMatchOverride> = runCatching {
        val root = JSONObject(value)
        buildMap {
            root.keys().forEach { mediaId ->
                val obj = root.optJSONObject(mediaId) ?: return@forEach
                val videoId = obj.optString("videoId").takeIf { it.isNotBlank() } ?: return@forEach
                put(
                    mediaId,
                    YouTubeMatchOverride(
                        videoId = videoId,
                        title = obj.optString("title"),
                        artist = obj.optString("artist"),
                        thumbnail = obj.optString("thumbnail").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())

    fun encode(overrides: Map<String, YouTubeMatchOverride>): String {
        val root = JSONObject()
        overrides.forEach { (mediaId, value) ->
            root.put(
                mediaId,
                JSONObject()
                    .put("videoId", value.videoId)
                    .put("title", value.title)
                    .put("artist", value.artist)
                    .put("thumbnail", value.thumbnail ?: ""),
            )
        }
        return root.toString()
    }
}
