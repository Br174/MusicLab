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
    AUTH_REQUIRED,
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
 * Structured source for cover/version discovery through SecondHandSongs.
 *
 * MusicLab first searches by title + performer and, if that is too strict,
 * retries by title only. It then follows the native performance relations:
 * current performance -> original performance -> covers. A direct work search
 * is also used when performance search cannot identify the recording. No
 * CAPTCHA/access-control bypass is attempted and no API key is hardcoded.
 */
internal object SecondHandSongsCoverSource {
    private const val BASE_URL = "https://secondhandsongs.com"
    private const val REQUEST_TIMEOUT_MS = 7_500
    private const val MAX_SEARCH_CANDIDATES = 6
    private const val MAX_WORK_CANDIDATES = 6
    private const val MAX_DISCOVERED_COVERS = 100
    private const val MAX_ORIGINAL_EXPANSIONS = 2
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

    private data class WorkRef(
        val id: String,
        val title: String,
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
        val candidates = linkedMapOf<String, PerformanceRef>()
        var lastUrl: String? = null
        var lastStatus: SecondHandSongsStatus? = null

        val searchArtists = buildList {
            if (artist.isNotBlank()) add(artist)
            add("")
        }.distinct()

        for (searchArtist in searchArtists) {
            val searchUrl = buildSearchUrl(title, searchArtist)
            lastUrl = searchUrl
            when (val search = fetchJson(searchUrl)) {
                is FetchJson.Error -> {
                    lastStatus = search.status
                    if (
                        search.status == SecondHandSongsStatus.BLOCKED ||
                        search.status == SecondHandSongsStatus.AUTH_REQUIRED ||
                        search.status == SecondHandSongsStatus.RATE_LIMITED
                    ) {
                        return SecondHandSongsLookup(emptyList(), search.status, search.url)
                    }
                }

                is FetchJson.Ok -> {
                    val root = parseJson(search.body) ?: run {
                        lastStatus = SecondHandSongsStatus.NETWORK_ERROR
                        continue
                    }
                    collectPerformanceRefs(root)
                        .filter { ref ->
                            val titleScore = textSimilarity(title, ref.title)
                            val artistScore = if (artist.isBlank()) 1.0 else textSimilarity(artist, ref.artist)
                            titleScore >= 0.60 &&
                                (searchArtist.isBlank() || artistScore >= 0.24 || titleScore >= 0.94)
                        }
                        .sortedByDescending { ref ->
                            textSimilarity(title, ref.title) * 0.80 +
                                (if (artist.isBlank()) 1.0 else textSimilarity(artist, ref.artist)) * 0.20
                        }
                        .forEach { ref -> candidates.putIfAbsent(ref.id, ref) }
                }
            }
            if (candidates.size >= MAX_SEARCH_CANDIDATES) break
        }

        if (candidates.isEmpty()) {
            val workFallback = lookupByWorkTitle(title)
            if (
                workFallback.status != SecondHandSongsStatus.NO_MATCH ||
                workFallback.covers.isNotEmpty()
            ) {
                return workFallback
            }
            return SecondHandSongsLookup(
                emptyList(),
                lastStatus ?: SecondHandSongsStatus.NO_MATCH,
                lastUrl,
            )
        }

        for (candidate in candidates.values.take(MAX_SEARCH_CANDIDATES)) {
            val detailRoot = fetchPerformanceRoot(candidate.id)
            val detailed = detailRoot
                ?.let(::collectPerformanceRefs)
                ?.firstOrNull { it.id == candidate.id }
                ?: candidate
            val workId = detailed.workIds.firstOrNull().orEmpty()

            val relationRefs = linkedMapOf<String, PerformanceRef>()
            detailRoot?.let { root ->
                collectPerformanceRefs(root)
                    .filter { it.id != candidate.id }
                    .forEach { relationRefs.putIfAbsent(it.id, it) }

                originalPerformanceIds(root)
                    .take(MAX_ORIGINAL_EXPANSIONS)
                    .forEach { originalId ->
                        fetchPerformanceRoot(originalId)?.let { originalRoot ->
                            collectPerformanceRefs(originalRoot)
                                .filter { it.id != candidate.id && it.id != originalId }
                                .forEach { relationRefs.putIfAbsent(it.id, it) }
                        }
                    }
            }

            val relationCovers = relationRefs.values
                .mapNotNull { ref -> ref.toCover(workId) }
                .distinctBy { "${canonical(it.title)}|${canonical(it.artist)}" }
                .take(MAX_DISCOVERED_COVERS)

            if (relationCovers.isNotEmpty()) {
                return SecondHandSongsLookup(
                    covers = relationCovers,
                    status = SecondHandSongsStatus.OK,
                    sourceUrl = "$BASE_URL/performance/${candidate.id}",
                )
            }

            if (workId.isBlank()) continue
            val workResult = fetchWorkVersions(workId)
            if (
                workResult.status == SecondHandSongsStatus.OK ||
                workResult.status == SecondHandSongsStatus.BLOCKED ||
                workResult.status == SecondHandSongsStatus.AUTH_REQUIRED ||
                workResult.status == SecondHandSongsStatus.RATE_LIMITED
            ) {
                return workResult
            }
        }

        val workFallback = lookupByWorkTitle(title)
        if (
            workFallback.status != SecondHandSongsStatus.NO_MATCH ||
            workFallback.covers.isNotEmpty()
        ) {
            return workFallback
        }

        return SecondHandSongsLookup(emptyList(), SecondHandSongsStatus.NO_MATCH, lastUrl)
    }

    private fun lookupByWorkTitle(title: String): SecondHandSongsLookup {
        val searchUrl = buildWorkSearchUrl(title)
        val root = when (val search = fetchJson(searchUrl)) {
            is FetchJson.Error ->
                return SecondHandSongsLookup(emptyList(), search.status, search.url)
            is FetchJson.Ok ->
                parseJson(search.body)
                    ?: return SecondHandSongsLookup(
                        emptyList(),
                        SecondHandSongsStatus.NETWORK_ERROR,
                        search.url,
                    )
        }

        val works = collectWorkRefs(root)
            .filter { textSimilarity(title, it.title) >= 0.60 }
            .sortedByDescending { textSimilarity(title, it.title) }
            .distinctBy { it.id }
            .take(MAX_WORK_CANDIDATES)

        if (works.isEmpty()) {
            return SecondHandSongsLookup(emptyList(), SecondHandSongsStatus.NO_MATCH, searchUrl)
        }

        var lastStatus: SecondHandSongsStatus? = null
        for (work in works) {
            val result = fetchWorkVersions(work.id)
            when (result.status) {
                SecondHandSongsStatus.OK -> return result
                SecondHandSongsStatus.BLOCKED,
                SecondHandSongsStatus.AUTH_REQUIRED,
                SecondHandSongsStatus.RATE_LIMITED -> return result
                SecondHandSongsStatus.NETWORK_ERROR -> lastStatus = result.status
                SecondHandSongsStatus.NO_MATCH -> Unit
            }
        }

        return SecondHandSongsLookup(
            emptyList(),
            lastStatus ?: SecondHandSongsStatus.NO_MATCH,
            searchUrl,
        )
    }

    private fun fetchWorkVersions(workId: String): SecondHandSongsLookup {
        if (workId.isBlank()) {
            return SecondHandSongsLookup(emptyList(), SecondHandSongsStatus.NO_MATCH)
        }
        val workUrl = "$BASE_URL/work/$workId"
        return when (val workResponse = fetchJson(workUrl)) {
            is FetchJson.Error ->
                SecondHandSongsLookup(emptyList(), workResponse.status, workResponse.url)

            is FetchJson.Ok -> {
                val root = parseJson(workResponse.body)
                    ?: return SecondHandSongsLookup(
                        emptyList(),
                        SecondHandSongsStatus.NETWORK_ERROR,
                        workUrl,
                    )
                val versions = collectPerformanceRefs(root)
                    .mapNotNull { ref -> ref.toCover(workId) }
                    .distinctBy { "${canonical(it.title)}|${canonical(it.artist)}" }
                    .take(MAX_DISCOVERED_COVERS)

                if (versions.isEmpty()) {
                    SecondHandSongsLookup(emptyList(), SecondHandSongsStatus.NO_MATCH, workUrl)
                } else {
                    SecondHandSongsLookup(
                        covers = versions,
                        status = SecondHandSongsStatus.OK,
                        sourceUrl = workUrl,
                    )
                }
            }
        }
    }

    private fun buildSearchUrl(title: String, artist: String): String = buildString {
        append("$BASE_URL/search/performance?title=")
        append(URLEncoder.encode(title, "UTF-8"))
        if (artist.isNotBlank()) {
            append("&performer=")
            append(URLEncoder.encode(artist, "UTF-8"))
        }
        append("&pageSize=20&page=1")
    }

    private fun buildWorkSearchUrl(title: String): String = buildString {
        append("$BASE_URL/search/work?title=")
        append(URLEncoder.encode(title, "UTF-8"))
        append("&pageSize=20&page=1")
    }

    private fun fetchPerformanceRoot(id: String): JSONObject? {
        if (id.isBlank()) return null
        return when (val response = fetchJson("$BASE_URL/performance/$id")) {
            is FetchJson.Error -> null
            is FetchJson.Ok -> parseJson(response.body) as? JSONObject
        }
    }

    private fun originalPerformanceIds(root: JSONObject): List<String> {
        val originals = root.optJSONArray("originals") ?: return emptyList()
        return collectPerformanceRefs(originals).map { it.id }.distinct()
    }

    private fun PerformanceRef.toCover(fallbackWorkId: String): SecondHandSongsCover? {
        if (title.isBlank() || artist.isBlank()) return null
        return SecondHandSongsCover(
            title = title,
            artist = artist,
            performanceId = id,
            workId = workIds.firstOrNull().orEmpty().ifBlank { fallbackWorkId },
            year = year,
        )
    }

    private fun fetchJson(url: String): FetchJson = runCatching {
        val response = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT_MS)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .execute()

        val body = response.body()
        when (response.statusCode()) {
            in 200..299 -> when {
                body.trim().startsWith("{") || body.trim().startsWith("[") ->
                    FetchJson.Ok(body, response.url().toString())
                looksLikeChallenge(body) ->
                    FetchJson.Error(SecondHandSongsStatus.BLOCKED, response.url().toString())
                else ->
                    FetchJson.Error(SecondHandSongsStatus.NETWORK_ERROR, response.url().toString())
            }
            401 -> FetchJson.Error(SecondHandSongsStatus.AUTH_REQUIRED, response.url().toString())
            403 -> FetchJson.Error(SecondHandSongsStatus.BLOCKED, response.url().toString())
            429 -> FetchJson.Error(SecondHandSongsStatus.RATE_LIMITED, response.url().toString())
            else -> FetchJson.Error(SecondHandSongsStatus.NETWORK_ERROR, response.url().toString())
        }
    }.getOrElse {
        FetchJson.Error(SecondHandSongsStatus.NETWORK_ERROR, url)
    }

    private fun looksLikeChallenge(body: String): Boolean {
        val text = body.lowercase()
        return text.contains("captcha") ||
            text.contains("verify you are human") ||
            text.contains("access denied") ||
            text.contains("cloudflare") ||
            text.contains("security check")
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

    private fun collectWorkRefs(root: Any): List<WorkRef> {
        val result = linkedMapOf<String, WorkRef>()

        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val uri = value.optString("uri").trim()
                    val entityType = value.optString("entityType").trim()
                    val looksLikeWork =
                        entityType.equals("work", ignoreCase = true) || uri.contains("/work/")
                    if (looksLikeWork) {
                        val id = uri.substringAfterLast('/').substringBefore('?').trim()
                        val title = value.optString("title").trim()
                        if (id.isNotBlank() && title.isNotBlank()) {
                            result.putIfAbsent(id, WorkRef(id, title))
                        }
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

        return PerformanceRef(
            id = id,
            title = title,
            artist = performer,
            workIds = workIds.distinct(),
            year = parseYear(obj),
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
