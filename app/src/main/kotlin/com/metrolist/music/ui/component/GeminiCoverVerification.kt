/**
 * MusicLab grounded cover verification
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class GeminiCoverVerificationConfig(
    val apiKey: String,
    val model: String,
)

internal enum class GeminiCoverDecision {
    SAME_WORK,
    DIFFERENT_WORK,
    UNCERTAIN,
}

internal data class GeminiCoverVerdict(
    val decision: GeminiCoverDecision,
    val translatedOrAdaptedTitle: Boolean,
    val webSourceConfirmations: Int,
)

internal data class GeminiCoverReference(
    val title: String,
    val artist: String,
    val translatedOrAdaptedTitle: Boolean,
)

/**
 * Uses the Gemini native API only for cover discovery/verification so the
 * existing lyrics AI stack remains untouched. Google Search grounding is
 * requested for every call; ungrounded answers are never enough to upgrade a
 * candidate to VERIFIED on their own.
 */
internal object GeminiCoverVerification {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val verificationCache = ConcurrentHashMap<String, CachedVerdict>()
    private val discoveryCache = ConcurrentHashMap<String, CachedReferences>()

    suspend fun discover(
        originalTitle: String,
        originalArtist: String,
        config: GeminiCoverVerificationConfig,
    ): List<GeminiCoverReference> = withContext(Dispatchers.IO) {
        if (!config.isUsable()) return@withContext emptyList()

        val cacheKey = "${config.model}|${originalTitle.trim()}|${originalArtist.trim()}"
        discoveryCache[cacheKey]
            ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
            ?.let { return@withContext it.references }

        val prompt =
            """Find genuine cover recordings or translated/adapted versions of the same musical composition.

Original recording:
Title: ${originalTitle.trim()}
Artist: ${originalArtist.trim()}

Use Google Search. Return only versions performed by a different artist when possible. A translated/adapted title is valid only when it is the same underlying composition. Exclude same-title unrelated songs, remasters, karaoke, backing tracks, tutorials, reactions, mashups and medleys.

Return ONLY valid JSON with this shape:
{"covers":[{"title":"exact candidate title","artist":"candidate artist","adapted_title":false}]}

Return at most 10 high-confidence candidates. If evidence is weak, omit the candidate rather than guessing."""

        val responseBody =
            executeGroundedRequest(
                prompt = prompt,
                config = config,
                maxOutputTokens = 1200,
            ) ?: return@withContext emptyList()

        val grounded = parseGroundedResponse(responseBody) ?: return@withContext emptyList()
        if (grounded.webSourceCount == 0) return@withContext emptyList()

        val references =
            parseDiscoveryText(grounded.text)
                .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
                .take(MAX_DISCOVERY_RESULTS)

        discoveryCache[cacheKey] =
            CachedReferences(
                references = references,
                expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
            )
        references
    }

    suspend fun verify(
        originalTitle: String,
        originalArtist: String,
        originalDurationSec: Int,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int,
        config: GeminiCoverVerificationConfig,
    ): GeminiCoverVerdict? = withContext(Dispatchers.IO) {
        if (!config.isUsable()) return@withContext null

        val cacheKey =
            listOf(
                config.model,
                originalTitle.trim(),
                originalArtist.trim(),
                candidateTitle.trim(),
                candidateArtist.trim(),
            ).joinToString("|")

        verificationCache[cacheKey]
            ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
            ?.let { return@withContext it.verdict }

        val originalDuration = originalDurationSec.takeIf { it > 0 }?.toString() ?: "unknown"
        val candidateDuration = candidateDurationSec.takeIf { it > 0 }?.toString() ?: "unknown"
        val prompt =
            """Use Google Search to verify whether these two recordings are performances of the SAME underlying musical composition.

Original:
Title: ${originalTitle.trim()}
Artist: ${originalArtist.trim()}
Duration seconds: $originalDuration

Candidate:
Title: ${candidateTitle.trim()}
Artist: ${candidateArtist.trim()}
Duration seconds: $candidateDuration

A true cover, foreign-language adaptation or translated-title version counts as the same work. A same-title unrelated song does not. A remix, mashup, medley, karaoke, backing track, tutorial or reaction does not count. Be conservative: when evidence is insufficient, answer uncertain.

Return ONLY valid JSON:
{"verdict":"same_work|different_work|uncertain","adapted_title":false}"""

        val responseBody =
            executeGroundedRequest(
                prompt = prompt,
                config = config,
                maxOutputTokens = 320,
            ) ?: return@withContext null

        val grounded = parseGroundedResponse(responseBody) ?: return@withContext null
        val verdict =
            parseVerificationText(
                text = grounded.text,
                webSourceCount = grounded.webSourceCount,
            ) ?: return@withContext null

        verificationCache[cacheKey] =
            CachedVerdict(
                verdict = verdict,
                expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
            )
        verdict
    }

    private fun GeminiCoverVerificationConfig.isUsable(): Boolean =
        apiKey.isNotBlank() && MODEL_REGEX.matches(model.trim())

    private fun executeGroundedRequest(
        prompt: String,
        config: GeminiCoverVerificationConfig,
        maxOutputTokens: Int,
    ): String? {
        val model = config.model.trim()
        val body =
            JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put("text", prompt),
                                ),
                            )
                        },
                    ),
                )
                put(
                    "tools",
                    JSONArray().put(
                        JSONObject().put("google_search", JSONObject()),
                    ),
                )
                put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.0)
                        .put("maxOutputTokens", maxOutputTokens),
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
                response.body?.string()
            }
        }.getOrNull()
    }

    internal fun parseGroundedResponse(responseBody: String): GroundedText? {
        val root = runCatching { JSONObject(responseBody) }.getOrNull() ?: return null
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return null

        val text =
            buildString {
                for (index in 0 until parts.length()) {
                    val value = parts.optJSONObject(index)?.optString("text").orEmpty()
                    if (value.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(value)
                    }
                }
            }.trim()
        if (text.isBlank()) return null

        val metadata = candidate.optJSONObject("groundingMetadata")
        val chunks = metadata?.optJSONArray("groundingChunks")
        val chunkKeys = mutableMapOf<Int, String>()
        if (chunks != null) {
            for (index in 0 until chunks.length()) {
                val web = chunks.optJSONObject(index)?.optJSONObject("web") ?: continue
                val uri = web.optString("uri").trim()
                val title = web.optString("title").trim()
                val key = uri.ifBlank { title }
                if (key.isNotBlank()) chunkKeys[index] = key
            }
        }

        // Only count web chunks explicitly cited by groundingSupports. A chunk
        // merely retrieved during search is not evidence for the model's
        // conclusion and must not promote/reject a cover candidate.
        val supportedSources = linkedSetOf<String>()
        val supports = metadata?.optJSONArray("groundingSupports")
        if (supports != null) {
            for (supportIndex in 0 until supports.length()) {
                val indices =
                    supports
                        .optJSONObject(supportIndex)
                        ?.optJSONArray("groundingChunkIndices")
                        ?: continue
                for (indexPosition in 0 until indices.length()) {
                    chunkKeys[indices.optInt(indexPosition, -1)]?.let(supportedSources::add)
                }
            }
        }

        return GroundedText(
            text = text,
            webSourceCount = supportedSources.size.coerceAtMost(MAX_WEB_CONFIRMATIONS),
        )
    }

    internal fun parseDiscoveryText(text: String): List<GeminiCoverReference> {
        val json = extractJsonObject(text) ?: return emptyList()
        val covers = json.optJSONArray("covers") ?: return emptyList()
        val result = mutableListOf<GeminiCoverReference>()
        for (index in 0 until covers.length()) {
            val item = covers.optJSONObject(index) ?: continue
            val title = item.optString("title").trim()
            val artist = item.optString("artist").trim()
            if (title.isBlank() || artist.isBlank()) continue
            result +=
                GeminiCoverReference(
                    title = title,
                    artist = artist,
                    translatedOrAdaptedTitle = item.optBoolean("adapted_title", false),
                )
        }
        return result
    }

    internal fun parseVerificationText(
        text: String,
        webSourceCount: Int,
    ): GeminiCoverVerdict? {
        val json = extractJsonObject(text) ?: return null
        val decision =
            when (json.optString("verdict").trim().lowercase()) {
                "same_work" -> GeminiCoverDecision.SAME_WORK
                "different_work" -> GeminiCoverDecision.DIFFERENT_WORK
                "uncertain" -> GeminiCoverDecision.UNCERTAIN
                else -> return null
            }

        return GeminiCoverVerdict(
            decision = decision,
            translatedOrAdaptedTitle = json.optBoolean("adapted_title", false),
            webSourceConfirmations = webSourceCount.coerceIn(0, MAX_WEB_CONFIRMATIONS),
        )
    }

    private fun extractJsonObject(text: String): JSONObject? {
        val cleaned =
            text
                .replace("```json", "", ignoreCase = true)
                .replace("```", "")
                .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
    }

    internal data class GroundedText(
        val text: String,
        val webSourceCount: Int,
    )

    private data class CachedVerdict(
        val verdict: GeminiCoverVerdict,
        val expiresAtMs: Long,
    )

    private data class CachedReferences(
        val references: List<GeminiCoverReference>,
        val expiresAtMs: Long,
    )

    private const val CACHE_TTL_MS = 12 * 60 * 60 * 1000L
    private const val MAX_DISCOVERY_RESULTS = 10
    private const val MAX_WEB_CONFIRMATIONS = 2
    private val MODEL_REGEX = Regex("[A-Za-z0-9._-]+")
}
