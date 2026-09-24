/**
 * MusicLab MusicBrainz cover source
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

internal enum class MusicBrainzStatus {
    OK,
    NO_MATCH,
    NETWORK_ERROR,
}

internal data class MusicBrainzCover(
    val title: String,
    val artist: String,
    val recordingId: String,
    val workId: String,
    val year: Int? = null,
)

internal data class MusicBrainzLookup(
    val covers: List<MusicBrainzCover>,
    val status: MusicBrainzStatus,
    val sourceUrl: String? = null,
)

internal data class MusicBrainzRecordingCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val score: Double,
)

internal data class MusicBrainzWorkCandidate(
    val id: String,
    val title: String,
    val score: Double,
)

/**
 * Structured source for identifying recordings of the same musical work.
 *
 * Resolution order:
 *  1. recording title + artist name;
 *  2. recording title + credited artist;
 *  3. recording title only;
 *  4. direct work-title search as a final structured fallback.
 *
 * Every candidate work is validated by checking that its recordings include
 * the requested title/artist before versions are returned. This prevents
 * same-title unrelated compositions from leaking into cover results.
 *
 * The public MusicBrainz service asks clients to stay at or below one request
 * per second, so all requests from this source share a small global limiter.
 */
internal object MusicBrainzCoverSource {
    private const val BASE_URL = "https://musicbrainz.org/ws/2"
    private const val REQUEST_TIMEOUT_MS = 7_500
    private const val MAX_SEARCH_CANDIDATES = 10
    private const val MAX_DETAIL_CANDIDATES = 6
    private const val MAX_WORKS_PER_RECORDING = 3
    private const val MAX_WORK_SEARCH_CANDIDATES = 6
    private const val MAX_DISCOVERED_COVERS = 100
    private const val MIN_REQUEST_INTERVAL_MS = 1_100L
    private const val CACHE_TTL_MS = 12L * 60L * 60L * 1000L
    private const val ERROR_CACHE_TTL_MS = 10L * 60L * 1000L
    private const val USER_AGENT = "MusicLab/0.8.9 (https://github.com/Br174/MusicLab)"

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val rateLock = Any()
    private var nextAllowedRequestAtMs = 0L

    private data class CacheEntry(
        val createdAt: Long,
        val lookup: MusicBrainzLookup,
    )

    fun lookup(title: String, artist: String): MusicBrainzLookup {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank()) {
            return MusicBrainzLookup(emptyList(), MusicBrainzStatus.NO_MATCH)
        }

        val key = "${canonical(cleanTitle)}|${canonical(cleanArtist)}"
        val now = System.currentTimeMillis()
        cache[key]?.let { entry ->
            val ttl = if (
                entry.lookup.status == MusicBrainzStatus.OK ||
                entry.lookup.status == MusicBrainzStatus.NO_MATCH
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

    private fun lookupFresh(title: String, artist: String): MusicBrainzLookup {
        val candidates = linkedMapOf<String, MusicBrainzRecordingCandidate>()
        var lastUrl: String? = null
        var sawNetworkError = false

        val rawQueries = buildList {
            if (artist.isNotBlank()) {
                add(
                    "recording:\"${escapeLucene(title)}\" AND " +
                        "artistname:\"${escapeLucene(artist)}\"",
                )
                add(
                    "recording:\"${escapeLucene(title)}\" AND " +
                        "artist:\"${escapeLucene(artist)}\"",
                )
            }
            add("recording:\"${escapeLucene(title)}\"")
        }.distinct()

        for (rawQuery in rawQueries) {
            val searchUrl = "$BASE_URL/recording/?query=${URLEncoder.encode(rawQuery, "UTF-8")}" +
                "&limit=$MAX_SEARCH_CANDIDATES"
            lastUrl = searchUrl
            val searchDocument = fetchXml(searchUrl)
            if (searchDocument == null) {
                sawNetworkError = true
                continue
            }

            parseRecordingSearch(searchDocument, title, artist).forEach { candidate ->
                val existing = candidates[candidate.id]
                if (existing == null || candidate.score > existing.score) {
                    candidates[candidate.id] = candidate
                }
            }

            // A few good candidates are enough; avoid needless API calls.
            if (candidates.size >= 4) break
        }

        val orderedCandidates = candidates.values
            .sortedByDescending { it.score }
            .take(MAX_DETAIL_CANDIDATES)
        val seenWorkIds = mutableSetOf<String>()

        for (candidate in orderedCandidates) {
            val recordingUrl = "$BASE_URL/recording/${candidate.id}?inc=work-rels+artist-credits"
            lastUrl = recordingUrl
            val detail = fetchXml(recordingUrl)
            if (detail == null) {
                sawNetworkError = true
                continue
            }

            for (workId in parseWorkIds(detail).take(MAX_WORKS_PER_RECORDING)) {
                if (!seenWorkIds.add(workId)) continue
                val result = browseValidatedWork(
                    workId = workId,
                    wantedTitle = title,
                    wantedArtist = artist,
                    excludedRecordingIds = candidates.keys,
                )
                if (result == null) {
                    sawNetworkError = true
                    continue
                }
                lastUrl = result.sourceUrl
                if (result.covers.isNotEmpty()) return result
            }
        }

        // Some MusicBrainz recordings are missing recording -> work links even
        // though the work and its other recordings exist. Search the work
        // itself, then validate it against the requested recording/artist.
        val workFallback = lookupByWorkTitle(
            title = title,
            artist = artist,
            excludedRecordingIds = candidates.keys,
        )
        if (workFallback != null) {
            if (workFallback.status == MusicBrainzStatus.OK || workFallback.covers.isNotEmpty()) {
                return workFallback
            }
            if (workFallback.status == MusicBrainzStatus.NETWORK_ERROR) sawNetworkError = true
            lastUrl = workFallback.sourceUrl ?: lastUrl
        }

        return MusicBrainzLookup(
            covers = emptyList(),
            status = if (sawNetworkError && candidates.isEmpty()) {
                MusicBrainzStatus.NETWORK_ERROR
            } else {
                MusicBrainzStatus.NO_MATCH
            },
            sourceUrl = lastUrl,
        )
    }

    private fun lookupByWorkTitle(
        title: String,
        artist: String,
        excludedRecordingIds: Set<String>,
    ): MusicBrainzLookup? {
        val rawQuery = "work:\"${escapeLucene(title)}\""
        val searchUrl = "$BASE_URL/work/?query=${URLEncoder.encode(rawQuery, "UTF-8")}" +
            "&limit=$MAX_WORK_SEARCH_CANDIDATES"
        val document = fetchXml(searchUrl)
            ?: return MusicBrainzLookup(emptyList(), MusicBrainzStatus.NETWORK_ERROR, searchUrl)

        val works = parseWorkSearch(document, title)
            .take(MAX_WORK_SEARCH_CANDIDATES)
        if (works.isEmpty()) {
            return MusicBrainzLookup(emptyList(), MusicBrainzStatus.NO_MATCH, searchUrl)
        }

        var sawNetworkError = false
        for (work in works) {
            val result = browseValidatedWork(
                workId = work.id,
                wantedTitle = title,
                wantedArtist = artist,
                excludedRecordingIds = excludedRecordingIds,
            )
            if (result == null) {
                sawNetworkError = true
                continue
            }
            if (result.covers.isNotEmpty()) return result
        }

        return MusicBrainzLookup(
            emptyList(),
            if (sawNetworkError) MusicBrainzStatus.NETWORK_ERROR else MusicBrainzStatus.NO_MATCH,
            searchUrl,
        )
    }

    /** Returns null only when the MusicBrainz browse request itself failed. */
    private fun browseValidatedWork(
        workId: String,
        wantedTitle: String,
        wantedArtist: String,
        excludedRecordingIds: Set<String>,
    ): MusicBrainzLookup? {
        val browseUrl = "$BASE_URL/recording?work=${URLEncoder.encode(workId, "UTF-8")}" +
            "&limit=100&inc=artist-credits"
        val browse = fetchXml(browseUrl) ?: return null
        val allRecordings = parseBrowseRecordingsInternal(
            document = browse,
            excludedRecordingIds = emptySet(),
            workId = workId,
        )

        if (!workMatchesOriginal(allRecordings, wantedTitle, wantedArtist)) {
            return MusicBrainzLookup(emptyList(), MusicBrainzStatus.NO_MATCH, browseUrl)
        }

        val covers = allRecordings
            .filterNot { it.recordingId in excludedRecordingIds }
            .filterNot { isOriginalRecordingLike(it, wantedTitle, wantedArtist) }
            .distinctBy { "${canonical(it.title)}|${canonical(it.artist)}" }
            .take(MAX_DISCOVERED_COVERS)

        return MusicBrainzLookup(
            covers = covers,
            status = if (covers.isEmpty()) MusicBrainzStatus.NO_MATCH else MusicBrainzStatus.OK,
            sourceUrl = browseUrl,
        )
    }

    private fun workMatchesOriginal(
        recordings: List<MusicBrainzCover>,
        wantedTitle: String,
        wantedArtist: String,
    ): Boolean {
        if (recordings.isEmpty()) return false
        if (wantedArtist.isBlank()) {
            return recordings.any { textSimilarity(wantedTitle, it.title) >= 0.90 }
        }
        return recordings.any { recording ->
            textSimilarity(wantedTitle, recording.title) >= 0.72 &&
                textSimilarity(wantedArtist, recording.artist) >= 0.42
        }
    }

    private fun isOriginalRecordingLike(
        recording: MusicBrainzCover,
        wantedTitle: String,
        wantedArtist: String,
    ): Boolean {
        if (wantedArtist.isBlank()) return false
        return textSimilarity(wantedTitle, recording.title) >= 0.72 &&
            textSimilarity(wantedArtist, recording.artist) >= 0.72
    }

    internal fun parseRecordingSearch(
        document: Document,
        wantedTitle: String,
        wantedArtist: String,
    ): List<MusicBrainzRecordingCandidate> {
        val wantedArtistKey = canonical(wantedArtist)

        return document.select("recording-list > recording")
            .mapNotNull { recording ->
                val id = recording.attr("id").trim()
                val title = directChildText(recording, "title")
                val artist = artistCredit(recording)
                if (id.isBlank() || title.isBlank()) return@mapNotNull null

                val titleScore = textSimilarity(wantedTitle, title)
                val artistScore = if (wantedArtistKey.isBlank()) {
                    1.0
                } else {
                    textSimilarity(wantedArtist, artist)
                }
                if (titleScore < 0.58) return@mapNotNull null
                if (wantedArtistKey.isNotBlank() && artistScore < 0.28 && titleScore < 0.93) {
                    return@mapNotNull null
                }

                MusicBrainzRecordingCandidate(
                    id = id,
                    title = title,
                    artist = artist,
                    score = titleScore * 0.78 + artistScore * 0.22,
                )
            }
            .distinctBy { it.id }
            .sortedByDescending { it.score }
    }

    internal fun parseWorkSearch(
        document: Document,
        wantedTitle: String,
    ): List<MusicBrainzWorkCandidate> =
        document.select("work-list > work")
            .mapNotNull { work ->
                val id = work.attr("id").trim()
                val title = directChildText(work, "title")
                if (id.isBlank() || title.isBlank()) return@mapNotNull null
                val score = textSimilarity(wantedTitle, title)
                if (score < 0.72) return@mapNotNull null
                MusicBrainzWorkCandidate(id, title, score)
            }
            .distinctBy { it.id }
            .sortedByDescending { it.score }

    internal fun parseWorkId(document: Document): String? = parseWorkIds(document).firstOrNull()

    internal fun parseWorkIds(document: Document): List<String> {
        val relations = document.select("relation-list[target-type=work] relation")
        return relations
            .sortedByDescending { relation ->
                if (relation.attr("type").equals("performance", ignoreCase = true)) 1 else 0
            }
            .mapNotNull { relation ->
                relation.selectFirst("work")
                    ?.attr("id")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
            .distinct()
    }

    internal fun parseBrowseRecordings(
        document: Document,
        originalRecordingId: String,
        workId: String,
    ): List<MusicBrainzCover> =
        parseBrowseRecordingsInternal(document, setOf(originalRecordingId), workId)

    private fun parseBrowseRecordingsInternal(
        document: Document,
        excludedRecordingIds: Set<String>,
        workId: String,
    ): List<MusicBrainzCover> =
        document.select("recording-list > recording")
            .mapNotNull { recording ->
                val recordingId = recording.attr("id").trim()
                if (recordingId.isBlank() || recordingId in excludedRecordingIds) return@mapNotNull null

                val title = directChildText(recording, "title")
                val artist = artistCredit(recording)
                if (title.isBlank() || artist.isBlank()) return@mapNotNull null

                val year = directChildText(recording, "first-release-date")
                    .take(4)
                    .toIntOrNull()
                    ?.takeIf { it in 1800..2100 }

                MusicBrainzCover(
                    title = title,
                    artist = artist,
                    recordingId = recordingId,
                    workId = workId,
                    year = year,
                )
            }
            .distinctBy { "${canonical(it.title)}|${canonical(it.artist)}" }

    private fun fetchXml(url: String): Document? {
        awaitRequestSlot()
        return runCatching {
            val response = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept", "application/xml")
                .timeout(REQUEST_TIMEOUT_MS)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .execute()
            if (response.statusCode() !in 200..299) return@runCatching null
            Jsoup.parse(response.body(), "", Parser.xmlParser())
        }.getOrNull()
    }

    private fun awaitRequestSlot() {
        synchronized(rateLock) {
            val now = System.currentTimeMillis()
            val delayMs = (nextAllowedRequestAtMs - now).coerceAtLeast(0L)
            if (delayMs > 0L) Thread.sleep(delayMs)
            nextAllowedRequestAtMs = System.currentTimeMillis() + MIN_REQUEST_INTERVAL_MS
        }
    }

    private fun artistCredit(recording: Element): String =
        recording.select("artist-credit name-credit artist name")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" & ")

    private fun directChildText(element: Element, tagName: String): String =
        element.children()
            .firstOrNull { it.tagName().equals(tagName, ignoreCase = true) }
            ?.text()
            ?.trim()
            .orEmpty()

    private fun escapeLucene(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

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
