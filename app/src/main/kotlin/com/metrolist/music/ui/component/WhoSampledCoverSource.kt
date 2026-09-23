/**
 * MusicLab WhoSampled cover source
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

internal enum class WhoSampledStatus {
    OK,
    NO_MATCH,
    BLOCKED,
    STRUCTURE_CHANGED,
    NETWORK_ERROR,
}

internal data class WhoSampledCover(
    val title: String,
    val artist: String,
    val url: String,
)

internal data class WhoSampledLookup(
    val covers: List<WhoSampledCover>,
    val status: WhoSampledStatus,
    val sourceUrl: String? = null,
)

/**
 * Reads public WhoSampled pages on demand without attempting to defeat access controls.
 *
 * Resolution order intentionally avoids making the protected global search page the
 * single point of failure:
 *  1. deterministic public track URL candidates;
 *  2. the public artist page and matching track links found there;
 *  3. WhoSampled's global search page as a final fallback.
 *
 * A 403/429, CAPTCHA, or human-verification page is still treated as BLOCKED. MusicLab
 * can then continue with its other cover sources instead of trying to bypass the check.
 */
internal object WhoSampledCoverSource {
    private const val BASE_URL = "https://www.whosampled.com"
    private const val REQUEST_TIMEOUT_MS = 5_500
    private const val MAX_SEARCH_CANDIDATES = 6
    private const val MAX_DIRECT_CANDIDATES = 8
    private const val MAX_PAGE_REQUESTS = 10
    private const val MAX_DISCOVERED_COVERS = 48
    private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    private const val ERROR_CACHE_TTL_MS = 2L * 60L * 1000L

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val createdAt: Long,
        val lookup: WhoSampledLookup,
    )

    private sealed interface FetchPage {
        data class Ok(val document: Document, val url: String) : FetchPage
        data class Error(val status: WhoSampledStatus) : FetchPage
    }

    fun lookup(title: String, artist: String): WhoSampledLookup {
        val key = "${canonical(title)}|${canonical(artist)}"
        val now = System.currentTimeMillis()
        cache[key]?.let { entry ->
            val ttl =
                if (entry.lookup.status == WhoSampledStatus.OK || entry.lookup.status == WhoSampledStatus.NO_MATCH) {
                    CACHE_TTL_MS
                } else {
                    ERROR_CACHE_TTL_MS
                }
            if (now - entry.createdAt < ttl) return entry.lookup
            cache.remove(key)
        }

        val lookup = lookupFresh(title.trim(), artist.trim())
        cache[key] = CacheEntry(now, lookup)
        return lookup
    }

    private fun lookupFresh(title: String, artist: String): WhoSampledLookup {
        if (title.isBlank()) return WhoSampledLookup(emptyList(), WhoSampledStatus.NO_MATCH)

        var sawBlocked = false
        var sawNetworkError = false
        var sawRecognizablePage = false

        fun rememberError(status: WhoSampledStatus) {
            when (status) {
                WhoSampledStatus.BLOCKED -> sawBlocked = true
                WhoSampledStatus.NETWORK_ERROR -> sawNetworkError = true
                else -> Unit
            }
        }

        directTrackUrls(title, artist)
            .take(MAX_DIRECT_CANDIDATES)
            .forEach { url ->
                when (val fetched = fetch(url)) {
                    is FetchPage.Error -> rememberError(fetched.status)
                    is FetchPage.Ok -> {
                        when (val resolved = resolveCandidatePage(fetched, title, artist)) {
                            CandidateResolution.Mismatch -> Unit
                            CandidateResolution.RecognizedNoCovers -> sawRecognizablePage = true
                            is CandidateResolution.Success -> return resolved.lookup
                        }
                    }
                }
            }

        directArtistUrls(artist).forEach { artistUrl ->
            when (val artistPage = fetch(artistUrl)) {
                is FetchPage.Error -> rememberError(artistPage.status)
                is FetchPage.Ok -> {
                    val trackUrls = parseSearchTrackUrls(artistPage.document, title, artist)
                    trackUrls.take(MAX_SEARCH_CANDIDATES).forEach { trackUrl ->
                        when (val trackPage = fetch(trackUrl)) {
                            is FetchPage.Error -> rememberError(trackPage.status)
                            is FetchPage.Ok -> {
                                when (val resolved = resolveCandidatePage(trackPage, title, artist)) {
                                    CandidateResolution.Mismatch -> Unit
                                    CandidateResolution.RecognizedNoCovers -> sawRecognizablePage = true
                                    is CandidateResolution.Success -> return resolved.lookup
                                }
                            }
                        }
                    }
                }
            }
        }

        if (sawRecognizablePage) {
            return WhoSampledLookup(emptyList(), WhoSampledStatus.OK, directTrackUrls(title, artist).firstOrNull())
        }

        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        val searchUrl = "$BASE_URL/search/?q=${URLEncoder.encode(query, "UTF-8")}"
        val searchPage = fetch(searchUrl)
        if (searchPage is FetchPage.Error) {
            val status =
                when {
                    searchPage.status == WhoSampledStatus.BLOCKED || sawBlocked -> WhoSampledStatus.BLOCKED
                    searchPage.status == WhoSampledStatus.NO_MATCH -> WhoSampledStatus.NO_MATCH
                    sawNetworkError || searchPage.status == WhoSampledStatus.NETWORK_ERROR -> WhoSampledStatus.NETWORK_ERROR
                    else -> searchPage.status
                }
            return WhoSampledLookup(emptyList(), status, searchUrl)
        }
        searchPage as FetchPage.Ok

        val candidateUrls = parseSearchTrackUrls(searchPage.document, title, artist)
        if (candidateUrls.isEmpty()) {
            val text = searchPage.document.text().lowercase()
            val status =
                if (
                    text.contains("no results") ||
                    text.contains("0 results") ||
                    text.contains("did not match")
                ) {
                    WhoSampledStatus.NO_MATCH
                } else {
                    WhoSampledStatus.STRUCTURE_CHANGED
                }
            return WhoSampledLookup(emptyList(), status, searchPage.url)
        }

        var sawMatchingTrack = false
        for (candidateUrl in candidateUrls.take(MAX_SEARCH_CANDIDATES)) {
            when (val candidatePage = fetch(candidateUrl)) {
                is FetchPage.Error -> rememberError(candidatePage.status)
                is FetchPage.Ok -> {
                    when (val resolved = resolveCandidatePage(candidatePage, title, artist)) {
                        CandidateResolution.Mismatch -> Unit
                        CandidateResolution.RecognizedNoCovers -> {
                            sawMatchingTrack = true
                            sawRecognizablePage = true
                        }
                        is CandidateResolution.Success -> return resolved.lookup
                    }
                }
            }
        }

        return WhoSampledLookup(
            covers = emptyList(),
            status =
                when {
                    sawRecognizablePage -> WhoSampledStatus.OK
                    sawBlocked -> WhoSampledStatus.BLOCKED
                    sawMatchingTrack -> WhoSampledStatus.STRUCTURE_CHANGED
                    sawNetworkError -> WhoSampledStatus.NETWORK_ERROR
                    else -> WhoSampledStatus.NO_MATCH
                },
            sourceUrl = searchPage.url,
        )
    }

    private sealed interface CandidateResolution {
        data object Mismatch : CandidateResolution
        data object RecognizedNoCovers : CandidateResolution
        data class Success(val lookup: WhoSampledLookup) : CandidateResolution
    }

    private fun resolveCandidatePage(
        candidatePage: FetchPage.Ok,
        requestedTitle: String,
        requestedArtist: String,
    ): CandidateResolution {
        val identity = parseTrackIdentity(candidatePage.document)
        if (identity != null) {
            val titleScore = textSimilarity(requestedTitle, identity.first)
            val artistScore =
                if (requestedArtist.isBlank()) 1.0 else textSimilarity(requestedArtist, identity.second)
            if (titleScore < 0.62 || artistScore < 0.35) return CandidateResolution.Mismatch
        }

        var canonicalPage = candidatePage
        val originalUrl = findOriginalTrackUrl(candidatePage.document, candidatePage.url)
        if (originalUrl != null && originalUrl != candidatePage.url) {
            val originalPage = fetch(originalUrl)
            if (originalPage is FetchPage.Ok) canonicalPage = originalPage
        }

        val canonicalIdentity = parseTrackIdentity(canonicalPage.document)
        val originalTitle = canonicalIdentity?.first ?: requestedTitle
        val originalArtist = canonicalIdentity?.second ?: requestedArtist

        val covers =
            collectCovers(
                startDocument = canonicalPage.document,
                startUrl = canonicalPage.url,
                originalTitle = originalTitle,
                originalArtist = originalArtist,
            )

        if (covers.isNotEmpty()) {
            return CandidateResolution.Success(
                WhoSampledLookup(
                    covers = covers,
                    status = WhoSampledStatus.OK,
                    sourceUrl = canonicalPage.url,
                ),
            )
        }

        val pageText = canonicalPage.document.text().lowercase()
        val recognizable =
            pageText.contains("song connections") ||
                pageText.contains("was covered in") ||
                pageText.contains("cover of") ||
                pageText.contains("covers of") ||
                pageText.contains("samples, covers and remixes") ||
                canonicalPage.document.title().contains("WhoSampled", ignoreCase = true)

        return if (recognizable) {
            CandidateResolution.RecognizedNoCovers
        } else {
            CandidateResolution.Mismatch
        }
    }

    internal fun directTrackUrls(title: String, artist: String): List<String> {
        if (title.isBlank() || artist.isBlank()) return emptyList()
        val artists = slugVariants(artist)
        val titles = slugVariants(title)
        return buildList {
            for (artistSlug in artists) {
                for (titleSlug in titles) {
                    if (artistSlug.isNotBlank() && titleSlug.isNotBlank()) {
                        add("$BASE_URL/$artistSlug/$titleSlug/")
                    }
                }
            }
        }.distinct()
    }

    private fun directArtistUrls(artist: String): List<String> {
        if (artist.isBlank()) return emptyList()
        return slugVariants(artist)
            .filter { it.isNotBlank() }
            .map { "$BASE_URL/$it/" }
            .distinct()
            .take(3)
    }

    private fun slugVariants(value: String): List<String> {
        val normalized =
            value.trim()
                .replace('’', '\'')
                .replace(Regex("""\s+"""), " ")
        if (normalized.isBlank()) return emptyList()

        val punctuationPreserved = encodePathSegment(normalized.replace(' ', '-').replace('/', '-'))
        val punctuationStripped =
            normalized
                .replace(Regex("""[^\p{L}\p{N}]+"""), "-")
                .trim('-')
                .let(::encodePathSegment)
        val ampersandAsAnd =
            normalized
                .replace("&", " and ")
                .replace(Regex("""[^\p{L}\p{N}]+"""), "-")
                .trim('-')
                .let(::encodePathSegment)

        return listOf(punctuationPreserved, punctuationStripped, ampersandAsAnd)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")

    private fun collectCovers(
        startDocument: Document,
        startUrl: String,
        originalTitle: String,
        originalArtist: String,
    ): List<WhoSampledCover> {
        val result = linkedMapOf<String, WhoSampledCover>()
        val visited = mutableSetOf<String>()
        val queued = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, Document?>>()

        queue.add(startUrl to startDocument)
        queued += startUrl

        var requests = 0
        while (queue.isNotEmpty() && requests < MAX_PAGE_REQUESTS && result.size < MAX_DISCOVERED_COVERS) {
            val (url, suppliedDocument) = queue.removeFirst()
            if (!visited.add(url)) continue

            val document =
                suppliedDocument ?: when (val fetched = fetch(url)) {
                    is FetchPage.Ok -> {
                        requests++
                        fetched.document
                    }
                    is FetchPage.Error -> {
                        requests++
                        continue
                    }
                }

            parseCoverRefs(document, originalTitle, originalArtist).forEach { cover ->
                val key = "${canonical(cover.title)}|${canonical(cover.artist)}"
                result.putIfAbsent(key, cover)
            }

            parseCoverNavigationUrls(document, url).forEach { next ->
                if (
                    next !in visited &&
                    queued.add(next) &&
                    queued.size <= MAX_PAGE_REQUESTS * 4
                ) {
                    queue.add(next to null)
                }
            }
        }

        return result.values.take(MAX_DISCOVERED_COVERS)
    }

    internal fun parseSearchTrackUrls(
        document: Document,
        title: String,
        artist: String,
    ): List<String> {
        return document.select("a[href]")
            .mapNotNull { link ->
                val url = absoluteWhoSampledUrl(link)
                if (!isTrackPageUrl(url)) return@mapNotNull null

                val displayedTitle = cleanDisplayTitle(link.text())
                    .ifBlank { trackTitleFromUrl(url) }
                val artistHint = artistFromTrackUrl(url)
                val titleScore = textSimilarity(title, displayedTitle)
                val artistScore = if (artist.isBlank()) 1.0 else textSimilarity(artist, artistHint)
                val score = titleScore * 0.76 + artistScore * 0.24
                if (score < 0.44) return@mapNotNull null
                url to score
            }
            .distinctBy { it.first }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    internal fun parseCoverRefs(
        document: Document,
        originalTitle: String,
        originalArtist: String,
    ): List<WhoSampledCover> {
        val result = linkedMapOf<String, WhoSampledCover>()

        fun addTrackLink(link: Element, scope: Element?) {
            val url = absoluteWhoSampledUrl(link)
            if (!isTrackPageUrl(url)) return

            val title = cleanDisplayTitle(link.text()).ifBlank { trackTitleFromUrl(url) }
            val artist = artistFromScopeOrUrl(scope, url)
            if (title.isBlank() || artist.isBlank()) return

            val isOriginal =
                textSimilarity(title, originalTitle) >= 0.90 &&
                    (originalArtist.isBlank() || textSimilarity(artist, originalArtist) >= 0.66)
            if (isOriginal) return

            val key = "${canonical(title)}|${canonical(artist)}"
            result.putIfAbsent(key, WhoSampledCover(title, artist, url))
        }

        if (isCoverRelationshipPage(document.location())) {
            document.select("a[href]")
                .firstOrNull { link ->
                    val url = absoluteWhoSampledUrl(link)
                    if (!isTrackPageUrl(url)) return@firstOrNull false
                    val title = cleanDisplayTitle(link.text()).ifBlank { trackTitleFromUrl(url) }
                    val artist = artistFromTrackUrl(url)
                    !(
                        textSimilarity(title, originalTitle) >= 0.90 &&
                            (originalArtist.isBlank() || textSimilarity(artist, originalArtist) >= 0.66)
                    )
                }
                ?.let { addTrackLink(it, it.parent()) }
        }

        document.select("a[href]").forEach { link ->
            if (!isTrackPageUrl(absoluteWhoSampledUrl(link))) return@forEach
            val scope = findCoverScope(link) ?: return@forEach
            addTrackLink(link, scope)
        }

        return result.values.toList()
    }

    private fun parseCoverNavigationUrls(document: Document, currentUrl: String): List<String> {
        val result = linkedSetOf<String>()
        val currentText = document.text().lowercase()
        val paginationIsCoverRelated =
            currentUrl.contains("/covers/") ||
                currentText.contains("covers found") ||
                currentText.contains("other covers of") ||
                currentText.contains("was covered in")

        document.select("a[href]").forEach { link ->
            val url = absoluteWhoSampledUrl(link)
            if (!isSameHost(url)) return@forEach

            val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
            val coverScope = findCoverScope(link)
            when {
                path.startsWith("/cover/") && coverScope != null -> result += url
                path.endsWith("/covers/") && coverScope != null -> result += url
                link.text().trim().equals("see all", ignoreCase = true) && coverScope != null -> result += url
                paginationIsCoverRelated && url.contains("sp=") -> result += url
            }
        }

        return result.toList()
    }

    private fun findOriginalTrackUrl(document: Document, currentUrl: String): String? {
        val markers =
            document.select("h2,h3,h4,h5,p,div,span,strong")
                .filter { element ->
                    val own = element.ownText().lowercase()
                    own.contains("is a cover of") || own.matches(Regex(""".*\bis a cover of\b.*"""))
                }

        for (marker in markers) {
            val scopes = buildList<Element> {
                add(marker)
                marker.nextElementSibling()?.let(::add)
                marker.parent()?.let { parent ->
                    add(parent)
                    parent.nextElementSibling()?.let(::add)
                }
            }

            for (scope in scopes) {
                scope.select("a[href]").forEach { link ->
                    val url = absoluteWhoSampledUrl(link)
                    if (url != currentUrl && isTrackPageUrl(url)) return url
                }
            }
        }
        return null
    }

    private fun parseTrackIdentity(document: Document): Pair<String, String>? {
        val rawTitle =
            document.title()
                .substringBefore(" | WhoSampled")
                .substringBefore(" - Samples, Covers and Remixes")
                .trim()

        val separator = rawTitle.lastIndexOf(" by ")
        if (separator > 0 && separator < rawTitle.length - 4) {
            val title = rawTitle.substring(0, separator).trim()
            val artist = rawTitle.substring(separator + 4).trim()
            if (title.isNotBlank() && artist.isNotBlank()) return title to artist
        }

        val og = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val ogClean =
            og.substringBefore(" | WhoSampled")
                .substringBefore(" - Samples, Covers and Remixes")
                .trim()
        val ogSeparator = ogClean.lastIndexOf(" by ")
        if (ogSeparator > 0 && ogSeparator < ogClean.length - 4) {
            return ogClean.substring(0, ogSeparator).trim() to
                ogClean.substring(ogSeparator + 4).trim()
        }

        return null
    }

    private fun findCoverScope(link: Element): Element? {
        var current: Element? = link.parent()
        repeat(6) {
            val element = current ?: return null
            val text = element.text().lowercase()
            if (
                text.length <= 1_900 &&
                COVER_CONTEXT_PHRASES.any { phrase -> text.contains(phrase) }
            ) {
                return element
            }
            current = element.parent()
        }
        return null
    }

    private fun artistFromScopeOrUrl(scope: Element?, trackUrl: String): String {
        val artistSegment = pathSegments(trackUrl).firstOrNull().orEmpty()
        if (scope != null && artistSegment.isNotBlank()) {
            scope.select("a[href]").firstOrNull { link ->
                val url = absoluteWhoSampledUrl(link)
                val segments = pathSegments(url)
                segments.size == 1 &&
                    canonical(segments.first().replace('-', ' ')) ==
                    canonical(artistSegment.replace('-', ' '))
            }?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return artistFromTrackUrl(trackUrl)
    }

    private fun fetch(url: String): FetchPage {
        return try {
            val response =
                Jsoup.connect(url)
                    .userAgent("MusicLab/0.8.9 (Android; public WhoSampled cover lookup)")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .referrer(BASE_URL)
                    .timeout(REQUEST_TIMEOUT_MS)
                    .maxBodySize(3_000_000)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .execute()

            val status = response.statusCode()
            val body = response.body()
            when {
                status == 403 || status == 429 || looksLikeAccessChallenge(body) ->
                    FetchPage.Error(WhoSampledStatus.BLOCKED)
                status == 404 ->
                    FetchPage.Error(WhoSampledStatus.NO_MATCH)
                status !in 200..299 ->
                    FetchPage.Error(WhoSampledStatus.NETWORK_ERROR)
                else -> {
                    val document = response.parse()
                    FetchPage.Ok(document, response.url().toString())
                }
            }
        } catch (_: Exception) {
            FetchPage.Error(WhoSampledStatus.NETWORK_ERROR)
        }
    }

    private fun looksLikeAccessChallenge(body: String): Boolean {
        val text = body.lowercase()
        return text.contains("cf-chl-") ||
            text.contains("verify you are human") ||
            text.contains("attention required") ||
            text.contains("captcha") ||
            text.contains("access denied")
    }

    private fun absoluteWhoSampledUrl(link: Element): String {
        val absolute = link.absUrl("href").trim()
        if (absolute.isNotBlank()) return absolute.substringBefore('#')
        val href = link.attr("href").trim()
        return when {
            href.startsWith("https://www.whosampled.com") -> href.substringBefore('#')
            href.startsWith("/") -> "$BASE_URL$href".substringBefore('#')
            else -> ""
        }
    }

    private fun isSameHost(url: String): Boolean =
        runCatching {
            val host = URI(url).host.orEmpty().lowercase()
            host == "www.whosampled.com" || host == "whosampled.com"
        }.getOrDefault(false)

    private fun isTrackPageUrl(url: String): Boolean {
        if (!isSameHost(url)) return false
        val segments = pathSegments(url)
        if (segments.size != 2) return false
        if (segments.first().lowercase() in RESERVED_ROOTS) return false
        if (segments.last().lowercase() in RESERVED_SECOND_SEGMENTS) return false
        return true
    }

    private fun isCoverRelationshipPage(url: String): Boolean =
        runCatching { URI(url).path.orEmpty().startsWith("/cover/") }.getOrDefault(false)

    private fun pathSegments(url: String): List<String> =
        runCatching {
            URI(url).path.orEmpty()
                .trim('/')
                .split('/')
                .filter { it.isNotBlank() }
                .map { URLDecoder.decode(it, "UTF-8") }
        }.getOrDefault(emptyList())

    private fun artistFromTrackUrl(url: String): String =
        pathSegments(url).firstOrNull()
            ?.replace('-', ' ')
            ?.trim()
            .orEmpty()

    private fun trackTitleFromUrl(url: String): String =
        pathSegments(url).getOrNull(1)
            ?.replace('-', ' ')
            ?.trim()
            .orEmpty()

    private fun cleanDisplayTitle(value: String): String =
        value.trim()
            .replace(Regex("""\s+\((?:18|19|20)\d{2}\)\s*$"""), "")
            .substringBefore(" by ")
            .trim()

    private fun textSimilarity(a: String, b: String): Double {
        val left = canonical(a)
        val right = canonical(b)
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        if (left.startsWith(right) || right.startsWith(left)) return 0.92

        val l = left.split(' ').filter { it.length > 1 }.toSet()
        val r = right.split(' ').filter { it.length > 1 }.toSet()
        if (l.isEmpty() || r.isEmpty()) return 0.0
        val overlap = l.intersect(r).size.toDouble()
        val containment = overlap / max(1, minOf(l.size, r.size)).toDouble()
        val jaccard = overlap / l.union(r).size.toDouble()
        return containment * 0.7 + jaccard * 0.3
    }

    private fun canonical(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

    private val COVER_CONTEXT_PHRASES =
        listOf(
            "is a cover of",
            "was covered in",
            "other covers of",
            "covers of",
            "cover version",
            "covers found",
        )

    private val RESERVED_ROOTS =
        setOf(
            "about",
            "album",
            "browse",
            "contact",
            "cover",
            "discover",
            "login",
            "news",
            "privacy",
            "remix",
            "sample",
            "search",
            "signup",
            "submit",
            "terms",
        )

    private val RESERVED_SECOND_SEGMENTS =
        setOf(
            "covers",
            "remixes",
            "sampled",
            "samples",
        )
}
