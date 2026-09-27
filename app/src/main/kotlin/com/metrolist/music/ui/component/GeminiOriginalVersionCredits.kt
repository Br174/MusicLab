/**
 * AI-only metadata enrichment for playable Originali results.
 * YouTube/YouTube Music provide only playable items; year, album and credits come from Gemini.
 */
package com.metrolist.music.ui.component

import com.metrolist.innertube.models.SongItem
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

internal data class GeminiVersionCredits(
    val year: Int? = null,
    val album: String? = null,
    val songwriters: List<String> = emptyList(),
    val composers: List<String> = emptyList(),
    val lyricists: List<String> = emptyList(),
    val producers: List<String> = emptyList(),
    val label: String? = null,
)

internal object GeminiOriginalVersionCredits {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val cache = ConcurrentHashMap<String, GeminiVersionCredits>()

    suspend fun enrich(
        identity: GeminiOriginalIdentity,
        songs: List<SongItem>,
        config: GeminiCoverVerificationConfig?,
    ): Map<String, GeminiVersionCredits> = withContext(Dispatchers.IO) {
        if (config == null || config.apiKey.isBlank()) return@withContext emptyMap()

        val unique = songs.distinctBy { it.id }.take(MAX_BATCH)
        if (unique.isEmpty()) return@withContext emptyMap()

        val ready = linkedMapOf<String, GeminiVersionCredits>()
        val pending = unique.filter { song ->
            val key = cacheKey(identity, song)
            val cached = cache[key]
            if (cached != null) {
                ready[song.id] = cached
                false
            } else {
                true
            }
        }
        if (pending.isEmpty()) return@withContext ready

        val inputs = buildJsonArray {
            pending.forEach { song ->
                add(
                    buildJsonObject {
                        put("id", song.id)
                        put("title", song.title)
                        put("artists", song.artists.joinToString(", ") { it.name })
                    },
                )
            }
        }

        val prompt = """Sei il motore crediti della funzione Originali di un'app musicale.
L'AI ha già deciso che la composizione è \"${identity.title}\" e che l'interprete originale è ${identity.originalArtists.joinToString(", ")}.

Ricevi qui sotto versioni riproducibili trovate su YouTube/YouTube Music. ATTENZIONE: YouTube e YouTube Music sono soltanto fonti di riproduzione. Non usare o considerare i loro metadati come fonte per anno, album o crediti. Devi aggiungere questi dati esclusivamente in base alla tua conoscenza.

Per ogni elemento identifica la specifica incisione/versione indicata dal titolo e dagli interpreti. Se sai che quella specifica versione è contenuta in un album, indica il nome dell'album. Inserisci anche anno e crediti che conosci. Se un dato non è noto, usa null o []. Non inventare titoli di album.

Input:
$inputs

Rispondi SOLO con JSON valido:
{
  "versions":[
    {
      "id":"id ricevuto",
      "year":1965,
      "album":"nome album oppure null",
      "songwriters":["nome"],
      "composers":["nome"],
      "lyricists":["nome"],
      "producers":["nome"],
      "label":"etichetta oppure null"
    }
  ]
}
"""

        val text = executeWithFallbackModels(
            config = config,
            prompt = prompt,
            maxOutputTokens = 1700,
        ) ?: return@withContext ready

        parse(text, pending.map { it.id }.toSet()).forEach { (id, credits) ->
            val song = pending.firstOrNull { it.id == id } ?: return@forEach
            cache[cacheKey(identity, song)] = credits
            ready[id] = credits
        }
        ready
    }

    private fun executeWithFallbackModels(
        config: GeminiCoverVerificationConfig,
        prompt: String,
        maxOutputTokens: Int,
    ): String? {
        val models = listOf(config.model, CURRENT_DEFAULT_MODEL, LEGACY_DEFAULT_MODEL)
            .filter { it.isNotBlank() }
            .distinct()

        for (model in models) {
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
                put(
                    "generationConfig",
                    buildJsonObject {
                        put("maxOutputTokens", maxOutputTokens)
                        put("temperature", 0.10)
                        put("responseMimeType", "application/json")
                    },
                )
            }

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .addHeader("x-goog-api-key", config.apiKey.trim())
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(mediaType))
                .build()

            val responseText = runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                    val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@use null
                    candidate["content"]?.jsonObject
                        ?.get("parts")?.jsonArray
                        ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                        ?.joinToString("\n")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            if (!responseText.isNullOrBlank()) return responseText
        }
        return null
    }

    private fun parse(
        text: String,
        allowedIds: Set<String>,
    ): Map<String, GeminiVersionCredits> {
        val cleaned = text
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap()
        val root = runCatching {
            json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return emptyMap()

        val versions = root["versions"]?.runCatching { jsonArray }?.getOrNull() ?: return emptyMap()
        return buildMap {
            versions.forEach { element ->
                val item = element.runCatching { jsonObject }.getOrNull() ?: return@forEach
                val id = item.string("id")
                if (id !in allowedIds) return@forEach
                put(
                    id,
                    GeminiVersionCredits(
                        year = item.year("year"),
                        album = item.nullableString("album"),
                        songwriters = item.strings("songwriters"),
                        composers = item.strings("composers"),
                        lyricists = item.strings("lyricists"),
                        producers = item.strings("producers"),
                        label = item.nullableString("label"),
                    ),
                )
            }
        }
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.runCatching { jsonPrimitive }?.getOrNull()?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.nullableString(key: String): String? =
        string(key).takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private fun JsonObject.strings(key: String): List<String> {
        val value = get(key) ?: return emptyList()
        return value.runCatching { jsonArray }.getOrNull()
            ?.mapNotNull { it.runCatching { jsonPrimitive }.getOrNull()?.contentOrNull?.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: emptyList()
    }

    private fun JsonObject.year(key: String): Int? {
        val primitive = get(key)?.runCatching { jsonPrimitive }?.getOrNull() ?: return null
        val value = primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
        return value?.takeIf { it in 1800..2100 }
    }

    private fun cacheKey(identity: GeminiOriginalIdentity, song: SongItem): String =
        listOf(
            identity.title.lowercase(),
            identity.originalArtists.joinToString("|").lowercase(),
            song.title.lowercase(),
            song.artists.joinToString("|") { it.name.lowercase() },
        ).joinToString("::")

    private const val MAX_BATCH = 8
    private const val LEGACY_DEFAULT_MODEL = "gemini-2.5-flash-lite"
    private const val CURRENT_DEFAULT_MODEL = "gemini-3.5-flash-lite"
}
