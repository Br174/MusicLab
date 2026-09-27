/**
 * MusicLab AI-first cover discovery.
 * Gemini decides cover/remix/live candidates and their editorial metadata.
 * YouTube/YouTube Music are used later only to locate playable media.
 */
package com.metrolist.music.ui.component

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val initialCache = ConcurrentHashMap<String, CachedDiscovery>()
    private val expandedCache = ConcurrentHashMap<String, CachedDiscovery>()

    /**
     * Fast first pass: only five strong studio covers. The UI can resolve and show them
     * immediately while the exhaustive research continues in background.
     */
    suspend fun discoverInitial(
        originalTitle: String,
        originalArtist: String,
        config: GeminiCoverVerificationConfig,
    ): AiCoverDiscoveryResult = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank() || originalTitle.isBlank()) {
            return@withContext AiCoverDiscoveryResult(null, emptyList())
        }

        val cacheKey = "initial10|${config.model}|${canonical(originalTitle)}|${canonical(originalArtist)}"
        initialCache[cacheKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let {
            return@withContext it.value
        }

        val prompt = """Sei il motore Cover AI-first di un'app musicale.
L'utente parte dalla registrazione ORIGINALE e vuole vedere subito alcune cover autentiche della STESSA composizione.

Brano originale:
Titolo: ${originalTitle.trim()}
Interprete originale: ${originalArtist.trim().ifBlank { "sconosciuto" }}

La tua decisione è definitiva per l'app. Non chiedere conferme.
Per questa PRIMA RISPOSTA restituisci al massimo $INITIAL_LIMIT cover DA STUDIO molto sicure, preferendo le più note e, se possibile, includendo anche una delle prime storicamente.

Regole categoriche:
- cover = SOLO incisione/registrazione in studio pubblicata da un interprete diverso dall'originale;
- live = NON va inserita in questa prima risposta;
- remix = NON va inserito in questa prima risposta.

Escludi l'interprete originale, karaoke, tribute generici, tutorial, reaction, backing track, mashup e medley.
Per ogni cover aggiungi direttamente, se li conosci: anno della specifica incisione, album, autori, compositori, parolieri, produttori ed etichetta. Se un dato non lo conosci usa null o [].
Aggiungi anche i crediti del brano originale.

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
      "title":"titolo della specifica cover",
      "artist":"interprete",
      "category":"cover",
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

        val text = executeWithFallbackModels(
            config = config,
            prompt = prompt,
            maxOutputTokens = 2200,
            useGoogleSearch = false,
        ) ?: return@withContext AiCoverDiscoveryResult(null, emptyList())

        val parsed = parse(text, originalArtist)
        val filtered = sanitize(parsed, originalArtist, AiCoverCategory.COVER, INITIAL_LIMIT)
        initialCache[cacheKey] = CachedDiscovery(filtered, System.currentTimeMillis() + CACHE_TTL_MS)
        filtered
    }

    /**
     * Exhaustive second phase. The AI researches by period/category instead of asking one
     * oversized question. Two research rounds run at a time to keep the wall-clock time
     * reasonable while avoiding a burst of requests. The final set is capped at 100 candidates.
     */
    suspend fun discoverExpanded(
        originalTitle: String,
        originalArtist: String,
        existing: List<AiCoverCandidate>,
        config: GeminiCoverVerificationConfig,
    ): AiCoverDiscoveryResult = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank() || originalTitle.isBlank()) {
            return@withContext AiCoverDiscoveryResult(null, emptyList())
        }

        val cacheKey = "expanded10|${config.model}|${canonical(originalTitle)}|${canonical(originalArtist)}"
        expandedCache[cacheKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let {
            return@withContext it.value
        }

        val collected = linkedMapOf<String, AiCoverCandidate>()
        existing.forEach { collected[it.stableKey] = it }
        val originalExistingKeys = existing.map { it.stableKey }.toSet()

        for (roundBatch in RESEARCH_ROUNDS.chunked(2)) {
            if (collected.size >= MAX_TOTAL_CANDIDATES) break

            val excludedSnapshot = collected.values.toList()
            val results = coroutineScope {
                roundBatch.map { focus ->
                    async(Dispatchers.IO) {
                        discoverResearchRound(
                            originalTitle = originalTitle,
                            originalArtist = originalArtist,
                            existing = excludedSnapshot,
                            focus = focus,
                            config = config,
                        )
                    }
                }.awaitAll()
            }

            results.flatten().forEach { candidate ->
                if (collected.size < MAX_TOTAL_CANDIDATES) {
                    collected.putIfAbsent(candidate.stableKey, candidate)
                }
            }
        }

        val additions = collected.values
            .filter { it.stableKey !in originalExistingKeys }
            .sortedWith(candidateOrder)
            .take((MAX_TOTAL_CANDIDATES - existing.size).coerceAtLeast(0))

        val result = AiCoverDiscoveryResult(original = null, versions = additions)
        expandedCache[cacheKey] = CachedDiscovery(result, System.currentTimeMillis() + CACHE_TTL_MS)
        result
    }

    private fun discoverResearchRound(
        originalTitle: String,
        originalArtist: String,
        existing: List<AiCoverCandidate>,
        focus: ResearchFocus,
        config: GeminiCoverVerificationConfig,
    ): List<AiCoverCandidate> {
        val excludedText = existing
            .take(100)
            .joinToString("\n") { "- ${it.artist} — ${it.title} [${it.category.name.lowercase()}]" }

        val prompt = """Sei un ricercatore discografico AI e sei il motore definitivo della funzione Cerca cover.
Devi trovare MOLTE versioni reali della STESSA composizione, non solo le più famose.

Composizione originale:
Titolo: ${originalTitle.trim()}
Interprete originale: ${originalArtist.trim().ifBlank { "sconosciuto" }}

FOCUS DI QUESTA PASSATA:
${focus.instructions}

Ricerca in modo ampio: discografie, pubblicazioni, archivi musicali, pagine di artisti, esecuzioni documentate e fonti reperibili tramite la ricerca Google quando disponibile. I siti sono soltanto materiale di ricerca: la decisione finale su cosa inserire, categoria, anno, album e crediti è TUA. Non chiedere conferme e non richiedere verifiche successive all'app.

Classificazione obbligatoria:
- cover = SOLO incisione/registrazione IN STUDIO pubblicata da un interprete diverso dall'originale. Se è una performance dal vivo NON chiamarla cover: mettila in live.
- remix = remix, rework, club/radio/extended mix della stessa composizione, attribuito a un remixer/artista diverso dall'interprete originale.
- live = esecuzione dal vivo, concerto, festival, sessione, TV, radio, locale, video-performance o altra registrazione NON da studio della stessa composizione eseguita da un interprete diverso dall'originale.

NON inserire versioni dello stesso interprete originale. Escludi karaoke, tutorial, reaction, backing track, mashup, medley e tribute anonimi.
Cerca anche versioni poco note e molto vecchie. Non limitarti alle prime che ricordi. Per questa passata restituisci fino a ${focus.limit} elementi pertinenti.
Per ogni versione aggiungi anno della specifica versione, album/pubblicazione se la conosci e crediti (autori, compositori, parolieri, produttori, etichetta). Se un dato è ignoto usa null o [].

Versioni già note da NON ripetere:
${excludedText.ifBlank { "(nessuna)" }}

Rispondi SOLO con JSON valido:
{
  "original": null,
  "versions":[
    {
      "title":"titolo specifico",
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

        val text = executeWithFallbackModels(
            config = config,
            prompt = prompt,
            maxOutputTokens = 3600,
            useGoogleSearch = true,
        ) ?: return emptyList()

        return sanitize(parse(text, originalArtist), originalArtist, focus.category, focus.limit).versions
    }

    private fun sanitize(
        parsed: AiCoverDiscoveryResult,
        originalArtist: String,
        forcedCategory: AiCoverCategory?,
        limit: Int,
    ): AiCoverDiscoveryResult {
        val versions = parsed.versions
            .asSequence()
            .filterNot { sameArtist(it.artist, originalArtist) }
            .filterNot { DISALLOWED.containsMatchIn(it.title.lowercase()) }
            .map { candidate -> if (forcedCategory != null) candidate.copy(category = forcedCategory) else candidate }
            .distinctBy { it.stableKey }
            .take(limit)
            .toList()
        return parsed.copy(versions = versions)
    }

    private fun executeWithFallbackModels(
        config: GeminiCoverVerificationConfig,
        prompt: String,
        maxOutputTokens: Int,
        useGoogleSearch: Boolean,
    ): String? {
        val searched = executeModels(config, prompt, maxOutputTokens, useGoogleSearch)
        if (searched != null || !useGoogleSearch) return searched
        return executeModels(config, prompt, maxOutputTokens, false)
    }

    private fun executeModels(
        config: GeminiCoverVerificationConfig,
        prompt: String,
        maxOutputTokens: Int,
        useGoogleSearch: Boolean,
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
                        put("temperature", 0.12)
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
                        ?.mapNotNull { part -> part.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
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
            if (title.isBlank() && artist.isBlank()) null else AiCoverOriginalInfo(
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

    private data class ResearchFocus(
        val category: AiCoverCategory,
        val instructions: String,
        val limit: Int = 16,
    )

    private data class CachedDiscovery(
        val value: AiCoverDiscoveryResult,
        val expiresAtMs: Long,
    )

    private val candidateOrder = compareBy<AiCoverCandidate> { if (it.year == null) 1 else 0 }
        .thenBy { it.year ?: Int.MAX_VALUE }
        .thenBy { it.artist.lowercase() }

    private val RESEARCH_ROUNDS = listOf(
        ResearchFocus(
            AiCoverCategory.COVER,
            "COVER DA STUDIO più antiche: cerca dalle primissime reinterpretazioni fino alla fine degli anni 1960. Includi incisioni rare, internazionali e non solo i nomi famosi. Se il brano originale è successivo a questo periodo, restituisci solo ciò che storicamente può esistere.",
        ),
        ResearchFocus(
            AiCoverCategory.COVER,
            "COVER DA STUDIO pubblicate negli anni 1970 e 1980. Cerca in modo ampio tra singoli, album, compilation ufficiali e discografie di artisti.",
        ),
        ResearchFocus(
            AiCoverCategory.COVER,
            "COVER DA STUDIO pubblicate negli anni 1990 e 2000. Cerca anche reinterpretazioni meno note, internazionali e versioni presenti in album.",
        ),
        ResearchFocus(
            AiCoverCategory.COVER,
            "COVER DA STUDIO dal 2010 a oggi, più eventuali cover da studio di qualsiasi epoca rimaste fuori nelle passate precedenti. Cerca profondamente e non fermarti ai risultati più popolari.",
        ),
        ResearchFocus(
            AiCoverCategory.LIVE,
            "ESECUZIONI LIVE storiche fino alla fine degli anni 1980: concerti, festival, TV, radio, sessioni, locali e performance video documentate. Devono essere eseguite da artisti diversi dall'originale e non essere incisioni in studio.",
        ),
        ResearchFocus(
            AiCoverCategory.LIVE,
            "ESECUZIONI LIVE dal 1990 a oggi: concerti, festival, sessioni, TV/radio, locali, video-performance e altre registrazioni non da studio. Cerca anche artisti meno noti quando la performance è identificabile.",
        ),
        ResearchFocus(
            AiCoverCategory.REMIX,
            "REMIX e REWORK ufficiali o chiaramente attribuiti della stessa composizione, di qualsiasi epoca: club mix, extended mix, radio remix, rework e collaborazioni di remix. Non inserire semplici remaster.",
        ),
        ResearchFocus(
            AiCoverCategory.COVER,
            "PASSATA FINALE ESAUSTIVA sulle COVER DA STUDIO: cerca tutte le versioni rimaste fuori, incluse incisioni rare, regionali, internazionali, soundtrack, album tributo con artista identificato e pubblicazioni poco note. Non ripetere quelle già elencate.",
            limit = 20,
        ),
    )

    private val DISALLOWED = Regex("\\b(karaoke|reaction|tutorial|lesson|backing track|instrumental backing|mashup|medley)\\b")
    private const val INITIAL_LIMIT = 5
    private const val MAX_TOTAL_CANDIDATES = 100
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