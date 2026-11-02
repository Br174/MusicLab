package com.metrolist.music.ui.component

import android.content.Context
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.discogs.CompilationResolvedTrack
import com.metrolist.music.discogs.DiscogsCompilationDetail
import com.metrolist.music.playback.MusicLabSectionMemory
import org.json.JSONArray
import org.json.JSONObject

/**
 * R44: persist only fully verified playable metadata, never media, tokens or
 * provider credentials. A changed Discogs tracklist invalidates stale entries.
 */
internal object MusicLabCompilationResolvedCache {
    private fun key(id: Int) = "compilation:resolved:$id"
    private fun fingerprint(track: com.metrolist.music.discogs.DiscogsTrack): String =
        listOf(track.position, track.title.trim(), track.artists.joinToString("|"))
            .joinToString("\u001f").lowercase(java.util.Locale.ROOT)

    fun load(context: Context, current: DiscogsCompilationDetail): Map<Int, CompilationResolvedTrack> =
        runCatching {
            val root = MusicLabSectionMemory.readState(context, key(current.id))
            if (root.optInt("releaseId") != current.id) return@runCatching emptyMap()
            val list = root.optJSONArray("tracks") ?: return@runCatching emptyMap()
            buildMap {
                for (i in 0 until list.length()) {
                    val row = list.optJSONObject(i) ?: continue
                    val position = row.optInt("index", -1)
                    val track = current.tracks.getOrNull(position) ?: continue
                    if (fingerprint(track) != row.optString("fingerprint")) continue
                    val id = row.optString("id")
                    val title = row.optString("songTitle")
                    val thumbnail = row.optString("thumbnail")
                    if (id.isBlank() || title.isBlank() || thumbnail.isBlank()) continue
                    val artistRows = row.optJSONArray("artists") ?: JSONArray()
                    val artists = (0 until artistRows.length()).mapNotNull { ai ->
                        artistRows.optJSONObject(ai)?.let { artist ->
                            artist.optString("name").takeIf(String::isNotBlank)?.let { name ->
                                Artist(name, artist.optString("id").takeIf(String::isNotBlank))
                            }
                        }
                    }
                    val song = SongItem(
                        id = id, title = title,
                        artists = artists.ifEmpty { track.artists.map { Artist(it, null) } },
                        thumbnail = thumbnail,
                        duration = row.optInt("duration", -1).takeIf { it >= 0 },
                        musicVideoType = row.optString("musicVideoType").takeIf(String::isNotBlank),
                        explicit = row.optBoolean("explicit"),
                        setVideoId = row.optString("setVideoId").takeIf(String::isNotBlank),
                    )
                    put(position, CompilationResolvedTrack(
                        track, song, row.optString("source", "MusicLab"), row.optBoolean("discogsVideo")))
                }
            }
        }.getOrDefault(emptyMap())

    fun save(context: Context, current: DiscogsCompilationDetail,
             resolved: Map<Int, CompilationResolvedTrack>) {
        val root = JSONObject().apply {
            put("releaseId", current.id)
            put("tracks", JSONArray().apply {
                resolved.entries.sortedBy { it.key }.take(200).forEach { (index, entry) ->
                    val track = current.tracks.getOrNull(index) ?: return@forEach
                    if (entry.track.title != track.title || entry.song.id.isBlank()) return@forEach
                    put(JSONObject().apply {
                        put("index", index)
                        put("fingerprint", fingerprint(track))
                        put("id", entry.song.id)
                        put("songTitle", entry.song.title)
                        put("thumbnail", entry.song.thumbnail)
                        put("duration", entry.song.duration)
                        put("musicVideoType", entry.song.musicVideoType)
                        put("explicit", entry.song.explicit)
                        put("setVideoId", entry.song.setVideoId)
                        put("source", entry.source)
                        put("discogsVideo", entry.usedDiscogsVideo)
                        put("artists", JSONArray().apply {
                            entry.song.artists.forEach { artist ->
                                put(JSONObject().put("name", artist.name).put("id", artist.id))
                            }
                        })
                    })
                }
            })
        }
        MusicLabSectionMemory.writeState(context, key(current.id), root)
    }
}
