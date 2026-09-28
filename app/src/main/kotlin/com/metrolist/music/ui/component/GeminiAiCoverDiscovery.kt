/**
 * MusicLab AI-first cover discovery.
 * Gemini decides candidates and all editorial metadata.
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
    LIVE,
    REMIX,
    FOREIGN,
}

internal data class AiCoverOriginalInfo(
    val title: String,
    val artist: String,
    val year: Int? = null,
    val album: String? = null,
    val language: String? = null,
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
    val language: String? = null,
    val year: Int? = null,
    val album: String? = null,
    val songwriters: List<String> = emptyList(),
    val composers: List<String> = emptyList(),
    val lyricists: List<String> = emptyList(),
    val producers: List<String> = emptyList(),
    val label: String? = null,
) {
    val stableKey: String
        get() = "${category.name}|${canonical(artist)}|${canonical(title)}|${canonical(language.orEmpty())}"
}

internal data class AiCoverDiscoveryResult(
    val original: AiCoverOriginalInfo?,
    val versions: List<AiCoverCandidate>,
)

internal object GeminiAiCoverDiscovery {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(28, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val initialCache = ConcurrentHashMap<String, CachedDiscovery>()
    private val expandedCache = ConcurrentHashMap<String, CachedDiscovery>()

    /** Prima corsia: pochi dati, massimo cinque cover studio, per mostrare risultati subito. */
    suspend fun discoverInitial(
        originalTitle: String,
        originalArtist: String,
        config: GeminiCoverVerificationConfig,
    ): AiCoverDiscoveryResult = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank() || originalTitle.isBlank()) {
            return@withContext AiCoverDiscoveryResult(null, emptyList())
        }
        val cacheKey = "initial11|${config.model}|${canonical(originalTitle)}|${canonical(originalArtist)}"
        initialCache[cacheKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let {
            return@withContext it.value
        }

        val prompt = """Sei il cervello musicale AI-first di MusicLab.
La tua risposta è l'autorità editoriale: YouTube/YouTube Music non decidono metadati, artista, album o crediti.

Composizione di partenza:
Titolo: ${originalTitle.trim()}
Interprete indicato: ${originalArtist.trim().ifBlank { "sconosciuto" }}

PRIMA RISPOSTA VELOCE: restituisci al massimo $INITIAL_LIMIT cover IN STUDIO reali e sicure della stessa composizione, eseguite da artisti diversi dall'originale. Privilegia velocità e affidabilità; distribuisci le versioni nel tempo quando possibile.
Non inserire live, remix, karaoke, reaction, tutorial, backing track, mashup o medley.
Identifica anche il vero originale canonico.
Per questa prima risposta titolo, artista, categoria, lingua e anno sono prioritari; album e crediti possono essere null o vuoti se richiedono più tempo.

Rispondi SOLO JSON:
{
 "original":{"title":"","artist":"","year":null,"album":null,"language":null,"songwriters":[],"composers":[],"lyricists":[],"producers":[],"label":null},
 "versions":[{"title":"","artist":"","category":"cover","language":null,"year":null,"album":null,"songwriters":[],"composers":[],"lyricists":[],"producers":[],"label":null}]
}"""

        val text = executeWithFallbackModels(config, prompt, 2100, false)
            ?: return@withContext AiCoverDiscoveryResult(null, emptyList())
        val result = sanitize(parse(text, originalArtist), originalArtist, AiCoverCategory.COVER, INITIAL_LIMIT)
        initialCache[cacheKey] = CachedDiscovery(result, System.currentTimeMillis() + CACHE_TTL_MS)
        result
    }

    /** Compatibilità con LAB10: raccoglie tutti i batch progressivi in un unico risultato. */
    suspend fun discoverExpanded(
        originalTitle: String,
        originalArtist: String,
        existing: List<AiCoverCandidate>,
        config: GeminiCoverVerificationConfig,
    ): AiCoverDiscoveryResult {
        val cacheKey = "expanded11|${config.model}|${canonical(originalTitle)}|${canonical(originalArtist)}"
        expandedCache[cacheKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let { return it.value }
        val additions = mutableListOf<AiCoverCandidate>()
        discoverExpandedBatches(originalTitle, originalArtist, existing, config) { batch ->
            additions += batch
        }
        val result = AiCoverDiscoveryResult(null, additions.distinctBy { it.stableKey }.take(MAX_TOTAL_CANDIDATES))
        expandedCache[cacheKey] = CachedDiscovery(result, System.currentTimeMillis() + CACHE_TTL_MS)
        return result
    }

    /**
     * Ricerca vera della LAB11: emette ogni gruppo appena pronto. La UI può risolvere
     * il playback del batch mentre gli altri filoni AI continuano in background.
     */
    suspend fun discoverExpandedBatches(
        originalTitle: String,
        originalArtist: String,
        existing: List<AiCoverCandidate>,
        config: GeminiCoverVerificationConfig,
        onBatch: suspend (List<AiCoverCandidate>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank() || originalTitle.isBlank()) return@withContext

        val collected = linkedMapOf<String, AiCoverCandidate>()
        existing.forEach { collected[it.stableKey] = it }
        var emptyPasses = 0

        for (roundGroup in RESEARCH_ROUNDS.chunked(CONCURRENT_RESEARCH)) {
            if (collected.size >= MAX_TOTAL_CANDIDATES || emptyPasses >= MAX_EMPTY_PASSES) break
            val snapshot = collected.values.toList()
            val results = coroutineScope {
                roundGroup.map { focus ->
                    async(Dispatchers.IO) {
                        discoverResearchRound(originalTitle, originalArtist, snapshot, focus, config)
                    }
                }.awaitAll()
            }

            var addedInGroup = 0
            for (batch in results) {
                val fresh = batch.filter { !collected.containsKey(it.stableKey) }
                    .take((MAX_TOTAL_CANDIDATES - collected.size).coerceAtLeast(0))
                fresh.forEach { collected[it.stableKey] = it }
                if (fresh.isNotEmpty()) {
                    addedInGroup += fresh.size
                    onBatch(fresh.sortedWith(candidateOrder))
                }
            }
            emptyPasses = if (addedInGroup == 0) emptyPasses + 1 else 0
        }
    }

    private fun discoverResearchRound(
        originalTitle: String,
        originalArtist: String,
        existing: List<AiCoverCandidate>,
        focus: ResearchFocus,
        config: GeminiCoverVerificationConfig,
    ): List<AiCoverCandidate> {
        val excluded = existing.take(150).joinToString("\n") {
            "- ${it.artist} — ${it.title} [${it.category.name.lowercase()}${it.language?.let { l -> ", $l" }.orEmpty()}]"
        }

        val prompt = """Sei il motore musicale AI-first centrale di MusicLab.
Devi trovare versioni REALI della stessa composizione. Sei TU a decidere identità, categoria e metadati. Non chiedere a YouTube di decidere nulla.

Composizione:
Titolo: ${originalTitle.trim()}
Interprete originale: ${originalArtist.trim().ifBlank { "sconosciuto" }}

FOCUS DI QUESTA PASSATA:
${focus.instructions}

Regole di classificazione ESCLUSIVE:
- cover = incisione/registrazione IN STUDIO pubblicata da un interprete diverso dall'originale, nella lingua dell'originale;
- live = esecuzione non da studio: concerto, TV, radio, sessione, festival o performance video;
- remix = remix/rework/club mix/radio mix/extended mix;
- straniera = incisione IN STUDIO in una lingua diversa dall'originale, inclusi adattamenti con titolo tradotto o totalmente diverso.
Se si sovrappongono caratteristiche usa la precedenza: remix > live > straniera > cover.
Escludi l'interprete originale, karaoke, reaction, tutorial, backing track, mashup, medley e tribute anonimi.
Non inventare versioni per raggiungere un numero. Cerca anche versioni poco note e storiche. Restituisci fino a ${focus.limit} elementi nuovi.
Prima scopri titolo/artista/categoria/lingua/anno; album e crediti vanno aggiunti se li conosci senza rallentare inutilmente.

Già note, da NON ripetere:
${excluded.ifBlank { "(nessuna)" }}

Rispondi SOLO JSON:
{"original":null,"versions":[{"title":"","artist":"","category":"cover|live|remix|straniera","language":null,"year":null,"album":null,"songwriters":[],"composers":[],"lyricists":[],"producers":[],"label":null}]}
"""

        val text = executeWithFallbackModels(config, prompt, 3500, focus.deepResearch) ?: return emptyList()
        return sanitize(parse(text, originalArtist), originalArtist, focus.category, focus.limit).versions
    }

    private fun sanitize(
        parsed: AiCoverDiscoveryResult,
        originalArtist: String,
        forcedCategory: AiCoverCategory?,
        limit: Int,
    ): AiCoverDiscoveryResult {
        val versions = parsed.versions.asSequence()
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
        val models = listOf(config.model.trim(), CURRENT_MODEL, LEGACY_MODEL).filter { it.isNotBlank() }.distinct()
        for (model in models) {
            val body = buildJsonObject {
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
                    })
                })
                if (useGoogleSearch) {
                    put("tools", buildJsonArray { add(buildJsonObject { put("google_search", buildJsonObject {}) }) })
                }
                put("generationConfig", buildJsonObject {
                    put("maxOutputTokens", maxOutputTokens)
                    put("temperature", 0.1)
                    put("responseMimeType", "application/json")
                })
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
                    val root = response.body?.string()?.let { json.parseToJsonElement(it).jsonObject } ?: return@use null
                    root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("content")?.jsonObject?.get("parts")?.jsonArray
                        ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                        ?.joinToString("\n")?.trim()?.takeIf { it.isNotBlank() }
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
                language = obj.nullableString("language"),
                songwriters = obj.strings("songwriters"),
                composers = obj.strings("composers"),
                lyricists = obj.strings("lyricists"),
                producers = obj.strings("producers"),
                label = obj.nullableString("label"),
            )
        }

        val versions = root["versions"]?.runCatching { jsonArray }?.getOrNull()?.mapNotNull { element ->
            val obj = element.runCatching { jsonObject }.getOrNull() ?: return@mapNotNull null
            val title = obj.string("title")
            val artist = obj.string("artist")
            if (title.isBlank() || artist.isBlank()) return@mapNotNull null
            val category = when (obj.string("category").lowercase()) {
                "live", "dal vivo" -> AiCoverCategory.LIVE
                "remix", "mix", "rework" -> AiCoverCategory.REMIX
                "straniera", "foreign", "adattamento", "adaptation" -> AiCoverCategory.FOREIGN
                else -> AiCoverCategory.COVER
            }
            AiCoverCandidate(
                title = title,
                artist = artist,
                category = category,
                language = obj.nullableString("language"),
                year = obj.year("year"),
                album = obj.nullableString("album"),
                songwriters = obj.strings("songwriters"),
                composers = obj.strings("composers"),
                lyricists = obj.strings("lyricists"),
                producers = obj.strings("producers"),
                label = obj.nullableString("label"),
            )
        }.orEmpty()
        return AiCoverDiscoveryResult(original, versions)
    }

    private fun extractObject(text: String): JsonObject? {
        val cleaned = text.replace("```json", "", ignoreCase = true).replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.runCatching { jsonPrimitive }?.getOrNull()?.contentOrNull?.trim().orEmpty()
    private fun JsonObject.nullableString(key: String): String? =
        string(key).takeIf { it.isNotBlank() && !it.equals("null", true) }
    private fun JsonObject.strings(key: String): List<String> {
        val element = get(key) ?: return emptyList()
        val array = element.runCatching { jsonArray }.getOrNull()
        if (array != null) return array.mapNotNull {
            it.runCatching { jsonPrimitive }.getOrNull()?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        }.distinct()
        return element.runCatching { jsonPrimitive }.getOrNull()?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", true) }?.let(::listOf).orEmpty()
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
        val limit: Int = 14,
        val deepResearch: Boolean = false,
    )
    private data class CachedDiscovery(val value: AiCoverDiscoveryResult, val expiresAtMs: Long)

    private val candidateOrder = compareBy<AiCoverCandidate> { if (it.year == null) 1 else 0 }
        .thenBy { it.year ?: Int.MAX_VALUE }.thenBy { it.artist.lowercase() }

    private val RESEARCH_ROUNDS = listOf(
        ResearchFocus(AiCoverCategory.COVER, "Cover da studio dalle prime reinterpretazioni fino al 1969. Cerca anche incisioni rare e regionali."),
        ResearchFocus(AiCoverCategory.COVER, "Cover da studio degli anni 1970 e 1980, incluse pubblicazioni meno note."),
        ResearchFocus(AiCoverCategory.COVER, "Cover da studio degli anni 1990 e 2000."),
        ResearchFocus(AiCoverCategory.COVER, "Cover da studio dal 2010 a oggi e recupero di quelle rimaste fuori."),
        ResearchFocus(AiCoverCategory.FOREIGN, "Adattamenti e cover in inglese, francese e spagnolo. Cerca anche titoli tradotti o completamente diversi.", deepResearch = true),
        ResearchFocus(AiCoverCategory.FOREIGN, "Adattamenti e cover in portoghese, tedesco, olandese, lingue nordiche e greco.", deepResearch = true),
        ResearchFocus(AiCoverCategory.FOREIGN, "Europa orientale e Balcani: polacco, ceco, slovacco, ungherese, rumeno, bulgaro, croato, serbo, sloveno, bosniaco, albanese e altre lingue documentate.", deepResearch = true),
        ResearchFocus(AiCoverCategory.FOREIGN, "Russo, ucraino, baltico, turco, ebraico, arabo e altre versioni mediorientali documentate.", deepResearch = true),
        ResearchFocus(AiCoverCategory.FOREIGN, "Asia e resto del mondo: giapponese, coreano, cinese e qualsiasi altra lingua/adattamento reale rimasto fuori.", deepResearch = true),
        ResearchFocus(AiCoverCategory.LIVE, "Performance live storiche: concerti, festival, TV, radio e sessioni fino alla fine degli anni 1980."),
        ResearchFocus(AiCoverCategory.LIVE, "Performance live dal 1990 a oggi, comprese sessioni e video-performance identificabili."),
        ResearchFocus(AiCoverCategory.REMIX, "Remix e rework reali di qualsiasi epoca: club, extended, radio, dance e altre versioni attribuite."),
        ResearchFocus(AiCoverCategory.COVER, "Passata di recupero: quali cover studio reali della composizione non sono ancora nella lista? Cerca per decennio, artista e pubblicazione.", 18, true),
        ResearchFocus(AiCoverCategory.FOREIGN, "Passata finale: quali adattamenti linguistici reali mancano ancora? Non inventare per riempire la lista.", 18, true),
        ResearchFocus(AiCoverCategory.LIVE, "Passata finale: quali live di altri artisti mancano ancora?", 16, true),
        ResearchFocus(AiCoverCategory.REMIX, "Passata finale: quali remix/rework mancano ancora?", 16, true),
    )

    private val DISALLOWED = Regex("\\b(karaoke|reaction|tutorial|lesson|backing track|instrumental backing|mashup|medley)\\b")
    private const val INITIAL_LIMIT = 5
    private const val MAX_TOTAL_CANDIDATES = 150
    private const val CONCURRENT_RESEARCH = 3
    private const val MAX_EMPTY_PASSES = 2
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
