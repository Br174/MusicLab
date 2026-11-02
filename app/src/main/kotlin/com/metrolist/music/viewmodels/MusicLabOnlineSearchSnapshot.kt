package com.metrolist.music.viewmodels

import android.content.Context
import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.pages.SearchSummary
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.models.ItemsPage
import com.metrolist.music.playback.MusicLabSectionMemory
import org.json.JSONArray
import org.json.JSONObject

/**
 * R44: local metadata snapshots for completed online search results.
 * No provider tokens, playback queue, downloaded media or remote keys.
 * An unsupported item invalidates the cache instead of showing a partial list.
 */
internal object MusicLabOnlineSearchSnapshot {
    private fun key(query: String, source: String, filter: String): String =
        "online:result:$source:$filter:$query"

    private fun encodeArtist(artist: Artist?): JSONObject? = artist?.let {
        JSONObject().put("name", it.name).put("id", it.id)
    }

    private fun decodeArtist(json: JSONObject?): Artist? =
        json?.optString("name")?.takeIf(String::isNotBlank)?.let {
            Artist(it, json.optString("id").takeIf(String::isNotBlank))
        }

    private fun encodeArtists(artists: List<Artist>?): JSONArray = JSONArray().apply {
        artists.orEmpty().forEach { encodeArtist(it)?.let(::put) }
    }

    private fun decodeArtists(arr: JSONArray?): List<Artist> = if (arr == null) emptyList()
    else (0 until arr.length()).mapNotNull { decodeArtist(arr.optJSONObject(it)) }

    private fun encode(item: YTItem): JSONObject? = JSONObject().apply {
        put("id", item.id)
        put("title", item.title)
        put("thumbnail", item.thumbnail)
        put("explicit", item.explicit)
        when (item) {
            is SongItem -> {
                put("type", "song")
                put("artists", encodeArtists(item.artists))
                put("duration", item.duration)
                put("musicVideoType", item.musicVideoType)
                put("setVideoId", item.setVideoId)
            }
            is AlbumItem -> {
                put("type", "album")
                put("browseId", item.browseId)
                put("playlistId", item.playlistId)
                put("artists", encodeArtists(item.artists))
                put("year", item.year)
            }
            is ArtistItem -> {
                put("type", "artist")
                put("channelId", item.channelId)
                put("profile", item.isProfile)
            }
            is PlaylistItem -> {
                put("type", "playlist")
                put("author", encodeArtist(item.author))
                put("count", item.songCountText)
                put("podcast", item.isPodcast)
                put("description", item.description)
            }
            is PodcastItem -> {
                put("type", "podcast")
                put("author", encodeArtist(item.author))
                put("count", item.episodeCountText)
                put("channelId", item.channelId)
            }
            is EpisodeItem -> {
                put("type", "episode")
                put("author", encodeArtist(item.author))
                put("duration", item.duration)
                put("date", item.publishDateText)
            }
            else -> return null
        }
    }

    private fun decode(json: JSONObject): YTItem? = runCatching {
        val id = json.getString("id")
        val title = json.getString("title")
        val cover = json.optString("thumbnail")
        if (id.isBlank() || title.isBlank()) return@runCatching null
        val explicit = json.optBoolean("explicit")
        when (json.optString("type")) {
            "song" -> SongItem(id = id, title = title,
                artists = decodeArtists(json.optJSONArray("artists")),
                duration = json.optInt("duration", -1).takeIf { it >= 0 },
                musicVideoType = json.optString("musicVideoType").takeIf(String::isNotBlank),
                setVideoId = json.optString("setVideoId").takeIf(String::isNotBlank),
                thumbnail = cover, explicit = explicit)
            "album" -> AlbumItem(
                browseId = json.optString("browseId"), playlistId = json.optString("playlistId"),
                id = id, title = title,
                artists = decodeArtists(json.optJSONArray("artists")),
                year = json.optInt("year", -1).takeIf { it >= 0 },
                thumbnail = cover, explicit = explicit)
            "artist" -> ArtistItem(
                id = id, title = title, thumbnail = cover,
                channelId = json.optString("channelId").takeIf(String::isNotBlank),
                shuffleEndpoint = null, radioEndpoint = null,
                isProfile = json.optBoolean("profile"))
            "playlist" -> PlaylistItem(
                id = id, title = title, thumbnail = cover,
                author = decodeArtist(json.optJSONObject("author")),
                songCountText = json.optString("count").takeIf(String::isNotBlank),
                playEndpoint = null, shuffleEndpoint = null, radioEndpoint = null,
                isPodcast = json.optBoolean("podcast"),
                description = json.optString("description").takeIf(String::isNotBlank))
            "podcast" -> PodcastItem(
                id = id, title = title, thumbnail = cover,
                author = decodeArtist(json.optJSONObject("author")),
                episodeCountText = json.optString("count").takeIf(String::isNotBlank),
                playEndpoint = null, shuffleEndpoint = null,
                channelId = json.optString("channelId").takeIf(String::isNotBlank))
            "episode" -> EpisodeItem(
                id = id, title = title, thumbnail = cover,
                author = decodeArtist(json.optJSONObject("author")),
                duration = json.optInt("duration", -1).takeIf { it >= 0 },
                publishDateText = json.optString("date").takeIf(String::isNotBlank),
                explicit = explicit)
            else -> null
        }
    }.getOrNull()

    private fun encodeItems(items: List<YTItem>): JSONArray? {
        val array = JSONArray()
        for (item in items.take(300)) array.put(encode(item) ?: return null)
        return array
    }

    private fun decodeItems(array: JSONArray?): List<YTItem>? {
        if (array == null) return null
        val result = ArrayList<YTItem>(array.length())
        for (i in 0 until array.length()) result.add(
            decode(array.optJSONObject(i) ?: return null) ?: return null)
        return result
    }

    fun saveSummary(context: Context, query: String, source: String,
                    summary: SearchSummaryPage) {
        val root = JSONObject().put("schema", 1)
        val groups = JSONArray()
        summary.summaries.forEach { section ->
            groups.put(JSONObject()
                .put("title", section.title)
                .put("items", encodeItems(section.items) ?: return))
        }
        root.put("groups", groups)
        MusicLabSectionMemory.writeState(context, key(query, source, "summary"), root)
    }

    fun loadSummary(context: Context, query: String, source: String): SearchSummaryPage? =
        runCatching {
            val root = MusicLabSectionMemory.readState(context, key(query, source, "summary"))
            if (root.optInt("schema") != 1) return@runCatching null
            val groups = root.optJSONArray("groups") ?: return@runCatching null
            val result = (0 until groups.length()).map { i ->
                val group = groups.optJSONObject(i) ?: return@runCatching null
                SearchSummary(group.optString("title"),
                    decodeItems(group.optJSONArray("items")) ?: return@runCatching null)
            }
            SearchSummaryPage(result)
        }.getOrNull()

    fun savePage(context: Context, query: String, source: String, filter: String,
                 page: ItemsPage) {
        val items = encodeItems(page.items) ?: return
        val root = JSONObject().put("schema", 1)
            .put("items", items).put("continuation", page.continuation)
        MusicLabSectionMemory.writeState(context, key(query, source, filter), root)
    }

    fun loadPage(context: Context, query: String, source: String, filter: String): ItemsPage? =
        runCatching {
            val root = MusicLabSectionMemory.readState(context, key(query, source, filter))
            if (root.optInt("schema") != 1) return@runCatching null
            ItemsPage(decodeItems(root.optJSONArray("items")) ?: return@runCatching null,
                root.optString("continuation").takeIf(String::isNotBlank))
        }.getOrNull()
}
