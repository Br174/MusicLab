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

        val cacheKey = "v3|${config.model}|${currentTitle.trim().lowercase()}|${currentArtist.trim().lowercase()}"
        cache[cacheKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let { return@withContext it.identity }

        val cloudIdentity = if (config.cloudEndpoint.isNotBlank()) {
            CloudMusicDiscovery.identifyOriginal(currentTitle, currentArtist, config)
        } else null

        val directIdentity = if (config.isUsable()) {
            val prompt = """Sei il motore Originali di MusicLab. Lavora per STEP.

STEP 1 — IDENTITÀ CANONICA.
La traccia corrente può essere cover, live, duetto, TV o avere un titolo descrittivo.
Titolo/video: ${currentTitle.trim()}
Interprete/canale: ${currentArtist.trim().ifBlank { "sconosciuto" }}

Stabilisci la COMPOSIZIONE canonica e chi è il vero INTERPRETE ORIGINALE della prima incisione/pubblicazione. Non confondere autore o compositore con interprete, e non assumere che l'artista corrente sia l'originale. Usa la ricerca Google per verificare i casi ambigui.
Per le successive ricerche Originali valgono due ancore obbligatorie: titolo canonico completo come frase autonoma + interprete originale. Maiuscole, accenti e punteggiatura non contano; live, remix, feat., luogo, anno o alias possono essere aggiunti senza invalidare il titolo.

In questo step NON servono crediti completi. Restituisci solo titolo canonico, interprete originale, anno della prima pubblicazione e album/singolo se noto. I crediti saranno caricati soltanto quando l'utente apre Dettagli.

Rispondi SOLO JSON valido:
{
  "title":"titolo canonico",
  "original_artists":["interprete originale"],
  "year":1965,
  "album":"album o singolo oppure null"
}"""
            executeWithFallbackModels(prompt, config, useGoogleSearch = true, maxOutputTokens = 900)?.let(::parseIdentity)
        } else null

        val identity = mergeIdentities(cloudIdentity, directIdentity) ?: return@withContext null
        cache[cacheKey] = CachedIdentity(identity, System.currentTimeMillis() + CACHE_TTL_MS)
        identity
    }

    /**
     * STEP 2/3 di Originali: Gemini suggerisce nuove interrogazioni quando le ricerche
     * standard non hanno ancora prodotto almeno il target di versioni riproducibili.
     */
    suspend fun planVersionQueries(
        identity: GeminiOriginalIdentity,
        existingQueries: List<String>,
        round: Int,
        config: GeminiCoverVerificationConfig?,
    ): List<String> = withContext(Dispatchers.IO) {
        if (config == null || !config.isUsable()) return@withContext emptyList()
        val excluded = existingQueries.take(80).joinToString("\n") { "- $it" }
        val prompt = """Sei il pianificatore di ricerca della funzione Originali di MusicLab.
Composizione: ${identity.title}
Interprete originale: ${identity.originalArtists.joinToString(", ")}
Anno originale: ${identity.year ?: "sconosciuto"}

Siamo al giro ${round + 1}. Dobbiamo localizzare almeno 10 registrazioni/performance REALI della stessa composizione in cui compare l'interprete originale. Non devi inventare risultati: devi produrre QUERY DI RICERCA utili per YouTube Music e YouTube.
Ogni strategia deve mantenere le due ancore: titolo canonico completo come frase autonoma + interprete originale. Sono ammesse aggiunte descrittive, ma non titoli diversi che contengono solo una parte del titolo canonico.

Cerca strategie diverse: incisioni studio/remaster, album e singoli, live, TV/radio, sessioni, duetti/collaborazioni, acoustic/unplugged, remix ufficiali, anni/eventi noti. Se i giri precedenti hanno fallito, cambia strategia.

Query già usate, da NON ripetere:
${excluded.ifBlank { "(nessuna)" }}

Restituisci fino a 18 query complete e concise. Nessun credito.
Rispondi SOLO JSON: {"queries":["query 1","query 2"]}
"""
        val answer = executeWithFallbackModels(
            prompt = prompt,
            config = config,
            useGoogleSearch = round > 0,
            maxOutputTokens = 1400,
        ) ?: return@withContext emptyList()
        parseQueries(answer.text)
            .filter { it.isNotBlank() && it !in existingQueries }
            .distinct()
            .take(18)
    }

    private fun mergeIdentities(
        cloud: GeminiOriginalIdentity?,
        direct: GeminiOriginalIdentity?,
    ): GeminiOriginalIdentity? {
        if (direct == null) return cloud
        if (cloud == null) return direct
        return direct.copy(
            year = direct.year ?: cloud.year,
            album = direct.album ?: cloud.album,
            songwriters = direct.songwriters.ifEmpty { cloud.songwriters },
            composers = direct.composers.ifEmpty { cloud.composers },
            lyricists = direct.lyricists.ifEmpty { cloud.lyricists },
            producers = direct.producers.ifEmpty { cloud.producers },
            label = direct.label ?: cloud.label,
        )
    }

    private fun parseQueries(text: String): List<String> {
        val root = extractJsonObject(text) ?: return emptyList()
        return root["queries"]?.runCatching { jsonArray }?.getOrNull()
            ?.mapNotNull { it.runCatching { jsonPrimitive }.getOrNull()?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
            ?.distinct()
            .orEmpty()
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
