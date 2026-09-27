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
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val cache = ConcurrentHashMap<String, CachedIdentity>()

    suspend fun identify(
        currentTitle: String,
        currentArtist: String,
        config: GeminiCoverVerificationConfig,
    ): GeminiOriginalIdentity? = withContext(Dispatchers.IO) {
        if (!config.isUsable()) return@withContext null
        if (currentTitle.isBlank()) return@withContext null

        val cacheKey = "v1|${config.model}|${currentTitle.trim()}|${currentArtist.trim()}"
        cache[cacheKey]
            ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
            ?.let { return@withContext it.identity }

        val prompt =
            """Sei il motore principale della funzione Originali di un'app musicale.
La traccia corrente può essere una cover, una performance televisiva, un live, un duetto, una reinterpretazione o un video con un titolo descrittivo.

Traccia corrente:
Titolo/video: ${currentTitle.trim()}
Interprete/canale indicato: ${currentArtist.trim().ifBlank { "sconosciuto" }}

Il tuo compito è identificare direttamente la CANZONE ORIGINALE e l'INTERPRETE ORIGINALE della stessa composizione. La tua risposta sarà usata come riferimento principale: non proporre liste di possibili candidati e non chiedere conferme.

Restituisci inoltre i crediti musicali utili quando li conosci: autori, compositori, parolieri, produttori, etichetta, album/singolo e anno della prima pubblicazione dell'incisione originale. Se un credito non è noto con ragionevole sicurezza, lascialo vuoto invece di inventarlo.

Per original_artists inserisci il cantante, gruppo o gli interpreti accreditati nell'incisione originale. Non inserire l'artista della cover a meno che sia davvero uno degli interpreti originali.

Rispondi SOLO con JSON valido in questa forma:
{
  "title":"titolo canonico della canzone",
  "original_artists":["interprete originale"],
  "year":1965,
  "songwriters":["nome"],
  "composers":["nome"],
  "lyricists":["nome"],
  "producers":["nome"],
  "label":"etichetta oppure null",
  "album":"album o singolo oppure null"
}

Se l'anno non è noto usa null. Se alcuni elenchi di crediti non sono noti usa []."""

        val grounded = executeWithFallbackModels(
            prompt = prompt,
            config = config,
            useGoogleSearch = true,
            maxOutputTokens = 1100,
        )

        val response = grounded ?: executeWithFallbackModels(
            prompt = prompt,
            config = config,
            useGoogleSearch = false,
            maxOutputTokens = 1100,
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
        when (requested) {
            LEGACY_DEFAULT_MODEL -> listOf(LEGACY_DEFAULT_MODEL, CURRENT_DEFAULT_MODEL)
            else -> listOf(requested, CURRENT_DEFAULT_MODEL).distinct()
        }

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
                    put("temperature", 0.15)
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
