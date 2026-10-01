package com.metrolist.music.ui.component

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Wikidata is an evidence provider for the MusicLab AI Brain, never a veto.
 * Missing data and network failures stay neutral; positive labels/aliases can
 * widen discovery, especially for translated/adapted titles.
 */
internal enum class WikidataStatus {
    OK,
    NO_MATCH,
    NETWORK_ERROR,
}

internal data class WikidataWorkEvidence(
    val entityId: String,
    val titles: List<String>,
    val aliases: List<String>,
    val composerEntityIds: List<String> = emptyList(),
    val lyricistEntityIds: List<String> = emptyList(),
)

internal data class WikidataLookup(
    val work: WikidataWorkEvidence?,
    val status: WikidataStatus,
    val sourceUrl: String? = null,
)

internal object WikidataCoverSource {
    private const val API_URL = "https://www.wikidata.org/w/api.php"
    private const val ENTITY_URL = "https://www.wikidata.org/wiki/Special:EntityData"
    private const val REQUEST_TIMEOUT_MS = 7_500
    private const val MAX_SEARCH_RESULTS = 5
    private const val MAX_ENTITY_PROBES = 4
    private const val CACHE_TTL_MS = 12L * 60L * 60L * 1000L
    private const val ERROR_CACHE_TTL_MS = 10L * 60L * 1000L
    private const val USER_AGENT = "MusicLab/0.9.2 (https://github.com/Br174/MusicLab)"

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val createdAt: Long,
        val lookup: WikidataLookup,
    )

    fun lookup(title: String, artist: String = ""): WikidataLookup {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return WikidataLookup(null, WikidataStatus.NO_MATCH)

        val key = "${canonical(cleanTitle)}|${canonical(artist)}"
        val now = System.currentTimeMillis()
        cache[key]?.let { entry ->
            val ttl = if (
                entry.lookup.status == WikidataStatus.OK ||
                entry.lookup.status == WikidataStatus.NO_MATCH
            ) {
                CACHE_TTL_MS
            } else {
                ERROR_CACHE_TTL_MS
            }
            if (now - entry.createdAt < ttl) return entry.lookup
            cache.remove(key)
        }

        val lookup = lookupFresh(cleanTitle, artist.trim())
        cache[key] = CacheEntry(now, lookup)
        return lookup
    }

    private fun lookupFresh(title: String, artist: String): WikidataLookup {
        val queries = linkedSetOf<String>().apply {
            if (artist.isNotBlank()) add("$title $artist")
            add(title)
        }
        val languages = listOf("it", "en")
        val entityIds = linkedSetOf<String>()
        var lastUrl: String? = null
        var sawNetworkError = false

        for (query in queries) {
            for (language in languages) {
                val url = searchUrl(query, language)
                lastUrl = url
                val payload = fetchJson(url)
                if (payload == null) {
                    sawNetworkError = true
                    continue
                }
                parseSearchEntityIds(payload).forEach(entityIds::add)
                if (entityIds.size >= MAX_ENTITY_PROBES) break
            }
            if (entityIds.size >= MAX_ENTITY_PROBES) break
        }

        if (entityIds.isEmpty()) {
            return WikidataLookup(
                work = null,
                status = if (sawNetworkError) WikidataStatus.NETWORK_ERROR else WikidataStatus.NO_MATCH,
                sourceUrl = lastUrl,
            )
        }

        var firstPlausible: WikidataWorkEvidence? = null
        for (entityId in entityIds.take(MAX_ENTITY_PROBES)) {
            val url = "$ENTITY_URL/${URLEncoder.encode(entityId, "UTF-8")}.json"
            lastUrl = url
            val payload = fetchJson(url)
            if (payload == null) {
                sawNetworkError = true
                continue
            }
            val evidence = parseEntityPayload(payload, entityId) ?: continue
            if (firstPlausible == null) firstPlausible = evidence
            if (matchesTitle(evidence, title)) {
                return WikidataLookup(evidence, WikidataStatus.OK, url)
            }
        }

        // A search hit that cannot be tied back to the requested title is not
        // negative evidence. We deliberately report NO_MATCH rather than using
        // the unrelated entity to influence the AI.
        return WikidataLookup(
            work = null,
            status = if (sawNetworkError && firstPlausible == null) {
                WikidataStatus.NETWORK_ERROR
            } else {
                WikidataStatus.NO_MATCH
            },
            sourceUrl = lastUrl,
        )
    }

    /** Pure seam used by tests and by future cached/web adapters. */
    internal fun lookupFromPayloads(
        searchPayload: String,
        entityPayloadProvider: (String) -> String?,
    ): WikidataLookup {
        val ids = parseSearchEntityIds(searchPayload)
        if (ids.isEmpty()) return WikidataLookup(null, WikidataStatus.NO_MATCH)

        var sawMissingPayload = false
        for (id in ids.take(MAX_ENTITY_PROBES)) {
            val payload = entityPayloadProvider(id)
            if (payload == null) {
                sawMissingPayload = true
                continue
            }
            val evidence = parseEntityPayload(payload, id) ?: continue
            return WikidataLookup(
                work = evidence,
                status = WikidataStatus.OK,
                sourceUrl = "$ENTITY_URL/$id.json",
            )
        }
        return WikidataLookup(
            work = null,
            status = if (sawMissingPayload) WikidataStatus.NETWORK_ERROR else WikidataStatus.NO_MATCH,
        )
    }

    internal fun parseEntityPayload(payload: String, entityId: String): WikidataWorkEvidence? {
        val root = parseObject(payload) ?: return null
        val entities = root["entities"].asObject() ?: return null
        val entity = entities[entityId].asObject() ?: return null

        val titles = linkedSetOf<String>()
        entity["labels"].asObject()?.values?.forEach { rawLabel ->
            rawLabel.asObject()
                ?.get("value")
                .asString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(titles::add)
        }

        val aliases = linkedSetOf<String>()
        entity["aliases"].asObject()?.values?.forEach { rawAliases ->
            rawAliases.asArray()?.forEach { rawAlias ->
                rawAlias.asObject()
                    ?.get("value")
                    .asString()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(aliases::add)
            }
        }

        val claims = entity["claims"].asObject()
        val composerIds = claimEntityIds(claims, "P86")
        val lyricistIds = claimEntityIds(claims, "P676")

        if (titles.isEmpty() && aliases.isEmpty()) return null
        val declaredId = entity["id"].asString()?.trim().orEmpty()
        return WikidataWorkEvidence(
            entityId = declaredId.ifBlank { entityId },
            titles = titles.toList(),
            aliases = aliases.filterNot { alias -> titles.any { canonical(it) == canonical(alias) } },
            composerEntityIds = composerIds,
            lyricistEntityIds = lyricistIds,
        )
    }

    internal fun parseSearchEntityIds(payload: String): List<String> {
        val root = parseObject(payload) ?: return emptyList()
        val search = root["search"].asArray() ?: return emptyList()
        return search.mapNotNull { raw ->
            raw.asObject()
                ?.get("id")
                .asString()
                ?.trim()
                ?.takeIf { it.matches(Regex("Q\\d+")) }
        }.distinct().take(MAX_SEARCH_RESULTS)
    }

    private fun claimEntityIds(claims: JsonObject?, property: String): List<String> {
        val statements = claims?.get(property).asArray() ?: return emptyList()
        return statements.mapNotNull { rawStatement ->
            rawStatement.asObject()
                ?.get("mainsnak").asObject()
                ?.get("datavalue").asObject()
                ?.get("value").asObject()
                ?.get("id").asString()
                ?.trim()
                ?.takeIf { it.matches(Regex("Q\\d+")) }
        }.distinct()
    }

    private fun matchesTitle(evidence: WikidataWorkEvidence, wantedTitle: String): Boolean {
        val wanted = canonical(wantedTitle)
        if (wanted.isBlank()) return false
        return (evidence.titles + evidence.aliases).any { candidate ->
            val key = canonical(candidate)
            key == wanted || key.startsWith("$wanted ") || wanted.startsWith("$key ")
        }
    }

    private fun searchUrl(query: String, language: String): String =
        "$API_URL?action=wbsearchentities&format=json&type=item&limit=$MAX_SEARCH_RESULTS" +
            "&language=${URLEncoder.encode(language, "UTF-8")}" +
            "&uselang=${URLEncoder.encode(language, "UTF-8")}" +
            "&search=${URLEncoder.encode(query, "UTF-8")}" 

    private fun fetchJson(url: String): String? = runCatching {
        Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .header("Accept", "application/json")
            .ignoreContentType(true)
            .timeout(REQUEST_TIMEOUT_MS)
            .execute()
            .takeIf { it.statusCode() in 200..299 }
            ?.body()
    }.getOrNull()

    private fun parseObject(payload: String): JsonObject? = runCatching {
        json.parseToJsonElement(payload).asObject()
    }.getOrNull()

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
    private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray
    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun canonical(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}
