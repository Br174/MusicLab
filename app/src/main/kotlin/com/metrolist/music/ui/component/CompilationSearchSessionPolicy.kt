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
                    val title = entry.optString("trackTitle").trim().ifBlank { null }
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
