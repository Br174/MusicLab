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
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fallback used only when the direct WhoSampled reader cannot return data.
 *
 * It does not attempt to defeat WhoSampled access controls. Instead it asks
 * Gemini Google Search grounding to search the public web index for pages on
 * whosampled.com and accepts a candidate only when:
 *  - the grounded response contains WhoSampled as a web source; and
 *  - the candidate itself carries a whosampled.com source URL.
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
        if (config.apiKey.isBlank() || !MODEL_REGEX.matches(config.model.trim())) {
            return@withContext emptyList()
        }
        if (originalTitle.isBlank()) return@withContext emptyList()

        val cacheKey = "${config.model}|${originalTitle.trim()}|${originalArtist.trim()}"
        cache[cacheKey]
            ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
            ?.let { return@withContext it.covers }

        val prompt =
            """Use Google Search to search ONLY public pages on whosampled.com for explicit cover-song relationships involving this recording.

Original recording:
Title: ${originalTitle.trim()}
Artist: ${originalArtist.trim()}

Start with a site-restricted query equivalent to:
site:whosampled.com \"${originalTitle.trim()}\" \"${originalArtist.trim()}\" cover
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

            val covers =
                parseCovers(response.text)
                    .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
                    .take(MAX_RESULTS)
            if (covers.isNotEmpty()) {
                cache[cacheKey] = CachedResult(
                    covers = covers,
                    expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
                )
                return@withContext covers
            }
        }

        cache[cacheKey] = CachedResult(
            covers = emptyList(),
            expiresAtMs = System.currentTimeMillis() + EMPTY_CACHE_TTL_MS,
        )
        emptyList()
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
            Request
                .Builder()
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
        val candidate =
            root["candidates"]
                ?.runCatching { jsonArray }
                ?.getOrNull()
                ?.firstOrNull()
                ?.runCatching { jsonObject }
                ?.getOrNull()
                ?: return null

        val parts =
            candidate["content"]
                ?.runCatching { jsonObject }
                ?.getOrNull()
                ?.get("parts")
                ?.runCatching { jsonArray }
                ?.getOrNull()
                ?: return null

        val text =
            buildString {
                parts.forEach { part ->
                    val value =
                        part.runCatching { jsonObject }
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

        val metadata =
            candidate["groundingMetadata"]
                ?.runCatching { jsonObject }
                ?.getOrNull()
        val chunks =
            metadata
                ?.get("groundingChunks")
                ?.runCatching { jsonArray }
                ?.getOrNull()

        val hasWhoSampled = chunks?.any { chunk ->
            val web =
                chunk.runCatching { jsonObject }
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
        val covers =
            root["covers"]
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
            WhoSampledCover(
                title = title,
                artist = artist,
                url = sourceUrl,
            )
        }
    }

    private fun isWhoSampledUrl(value: String): Boolean =
        runCatching {
            val uri = URI(value.trim())
            val host = uri.host.orEmpty().lowercase()
            uri.scheme == "https" && (host == "whosampled.com" || host == "www.whosampled.com")
        }.getOrDefault(false)

    private fun extractJsonObject(text: String): JsonObject? {
        val cleaned =
            text
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

    private data class GroundedResponse(
        val text: String,
        val hasWhoSampledGrounding: Boolean,
    )

    private data class CachedResult(
        val covers: List<WhoSampledCover>,
        val expiresAtMs: Long,
    )

    private const val MAX_RESULTS = 48
    private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    private const val EMPTY_CACHE_TTL_MS = 5L * 60L * 1000L
    private const val LEGACY_DEFAULT_MODEL = "gemini-2.5-flash-lite"
    private const val CURRENT_DEFAULT_MODEL = "gemini-3.5-flash-lite"
    private val MODEL_REGEX = Regex("[A-Za-z0-9._-]+")
}
