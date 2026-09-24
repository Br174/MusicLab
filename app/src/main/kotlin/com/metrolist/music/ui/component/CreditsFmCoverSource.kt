/**
 * MusicLab Credits.fm cover source
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

internal enum class CreditsFmStatus {
    OK,
    NO_MATCH,
    AUTH_REQUIRED,
    RATE_LIMITED,
    NETWORK_ERROR,
}

internal data class CreditsFmCover(
    val title: String,
    val artist: String,
    val isrc: String,
    val year: Int? = null,
)

internal data class CreditsFmLookup(
    val covers: List<CreditsFmCover>,
    val status: CreditsFmStatus,
    val sourceUrl: String? = null,
)

/**
 * Public read-only Credits.fm integration.
 *
 * Flow: title/artist -> matching ISRC -> typed cover relationships -> metadata
 * for the related recordings. No API key is embedded and no contribution/write
 * endpoint is called.
 */
internal object CreditsFmCoverSource {
    private const val BASE_URL = "https://api.credits.fm/v1"
    private const val REQUEST_TIMEOUT_MS = 7_500
    private const val MAX_RECORDING_CANDIDATES = 3
    private const val MAX_RELATION_ISRCS = 28
    private const val MAX_METADATA_FETCHES = 16
    private const val MAX_COVERS = 80
    private const val CACHE_TTL_MS = 8L * 60L * 60L * 1000L
    private const val ERROR_CACHE_TTL_MS = 8L * 60L * 1000L
    private const val USER_AGENT = "MusicLab/0.8.9 (Android; Credits.fm public read client)"

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val createdAt: Long,
        val lookup: CreditsFmLookup,
    )

    private data class RecordingRef(
        val isrc: String,
        val title: String,
        val artist: String,
        val year: Int? = null,
    )

    private sealed interface FetchJson {
        data class Ok(val body: String, val url: String) : FetchJson
        data class Error(val status: CreditsFmStatus, val url: String) : FetchJson
    }

    fun lookup(title: String, artist: String): CreditsFmLookup {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank()) return CreditsFmLookup(emptyList(), CreditsFmStatus.NO_MATCH)

        val key = "${canonical(cleanTitle)}|${canonical(cleanArtist)}"
        val now = System.currentTimeMillis()
        cache[key]?.let { entry ->
            val ttl = if (entry.lookup.status == CreditsFmStatus.OK || entry.lookup.status == CreditsFmStatus.NO_MATCH) {
                CACHE_TTL_MS
            } else {
                ERROR_CACHE_TTL_MS
            }
            if (now - entry.createdAt < ttl) return entry.lookup
            cache.remove(key)
        }

        val result = lookupFresh(cleanTitle, cleanArtist)
        cache[key] = CacheEntry(now, result)
        return result
    }

    private fun lookupFresh(title: String, artist: String): CreditsFmLookup {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        val searchUrl = buildString {
            append("$BASE_URL/search?q=")
            append(URLEncoder.encode(query, "UTF-8"))
            append("&type=isrc&match=recording_title&exclude_lyrics=true&limit=24")
        }

        val searchRoot = when (val response = fetchJson(searchUrl)) {
            is FetchJson.Error -> return CreditsFmLookup(emptyList(), response.status, response.url)
            is FetchJson.Ok -> parseJson(response.body)
                ?: return CreditsFmLookup(emptyList(), CreditsFmStatus.NETWORK_ERROR, response.url)
        }

        val candidates = collectRecordingRefs(searchRoot)
            .filter { candidate ->
                val titleScore = similarity(title, candidate.title)
                val artistScore = if (artist.isBlank()) 1.0 else similarity(artist, candidate.artist)
                titleScore >= 0.62 && (artist.isBlank() || artistScore >= 0.28 || titleScore >= 0.96)
            }
            .sortedByDescending { candidate ->
                similarity(title, candidate.title) * 0.78 +
                    (if (artist.isBlank()) 1.0 else similarity(artist, candidate.artist)) * 0.22
            }
            .distinctBy { it.isrc }
            .take(MAX_RECORDING_CANDIDATES)

        if (candidates.isEmpty()) {
            return CreditsFmLookup(emptyList(), CreditsFmStatus.NO_MATCH, searchUrl)
        }

        val related = linkedMapOf<String, RecordingRef>()
        var lastUrl: String = searchUrl
        var terminalStatus: CreditsFmStatus? = null

        for (candidate in candidates) {
            val relationshipUrl = "$BASE_URL/isrc/${candidate.isrc}/relationships?type=cover&direction=both&limit=200"
            lastUrl = relationshipUrl
            when (val response = fetchJson(relationshipUrl)) {
                is FetchJson.Error -> {
                    terminalStatus = response.status
                    if (response.status == CreditsFmStatus.AUTH_REQUIRED || response.status == CreditsFmStatus.RATE_LIMITED) break
                }
                is FetchJson.Ok -> {
                    val root = parseJson(response.body) ?: continue
                    collectCoverRelationshipRefs(root, candidate.isrc).forEach { ref ->
                        if (ref.isrc != candidate.isrc) {
                            related.putIfAbsent(ref.isrc, ref)
                        }
                    }
                }
            }
            if (related.size >= MAX_RELATION_ISRCS) break
        }

        if (related.isEmpty()) {
            return CreditsFmLookup(
                emptyList(),
                terminalStatus ?: CreditsFmStatus.NO_MATCH,
                lastUrl,
            )
        }

        var metadataFetches = 0
        val covers = linkedMapOf<String, CreditsFmCover>()
        for ((isrc, embedded) in related) {
            var resolved = embedded
            if ((resolved.title.isBlank() || resolved.artist.isBlank()) && metadataFetches < MAX_METADATA_FETCHES) {
                metadataFetches++
                val detailUrl = "$BASE_URL/isrc/$isrc?contribute=false"
                when (val detail = fetchJson(detailUrl)) {
                    is FetchJson.Ok -> {
                        parseJson(detail.body)?.let { root ->
                            collectRecordingRefs(root).firstOrNull { it.isrc == isrc }?.let { resolved = it }
                        }
                    }
                    is FetchJson.Error -> Unit
                }
            }

            if (resolved.title.isBlank() || resolved.artist.isBlank()) continue
            if (artist.isNotBlank() && similarity(artist, resolved.artist) >= 0.92) continue

            // Cover relations can point in either direction. Keep only recordings
            // whose displayed title still resembles the requested composition;
            // this drops most unrelated graph neighbors without inventing data.
            if (similarity(title, resolved.title) < 0.48) continue

            covers.putIfAbsent(
                isrc,
                CreditsFmCover(
                    title = resolved.title,
                    artist = resolved.artist,
                    isrc = isrc,
                    year = resolved.year,
                ),
            )
            if (covers.size >= MAX_COVERS) break
        }

        return if (covers.isNotEmpty()) {
            CreditsFmLookup(covers.values.toList(), CreditsFmStatus.OK, lastUrl)
        } else {
            CreditsFmLookup(emptyList(), terminalStatus ?: CreditsFmStatus.NO_MATCH, lastUrl)
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

        val body = response.body()
        when (response.statusCode()) {
            in 200..299 -> if (body.trim().startsWith("{") || body.trim().startsWith("[")) {
                FetchJson.Ok(body, response.url().toString())
            } else {
                FetchJson.Error(CreditsFmStatus.NETWORK_ERROR, response.url().toString())
            }
            401, 403 -> FetchJson.Error(CreditsFmStatus.AUTH_REQUIRED, response.url().toString())
            429 -> FetchJson.Error(CreditsFmStatus.RATE_LIMITED, response.url().toString())
            else -> FetchJson.Error(CreditsFmStatus.NETWORK_ERROR, response.url().toString())
        }
    }.getOrElse {
        FetchJson.Error(CreditsFmStatus.NETWORK_ERROR, url)
    }

    private fun parseJson(body: String): Any? = runCatching {
        val clean = body.trim()
        when {
            clean.startsWith("{") -> JSONObject(clean)
            clean.startsWith("[") -> JSONArray(clean)
            else -> null
        }
    }.getOrNull()

    private fun collectRecordingRefs(root: Any): List<RecordingRef> {
        val result = linkedMapOf<String, RecordingRef>()

        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    recordingFromObject(value)?.let { ref -> result.putIfAbsent(ref.isrc, ref) }
                    value.keys().forEach { key -> visit(value.opt(key)) }
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
            }
        }

        visit(root)
        return result.values.toList()
    }

    private fun collectCoverRelationshipRefs(root: Any, currentIsrc: String): List<RecordingRef> {
        val result = linkedMapOf<String, RecordingRef>()

        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    if (looksLikeCoverRelation(value)) {
                        val refs = collectRecordingRefs(value)
                        refs.filter { it.isrc != currentIsrc }.forEach { ref ->
                            result.putIfAbsent(ref.isrc, ref)
                        }
                        collectDirectIsrcs(value)
                            .filter { it != currentIsrc }
                            .forEach { isrc ->
                                result.putIfAbsent(isrc, RecordingRef(isrc, "", "", null))
                            }
                    }
                    value.keys().forEach { key -> visit(value.opt(key)) }
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
            }
        }

        visit(root)
        return result.values.take(MAX_RELATION_ISRCS)
    }

    private fun looksLikeCoverRelation(obj: JSONObject): Boolean {
        val values = listOf(
            obj.optString("type"),
            obj.optString("relation_type"),
            obj.optString("relationship_type"),
            obj.optString("relation"),
            obj.optString("kind"),
        )
        return values.any { it.equals("cover", ignoreCase = true) || it.contains("cover", ignoreCase = true) }
    }

    private fun collectDirectIsrcs(obj: JSONObject): Set<String> {
        val result = linkedSetOf<String>()

        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (key.contains("isrc", ignoreCase = true) && child is String) {
                        normalizeIsrc(child)?.let(result::add)
                    }
                    visit(child)
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                is String -> normalizeIsrc(value)?.let { isrc ->
                    if (ISRC_REGEX.matches(isrc)) result += isrc
                }
            }
        }

        visit(obj)
        return result
    }

    private fun recordingFromObject(obj: JSONObject): RecordingRef? {
        val isrc = sequenceOf(
            obj.optString("isrc"),
            obj.optString("recording_isrc"),
            obj.optString("code"),
            obj.optString("id"),
        ).mapNotNull(::normalizeIsrc).firstOrNull { ISRC_REGEX.matches(it) } ?: return null

        val title = firstText(obj, "recording_title", "song_title", "track_title", "title", "name")
        val artist = artistText(obj)
        val year = firstYear(obj)
        return RecordingRef(isrc, title, artist, year)
    }

    private fun firstText(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.opt(key)
            when (value) {
                is String -> if (value.isNotBlank()) return value.trim()
                is JSONObject -> {
                    val nested = firstText(value, "name", "title", "artist_name")
                    if (nested.isNotBlank()) return nested
                }
            }
        }
        return ""
    }

    private fun artistText(obj: JSONObject): String {
        val direct = firstText(obj, "artist_name", "performer_name", "artist", "performer")
        if (direct.isNotBlank()) return direct

        for (key in listOf("artist_names", "artists", "performers", "release_artists")) {
            val value = obj.opt(key)
            when (value) {
                is JSONArray -> {
                    val names = buildList {
                        for (index in 0 until value.length()) {
                            when (val item = value.opt(index)) {
                                is String -> if (item.isNotBlank()) add(item.trim())
                                is JSONObject -> firstText(item, "name", "artist_name").takeIf { it.isNotBlank() }?.let(::add)
                            }
                        }
                    }
                    if (names.isNotEmpty()) return names.joinToString(", ")
                }
                is String -> if (value.isNotBlank()) return value.trim()
            }
        }
        return ""
    }

    private fun firstYear(obj: JSONObject): Int? {
        for (key in listOf("year", "release_year", "first_release_year", "date", "release_date")) {
            val value = obj.opt(key)?.toString().orEmpty()
            YEAR_REGEX.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun normalizeIsrc(value: String): String? {
        val clean = value.uppercase().replace(Regex("[^A-Z0-9]"), "")
        return clean.takeIf { ISRC_REGEX.matches(it) }
    }

    private fun canonical(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun similarity(a: String, b: String): Double {
        val aa = canonical(a)
        val bb = canonical(b)
        if (aa.isBlank() || bb.isBlank()) return 0.0
        if (aa == bb) return 1.0
        if (aa.contains(bb) || bb.contains(aa)) return 0.92
        val aWords = aa.split(' ').filter { it.length > 1 }.toSet()
        val bWords = bb.split(' ').filter { it.length > 1 }.toSet()
        if (aWords.isEmpty() || bWords.isEmpty()) return 0.0
        val overlap = aWords.intersect(bWords).size.toDouble()
        val containment = overlap / max(1, minOf(aWords.size, bWords.size)).toDouble()
        val jaccard = overlap / aWords.union(bWords).size.toDouble()
        return containment * 0.68 + jaccard * 0.32
    }

    private val ISRC_REGEX = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")
    private val YEAR_REGEX = Regex("\\b((?:18|19|20)\\d{2})\\b")
}
