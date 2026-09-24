/**
 * MusicLab SecondHandSongs cover source
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

internal enum class SecondHandSongsStatus {
    OK,
    NO_MATCH,
    BLOCKED,
    RATE_LIMITED,
    NETWORK_ERROR,
}

internal data class SecondHandSongsCover(
    val title: String,
    val artist: String,
    val performanceId: String,
    val workId: String,
    val year: Int? = null,
)

internal data class SecondHandSongsLookup(
    val covers: List<SecondHandSongsCover>,
    val status: SecondHandSongsStatus,
    val sourceUrl: String? = null,
)

/**
 * Structured fallback for cover/version discovery through SecondHandSongs.
 *
 * The public API returns JSON when Accept: application/json is requested.
 * No API key is required for the basic metadata used here. MusicLab does not
 * request or depend on SecondHandSongs' external YouTube/Spotify links: each
 * discovered performance is resolved to a playable item through YouTube Music.
 */
internal object SecondHandSongsCoverSource {
    private const val BASE_URL = "https://secondhandsongs.com"
    private const val REQUEST_TIMEOUT_MS = 7_500
    private const val MAX_SEARCH_CANDIDATES = 5
    private const val MAX_DISCOVERED_COVERS = 80
    private const val CACHE_TTL_MS = 12L * 60L * 60L * 1000L
    private const val ERROR_CACHE_TTL_MS = 10L * 60L * 1000L
    private const val USER_AGENT = "MusicLab/0.8.9 (https://github.com/Br174/MusicLab)"

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val createdAt: Long,
        val lookup: SecondHandSongsLookup,
    )

    private sealed interface FetchJson {
        data class Ok(val body: String, val url: String) : FetchJson
        data class Error(val status: SecondHandSongsStatus, val url: String) : FetchJson
    }

    private data class PerformanceRef(
        val id: String,
        val title: String,
        val artist: String,
        val workIds: List<String>,
        val year: Int?,
    )

    fun lookup(title: String, artist: String): SecondHandSongsLookup {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank()) {
            return SecondHandSongsLookup(emptyList(), SecondHandSongsStatus.NO_MATCH)
        }

        val key = "${canonical(cleanTitle)}|${canonical(cleanArtist)}"
        val now = System.currentTimeMillis()
        cache[key]?.let { entry ->
            val ttl = if (
                entry.lookup.status == SecondHandSongsStatus.OK ||
                entry.lookup.status == SecondHandSongsStatus.NO_MATCH
            ) {
                CACHE_TTL_MS
            } else {
                ERROR_CACHE_TTL_MS
            }
            if (now - entry.createdAt < ttl) return entry.lookup
            cache.remove(key)
        }

        val lookup = lookupFresh(cleanTitle, cleanArtist)
        cache[key] = CacheEntry(now, lookup)
        return lookup
    }

    private fun lookupFresh(title: String, artist: String): SecondHandSongsLookup {
        val searchUrl = buildString {
            append("$BASE_URL/search/performance?title=")
            append(URLEncoder.encode(title, "UTF-8"))
            if (artist.isNotBlank()) {
                append("&performer=")
                append(URLEncoder.encode(artist, "UTF-8"))
            }
            append("&pageSize=10&page=1")
        }

        val search = fetchJson(searchUrl)
        if (search is FetchJson.Error) {
            return SecondHandSongsLookup(emptyList(), search.status, search.url)
        }
        search as FetchJson.Ok

        val searchRoot = parseJson(search.body)
            ?: return SecondHandSongsLookup(emptyList(), SecondHandSongsStatus.NETWORK_ERROR, search.url)
        val candidates = collectPerformanceRefs(searchRoot)
            .filter { ref ->
                val titleScore = textSimilarity(title, ref.title)
                val artistScore = if (artist.isBlank()) 1.0 else textSimilarity(artist, ref.artist)
                titleScore >= 0.60 && (artist.isBlank() || artistScore >= 0.28 || titleScore >= 0.94)
            }
            .sortedByDescending { ref ->
                textSimilarity(title, ref.title) * 0.78 +
                    (if (artist.isBlank()) 1.0 else textSimilarity(artist, ref.artist)) * 0.22
            }
            .distinctBy { it.id }
            .take(MAX_SEARCH_CANDIDATES)

        if (candidates.isEmpty()) {
            return SecondHandSongsLookup(emptyList(), SecondHandSongsStatus.NO_MATCH, search.url)
        }

        for (candidate in candidates) {
            val detailed = if (candidate.workIds.isNotEmpty()) {
                candidate
            } else {
                fetchPerformance(candidate.id) ?: candidate
            }

            val workId = detailed.workIds.firstOrNull() ?: continue
            val workUrl = "$BASE_URL/work/$workId"
            when (val workResponse = fetchJson(workUrl)) {
                is FetchJson.Error -> {
                    if (workResponse.status == SecondHandSongsStatus.BLOCKED ||
                        workResponse.status == SecondHandSongsStatus.RATE_LIMITED
                    ) {
                        return SecondHandSongsLookup(emptyList(), workResponse.status, workUrl)
                    }
                }
                is FetchJson.Ok -> {
                    val root = parseJson(workResponse.body) ?: continue
                    val versions = collectPerformanceRefs(root)
                        .filter { it.id != candidate.id }
                        .mapNotNull { ref ->
                            if (ref.title.isBlank() || ref.artist.isBlank()) return@mapNotNull null
                            SecondHandSongsCover(
                                title = ref.title,
                                artist = ref.artist,
                                performanceId = ref.id,
                                workId = workId,
                                year = ref.year,
                            )
                        }
                        .distinctBy { "${canonical(it.title)}|${canonical(it.artist)}" }
                        .take(MAX_DISCOVERED_COVERS)

                    if (versions.isNotEmpty()) {
                        return SecondHandSongsLookup(
                            covers = versions,
                            status = SecondHandSongsStatus.OK,
                            sourceUrl = workUrl,
                        )
                    }
                }
            }
        }

        return SecondHandSongsLookup(emptyList(), SecondHandSongsStatus.NO_MATCH, search.url)
    }

    private fun fetchPerformance(id: String): PerformanceRef? {
        if (id.isBlank()) return null
        return when (val response = fetchJson("$BASE_URL/performance/$id")) {
            is FetchJson.Error -> null
            is FetchJson.Ok -> {
                val root = parseJson(response.body) ?: return null
                collectPerformanceRefs(root).firstOrNull { it.id == id }
                    ?: (root as? JSONObject)?.let(::performanceFromObject)
            }
        }
    }

    private fun fetchJson(url: String): FetchJson = runCatching {
        val response = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT_MS)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .execute()

        when (response.statusCode()) {
            in 200..299 -> FetchJson.Ok(response.body(), response.url().toString())
            403 -> FetchJson.Error(SecondHandSongsStatus.BLOCKED, response.url().toString())
            429 -> FetchJson.Error(SecondHandSongsStatus.RATE_LIMITED, response.url().toString())
            else -> FetchJson.Error(SecondHandSongsStatus.NETWORK_ERROR, response.url().toString())
        }
    }.getOrElse {
        FetchJson.Error(SecondHandSongsStatus.NETWORK_ERROR, url)
    }

    private fun parseJson(body: String): Any? {
        val trimmed = body.trim()
        return runCatching {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> null
            }
        }.getOrNull()
    }

    private fun collectPerformanceRefs(root: Any): List<PerformanceRef> {
        val result = linkedMapOf<String, PerformanceRef>()

        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    performanceFromObject(value)?.let { ref ->
                        result.putIfAbsent(ref.id, ref)
                    }
                    value.keys().forEach { key -> visit(value.opt(key)) }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) visit(value.opt(index))
                }
            }
        }

        visit(root)
        return result.values.toList()
    }

    private fun performanceFromObject(obj: JSONObject): PerformanceRef? {
        val uri = obj.optString("uri").trim()
        val entityType = obj.optString("entityType").trim()
        val looksLikePerformance =
            entityType.equals("performance", ignoreCase = true) || uri.contains("/performance/")
        if (!looksLikePerformance) return null

        val id = uri.substringAfterLast('/').substringBefore('?').trim()
        val title = obj.optString("title").trim()
        val performer = obj.optJSONObject("performer")?.optString("name")?.trim().orEmpty()
        if (id.isBlank() || title.isBlank()) return null

        val workIds = mutableListOf<String>()
        obj.optJSONArray("works")?.let { works ->
            for (i in 0 until works.length()) {
                val workUri = works.optJSONObject(i)?.optString("uri").orEmpty()
                val workId = workUri.substringAfterLast('/').substringBefore('?').trim()
                if (workId.isNotBlank()) workIds += workId
            }
        }

        val year = parseYear(obj)
        return PerformanceRef(
            id = id,
            title = title,
            artist = performer,
            workIds = workIds.distinct(),
            year = year,
        )
    }

    private fun parseYear(obj: JSONObject): Int? {
        val candidates = buildList {
            add(obj.optString("date"))
            add(obj.optString("releaseDate"))
            add(obj.optString("released"))
            obj.optJSONArray("releases")?.let { releases ->
                for (i in 0 until releases.length()) {
                    val release = releases.optJSONObject(i) ?: continue
                    add(release.optString("date"))
                    add(release.optString("releaseDate"))
                }
            }
        }
        return candidates.asSequence()
            .mapNotNull { value -> Regex("\\b(18|19|20)\\d{2}\\b").find(value)?.value?.toIntOrNull() }
            .firstOrNull { it in 1800..2100 }
    }

    private fun textSimilarity(a: String, b: String): Double {
        val left = canonical(a)
        val right = canonical(b)
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        if (left.contains(right) || right.contains(left)) return 0.92

        val leftWords = left.split(' ').filter { it.length > 1 }.toSet()
        val rightWords = right.split(' ').filter { it.length > 1 }.toSet()
        if (leftWords.isEmpty() || rightWords.isEmpty()) return 0.0
        val overlap = leftWords.intersect(rightWords).size.toDouble()
        val containment = overlap / max(1, minOf(leftWords.size, rightWords.size)).toDouble()
        val union = leftWords.union(rightWords).size.coerceAtLeast(1)
        val jaccard = overlap / union.toDouble()
        return containment * 0.72 + jaccard * 0.28
    }

    private fun canonical(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}
