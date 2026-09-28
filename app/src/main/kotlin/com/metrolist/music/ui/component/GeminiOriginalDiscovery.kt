/**
 * MusicLab AI-first original recording identification.
 * Gemini is the authoritative discovery layer for Originali; YouTube/YouTube Music
 * are used only to locate playable versions of the performer identified by AI.
 */
package com.metrolist.music.ui.component

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

internal enum class GeminiOriginalMode {
    GOOGLE_SEARCH,
    MODEL_KNOWLEDGE,
}

internal data class GeminiOriginalIdentity(
    val title: String,
    val originalArtists: List<String>,
    val year: Int?,
    val songwriters: List<String>,
    val composers: List<String>,
    val lyricists: List<String>,
    val producers: List<String>,
    val label: String?,
    val album: String?,
    val mode: GeminiOriginalMode,
    val webSourceCount: Int,
)

internal object GeminiOriginalDiscovery {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val cache = ConcurrentHashMap<String, CachedIdentity>()

    suspend fun identify(
        currentTitle: String,
        currentArtist: String,
        config: GeminiCoverVerificationConfig,
    ): GeminiOriginalIdentity? = withContext(Dispatchers.IO) {
        if (currentTitle.isBlank()) return@withContext null

        val cacheKey = "v2|${config.model}|${currentTitle.trim().lowercase()}|${currentArtist.trim().lowercase()}"
        cache[cacheKey]
            ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
            ?.let { return@withContext it.identity }

        if (config.cloudEndpoint.isNotBlank()) {
            val cloudIdentity = CloudMusicDiscovery.identifyOriginal(
                title = currentTitle,
                artist = currentArtist,
                config = config,
            )
            if (cloudIdentity != null) {
                cache[cacheKey] = CachedIdentity(
                    identity = cloudIdentity,
                    expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
                )
                return@withContext cloudIdentity
            }
        }
        if (!config.isUsable()) return@withContext null

        val prompt =
            """Sei il motore Originali di un'app musicale. La traccia corrente può essere una cover, un live, un duetto, una performance TV o avere un titolo descrittivo.

Titolo/video: ${currentTitle.trim()}
Interprete/canale: ${currentArtist.trim().ifBlank { "sconosciuto" }}

Decidi direttamente, usando la tua conoscenza, qual è la stessa CANZONE nell'incisione originale e chi è l'INTERPRETE ORIGINALE. La tua decisione è quella usata dall'app: non proporre alternative, non chiedere conferme e non fare verifiche esterne.

Se conosci anno e crediti, inseriscili direttamente in base alla tua conoscenza. Usa null o [] solo quando davvero non li conosci. Includi quando noti: autori, compositori, parolieri, produttori, etichetta e album/singolo.

Rispondi SOLO con JSON valido:
{
  "title":"titolo canonico",
  "original_artists":["interprete originale"],
  "year":1965,
  "songwriters":["nome"],
  "composers":["nome"],
  "lyricists":["nome"],
  "producers":["nome"],
  "label":"etichetta oppure null",
  "album":"album o singolo oppure null"
}"""

        val response = executeWithFallbackModels(
            prompt = prompt,
            config = config,
            useGoogleSearch = false,
            maxOutputTokens = 750,
        ) ?: return@withContext null

        val parsed = parseIdentity(response) ?: return@withContext null
        cache[cacheKey] = CachedIdentity(
            identity = parsed,
            expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
        )
        parsed
    }

    private fun GeminiCoverVerificationConfig.isUsable(): Boolean =
        apiKey.isNotBlank() && MODEL_REGEX.matches(model.trim())

    private fun executeWithFallbackModels(
        prompt: String,
        config: GeminiCoverVerificationConfig,
        useGoogleSearch: Boolean,
        maxOutputTokens: Int,
    ): AiText? {
        for (model in modelCandidates(config.model.trim())) {
            val response = execute(
                model = model,
                apiKey = config.apiKey,
                prompt = prompt,
                useGoogleSearch = useGoogleSearch,
                maxOutputTokens = maxOutputTokens,
            ) ?: continue
            if (response.text.isNotBlank()) return response
        }
        return null
    }

    private fun modelCandidates(requested: String): List<String> =
        listOf(requested, CURRENT_DEFAULT_MODEL, LEGACY_DEFAULT_MODEL)
            .filter { it.isNotBlank() }
            .distinct()

    private fun execute(
        model: String,
        apiKey: String,
        prompt: String,
        useGoogleSearch: Boolean,
        maxOutputTokens: Int,
    ): AiText? {
        val body = buildJsonObject {
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
            if (useGoogleSearch) {
                put(
                    "tools",
                    buildJsonArray {
                        add(buildJsonObject { put("google_search", buildJsonObject {}) })
                    },
                )
            }
            put(
                "generationConfig",
                buildJsonObject {
                    put("maxOutputTokens", maxOutputTokens)
                    put("temperature", 0.10)
                    put("responseMimeType", "application/json")
                },
            )
        }

        val request = Request
            .Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey.trim())
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val responseBody = response.body?.string() ?: return@use null
                parseResponse(responseBody, useGoogleSearch)
            }
        }.getOrNull()
    }

    private fun parseResponse(responseBody: String, requestedGrounding: Boolean): AiText? {
        val root = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull() ?: return null
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

        val chunks = candidate["groundingMetadata"]
            ?.runCatching { jsonObject }
            ?.getOrNull()
            ?.get("groundingChunks")
            ?.runCatching { jsonArray }
            ?.getOrNull()
        val sourceCount = chunks?.size ?: 0

        return AiText(
            text = text,
            mode = if (requestedGrounding && sourceCount > 0) {
                GeminiOriginalMode.GOOGLE_SEARCH
            } else {
                GeminiOriginalMode.MODEL_KNOWLEDGE
            },
            webSourceCount = sourceCount,
        )
    }

    private fun parseIdentity(response: AiText): GeminiOriginalIdentity? {
        val root = extractJsonObject(response.text) ?: return null
        val title = root.string("title")
        val artists = root.strings("original_artists")
            .ifEmpty { root.nullableString("original_artist")?.let(::listOf).orEmpty() }
        if (title.isBlank() || artists.isEmpty()) return null

        return GeminiOriginalIdentity(
            title = title,
            originalArtists = artists,
            year = root.year("year"),
            songwriters = root.strings("songwriters"),
            composers = root.strings("composers"),
            lyricists = root.strings("lyricists"),
            producers = root.strings("producers"),
            label = root.nullableString("label"),
            album = root.nullableString("album"),
            mode = response.mode,
            webSourceCount = response.webSourceCount,
        )
    }

    private fun extractJsonObject(text: String): JsonObject? {
        val cleaned = text
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject
        }.getOrNull()
    }

    private fun JsonObject.string(key: String): String =
        get(key)
            ?.runCatching { jsonPrimitive }
            ?.getOrNull()
            ?.contentOrNull
            ?.trim()
            .orEmpty()

    private fun JsonObject.nullableString(key: String): String? =
        string(key).takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private fun JsonObject.strings(key: String): List<String> {
        val element = get(key) ?: return emptyList()
        val array = element.runCatching { jsonArray }.getOrNull()
        if (array != null) {
            return array.mapNotNull { item ->
                item.runCatching { jsonPrimitive }
                    .getOrNull()
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }.distinct()
        }

        return element.runCatching { jsonPrimitive }
            .getOrNull()
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?.let(::listOf)
            .orEmpty()
    }

    private fun JsonObject.year(key: String): Int? {
        val primitive = get(key)?.runCatching { jsonPrimitive }?.getOrNull() ?: return null
        val value = primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
        return value?.takeIf { it in 1800..2100 }
    }

    private data class AiText(
        val text: String,
        val mode: GeminiOriginalMode,
        val webSourceCount: Int,
    )

    private data class CachedIdentity(
        val identity: GeminiOriginalIdentity,
        val expiresAtMs: Long,
    )

    private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    private const val LEGACY_DEFAULT_MODEL = "gemini-2.5-flash-lite"
    private const val CURRENT_DEFAULT_MODEL = "gemini-3.5-flash-lite"
    private val MODEL_REGEX = Regex("[A-Za-z0-9._-]+")
}
