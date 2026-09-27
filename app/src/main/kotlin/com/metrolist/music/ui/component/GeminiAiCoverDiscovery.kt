/**
 * MusicLab AI-first cover discovery.
 * Gemini decides cover/remix/live candidates and their editorial metadata.
 * YouTube/YouTube Music are used later only to locate playable media.
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
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal enum class AiCoverCategory {
    COVER,
    REMIX,
    LIVE,
}

internal data class AiCoverOriginalInfo(
    val title: String,
    val artist: String,
    val year: Int? = null,
    val album: String? = null,
    val songwriters: List<String> = emptyList(),
    val composers: List<String> = emptyList(),
    val lyricists: List<String> = emptyList(),
    val producers: List<String> = emptyList(),
    val label: String? = null,
)

internal data class AiCoverCandidate(
    val title: String,
    val artist: String,
    val category: AiCoverCategory,
    val year: Int? = null,
    val album: String? = null,
    val songwriters: List<String> = emptyList(),
    val composers: List<String> = emptyList(),
    val lyricists: List<String> = emptyList(),
    val producers: List<String> = emptyList(),
    val label: String? = null,
) {
    val stableKey: String
        get() = "${category.name}|${canonical(artist)}|${canonical(title)}"
}

internal data class AiCoverDiscoveryResult(
    val original: AiCoverOriginalInfo?,
    val versions: List<AiCoverCandidate>,
)

internal object GeminiAiCoverDiscovery {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val initialCache = ConcurrentHashMap<String, CachedDiscovery>()
    private val expandedCache = ConcurrentHashMap<String, CachedDiscovery>()

    suspend fun discoverInitial(
        originalTitle: String,
        originalArtist: String,
        config: GeminiCoverVerificationConfig,
    ): AiCoverDiscoveryResult = withContext(Dispatchers.IO) {
        discover(
            originalTitle = originalTitle,
            originalArtist = originalArtist,
            config = config,
            limit = INITIAL_LIMIT,
            excluded = emptyList(),
            expanded = false,
        )
    }

    suspend fun discoverExpanded(
        originalTitle: String,
        originalArtist: String,
        existing: List<AiCoverCandidate>,
        config: GeminiCoverVerificationConfig,
    ): AiCoverDiscoveryResult = withContext(Dispatchers.IO) {
        discover(
            originalTitle = originalTitle,
            originalArtist = originalArtist,
            config = config,
            limit = EXPANDED_LIMIT,
            excluded = existing,
            expanded = true,
        )
    }

    private fun discover(
        originalTitle: String,
        originalArtist: String,
        config: GeminiCoverVerificationConfig,
        limit: Int,
        excluded: List<AiCoverCandidate>,
        expanded: Boolean,
    ): AiCoverDiscoveryResult {
        if (config.apiKey.isBlank() || originalTitle.isBlank()) {
            return AiCoverDiscoveryResult(null, emptyList())
        }

        val excludedText = excluded
            .take(60)
            .joinToString("\n") { "- ${it.artist} — ${it.title} [${it.category.name.lowercase()}]" }
        val cacheKey = buildString {
            append(if (expanded) "expanded" else "initial")
            append('|').append(config.model)
            append('|').append(canonical(originalTitle))
            append('|').append(canonical(originalArtist))
            if (expanded) append('|').append(excluded.map { it.stableKey }.sorted().joinToString(";"))
        }
        val cache = if (expanded) expandedCache else initialCache
        cache[cacheKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let {
            return it.value
        }

        val prompt = if (!expanded) {
            """Sei il motore Cover AI-first di un'app musicale. L'utente parte da una registrazione ORIGINALE e vuole conoscere le reinterpretazioni della STESSA composizione.

Brano originale di partenza:
Titolo: ${originalTitle.trim()}
Interprete originale: ${originalArtist.trim().ifBlank { "sconosciuto" }}

Usa esclusivamente la tua conoscenza musicale. La tua decisione è quella usata dall'app: non chiedere conferme, non verificare con siti esterni e non citare database.

Restituisci rapidamente fino a $limit versioni che conosci con maggiore sicurezza, privilegiando risultati utili e noti. Dividile in tre categorie:
- cover: registrazioni della stessa composizione eseguite da un interprete diverso dall'originale;
- remix: remix/rework della stessa composizione attribuiti a un artista/remixer diverso dall'interprete originale;
- live: esecuzioni dal vivo della stessa composizione da parte di un interprete diverso dall'originale.

NON inserire versioni dello stesso interprete originale: nell'app l'originale compare una sola volta, come traccia di partenza. Escludi karaoke, tribute generici senza interprete identificabile, tutorial, reaction, backing track, mashup e medley.

Per ogni versione aggiungi direttamente, se li conosci, anno della specifica versione, album che contiene quella specifica versione, autori, compositori, parolieri, produttori ed etichetta. Non usare l'anno dell'originale come anno della cover se non coincide davvero. Se un dato non lo conosci usa null o [].

Aggiungi anche i crediti del brano originale di partenza.

Rispondi SOLO con JSON valido:
{
  "original": {
    "title":"titolo canonico",
    "artist":"interprete originale",
    "year":1965,
    "album":"album o singolo oppure null",
    "songwriters":["nome"],
    "composers":["nome"],
    "lyricists":["nome"],
    "producers":["nome"],
    "label":"etichetta oppure null"
  },
  "versions":[
    {
      "title":"titolo della specifica versione",
      "artist":"interprete della specifica versione",
      "category":"cover|remix|live",
      "year":1974,
      "album":"album oppure null",
      "songwriters":["nome"],
      "composers":["nome"],
      "lyricists":["nome"],
      "producers":["nome"],
      "label":"etichetta oppure null"
    }
  ]
}"""
        } else {
            """Sei il motore Cover AI-first di un'app musicale. Continua la ricerca usando esclusivamente la tua conoscenza musicale.

Composizione originale:
Titolo: ${originalTitle.trim()}
Interprete originale: ${originalArtist.trim().ifBlank { "sconosciuto" }}

Sono già state trovate queste versioni e NON devi ripeterle:
${excludedText.ifBlank { "(nessuna)" }}

Trova fino a $limit ULTERIORI versioni reali della stessa composizione che conosci: cover di altri interpreti, remix/rework attribuiti ad altri artisti/remixer, oppure esecuzioni live di altri interpreti. Non inserire l'interprete originale, karaoke, tutorial, reaction, backing track, mashup o medley.

Per ciascuna versione aggiungi direttamente anno, album della specifica versione e crediti che conosci. Non verificare con siti esterni. Se non conosci un dato usa null o [].

Rispondi SOLO con JSON valido nello stesso formato:
{
  "original": null,
  "versions":[
    {
      "title":"titolo della specifica versione",
      "artist":"interprete",
      "category":"cover|remix|live",
      "year":1974,
      "album":"album oppure null",
      "songwriters":["nome"],
      "composers":["nome"],
      "lyricists":["nome"],
      "producers":["nome"],
      "label":"etichetta oppure null"
    }
  ]
}"""
        }

        val text = executeWithFallbackModels(
            config = config,
            prompt = prompt,
            maxOutputTokens = if (expanded) 6000 else 2200,
        ) ?: return AiCoverDiscoveryResult(null, emptyList())

        val parsed = parse(text, originalArtist)
        val filtered = parsed.copy(
            versions = parsed.versions
                .filterNot { sameArtist(it.artist, originalArtist) }
                .filterNot { DISALLOWED.containsMatchIn(it.title.lowercase()) }
                .distinctBy { it.stableKey }
                .take(limit),
        )
        cache[cacheKey] = CachedDiscovery(
            value = filtered,
            expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
        )
        return filtered
    }

    private fun executeWithFallbackModels(
        config: GeminiCoverVerificationConfig,
        prompt: String,
        maxOutputTokens: Int,
    ): String? {
        val models = listOf(config.model.trim(), CURRENT_MODEL, LEGACY_MODEL)
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
                        put("temperature", 0.15)
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
                    val root = response.body?.string()?.let { json.parseToJsonElement(it).jsonObject }
                        ?: return@use null
                    root["candidates"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("content")
                        ?.jsonObject
                        ?.get("parts")
                        ?.jsonArray
                        ?.mapNotNull { part ->
                            part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                        }
                        ?.joinToString("\n")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            if (!responseText.isNullOrBlank()) return responseText
        }
        return null
    }

    private fun parse(text: String, fallbackOriginalArtist: String): AiCoverDiscoveryResult {
        val root = extractObject(text) ?: return AiCoverDiscoveryResult(null, emptyList())
        val originalObject = root["original"]?.runCatching { jsonObject }?.getOrNull()
        val original = originalObject?.let { obj ->
            val title = obj.string("title")
            val artist = obj.string("artist").ifBlank { fallbackOriginalArtist }
            if (title.isBlank() && artist.isBlank()) {
                null
            } else {
                AiCoverOriginalInfo(
                    title = title,
                    artist = artist,
                    year = obj.year("year"),
                    album = obj.nullableString("album"),
                    songwriters = obj.strings("songwriters"),
                    composers = obj.strings("composers"),
                    lyricists = obj.strings("lyricists"),
                    producers = obj.strings("producers"),
                    label = obj.nullableString("label"),
                )
            }
        }

        val versions = root["versions"]
            ?.runCatching { jsonArray }
            ?.getOrNull()
            ?.mapNotNull { element ->
                val obj = element.runCatching { jsonObject }.getOrNull() ?: return@mapNotNull null
                val title = obj.string("title")
                val artist = obj.string("artist")
                val category = when (obj.string("category").lowercase()) {
                    "cover" -> AiCoverCategory.COVER
                    "remix", "mix", "rework" -> AiCoverCategory.REMIX
                    "live", "dal vivo" -> AiCoverCategory.LIVE
                    else -> AiCoverCategory.COVER
                }
                if (title.isBlank() || artist.isBlank()) return@mapNotNull null
                AiCoverCandidate(
                    title = title,
                    artist = artist,
                    category = category,
                    year = obj.year("year"),
                    album = obj.nullableString("album"),
                    songwriters = obj.strings("songwriters"),
                    composers = obj.strings("composers"),
                    lyricists = obj.strings("lyricists"),
                    producers = obj.strings("producers"),
                    label = obj.nullableString("label"),
                )
            }
            .orEmpty()

        return AiCoverDiscoveryResult(original, versions)
    }

    private fun extractObject(text: String): JsonObject? {
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
        get(key)?.runCatching { jsonPrimitive }?.getOrNull()?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.nullableString(key: String): String? =
        string(key).takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private fun JsonObject.strings(key: String): List<String> {
        val element = get(key) ?: return emptyList()
        val array = element.runCatching { jsonArray }.getOrNull()
        if (array != null) {
            return array.mapNotNull { item ->
                item.runCatching { jsonPrimitive }.getOrNull()?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            }.distinct()
        }
        return element.runCatching { jsonPrimitive }.getOrNull()?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?.let(::listOf)
            .orEmpty()
    }

    private fun JsonObject.year(key: String): Int? {
        val p = get(key)?.runCatching { jsonPrimitive }?.getOrNull() ?: return null
        val value = p.intOrNull ?: p.contentOrNull?.toIntOrNull()
        return value?.takeIf { it in 1800..2100 }
    }

    private fun sameArtist(a: String, b: String): Boolean {
        val ca = canonical(a).removePrefix("the ")
        val cb = canonical(b).removePrefix("the ")
        if (ca.isBlank() || cb.isBlank()) return false
        return ca == cb || ca.contains(" $cb ") || cb.contains(" $ca ")
    }

    private data class CachedDiscovery(
        val value: AiCoverDiscoveryResult,
        val expiresAtMs: Long,
    )

    private val DISALLOWED = Regex("\\b(karaoke|reaction|tutorial|lesson|backing track|instrumental backing|mashup|medley)\\b")
    private const val INITIAL_LIMIT = 10
    private const val EXPANDED_LIMIT = 48
    private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    private const val CURRENT_MODEL = "gemini-3.5-flash-lite"
    private const val LEGACY_MODEL = "gemini-2.5-flash-lite"
}

private fun canonical(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
