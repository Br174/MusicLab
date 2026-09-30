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

internal enum class AiBrainDecisionStatus {
    APPROVED,
    PROBABLE,
    UNCERTAIN,
    REJECTED;

    companion object {
        fun fromWire(value: String?): AiBrainDecisionStatus? {
            val normalized = value?.trim()?.uppercase().orEmpty()
            if (normalized.isBlank()) return null
            return entries.firstOrNull { it.name == normalized }
        }
    }
}

internal data class AiBrainSignal(
    val kind: String,
    val strength: String,
    val direction: String? = null,
)

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
    val sameWorkScore: Int? = null,
    val versionTypeScore: Int? = null,
    val brainStatus: AiBrainDecisionStatus? = null,
    val brainAdmission: String? = null,
    val brainSignals: List<AiBrainSignal> = emptyList(),
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

    /**
     * STEP 1: stabilisce la composizione canonica e raccoglie un primo gruppo leggero.
     * Cloudflare e Gemini non sono più alternativi: le due risposte vengono unite.
     * In questa fase chiediamo solo i metadati essenziali; i crediti completi sono on-demand.
     */
    suspend fun discoverInitial(
        originalTitle: String,
        originalArtist: String,
        config: GeminiCoverVerificationConfig,
    ): AiCoverDiscoveryResult = withContext(Dispatchers.IO) {
        if (originalTitle.isBlank() || (config.apiKey.isBlank() && config.cloudEndpoint.isBlank())) {
            return@withContext AiCoverDiscoveryResult(null, emptyList())
        }
        val cacheKey = "initial17|${config.model}|${canonical(originalTitle)}|${canonical(originalArtist)}"
        initialCache[cacheKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let {
            return@withContext it.value
        }

        val cloud = if (config.cloudEndpoint.isNotBlank()) {
            CloudMusicDiscovery.discoverCover(
                title = originalTitle,
                artist = originalArtist,
                config = config,
                phase = "initial",
                focus = "STEP 1: identifica con precisione la composizione canonica e il vero interprete originale; poi restituisci prime cover studio reali con soli titolo, artista, anno e album essenziali.",
            )
        } else null

        val direct = if (config.apiKey.isNotBlank()) {
            val prompt = """Sei il motore musicale AI-first di MusicLab. Lavora a STEP e non confondere l'interprete della traccia corrente con l'interprete originale.

STEP 1 — IDENTITÀ CANONICA.
Traccia di partenza:
Titolo/video: ${originalTitle.trim()}
Interprete/canale: ${originalArtist.trim().ifBlank { "sconosciuto" }}

Prima stabilisci qual è la COMPOSIZIONE e chi l'ha INCISA/INTERPRETATA ORIGINARIAMENTE. Se la traccia di partenza è una cover, live, duetto o video TV, NON usare automaticamente quel cantante come originale. Distingui interprete originale da autore/compositore. Usa la ricerca Google quando serve a evitare un'identità sbagliata.

STEP 2 — PRIMO GRUPPO.
Dopo l'identità, restituisci fino a $INITIAL_LIMIT cover IN STUDIO reali della stessa composizione, eseguite da altri artisti. In questa fase servono solo titolo, artista, categoria, lingua, anno e album se noto. NON spendere spazio sui crediti completi: verranno richiesti solo quando l'utente apre i dettagli.
Escludi karaoke, reaction, tutorial, backing track, mashup e medley.

Rispondi SOLO JSON:
{
 "original":{"title":"","artist":"","year":null,"album":null,"language":null},
 "versions":[{"title":"","artist":"","category":"cover","language":null,"year":null,"album":null}]
}"""
            executeWithFallbackModels(config, prompt, 2400, true)?.let { parse(it, originalArtist) }
        } else null

        val result = mergeDiscoveries(cloud, direct, originalArtist, AiCoverCategory.COVER, INITIAL_LIMIT)
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
        val cacheKey = "expanded16|${config.model}|${canonical(originalTitle)}|${canonical(originalArtist)}"
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
     * Emissione progressiva: la UI può mostrare dieci elementi alla volta mentre la
     * ricerca continua. Le cover hanno un obiettivo di almeno 50 candidati reali,
     * quando le fonti AI riescono effettivamente a documentarli; non si inventa nulla.
     */
    suspend fun discoverExpandedBatches(
        originalTitle: String,
        originalArtist: String,
        existing: List<AiCoverCandidate>,
        config: GeminiCoverVerificationConfig,
        onBatch: suspend (List<AiCoverCandidate>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (originalTitle.isBlank() || (config.apiKey.isBlank() && config.cloudEndpoint.isBlank())) return@withContext

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

        // Se le normali passate non hanno ancora documentato circa 50 cover studio,
        // esegui recuperi mirati. Ogni passata riceve l'elenco già noto, quindi chiede
        // soltanto elementi nuovi e si ferma se non trova più materiale reale.
        var recoveryEmptyPasses = 0
        for (focus in COVER_TARGET_RECOVERY_ROUNDS) {
            val currentCoverCount = collected.values.count { it.category == AiCoverCategory.COVER }
            if (
                currentCoverCount >= MIN_COVER_TARGET ||
                collected.size >= MAX_TOTAL_CANDIDATES ||
                recoveryEmptyPasses >= MAX_COVER_RECOVERY_EMPTY_PASSES
            ) {
                break
            }

            val batch = discoverResearchRound(
                originalTitle = originalTitle,
                originalArtist = originalArtist,
                existing = collected.values.toList(),
                focus = focus,
                config = config,
            )
            val fresh = batch
                .filter { !collected.containsKey(it.stableKey) }
                .take((MAX_TOTAL_CANDIDATES - collected.size).coerceAtLeast(0))

            fresh.forEach { collected[it.stableKey] = it }
            if (fresh.isNotEmpty()) {
                recoveryEmptyPasses = 0
                onBatch(fresh.sortedWith(candidateOrder))
            } else {
                recoveryEmptyPasses++
            }
        }
    }

    /**
     * STEP di recupero richiesto dalla UI quando i candidati trovati non diventano
     * abbastanza risultati riproducibili. Ogni round usa una strategia diversa e
     * riceve l'elenco già noto, quindi l'AI si auto-interroga senza ripetere gli stessi nomi.
     */
    suspend fun discoverRecoveryBatch(
        originalTitle: String,
        originalArtist: String,
        existing: List<AiCoverCandidate>,
        config: GeminiCoverVerificationConfig,
        round: Int,
    ): List<AiCoverCandidate> = withContext(Dispatchers.IO) {
        val focus = COVER_PLAYABLE_RECOVERY_FOCI[round.coerceAtLeast(0) % COVER_PLAYABLE_RECOVERY_FOCI.size]
        discoverResearchRound(originalTitle, originalArtist, existing, focus, config)
    }

    private fun discoverResearchRound(
        originalTitle: String,
        originalArtist: String,
        existing: List<AiCoverCandidate>,
        focus: ResearchFocus,
        config: GeminiCoverVerificationConfig,
    ): List<AiCoverCandidate> {
        val cloudVersions = if (config.cloudEndpoint.isNotBlank()) {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    CloudMusicDiscovery.discoverCover(
                        title = originalTitle,
                        artist = originalArtist,
                        config = config,
                        phase = "expand",
                        existing = existing,
                        focus = "STEP DI RICERCA: ${focus.instructions} Restituisci soprattutto NUOVI nomi/versioni; crediti completi non necessari ora.",
                    )
                }
            }.getOrNull()?.let { sanitize(it, originalArtist, focus.category, focus.limit).versions }.orEmpty()
        } else emptyList()

        if (config.apiKey.isBlank()) return cloudVersions.distinctBy { it.stableKey }.take(focus.limit)

        val excluded = existing.take(260).joinToString("\n") {
            "- ${it.artist} — ${it.title} [${it.category.name.lowercase()}${it.language?.let { l -> ", $l" }.orEmpty()}]"
        }

        val prompt = """Sei il motore musicale AI-first centrale di MusicLab. Devi lavorare A STEP e massimizzare i risultati REALI, non fermarti ai nomi più famosi.

Composizione canonica:
Titolo: ${originalTitle.trim()}
Interprete originale: ${originalArtist.trim().ifBlank { "sconosciuto" }}

STEP CORRENTE:
${focus.instructions}

Obiettivo di questo step: trovare fino a ${focus.limit} elementi NUOVI della stessa composizione. Se la prima memoria mentale produce pochi nomi, riesamina per decenni, album, singoli, paesi, programmi TV, festival o pubblicazioni digitali coerenti con il focus. Non ripetere gli elementi già noti.

Classificazione:
- cover = incisione IN STUDIO da un interprete diverso dall'originale nella lingua originale;
- live = concerto, TV, radio, sessione, festival o performance non da studio;
- remix = remix/rework/mix attribuito;
- straniera = incisione IN STUDIO in altra lingua, anche con titolo tradotto.
Precedenza: remix > live > straniera > cover.
Escludi karaoke, reaction, tutorial, backing track, mashup, medley e tribute anonimi.
Non inventare per raggiungere il numero.

IMPORTANTE: in questa fase restituisci SOLO metadati essenziali per la localizzazione audio: titolo, artista, categoria, lingua, anno e album se noto. I crediti completi saranno richiesti soltanto quando l'utente apre Dettagli.

Già note, da NON ripetere:
${excluded.ifBlank { "(nessuna)" }}

Rispondi SOLO JSON:
{"original":null,"versions":[{"title":"","artist":"","category":"cover|live|remix|straniera","language":null,"year":null,"album":null}]}
"""

        val directVersions = executeWithFallbackModels(config, prompt, 4300, focus.deepResearch)
            ?.let { sanitize(parse(it, originalArtist), originalArtist, focus.category, focus.limit).versions }
            .orEmpty()

        return (cloudVersions + directVersions)
            .distinctBy { it.stableKey }
            .take(focus.limit)
    }

    private fun mergeDiscoveries(
        cloud: AiCoverDiscoveryResult?,
        direct: AiCoverDiscoveryResult?,
        originalArtist: String,
        forcedCategory: AiCoverCategory?,
        limit: Int,
    ): AiCoverDiscoveryResult {
        val original = direct?.original ?: cloud?.original
        val merged = AiCoverDiscoveryResult(
            original = original,
            versions = buildList {
                cloud?.versions?.let(::addAll)
                direct?.versions?.let(::addAll)
            }.distinctBy { it.stableKey },
        )
        return sanitize(merged, originalArtist, forcedCategory, limit)
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
        ResearchFocus(AiCoverCategory.COVER, "Cover da studio dalle prime reinterpretazioni fino al 1969. Cerca anche incisioni rare e regionali.", 18),
        ResearchFocus(AiCoverCategory.COVER, "Cover da studio degli anni 1970 e 1980, incluse pubblicazioni meno note.", 18),
        ResearchFocus(AiCoverCategory.COVER, "Cover da studio degli anni 1990 e 2000.", 18),
        ResearchFocus(AiCoverCategory.COVER, "Cover da studio dal 2010 a oggi e recupero di quelle rimaste fuori.", 18),
        ResearchFocus(AiCoverCategory.FOREIGN, "Adattamenti e cover in inglese, francese e spagnolo. Cerca anche titoli tradotti o completamente diversi.", deepResearch = true),
        ResearchFocus(AiCoverCategory.FOREIGN, "Adattamenti e cover in portoghese, tedesco, olandese, lingue nordiche e greco.", deepResearch = true),
        ResearchFocus(AiCoverCategory.FOREIGN, "Europa orientale e Balcani: polacco, ceco, slovacco, ungherese, rumeno, bulgaro, croato, serbo, sloveno, bosniaco, albanese e altre lingue documentate.", deepResearch = true),
        ResearchFocus(AiCoverCategory.FOREIGN, "Russo, ucraino, baltico, turco, ebraico, arabo e altre versioni mediorientali documentate.", deepResearch = true),
        ResearchFocus(AiCoverCategory.FOREIGN, "Asia e resto del mondo: giapponese, coreano, cinese e qualsiasi altra lingua/adattamento reale rimasto fuori.", deepResearch = true),
        ResearchFocus(AiCoverCategory.LIVE, "Performance live storiche: concerti, festival, TV, radio e sessioni fino alla fine degli anni 1980."),
        ResearchFocus(AiCoverCategory.LIVE, "Performance live dal 1990 a oggi, comprese sessioni e video-performance identificabili."),
        ResearchFocus(AiCoverCategory.REMIX, "Remix e rework reali di qualsiasi epoca: club, extended, radio, dance e altre versioni attribuite."),
        ResearchFocus(AiCoverCategory.COVER, "Passata di recupero: quali cover studio reali della composizione non sono ancora nella lista? Cerca per decennio, artista e pubblicazione.", 22, true),
        ResearchFocus(AiCoverCategory.FOREIGN, "Passata finale: quali adattamenti linguistici reali mancano ancora? Non inventare per riempire la lista.", 18, true),
        ResearchFocus(AiCoverCategory.LIVE, "Passata finale: quali live di altri artisti mancano ancora?", 16, true),
        ResearchFocus(AiCoverCategory.REMIX, "Passata finale: quali remix/rework mancano ancora?", 16, true),
    )

    private val COVER_TARGET_RECOVERY_ROUNDS = listOf(
        ResearchFocus(AiCoverCategory.COVER, "Recupero cover studio: cerca incisioni reali non ancora elencate, soprattutto tra 1950 e 1979. Includi versioni regionali e singoli documentati.", 22, true),
        ResearchFocus(AiCoverCategory.COVER, "Recupero cover studio: cerca incisioni reali non ancora elencate tra 1980 e 1999, incluse versioni meno note ma attribuibili.", 22, true),
        ResearchFocus(AiCoverCategory.COVER, "Recupero cover studio: cerca incisioni reali non ancora elencate dal 2000 a oggi, incluse pubblicazioni digitali ufficiali.", 22, true),
        ResearchFocus(AiCoverCategory.COVER, "Ultimo recupero cover studio: trova qualsiasi reinterpretazione reale della stessa composizione ancora assente. Non inventare elementi per raggiungere il target.", 24, true),
    )

    private val COVER_PLAYABLE_RECOVERY_FOCI = listOf(
        ResearchFocus(AiCoverCategory.COVER, "Nuovo giro: cerca cover studio reali non ancora elencate, artista per artista e decennio per decennio. Privilegia incisioni ufficiali/localizzabili.", 36, true),
        ResearchFocus(AiCoverCategory.COVER, "Nuovo giro: esplora cataloghi, compilation, singoli, talent/show e reinterpretazioni ufficiali meno note della stessa composizione.", 36, true),
        ResearchFocus(AiCoverCategory.COVER, "Nuovo giro: cerca reinterpretazioni studio internazionali nella stessa lingua originale e pubblicazioni digitali attribuite.", 36, true),
        ResearchFocus(AiCoverCategory.COVER, "Nuovo giro: verifica quali interpreti e incisioni reali della composizione sono ancora assenti dalla lista già nota.", 40, true),
        ResearchFocus(AiCoverCategory.COVER, "Recupero profondo: usa ricerca web per trovare cover studio documentate che i passaggi precedenti non hanno nominato.", 40, true),
        ResearchFocus(AiCoverCategory.COVER, "Ultimo giro: trova soltanto nuove cover studio reali ancora assenti; se non esistono altri risultati affidabili restituisci lista vuota.", 40, true),
    )

    private val DISALLOWED = Regex("\\b(karaoke|reaction|tutorial|lesson|backing track|instrumental backing|mashup|medley)\\b")
    private const val INITIAL_LIMIT = 10
    private const val MIN_COVER_TARGET = 50
    private const val MAX_TOTAL_CANDIDATES = 320
    private const val CONCURRENT_RESEARCH = 3
    private const val MAX_EMPTY_PASSES = 2
    private const val MAX_COVER_RECOVERY_EMPTY_PASSES = 2
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
