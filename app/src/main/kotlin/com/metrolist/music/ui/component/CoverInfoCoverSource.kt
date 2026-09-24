/**
 * MusicLab COVER.INFO cover source
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

internal enum class CoverInfoStatus {
    OK,
    NO_MATCH,
    BLOCKED,
    RATE_LIMITED,
    NETWORK_ERROR,
}

internal data class CoverInfoCover(
    val title: String,
    val artist: String,
    val year: Int? = null,
    val url: String? = null,
)

internal data class CoverInfoLookup(
    val covers: List<CoverInfoCover>,
    val status: CoverInfoStatus,
    val sourceUrl: String? = null,
)

/**
 * Read-only COVER.INFO integration.
 *
 * COVER.INFO does not expose a documented public API, so MusicLab discovers
 * already-public song pages through normal web indexes, validates the requested
 * title/performer, then reads only rows explicitly labelled "Cover" on the public
 * song page. It never attempts to solve or bypass access-control challenges.
 */
internal object CoverInfoCoverSource {
    private const val REQUEST_TIMEOUT_MS = 8_000
    private const val MAX_INDEX_HITS = 18
    private const val MAX_PAGE_CANDIDATES = 4
    private const val MAX_COVERS = 90
    private const val CACHE_TTL_MS = 8L * 60L * 60L * 1000L
    private const val ERROR_CACHE_TTL_MS = 8L * 60L * 1000L
    private const val USER_AGENT = "MusicLab/0.8.9 (Android; COVER.INFO public-page lookup)"

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val createdAt: Long,
        val lookup: CoverInfoLookup,
    )

    private data class IndexHit(
        val title: String,
        val snippet: String,
        val url: String,
    )

    private sealed interface FetchHtml {
        data class Ok(val body: String, val url: String) : FetchHtml
        data class Error(val status: CoverInfoStatus, val url: String) : FetchHtml
    }

    fun lookup(title: String, artist: String): CoverInfoLookup {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank()) return CoverInfoLookup(emptyList(), CoverInfoStatus.NO_MATCH)

        val key = "${canonical(cleanTitle)}|${canonical(cleanArtist)}"
        val now = System.currentTimeMillis()
        cache[key]?.let { entry ->
            val ttl = if (entry.lookup.status == CoverInfoStatus.OK || entry.lookup.status == CoverInfoStatus.NO_MATCH) {
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

    private fun lookupFresh(title: String, artist: String): CoverInfoLookup {
        val queries = linkedSetOf<String>().apply {
            val quotedTitle = quoteForSearch(title)
            val quotedArtist = quoteForSearch(artist)
            if (artist.isNotBlank()) {
                add("site:cover.info/en/song $quotedTitle $quotedArtist")
                add("site:cover.info/de/song $quotedTitle $quotedArtist")
                add("site:cover.info/en/song $quotedTitle $quotedArtist Cover")
            }
            add("site:cover.info/en/song $quotedTitle")
            add("site:cover.info/de/song $quotedTitle")
        }

        val hits = linkedMapOf<String, IndexHit>()
        var terminalStatus: CoverInfoStatus? = null
        var lastUrl: String? = null

        for (query in queries) {
            when (val ddg = searchDuckDuckGo(query)) {
                is SearchResult.Ok -> ddg.hits.forEach { hit -> hits.putIfAbsent(hit.url, hit) }
                is SearchResult.Error -> terminalStatus = ddg.status
            }
            if (hits.size >= 6) break
        }

        if (hits.size < 3) {
            for (query in queries.take(2)) {
                when (val bing = searchBing(query)) {
                    is SearchResult.Ok -> bing.hits.forEach { hit -> hits.putIfAbsent(hit.url, hit) }
                    is SearchResult.Error -> if (terminalStatus == null) terminalStatus = bing.status
                }
                if (hits.size >= 6) break
            }
        }

        val ranked = hits.values
            .filter { isCoverInfoSongUrl(it.url) }
            .map { hit ->
                val haystack = "${hit.title} ${hit.snippet}"
                val titleScore = textPresenceScore(title, haystack)
                val artistScore = if (artist.isBlank()) 1.0 else textPresenceScore(artist, haystack)
                Triple(hit, titleScore, artistScore)
            }
            .filter { (_, titleScore, artistScore) ->
                titleScore >= 0.58 && (artist.isBlank() || artistScore >= 0.25 || titleScore >= 0.96)
            }
            .sortedByDescending { (_, titleScore, artistScore) -> titleScore * 0.78 + artistScore * 0.22 }
            .take(MAX_PAGE_CANDIDATES)

        if (ranked.isEmpty()) {
            return CoverInfoLookup(
                emptyList(),
                terminalStatus ?: CoverInfoStatus.NO_MATCH,
                hits.values.firstOrNull()?.url,
            )
        }

        for ((hit, _, _) in ranked) {
            lastUrl = hit.url
            when (val page = fetchHtml(hit.url)) {
                is FetchHtml.Error -> {
                    terminalStatus = page.status
                    if (page.status == CoverInfoStatus.BLOCKED || page.status == CoverInfoStatus.RATE_LIMITED) {
                        continue
                    }
                }
                is FetchHtml.Ok -> {
                    val doc = Jsoup.parse(page.body, page.url)
                    if (!pageMatches(doc.title(), doc.body()?.text().orEmpty(), title, artist)) continue
                    val covers = parseExplicitCovers(doc.select("a[href*='/song/']"), page.url, artist)
                    if (covers.isNotEmpty()) {
                        return CoverInfoLookup(
                            covers = covers.take(MAX_COVERS),
                            status = CoverInfoStatus.OK,
                            sourceUrl = page.url,
                        )
                    }
                }
            }
        }

        return CoverInfoLookup(
            emptyList(),
            terminalStatus ?: CoverInfoStatus.NO_MATCH,
            lastUrl,
        )
    }

    private sealed interface SearchResult {
        data class Ok(val hits: List<IndexHit>) : SearchResult
        data class Error(val status: CoverInfoStatus) : SearchResult
    }

    private fun searchDuckDuckGo(query: String): SearchResult {
        val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(query, "UTF-8")}" 
        return when (val response = fetchHtml(url)) {
            is FetchHtml.Error -> SearchResult.Error(response.status)
            is FetchHtml.Ok -> {
                val doc = Jsoup.parse(response.body, response.url)
                val hits = doc.select(".result").mapNotNull { result ->
                    val link = result.selectFirst("a.result__a") ?: return@mapNotNull null
                    val rawUrl = link.attr("href")
                    val target = decodeDuckDuckGoTarget(rawUrl)
                    if (!isCoverInfoSongUrl(target)) return@mapNotNull null
                    IndexHit(
                        title = link.text().trim(),
                        snippet = result.selectFirst(".result__snippet")?.text().orEmpty().trim(),
                        url = target,
                    )
                }.distinctBy { it.url }.take(MAX_INDEX_HITS)
                SearchResult.Ok(hits)
            }
        }
    }

    private fun searchBing(query: String): SearchResult {
        val url = "https://www.bing.com/search?q=${URLEncoder.encode(query, "UTF-8")}&count=20"
        return when (val response = fetchHtml(url)) {
            is FetchHtml.Error -> SearchResult.Error(response.status)
            is FetchHtml.Ok -> {
                val doc = Jsoup.parse(response.body, response.url)
                val hits = doc.select("li.b_algo").mapNotNull { result ->
                    val link = result.selectFirst("h2 a") ?: return@mapNotNull null
                    val target = link.absUrl("href").ifBlank { link.attr("href") }
                    if (!isCoverInfoSongUrl(target)) return@mapNotNull null
                    IndexHit(
                        title = link.text().trim(),
                        snippet = result.selectFirst(".b_caption p")?.text().orEmpty().trim(),
                        url = target,
                    )
                }.distinctBy { it.url }.take(MAX_INDEX_HITS)
                SearchResult.Ok(hits)
            }
        }
    }

    private fun fetchHtml(url: String): FetchHtml = runCatching {
        val response = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .timeout(REQUEST_TIMEOUT_MS)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .followRedirects(true)
            .execute()

        val body = response.body()
        when (response.statusCode()) {
            in 200..299 -> if (looksLikeChallenge(body)) {
                FetchHtml.Error(CoverInfoStatus.BLOCKED, response.url().toString())
            } else {
                FetchHtml.Ok(body, response.url().toString())
            }
            403 -> FetchHtml.Error(CoverInfoStatus.BLOCKED, response.url().toString())
            429 -> FetchHtml.Error(CoverInfoStatus.RATE_LIMITED, response.url().toString())
            else -> FetchHtml.Error(CoverInfoStatus.NETWORK_ERROR, response.url().toString())
        }
    }.getOrElse {
        FetchHtml.Error(CoverInfoStatus.NETWORK_ERROR, url)
    }

    private fun parseExplicitCovers(
        songLinks: List<Element>,
        currentUrl: String,
        originalArtist: String,
    ): List<CoverInfoCover> {
        val currentPath = runCatching { URI(currentUrl).path }.getOrDefault("")
        val result = linkedMapOf<String, CoverInfoCover>()

        for (link in songLinks) {
            val target = link.absUrl("href").ifBlank { link.attr("href") }
            if (!isCoverInfoSongUrl(target)) continue
            val targetPath = runCatching { URI(target).path }.getOrDefault("")
            if (targetPath == currentPath) continue

            val container = smallestCoverContainer(link) ?: continue
            val text = container.text().replace(Regex("\\s+"), " ").trim()
            if (!COVER_WORD.containsMatchIn(text)) continue

            var title = link.text().trim()
            val year = YEAR_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            title = title.replace(Regex("\\s*\\((?:18|19|20)\\d{2}\\)\\s*$"), "").trim()
            if (title.isBlank() || title.equals("Cover", ignoreCase = true)) continue

            val artist = container.select("a[href*='/en/artist/']")
                .map { it.text().trim() }
                .firstOrNull { it.isNotBlank() }
                ?.takeIf { candidate -> !candidate.equals(title, ignoreCase = true) }
                ?: artistFromSongUrl(target)

            if (artist.isBlank()) continue
            if (originalArtist.isNotBlank() && similarity(originalArtist, artist) >= 0.92) continue

            val key = "${canonical(title)}|${canonical(artist)}"
            result.putIfAbsent(
                key,
                CoverInfoCover(
                    title = title,
                    artist = artist,
                    year = year,
                    url = target,
                ),
            )
            if (result.size >= MAX_COVERS) break
        }
        return result.values.toList()
    }

    private fun smallestCoverContainer(link: Element): Element? {
        var current: Element? = link.parent()
        repeat(7) {
            val element = current ?: return null
            val text = element.text().replace(Regex("\\s+"), " ").trim()
            if (text.length in 5..650 && COVER_WORD.containsMatchIn(text)) return element
            current = element.parent()
        }
        return null
    }

    private fun pageMatches(pageTitle: String, bodyText: String, title: String, artist: String): Boolean {
        val head = pageTitle.take(300)
        val firstBody = bodyText.take(1_500)
        val titleScore = maxOf(textPresenceScore(title, head), textPresenceScore(title, firstBody))
        val artistScore = if (artist.isBlank()) 1.0 else maxOf(
            textPresenceScore(artist, head),
            textPresenceScore(artist, firstBody),
        )
        return titleScore >= 0.62 && (artist.isBlank() || artistScore >= 0.28 || titleScore >= 0.96)
    }

    private fun decodeDuckDuckGoTarget(rawUrl: String): String {
        val absolute = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
        return runCatching {
            val uri = URI(absolute)
            val query = uri.rawQuery.orEmpty()
            query.split('&')
                .mapNotNull { part ->
                    val pieces = part.split('=', limit = 2)
                    if (pieces.size == 2 && pieces[0] == "uddg") {
                        URLDecoder.decode(pieces[1], "UTF-8")
                    } else null
                }
                .firstOrNull()
                ?: absolute
        }.getOrDefault(absolute)
    }

    private fun isCoverInfoSongUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase().orEmpty().removePrefix("www.")
        host == "cover.info" && Regex("/(?:en|de)/song/").containsMatchIn(uri.path.orEmpty())
    }.getOrDefault(false)

    private fun artistFromSongUrl(url: String): String = runCatching {
        val segments = URI(url).path.orEmpty().split('/').filter { it.isNotBlank() }
        if (segments.size < 5 || segments[1] != "song") return@runCatching ""
        URLDecoder.decode(segments.last(), "UTF-8")
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }.getOrDefault("")

    private fun quoteForSearch(value: String): String {
        val clean = value.replace('"', ' ').replace(Regex("\\s+"), " ").trim()
        return if (clean.isBlank()) "" else "\"$clean\""
    }

    private fun looksLikeChallenge(body: String): Boolean {
        val text = body.lowercase()
        return text.contains("captcha") ||
            text.contains("verify you are human") ||
            text.contains("access denied") ||
            text.contains("security check")
    }

    private fun textPresenceScore(needle: String, haystack: String): Double = similarity(needle, haystack)

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
        if (bb.contains(aa)) return 0.96
        if (aa.contains(bb)) return 0.88
        val aWords = aa.split(' ').filter { it.length > 1 }.toSet()
        val bWords = bb.split(' ').filter { it.length > 1 }.toSet()
        if (aWords.isEmpty() || bWords.isEmpty()) return 0.0
        val overlap = aWords.intersect(bWords).size.toDouble()
        val containment = overlap / max(1, aWords.size).toDouble()
        val jaccard = overlap / aWords.union(bWords).size.toDouble()
        return containment * 0.72 + jaccard * 0.28
    }

    private val COVER_WORD = Regex("(?:^|\\s)Cover(?:\\s|$)", RegexOption.IGNORE_CASE)
    private val YEAR_REGEX = Regex("\\b((?:18|19|20)\\d{2})\\b")
}
