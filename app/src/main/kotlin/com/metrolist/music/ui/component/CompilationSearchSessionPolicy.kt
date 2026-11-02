package com.metrolist.music.ui.component

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** R38: editions are search metadata, not part of the work title. */
internal object CompilationSearchTitlePolicy {
    private val technicalSuffix = Regex(
        """\s*[\(\[]\s*(?:remaster(?:ed)?|\d{4}\s+remaster|live|radio\s+edit|album\s+version|single\s+version|mono|stereo|original\s+version|\d+\s*k(?:hz)?\s*remaster)[^\)\]]*[\)\]]\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun clean(input: String): String {
        var result = input.trim()
        repeat(3) { result = result.replace(technicalSuffix, "").trim() }
        return result.ifBlank { input.trim() }
    }
}

/** R46: Player fallback strips video-specific metadata, never the first complete query. */
internal object CompilationFallbackQueryPolicy {
    private val videoSuffix = Regex(
        """\s*[\(\[]\s*(?:official(?:\s+music)?\s+video|official\s+audio|music\s+video|video\s+ufficiale|lyric(?:s)?(?:\s+video)?|remaster(?:ed)?|live|hd|hq|4k)[^\)\]]*[\)\]]\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val separators = listOf(" - ", " – ", " — ", " | ", " : ")

    fun songTitle(fullVideoTitle: String, artist: String): String {
        var title = fullVideoTitle.trim()
        val singer = artist.trim()
        if (singer.isNotEmpty()) {
            for (sep in separators) {
                if (title.startsWith(singer + sep, ignoreCase = true)) {
                    title = title.substring(singer.length + sep.length).trim()
                    break
                }
                val suffix = sep + singer
                if (title.endsWith(suffix, ignoreCase = true)) {
                    title = title.dropLast(suffix.length).trim()
                    break
                }
            }
        }
        repeat(4) { title = title.replace(videoSuffix, "").trim() }
        return CompilationSearchTitlePolicy.clean(title)
    }

    fun query(fullVideoTitle: String, artist: String): String? {
        val title = songTitle(fullVideoTitle, artist)
        val singer = artist.trim().replace(Regex("\\s+"), " ")
        if (title.isBlank() || singer.isBlank()) return null
        return if (title.contains(singer, ignoreCase = true)) title else "$title $singer"
    }
}

/** Only search criteria are saved; never credentials, audio content, or result lists. */
internal object CompilationSearchHistoryStore {
    private val historyKey = stringPreferencesKey("musiclab_compilation_search_history_r38")
    private const val LIMIT = 30

    suspend fun load(context: Context): List<CompilationSearchCriteria> =
        decode(context.dataStore.data.first()[historyKey])

    suspend fun save(context: Context, criteria: CompilationSearchCriteria) {
        context.safeDataStoreEdit { prefs ->
            val entries = decode(prefs[historyKey])
            val updated = (listOf(criteria) + entries.filterNot { it == criteria }).take(LIMIT)
            prefs[historyKey] = encode(updated)
        }
    }

    private fun decode(raw: String?): List<CompilationSearchCriteria> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val data = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(data.length(), LIMIT)) {
                    val entry = data.optJSONObject(index) ?: continue
                    val query = entry.optString("query").trim()
                    val title = entry.optString("trackTitle").trim().takeIf(String::isNotBlank)
                    if (query.isBlank() && title == null && entry.optInt("year", 0) == 0 &&
                        entry.optString("genre").isBlank() && entry.optString("style").isBlank() &&
                        entry.optString("country").isBlank() && entry.optString("language").isBlank()) continue
                    add(CompilationSearchCriteria(
                        query = query,
                        year = entry.optInt("year", 0).takeIf { it in 1900..3000 },
                        genre = entry.optString("genre").takeIf(String::isNotBlank),
                        style = entry.optString("style").takeIf(String::isNotBlank),
                        country = entry.optString("country").takeIf(String::isNotBlank),
                        language = entry.optString("language").takeIf(String::isNotBlank),
                        trackTitle = title,
                        trackArtist = entry.optString("trackArtist").takeIf(String::isNotBlank),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(entries: List<CompilationSearchCriteria>): String =
        JSONArray().apply {
            entries.take(LIMIT).forEach { entry ->
                put(JSONObject().apply {
                    put("query", entry.query)
                    entry.year?.let { put("year", it) }
                    entry.genre?.let { put("genre", it) }
                    entry.style?.let { put("style", it) }
                    entry.country?.let { put("country", it) }
                    entry.language?.let { put("language", it) }
                    entry.trackTitle?.let { put("trackTitle", it) }
                    entry.trackArtist?.let { put("trackArtist", it) }
                })
            }
        }.toString()
}
