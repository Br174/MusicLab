package com.metrolist.music.ui.screens.search

import android.content.Context
import com.metrolist.music.playback.MusicLabSectionMemory
import org.json.JSONArray
import org.json.JSONObject

/**
 * R41 cached editorial list snapshots. Original providers remain authoritative:
 * cached results restore the visible screen immediately and can be refreshed.
 * Stored tracks are descriptive only and MUST NOT auto-start playback.
 */
internal object MusicLabEditorialSnapshot {
    fun saveHistorical(context: Context, date: Long, chart: HistoricalChart) {
        val data = JSONObject().apply {
            put("weekMillis", chart.week.millis)
            put("url", chart.week.url)
            put("songs", JSONArray().apply {
                chart.songs.take(200).forEach { row ->
                    put(JSONObject().apply {
                        put("rank", row.rank)
                        put("title", row.title)
                        put("artist", row.artist)
                        put("peak", row.peak)
                        put("weeks", row.weeks)
                        put("previous", row.previous)
                    })
                }
            })
        }
        MusicLabSectionMemory.writeState(context, "hitparade:results:$date", data)
    }

    fun loadHistorical(context: Context, date: Long): HistoricalChart? = runCatching {
        val obj = MusicLabSectionMemory.readState(context, "hitparade:results:$date")
        val songs = obj.optJSONArray("songs") ?: return@runCatching null
        if (!obj.has("weekMillis") || songs.length() == 0) return@runCatching null
        val rows = (0 until songs.length()).mapNotNull { i ->
            val row = songs.optJSONObject(i) ?: return@mapNotNull null
            val rank = row.optInt("rank")
            val title = row.optString("title")
            val artist = row.optString("artist")
            if (rank <= 0 || title.isBlank() || artist.isBlank()) return@mapNotNull null
            HistoricalHit(rank, title, artist, row.optString("peak").takeIf(String::isNotBlank),
                row.optString("weeks").takeIf(String::isNotBlank),
                row.optString("previous").takeIf(String::isNotBlank))
        }
        if (rows.isEmpty()) null else HistoricalChart(
            HistoricalWeek(obj.getLong("weekMillis"), obj.optString("url")), rows)
    }.getOrNull()

    fun saveChart(context: Context, key: String, chart: MusicLabChartResult) {
        MusicLabSectionMemory.writeState(context, "chart:results:$key", JSONObject().apply {
            put("source", chart.source)
            put("tracks", JSONArray().apply {
                chart.tracks.take(200).forEach { track ->
                    put(JSONObject().apply {
                        put("rank", track.rank)
                        put("title", track.title)
                        put("artist", track.artist)
                        put("youtubeId", track.youtubeId)
                        put("artwork", track.artwork)
                        put("trusted", track.sourceArtworkTrusted)
                    })
                }
            })
        })
    }

    fun loadChart(context: Context, key: String): MusicLabChartResult? = runCatching {
        val obj = MusicLabSectionMemory.readState(context, "chart:results:$key")
        val tracks = obj.optJSONArray("tracks") ?: return@runCatching null
        val entries = (0 until tracks.length()).mapNotNull { i ->
            val row = tracks.optJSONObject(i) ?: return@mapNotNull null
            val rank = row.optInt("rank")
            val title = row.optString("title")
            val artist = row.optString("artist")
            if (rank <= 0 || title.isBlank() || artist.isBlank()) return@mapNotNull null
            MusicLabChartTrack(rank, title, artist,
                row.optString("youtubeId").takeIf(String::isNotBlank),
                row.optString("artwork").takeIf(String::isNotBlank),
                row.optBoolean("trusted", false))
        }
        if (entries.isEmpty()) null else MusicLabChartResult(obj.optString("source"), entries)
    }.getOrNull()
}
