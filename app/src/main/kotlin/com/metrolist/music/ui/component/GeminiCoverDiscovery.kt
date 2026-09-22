/**
 * MusicLab grounded cover discovery
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class GeminiDiscoveredCover(
    val title: String,
    val artist: String,
    val year: Int?,
    val translatedOrAdaptedTitle: Boolean,
)

/**
 * Broader grounded discovery used by Cerca cover. It deliberately asks Gemini
 * to prioritise WhoSampled for relationship evidence, MusicBrainz for work
 * identity and Discogs/official sources for release chronology.
 */
internal object GeminiCoverDiscovery {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val discoveryCache = ConcurrentHashMap<String, CachedDiscovery>()
    private val yearCache = ConcurrentHashMap<String, CachedYear>()

    suspend fun discover(
        originalTitle: String,
        originalArtist: String,
        config: GeminiCoverVerificationConfig,
    ): List<GeminiDiscoveredCover> = withContext(Dispatchers.IO) {
        if (!config.isUsable()) return@withContext emptyList()

        val cacheKey = "${config.model}|${originalTitle.trim()}|${originalArtist.trim()}"
        discoveryCache[cacheKey]
            ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
            ?.let { return@withContext it.covers }

        val prompt =
            """Search the web for genuine cover recordings, reinterpretations and translated/adapted versions of the SAME underlying musical composition.

Original recording:
Title: ${originalTitle.trim()}
Artist: ${originalArtist.trim()}

Prioritise these sources when available:
1. WhoSampled for explicit cover relationships.
2. MusicBrainz for recordings linked to the same work.
3. Discogs and official artist/label sources for the earliest release year of that specific interpretation.

Return versions by other artists. A translated or adapted title is valid only if it is the same composition. Exclude same-title unrelated songs, remasters, karaoke, backing tracks, tutorials, reactions, mashups and medleys.

For year, return the EARLIEST documented public release year of that artist's specific recording/performance, not a later reissue year. If the year is uncertain, use null.

Return ONLY JSON in this shape:
{"covers":[{"title":"exact candidate title","artist":"candidate artist","year":1974,"adapted_title":false}]}

Return up to 36 high-confidence candidates. Do not invent weak guesses."""

        val grounded = executeGrounded(prompt, config, maxOutputTokens = 3000)
            ?: return@withContext emptyList()
        if (grounded.webSourceCount == 0) return@withContext emptyList()

        val covers =
            parseDiscoveryText(grounded.text)
                .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
                .take(MAX_DISCOVERY_RESULTS)

        discoveryCache[cacheKey] =
            CachedDiscovery(
                covers = covers,
                expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
            )
        covers
    }

    suspend fun resolveYear(
        title: String,
        artist: String,
        config: GeminiCoverVerificationConfig,
    ): Int? = withContext(Dispatchers.IO) {
        if (!config.isUsable()) return@withContext null
        if (title.isBlank() || artist.isBlank()) return@withContext null

        val cacheKey = "${config.model}|${title.trim()}|${artist.trim()}"
        yearCache[cacheKey]
            ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
            ?.let { return@withContext it.year }

        val prompt =
            """Use Google Search to find the EARLIEST documented public release year of this specific recording or performance:
Title: ${title.trim()}
Artist: ${artist.trim()}

Prefer MusicBrainz, Discogs, official artist/label discographies and other reliable music databases. Discogs release pages may describe later reissues, so do not use a reissue year when an earlier release of this same artist's recording is documented. Do not return the composition's original year if this artist recorded it later.

Return ONLY JSON:
{"year":1974}
Use null when reliable evidence is insufficient."""

        val grounded = executeGrounded(prompt, config, maxOutputTokens = 220)
        val year =
            grounded
                ?.takeIf { it.webSourceCount > 0 }
                ?.let { parseYearText(it.text) }

        yearCache[cacheKey] =
            CachedYear(
                year = year,
                expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
            )
        year
    }

    private fun GeminiCoverVerificationConfig.isUsable(): Boolean =
        apiKey.isNotBlank() && MODEL_REGEX.matches(model.trim())

    private fun executeGrounded(
        prompt: String,
        config: GeminiCoverVerificationConfig,
        maxOutputTokens: Int,
    ): GroundedText? {
        val model = config.model.trim()
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
                        put("temperature", 0.0)
                        put("maxOutputTokens", maxOutputTokens)
                    },
                )
            }

        val request =
            Request
                .Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .addHeader("x-goog-api-key", config.apiKey.trim())
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.let(::parseGroundedResponse)
            }
        }.getOrNull()
    }

    private fun parseGroundedResponse(responseBody: String): GroundedText? {
        val root = parseJsonObject(responseBody) ?: return null
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
        val chunkKeys = mutableMapOf<Int, String>()
        chunks?.forEachIndexed { index, chunk ->
            val web =
                chunk.runCatching { jsonObject }
                    .getOrNull()
                    ?.get("web")
                    ?.runCatching { jsonObject }
                    ?.getOrNull()
                    ?: return@forEachIndexed
            val uri = web.string("uri")
            val title = web.string("title")
            val key = uri.ifBlank { title }
            if (key.isNotBlank()) chunkKeys[index] = key
        }

        val supportedSources = linkedSetOf<String>()
        val supports =
            metadata
                ?.get("groundingSupports")
                ?.runCatching { jsonArray }
                ?.getOrNull()
        supports?.forEach supportLoop@ { support ->
            val indices =
                support.runCatching { jsonObject }
                    .getOrNull()
                    ?.get("groundingChunkIndices")
                    ?.runCatching { jsonArray }
                    ?.getOrNull()
                    ?: return@supportLoop
            indices.forEach indexLoop@ { indexElement ->
                val index =
                    indexElement.runCatching { jsonPrimitive }
                        .getOrNull()
                        ?.intOrNull
                        ?: return@indexLoop
                chunkKeys[index]?.let(supportedSources::add)
            }
        }

        return GroundedText(text, supportedSources.size)
    }

    internal fun parseDiscoveryText(text: String): List<GeminiDiscoveredCover> {
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
            if (title.isBlank() || artist.isBlank()) return@mapNotNull null
            GeminiDiscoveredCover(
                title = title,
                artist = artist,
                year = item.year("year"),
                translatedOrAdaptedTitle = item.boolean("adapted_title"),
            )
        }
    }

    internal fun parseYearText(text: String): Int? {
        val root = extractJsonObject(text) ?: return null
        return root.year("year")
    }

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

    private fun JsonObject.boolean(key: String): Boolean =
        get(key)
            ?.runCatching { jsonPrimitive }
            ?.getOrNull()
            ?.booleanOrNull
            ?: false

    private fun JsonObject.year(key: String): Int? {
        val primitive = get(key)?.runCatching { jsonPrimitive }?.getOrNull() ?: return null
        val value = primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
        return value?.takeIf { it in 1800..2100 }
    }

    private data class GroundedText(
        val text: String,
        val webSourceCount: Int,
    )

    private data class CachedDiscovery(
        val covers: List<GeminiDiscoveredCover>,
        val expiresAtMs: Long,
    )

    private data class CachedYear(
        val year: Int?,
        val expiresAtMs: Long,
    )

    private const val CACHE_TTL_MS = 12L * 60L * 60L * 1000L
    private const val MAX_DISCOVERY_RESULTS = 36
    private val MODEL_REGEX = Regex("[A-Za-z0-9._-]+")
}
