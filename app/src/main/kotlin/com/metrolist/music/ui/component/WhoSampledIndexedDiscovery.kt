/**
 * MusicLab WhoSampled indexed discovery fallback
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Fallback used when the direct WhoSampled reader cannot return data.
 *
 * It never attempts to solve or bypass WhoSampled access challenges. Instead:
 *  1. it searches public web indexes for already-indexed WhoSampled relationship pages;
 *  2. it accepts only results whose final URL belongs to whosampled.com;
 *  3. it validates the original title/artist encoded in the indexed result;
 *  4. only as a tertiary fallback, it can use Gemini Google Search grounding.
 */
internal object WhoSampledIndexedDiscovery {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val cache = ConcurrentHashMap<String, CachedResult>()

    suspend fun discover(
        originalTitle: String,
        originalArtist: String,
        config: GeminiCoverVerificationConfig,
    ): List<WhoSampledCover> = withContext(Dispatchers.IO) {
        if (originalTitle.isBlank()) return@withContext emptyList()

        val cacheKey = "v2|${originalTitle.trim()}|${originalArtist.trim()}"
        cache[cacheKey]
            ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
            ?.let { return@withContext it.covers }

        val indexed = discoverFromPublicIndexes(originalTitle.trim(), originalArtist.trim())
        if (indexed.isNotEmpty()) {
            val covers = indexed.distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
                .take(MAX_RESULTS)
            cache[cacheKey] = CachedResult(
                covers = covers,
                expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
            )
            return@withContext covers
        }

        if (config.apiKey.isBlank() || !MODEL_REGEX.matches(config.model.trim())) {
            cache[cacheKey] = CachedResult(
                covers = emptyList(),
                expiresAtMs = System.currentTimeMillis() + EMPTY_CACHE_TTL_MS,
            )
            return@withContext emptyList()
        }

        val grounded = discoverWithGrounding(originalTitle.trim(), originalArtist.trim(), config)
        cache[cacheKey] = CachedResult(
            covers = grounded,
            expiresAtMs = System.currentTimeMillis() +
                if (grounded.isEmpty()) EMPTY_CACHE_TTL_MS else CACHE_TTL_MS,
        )
        grounded
    }

    private fun discoverFromPublicIndexes(
        originalTitle: String,
        originalArtist: String,
    ): List<WhoSampledCover> {
        val quotedTitle = "\"$originalTitle\""
        val quotedArtist = originalArtist.takeIf { it.isNotBlank() }?.let { "\"$it\"" }.orEmpty()
        val queries = linkedSetOf(
            "site:whosampled.com/cover/ $quotedTitle $quotedArtist",
            "site:whosampled.com/cover/ $quotedTitle $quotedArtist cover",
            "site:whosampled.com $quotedTitle $quotedArtist \"cover of\"",
        )

        val hits = linkedMapOf<String, IndexedHit>()
        for (query in queries) {
            searchDuckDuckGo(query).forEach { hit -> hits.putIfAbsent(hit.url, hit) }
            if (hits.size < MIN_INDEX_HITS_BEFORE_BING) {
                searchBing(query).forEach { hit -> hits.putIfAbsent(hit.url, hit) }
            }
            if (hits.size >= MAX_INDEX_HITS) break
        }

        return hits.values
            .asSequence()
            .filter { isWhoSampledRelationshipUrl(it.url) }
            .mapNotNull { parseIndexedRelationship(it, originalTitle, originalArtist) }
            .distinctBy { "${canonical(it.title)}|${canonical(it.artist)}" }
            .take(MAX_RESULTS)
            .toList()
    }

    private fun searchDuckDuckGo(query: String): List<IndexedHit> {
        val url = "https://html.duckduckgo.com/html/?q=${URLEncoder.encode(query, "UTF-8")}"
        return runCatching {
            val response = Jsoup.connect(url)
                .userAgent(INDEX_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(INDEX_TIMEOUT_MS)
                .maxBodySize(2_000_000)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .execute()
            if (response.statusCode() !in 200..299) return@runCatching emptyList()
            val doc = response.parse()
            doc.select(".result").mapNotNull { result ->
                val anchor = result.selectFirst("a.result__a[href]") ?: return@mapNotNull null
                val target = decodeDuckDuckGoTarget(anchor.attr("href")) ?: return@mapNotNull null
                if (!isWhoSampledUrl(target)) return@mapNotNull null
                IndexedHit(
                    title = anchor.text().trim(),
                    snippet = result.selectFirst(".result__snippet")?.text()?.trim().orEmpty(),
                    url = target,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun searchBing(query: String): List<IndexedHit> {
        val url = "https://www.bing.com/search?q=${URLEncoder.encode(query, "UTF-8")}&count=20"
        return runCatching {
            val response = Jsoup.connect(url)
                .userAgent(INDEX_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(INDEX_TIMEOUT_MS)
                .maxBodySize(2_000_000)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .execute()
            if (response.statusCode() !in 200..299) return@runCatching emptyList()
            response.parse().select("li.b_algo").mapNotNull { result ->
                val anchor = result.selectFirst("h2 a[href]") ?: return@mapNotNull null
                val target = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
                if (!isWhoSampledUrl(target)) return@mapNotNull null
                IndexedHit(
                    title = anchor.text().trim(),
                    snippet = result.selectFirst(".b_caption p")?.text()?.trim().orEmpty(),
                    url = target,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun parseIndexedRelationship(
        hit: IndexedHit,
        requestedTitle: String,
        requestedArtist: String,
    ): WhoSampledCover? {
        val seo = hit.title
            .substringBefore(" | WhoSampled")
            .substringBefore(" - WhoSampled")
            .trim()

        EXPLICIT_TITLED_COVER.find(seo)?.let { match ->
            val coverArtist = cleanSeoText(match.groupValues[1])
            val coverTitle = cleanSeoText(match.groupValues[2])
            val originalArtist = cleanSeoText(match.groupValues[3])
            val originalTitle = cleanSeoText(match.groupValues[4])
            if (
                relationshipMatches(
                    requestedTitle = requestedTitle,
                    requestedArtist = requestedArtist,
                    originalTitle = originalTitle,
                    originalArtist = originalArtist,
                )
            ) {
                return WhoSampledCover(coverTitle, coverArtist, hit.url)
            }
        }

        SIMPLE_COVER.find(seo)?.let { match ->
            val coverArtist = cleanSeoText(match.groupValues[1])
            val originalArtist = cleanSeoText(match.groupValues[2])
            val originalTitle = cleanSeoText(match.groupValues[3])
            if (
                relationshipMatches(
                    requestedTitle = requestedTitle,
                    requestedArtist = requestedArtist,
                    originalTitle = originalTitle,
                    originalArtist = originalArtist,
                )
            ) {
                return WhoSampledCover(requestedTitle, coverArtist, hit.url)
            }
        }

        val combined = "$seo ${hit.snippet}".trim()
        SNIPPET_COVER.find(combined)?.let { match ->
            val coverTitle = cleanSeoText(match.groupValues[1])
            val coverArtist = cleanSeoText(match.groupValues[2])
            val originalTitle = cleanSeoText(match.groupValues[3])
            val originalArtist = cleanSeoText(match.groupValues[4])
            if (
                relationshipMatches(
                    requestedTitle = requestedTitle,
                    requestedArtist = requestedArtist,
                    originalTitle = originalTitle,
                    originalArtist = originalArtist,
                )
            ) {
                return WhoSampledCover(coverTitle, coverArtist, hit.url)
            }
        }

        return null
    }

    private fun relationshipMatches(
        requestedTitle: String,
        requestedArtist: String,
        originalTitle: String,
        originalArtist: String,
    ): Boolean {
        if (textSimilarity(requestedTitle, originalTitle) < 0.74) return false
        if (requestedArtist.isBlank()) return true
        return textSimilarity(requestedArtist, originalArtist) >= 0.42
    }

    private fun decodeDuckDuckGoTarget(href: String): String? {
        val raw = href.trim()
        if (raw.isBlank()) return null
        if (raw.startsWith("https://") && isWhoSampledUrl(raw)) return raw
        return runCatching {
            val absolute = if (raw.startsWith("//")) "https:$raw" else raw
            val uri = URI(absolute)
            val query = uri.rawQuery.orEmpty()
            val encoded = query.split('&')
                .firstOrNull { it.startsWith("uddg=") }
                ?.substringAfter('=')
                ?: return@runCatching null
            URLDecoder.decode(encoded, "UTF-8")
        }.getOrNull()
    }

    private fun isWhoSampledRelationshipUrl(value: String): Boolean {
        if (!isWhoSampledUrl(value)) return false
        val path = runCatching { URI(value).path.orEmpty().lowercase() }.getOrDefault("")
        return path.startsWith("/cover/") || path.endsWith("/covered/")
    }

    private fun cleanSeoText(value: String): String =
        value.trim()
            .trim('\'', '"', '‘', '’', '“', '”', ' ')
            .replace(Regex("\\s+"), " ")

    private fun canonical(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun textSimilarity(left: String, right: String): Double {
        val a = canonical(left)
        val b = canonical(right)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (a.startsWith(b) || b.startsWith(a)) return 0.94
        val aa = a.split(' ').filter { it.length > 1 }.toSet()
        val bb = b.split(' ').filter { it.length > 1 }.toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        val overlap = aa.intersect(bb).size.toDouble()
        val containment = overlap / max(1, minOf(aa.size, bb.size)).toDouble()
        val jaccard = overlap / aa.union(bb).size.toDouble()
        return containment * 0.68 + jaccard * 0.32
    }

    private fun discoverWithGrounding(
        originalTitle: String,
        originalArtist: String,
        config: GeminiCoverVerificationConfig,
    ): List<WhoSampledCover> {
        val prompt =
            """Use Google Search to search ONLY public pages on whosampled.com for explicit cover-song relationships involving this recording.

Original recording:
Title: $originalTitle
Artist: $originalArtist

Start with a site-restricted query equivalent to:
site:whosampled.com \"$originalTitle\" \"$originalArtist\" cover
Then broaden only with other site:whosampled.com queries if needed.

Important rules:
- Use ONLY WhoSampled pages as evidence. Ignore all other domains.
- Return a version only when a WhoSampled result explicitly shows that it is a cover of this composition, that this recording was covered in that version, or that both versions are connected through the same original composition.
- Different-language/adapted titles are allowed only when WhoSampled explicitly connects them.
- Exclude unrelated same-title songs, remixes, samples, mashups, karaoke, tribute/backing tracks and guesses.
- source_url MUST be the actual https://www.whosampled.com/... page supporting that candidate.
- If WhoSampled evidence is insufficient, return an empty list.

Return ONLY JSON:
{"covers":[{"title":"exact cover title","artist":"cover artist","source_url":"https://www.whosampled.com/..."}]}

Return up to 48 candidates."""

        for (model in modelCandidates(config.model.trim())) {
            val response = execute(model, config.apiKey, prompt) ?: continue
            if (!response.hasWhoSampledGrounding) continue
            val covers = parseCovers(response.text)
                .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
                .take(MAX_RESULTS)
            if (covers.isNotEmpty()) return covers
        }
        return emptyList()
    }

    private fun modelCandidates(requested: String): List<String> =
        listOf(requested, LEGACY_DEFAULT_MODEL, CURRENT_DEFAULT_MODEL)
            .filter { MODEL_REGEX.matches(it) }
            .distinct()

    private fun execute(
        model: String,
        apiKey: String,
        prompt: String,
    ): GroundedResponse? {
        val body =
            buildJsonObject {
                put(
                    "contents",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "parts",
                                    buildJsonArray {
                                        add(buildJsonObject { put("text", prompt) })
                                    },
                                )
                            },
                        )
                    },
                )
                put(
                    "tools",
                    buildJsonArray {
                        add(buildJsonObject { put("google_search", buildJsonObject {}) })
                    },
                )
                put(
                    "generationConfig",
                    buildJsonObject {
                        put("maxOutputTokens", 3200)
                    },
                )
            }

        val request =
            Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .addHeader("x-goog-api-key", apiKey.trim())
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.let(::parseResponse)
            }
        }.getOrNull()
    }

    private fun parseResponse(body: String): GroundedResponse? {
        val root = parseJsonObject(body) ?: return null
        val candidate = root["candidates"]
            ?.runCatching { jsonArray }
            ?.getOrNull()
            ?.firstOrNull()
            ?.runCatching { jsonObject }
            ?.getOrNull()
            ?: return null

        val parts = candidate["content"]
            ?.runCatching { jsonObject }
            ?.getOrNull()
            ?.get("parts")
            ?.runCatching { jsonArray }
            ?.getOrNull()
            ?: return null

        val text = buildString {
            parts.forEach { part ->
                val value = part.runCatching { jsonObject }
                    .getOrNull()
                    ?.get("text")
                    ?.runCatching { jsonPrimitive }
                    ?.getOrNull()
                    ?.contentOrNull
                    .orEmpty()
                if (value.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(value)
                }
            }
        }.trim()
        if (text.isBlank()) return null

        val metadata = candidate["groundingMetadata"]
            ?.runCatching { jsonObject }
            ?.getOrNull()
        val chunks = metadata
            ?.get("groundingChunks")
            ?.runCatching { jsonArray }
            ?.getOrNull()

        val hasWhoSampled = chunks?.any { chunk ->
            val web = chunk.runCatching { jsonObject }
                .getOrNull()
                ?.get("web")
                ?.runCatching { jsonObject }
                ?.getOrNull()
                ?: return@any false
            val uri = web.string("uri").lowercase()
            val title = web.string("title").lowercase()
            uri.contains("whosampled.com") || title.contains("whosampled")
        } == true

        return GroundedResponse(text = text, hasWhoSampledGrounding = hasWhoSampled)
    }

    private fun parseCovers(text: String): List<WhoSampledCover> {
        val root = extractJsonObject(text) ?: return emptyList()
        val covers = root["covers"]
            ?.runCatching { jsonArray }
            ?.getOrNull()
            ?: return emptyList()

        return covers.mapNotNull { element ->
            val item = element.runCatching { jsonObject }.getOrNull() ?: return@mapNotNull null
            val title = item.string("title")
            val artist = item.string("artist")
            val sourceUrl = item.string("source_url")
            if (title.isBlank() || artist.isBlank() || !isWhoSampledUrl(sourceUrl)) {
                return@mapNotNull null
            }
            WhoSampledCover(title = title, artist = artist, url = sourceUrl)
        }
    }

    private fun isWhoSampledUrl(value: String): Boolean =
        runCatching {
            val uri = URI(value.trim())
            val host = uri.host.orEmpty().lowercase()
            uri.scheme == "https" && (host == "whosampled.com" || host == "www.whosampled.com")
        }.getOrDefault(false)

    private fun extractJsonObject(text: String): JsonObject? {
        val cleaned = text
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return parseJsonObject(cleaned.substring(start, end + 1))
    }

    private fun parseJsonObject(value: String): JsonObject? =
        runCatching { json.parseToJsonElement(value).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String =
        get(key)
            ?.runCatching { jsonPrimitive }
            ?.getOrNull()
            ?.contentOrNull
            ?.trim()
            .orEmpty()

    private data class IndexedHit(
        val title: String,
        val snippet: String,
        val url: String,
    )

    private data class GroundedResponse(
        val text: String,
        val hasWhoSampledGrounding: Boolean,
    )

    private data class CachedResult(
        val covers: List<WhoSampledCover>,
        val expiresAtMs: Long,
    )

    private const val MAX_RESULTS = 48
    private const val MAX_INDEX_HITS = 40
    private const val MIN_INDEX_HITS_BEFORE_BING = 5
    private const val INDEX_TIMEOUT_MS = 8_000
    private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    private const val EMPTY_CACHE_TTL_MS = 5L * 60L * 1000L
    private const val LEGACY_DEFAULT_MODEL = "gemini-2.5-flash-lite"
    private const val CURRENT_DEFAULT_MODEL = "gemini-3.5-flash-lite"
    private const val INDEX_USER_AGENT = "MusicLab/0.8.9 (Android; public WhoSampled index lookup)"

    private val MODEL_REGEX = Regex("[A-Za-z0-9._-]+")
    private val EXPLICIT_TITLED_COVER = Regex(
        """^(.+?)[’']s\s+[\"“'](.+?)[\"”']\s+cover of\s+(.+?)[’']s\s+[\"“'](.+?)[\"”']""",
        RegexOption.IGNORE_CASE,
    )
    private val SIMPLE_COVER = Regex(
        """^(.+?)\s+cover of\s+(.+?)[’']s\s+[\"“'](.+?)[\"”']""",
        RegexOption.IGNORE_CASE,
    )
    private val SNIPPET_COVER = Regex(
        """(.+?)\s+by\s+(.+?)\s+(?:is a cover of|cover of)\s+(.+?)\s+by\s+(.+?)(?:\.|$)""",
        RegexOption.IGNORE_CASE,
    )
}
