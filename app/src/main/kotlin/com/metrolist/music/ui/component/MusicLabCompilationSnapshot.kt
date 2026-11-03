package com.metrolist.music.ui.component

import android.content.Context
import com.metrolist.music.discogs.DiscogsCompilationSummary
import com.metrolist.music.discogs.DiscogsSearchMode
import com.metrolist.music.playback.MusicLabSectionMemory
import org.json.JSONArray
import org.json.JSONObject

/** Saves only UI metadata, never Discogs tokens or playback state. */
internal object MusicLabCompilationSnapshot {
    private const val KEY = "compilation"
    data class Saved(
        val query: String = "",
        val year: Int? = null,
        val genre: String? = null,
        val style: String? = null,
        val country: String? = null,
        val language: String? = null,
        val filtersExpanded: Boolean = true,
        val results: List<DiscogsCompilationSummary> = emptyList(),
        val page: Int = 0,
        val pages: Int = 0,
        val total: Int = 0,
        val selectedRelease: Int? = null,
        val selectedTrack: Int? = null,
        val openRelease: Int? = null,
        val mode: DiscogsSearchMode = DiscogsSearchMode.COMPILATION,
        val lookupMode: CompilationLookupMode = CompilationLookupMode.TITLE_ARTIST,
        val originalTitle: String = "",
        val originalArtist: String = "",
        val trackTitle: String? = null,
        val trackArtist: String? = null,
    )

    private fun JSONObject.optional(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    fun load(context: Context): Saved = runCatching {
        val root = MusicLabSectionMemory.readState(context, KEY)
        val items = root.optJSONArray("results") ?: JSONArray()
        val summaries = (0 until items.length()).mapNotNull { i ->
            val r = items.optJSONObject(i) ?: return@mapNotNull null
            val id = r.optInt("id")
            if (id <= 0) return@mapNotNull null
            fun values(key: String): List<String> {
                val arr = r.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { idx ->
                    arr.optString(idx).takeIf(String::isNotBlank)
                }
            }
            DiscogsCompilationSummary(id, r.optInt("masterId").takeIf { it > 0 },
                r.optString("title"), r.optInt("year").takeIf { it > 0 },
                r.optional("country"), values("formats"), values("genres"), values("styles"),
                values("labels"), r.optional("thumbnail"), r.optional("cover"))
        }
        Saved(root.optString("query"), root.optInt("year").takeIf { it > 0 },
            root.optional("genre"), root.optional("style"), root.optional("country"),
            root.optional("language"), root.optBoolean("filtersExpanded", true),
            summaries, root.optInt("page"), root.optInt("pages"), root.optInt("total"),
            root.optInt("selectedRelease").takeIf { it > 0 },
            root.optInt("selectedTrack").takeIf { it >= 0 && root.has("selectedTrack") },
            root.optInt("openRelease").takeIf { it > 0 },
            DiscogsSearchMode.restored(root.optString("mode")),
            CompilationLookupMode.restored(root.optString("lookupMode")),
            root.optString("originalTitle"), root.optString("originalArtist"),
            root.optional("trackTitle"), root.optional("trackArtist"))
    }.getOrDefault(Saved())

    fun save(context: Context, saved: Saved) {
        val root = JSONObject().apply {
            put("query", saved.query)
            put("year", saved.year)
            put("genre", saved.genre)
            put("style", saved.style)
            put("country", saved.country)
            put("language", saved.language)
            put("mode", saved.mode.name)
            put("lookupMode", saved.lookupMode.name)
            put("originalTitle", saved.originalTitle)
            put("originalArtist", saved.originalArtist)
            put("trackTitle", saved.trackTitle)
            put("trackArtist", saved.trackArtist)
            put("filtersExpanded", saved.filtersExpanded)
            put("page", saved.page)
            put("pages", saved.pages)
            put("total", saved.total)
            put("selectedRelease", saved.selectedRelease)
            put("selectedTrack", saved.selectedTrack)
            put("openRelease", saved.openRelease)
            put("results", JSONArray().apply {
                saved.results.take(1200).forEach { r ->
                    put(JSONObject().apply {
                        put("id", r.id)
                        put("masterId", r.masterId)
                        put("title", r.title)
                        put("year", r.year)
                        put("country", r.country)
                        put("formats", JSONArray(r.formats))
                        put("genres", JSONArray(r.genres))
                        put("styles", JSONArray(r.styles))
                        put("labels", JSONArray(r.labels))
                        put("thumbnail", r.thumbnailUrl)
                        put("cover", r.coverUrl)
                    })
                }
            })
        }
        MusicLabSectionMemory.writeState(context, KEY, root)
    }
}
