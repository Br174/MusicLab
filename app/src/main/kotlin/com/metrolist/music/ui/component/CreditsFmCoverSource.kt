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
    val sourceCandidates: Int = 0,
    val worksFound: Int = 0,
    val linkedRecordings: Int = 0,
)

/**
 * Read-only Credits.fm integration.
 *
 * Primary path:
 * title/artist -> source ISRC -> linked ISWC -> other recordings of the same work.
 * Typed cover relations are a fallback for records without a useful ISWC expansion.
 *
 * The public API works without a key (with lower rate limits), so no secret is
 * embedded in the application and no write/contribution endpoint is called.
 */
internal object CreditsFmCoverSource {
    private const val BASE_URL = "https://api.credits.fm/v1"
    private const val REQUEST_TIMEOUT_MS = 8_000
    private const val MAX_RECORDING_CANDIDATES = 12
    private const val MAX_WORKS_PER_RECORDING = 4
    private const val MAX_RELATED_ISRCS = 240
    private const val MAX_METADATA_FETCHES = 80
    private const val MAX_COVERS = 120
    private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    private const val NO_MATCH_CACHE_TTL_MS = 60L * 60L * 1000L
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
            val ttl = when (entry.lookup.status) {
                CreditsFmStatus.OK -> CACHE_TTL_MS
                CreditsFmStatus.NO_MATCH -> NO_MATCH_CACHE_TTL_MS
                else -> 0L
            }
            if (ttl > 0L && now - entry.createdAt < ttl) return entry.lookup
            cache.remove(key)
        }

        val result = lookupFresh(cleanTitle, cleanArtist)
        if (result.status == CreditsFmStatus.OK || result.status == CreditsFmStatus.NO_MATCH) {
            cache[key] = CacheEntry(now, result)
        }
        return result
    }

    private fun lookupFresh(title: String, artist: String): CreditsFmLookup {
        // `match=recording_title` searches title/song fields only. Keep the artist
        // out of q, otherwise exact-title recordings by other performers (covers) can
        // disappear before we ever reach their shared ISWC.
        val encoded = URLEncoder.encode(title, "UTF-8")
        val searchUrl = "$BASE_URL/search?q=$encoded&type=isrc&match=recording_title&exclude_lyrics=true&limit=100&nocache=1"

        val searchRoot = when (val response = fetchJson(searchUrl)) {
            is FetchJson.Error -> return CreditsFmLookup(emptyList(), response.status, response.url)
            is FetchJson.Ok -> parseJson(response.body)
                ?: return CreditsFmLookup(emptyList(), CreditsFmStatus.NETWORK_ERROR, response.url)
        }

        val candidates = collectRecordingRefs(searchRoot)
            .filter { candidate ->
                val titleScore = similarity(title, candidate.title)
                val artistScore = if (artist.isBlank()) 1.0 else similarity(artist, candidate.artist)
                titleScore >= 0.58 && (artist.isBlank() || artistScore >= 0.22 || titleScore >= 0.96)
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
        val worksSeen = linkedSetOf<String>()
        val sourceCandidateCount = candidates.size
        var lastUrl = searchUrl
        var terminalStatus: CreditsFmStatus? = null

        for (candidate in candidates) {
            val beforeCandidate = related.size

            // Preferred path: recordings linked to the same composition (ISWC).
            val detailUrl = "$BASE_URL/isrc/${candidate.isrc}?contribute=false"
            lastUrl = detailUrl
            val works = when (val response = fetchJson(detailUrl)) {
                is FetchJson.Error -> {
                    terminalStatus = response.status
                    emptyList()
                }
                is FetchJson.Ok -> parseJson(response.body)
                    ?.let(::collectIswcs)
                    .orEmpty()
                    .take(MAX_WORKS_PER_RECORDING)
            }

            for (iswc in works) {
                worksSeen += iswc
                // Credits.fm identifier graph supports relationship expansion on the
                // canonical ISWC endpoint. depth=2 asks for enriched recording objects;
                // direct ISRC strings are also collected below as a defensive fallback.
                val workUrl = "$BASE_URL/iswc/${URLEncoder.encode(iswc, "UTF-8")}?include=recordings&depth=2&limit=-1&contribute=false"
                lastUrl = workUrl
                when (val response = fetchJson(workUrl)) {
                    is FetchJson.Error -> terminalStatus = response.status
                    is FetchJson.Ok -> parseJson(response.body)?.let { root ->
                        collectRecordingRefs(root)
                            .filter { it.isrc != candidate.isrc }
                            .forEach { related.putIfAbsent(it.isrc, it) }
                        collectDirectIsrcs(root)
                            .filter { it != candidate.isrc }
                            .forEach { isrc -> related.putIfAbsent(isrc, RecordingRef(isrc, "", "")) }
                    }
                }
                if (related.size >= MAX_RELATED_ISRCS) break
            }

            // Fallback: explicit typed cover relations from Credits Graph.
            if (related.size == beforeCandidate) {
                val graphUrl = "$BASE_URL/graph/isrc/${candidate.isrc}?depth=1&per_hop=25&max_nodes=120&people=0"
                lastUrl = graphUrl
                when (val response = fetchJson(graphUrl)) {
                    is FetchJson.Error -> terminalStatus = response.status
                    is FetchJson.Ok -> parseJson(response.body)?.let { root ->
                        collectCoverRelationshipRefs(root, candidate.isrc).forEach { ref ->
                            related.putIfAbsent(ref.isrc, ref)
                        }
                    }
                }
            }

            if (related.size == beforeCandidate) {
                val relationUrl = "$BASE_URL/isrc/${candidate.isrc}/relationships?limit=200"
                lastUrl = relationUrl
                when (val response = fetchJson(relationUrl)) {
                    is FetchJson.Error -> terminalStatus = response.status
                    is FetchJson.Ok -> parseJson(response.body)?.let { root ->
                        collectCoverRelationshipRefs(root, candidate.isrc).forEach { ref ->
                            related.putIfAbsent(ref.isrc, ref)
                        }
                    }
                }
            }

            if (related.size >= MAX_RELATED_ISRCS) break
        }

        if (related.isEmpty()) {
            return CreditsFmLookup(
                covers = emptyList(),
                status = terminalStatus ?: CreditsFmStatus.NO_MATCH,
                sourceUrl = lastUrl,
                sourceCandidates = sourceCandidateCount,
                worksFound = worksSeen.size,
                linkedRecordings = 0,
            )
        }

        var metadataFetches = 0
        val covers = linkedMapOf<String, CreditsFmCover>()
        for ((isrc, embedded) in related) {
            var resolved = embedded
            if ((resolved.title.isBlank() || resolved.artist.isBlank()) && metadataFetches < MAX_METADATA_FETCHES) {
                metadataFetches++
                val detailUrl = "$BASE_URL/isrc/$isrc?contribute=false"
                lastUrl = detailUrl
                when (val detail = fetchJson(detailUrl)) {
                    is FetchJson.Ok -> parseJson(detail.body)?.let { root ->
                        collectRecordingRefs(root).firstOrNull { it.isrc == isrc }?.let { resolved = it }
                    }
                    is FetchJson.Error -> Unit
                }
            }

            if (resolved.title.isBlank() || resolved.artist.isBlank()) continue
            if (artist.isNotBlank() && similarity(artist, resolved.artist) >= 0.92) continue
            if (similarity(title, resolved.title) < 0.42) continue

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
            CreditsFmLookup(
                covers = covers.values.toList(),
                status = CreditsFmStatus.OK,
                sourceUrl = lastUrl,
                sourceCandidates = sourceCandidateCount,
                worksFound = worksSeen.size,
                linkedRecordings = related.size,
            )
        } else {
            CreditsFmLookup(
                covers = emptyList(),
                status = terminalStatus ?: CreditsFmStatus.NO_MATCH,
                sourceUrl = lastUrl,
                sourceCandidates = sourceCandidateCount,
                worksFound = worksSeen.size,
                linkedRecordings = related.size,
            )
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
            404 -> FetchJson.Error(CreditsFmStatus.NO_MATCH, response.url().toString())
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

    private fun collectIswcs(root: Any): List<String> {
        val result = linkedSetOf<String>()

        fun visit(value: Any?, keyHint: String = "") {
            when (value) {
                is JSONObject -> value.keys().forEach { key -> visit(value.opt(key), key) }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index), keyHint)
                is String -> if (keyHint.contains("iswc", ignoreCase = true)) {
                    normalizeIswc(value)?.let(result::add)
                }
            }
        }

        visit(root)
        return result.toList()
    }

    private fun collectCoverRelationshipRefs(root: Any, currentIsrc: String): List<RecordingRef> {
        val result = linkedMapOf<String, RecordingRef>()

        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    if (looksLikeCoverRelation(value)) {
                        collectRecordingRefs(value)
                            .filter { it.isrc != currentIsrc }
                            .forEach { result.putIfAbsent(it.isrc, it) }
                        collectDirectIsrcs(value)
                            .filter { it != currentIsrc }
                            .forEach { isrc -> result.putIfAbsent(isrc, RecordingRef(isrc, "", "")) }
                    }
                    value.keys().forEach { key -> visit(value.opt(key)) }
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
            }
        }

        visit(root)
        return result.values.take(MAX_RELATED_ISRCS)
    }

    private fun looksLikeCoverRelation(obj: JSONObject): Boolean {
        val values = listOf(
            obj.optString("type"),
            obj.optString("relation_type"),
            obj.optString("relationship_type"),
            obj.optString("relationship"),
            obj.optString("relation"),
            obj.optString("kind"),
        )
        return values.any { it.equals("cover", ignoreCase = true) || it.contains("cover", ignoreCase = true) }
    }

    private fun collectDirectIsrcs(root: Any): Set<String> {
        val result = linkedSetOf<String>()

        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key -> visit(value.opt(key)) }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                // Graph depth=1 may expose recording relationships as plain ISRC
                // strings under a recordings array. ISRC format is strict enough to
                // safely recognize them regardless of their JSON key name.
                is String -> normalizeIsrc(value)?.let(result::add)
            }
        }

        visit(root)
        return result
    }

    private fun recordingFromObject(obj: JSONObject): RecordingRef? {
        val isrc = sequenceOf(
            obj.optString("isrc"),
            obj.optString("recording_isrc"),
            obj.optString("code"),
            obj.optString("id"),
        ).mapNotNull(::normalizeIsrc).firstOrNull() ?: return null

        return RecordingRef(
            isrc = isrc,
            title = firstText(obj, "recording_title", "song_title", "track_title", "title", "name"),
            artist = artistText(obj),
            year = firstYear(obj),
        )
    }

    private fun firstText(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            when (val value = obj.opt(key)) {
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
            when (val value = obj.opt(key)) {
                is JSONArray -> {
                    val names = buildList {
                        for (index in 0 until value.length()) {
                            when (val item = value.opt(index)) {
                                is String -> if (item.isNotBlank()) add(item.trim())
                                is JSONObject -> firstText(item, "name", "artist_name")
                                    .takeIf { it.isNotBlank() }
                                    ?.let(::add)
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

    private fun normalizeIswc(value: String): String? {
        val compact = value.uppercase().replace(Regex("[^A-Z0-9]"), "")
        if (!compact.startsWith("T")) return null
        val digits = compact.drop(1)
        if (digits.length != 10 || digits.any { !it.isDigit() }) return null
        return "T-${digits.take(9)}-${digits.last()}"
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
