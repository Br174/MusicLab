package com.metrolist.music.ui.component

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.TimeUnit

/** Optional first-publication enrichment. A model suggests; a document confirms. */
internal object CoverOriginalResolver {
    internal class SessionState {
        var attempted: Boolean = false
        var inFlight: Boolean = false
        var retryAfterMs: Long = 0L
        val credits: MutableList<com.metrolist.music.discogs.DiscogsCredit> = mutableListOf()
        fun reset() {
            attempted = false
            inFlight = false
            retryAfterMs = 0L
            credits.clear()
        }
    }
    data class Config(val provider: String, val key: String, val model: String)
    data class Proposal(val title: String, val artist: String, val document: String)
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS).callTimeout(9, TimeUnit.SECONDS).build()
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val allowedHosts = setOf("musicbrainz.org", "discogs.com", "cover.info", "secondhandsongs.com")
    private val firstPublication = Regex(
        "(?i)(?:first (?:released|published|issued)(?: recording| version)? by|originally (?:released|published|issued) by|prima (?:versione pubblicata|pubblicazione)(?: di| da)?)\\s*[:—-]?\\s*"
    )

    private val negatedClaim = Regex("(?i)\\b(?:not|never|non|mai)(?:\\s+\\p{L}+){0,2}\\s*$")

    internal fun safeDocument(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            allowedHosts.any { uri.host.equals(it, true) || uri.host?.endsWith(".$it", true) == true }
    }.getOrDefault(false)

    internal fun parseProposal(text: String): Proposal? = runCatching {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return@runCatching null
        val json = Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        fun field(key: String) = json[key]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val title = field("title")
        val artist = field("artist")
        val document = field("document")
        if (title.isBlank() || artist.isBlank() || !safeDocument(document)) null
        else Proposal(title, artist, document)
    }.getOrNull()

    /** Earliest known year or an AI statement never satisfies this documentary test. */
    internal fun documentsFirstPublication(text: String, proposal: Proposal): Boolean {
        val title = TitleMeaningResolver.canonical(proposal.title)
        val artist = TitleMeaningResolver.canonical(proposal.artist)
        if (title.isBlank() || artist.isBlank()) return false
        return firstPublication.findAll(text).any { match ->
            val prefix = TitleMeaningResolver.canonical(text.substring(maxOf(0, match.range.first - 60), match.range.first))
            if (negatedClaim.containsMatchIn(prefix)) return@any false
            val after = TitleMeaningResolver.canonical(text.substring(match.range.last + 1,
                minOf(text.length, match.range.last + 1 + proposal.artist.length + 12)))
            val context = TitleMeaningResolver.canonical(text.substring(
                maxOf(0, match.range.first - 500), minOf(text.length, match.range.last + 500)))
            (after == artist || after.startsWith("$artist ")) &&
                (" $context ").contains(" $title ")
        }
    }

    private fun propose(title: String, artist: String, credits: String, config: Config?): Proposal? {
        if (config == null || config.key.isBlank() || config.model.isBlank()) return null
        val prompt = "Identify the performer of the FIRST PUBLISHED recording of this composition. " +
            "The selected published recording may be a cover: title=$title; performer=$artist; credits=$credits. " +
            "Do not confuse composer, recording date, reissue or oldest search result with first publication. " +
            "Suggest only a hypothesis and one independent catalogue document explicitly describing first publication. " +
            "Return JSON {\"title\":\"work title\",\"artist\":\"first published performer\",\"document\":\"https catalogue URL\"}. " +
            "Document must be musicbrainz.org, discogs.com, cover.info or secondhandsongs.com. If unknown return {}."
        val body: JSONObject
        val builder = Request.Builder()
        when (config.provider) {
            "OpenRouter" -> {
                body = JSONObject().put("model", config.model).put("temperature", 0)
                    .put("max_tokens", 500).put("messages", org.json.JSONArray().put(
                        JSONObject().put("role", "user").put("content", prompt)))
                builder.url("https://openrouter.ai/api/v1/chat/completions")
                    .header("Authorization", "Bearer ${config.key}")
            }
            "Gemini" -> {
                if (!Regex("[A-Za-z0-9._-]+").matches(config.model)) return null
                body = JSONObject().put("contents", org.json.JSONArray().put(JSONObject().put(
                    "parts", org.json.JSONArray().put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", JSONObject().put("temperature", 0).put("maxOutputTokens", 600))
                builder.url("https://generativelanguage.googleapis.com/v1beta/models/${config.model}:generateContent")
                    .header("x-goog-api-key", config.key)
            }
            else -> return null
        }
        return runCatching {
            client.newCall(builder.post(body.toString().toRequestBody(mediaType)).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val root = JSONObject(response.body?.string() ?: return@use null)
                val text = if (config.provider == "OpenRouter") root.optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                else root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
                parseProposal(text)
            }
        }.getOrNull()
    }

    /** Uses the existing work catalogue first; success is never required by the caller. */
    suspend fun resolve(title: String, artist: String, known: () -> List<CoverSourceCandidate>, config: Config?,
        awaitResources: suspend () -> Unit,
        onCandidates: suspend (List<CoverSourceCandidate>) -> Unit,
    ) {
        if (title.isBlank() || artist.isBlank()) return
        if (CoverPriorityOrder.originalReference(known()) != null) return
        withTimeoutOrNull(32_000L) {
            awaitResources()
            val lookup = withContext(Dispatchers.IO) { MusicBrainzCoverSource.lookup(title, artist) }
            val credits = lookup.work?.credits.orEmpty().map {
                com.metrolist.music.discogs.DiscogsCredit(name = it.name, role = it.role)
            }
            val versions = withContext(Dispatchers.Default) { (lookup.originalVersions + lookup.covers).map { row ->
                CoverSourceCandidate(title = row.title, artist = row.artist, sources = listOf("MusicBrainz"),
                    year = row.year, sourceUrl = "https://musicbrainz.org/recording/${row.recordingId}",
                    workRelationConfirmed = true, credits = credits,
                    category = if (TitleMeaningResolver.matchesBaseTitle(title, row.title, setOf(row.artist)))
                        AiCoverCategory.COVER else AiCoverCategory.FOREIGN)
            }
            }
            for (batch in versions.chunked(25)) { awaitResources(); onCandidates(batch); yield() }
            // Existing catalogue documents are checked before asking the configured model.
            if (CoverPriorityOrder.originalReference(known()) != null) return@withTimeoutOrNull
            val documented = (known() + versions).filter { it.workRelationConfirmed && !it.sourceUrl.isNullOrBlank() }
                .sortedBy { it.year ?: Int.MAX_VALUE }
            val publication = withTimeoutOrNull(6_000L) documentation@ {
                for (candidate in documented) {
                    awaitResources()
                    val proposal = Proposal(candidate.title, candidate.artist, candidate.sourceUrl.orEmpty())
                    if (verifyDocument(proposal)) return@documentation candidate
                }
                null
            }
            if (publication != null) {
                onCandidates(listOf(publication.copy(originalWorkReference = true, firstPublicationConfirmed = true)))
                return@withTimeoutOrNull
            }
            if (CoverPriorityOrder.originalReference(known()) != null) return@withTimeoutOrNull
            awaitResources()
            val proposal = withContext(Dispatchers.IO) { propose(title, artist,
                credits.joinToString { "${it.role}: ${it.name}" }, config) } ?: return@withTimeoutOrNull
            awaitResources()
            // Same title alone is insufficient: anchor the suggested rendition to the
            // selected recording's actual work, in either catalogue lookup direction.
            val linked = withContext(Dispatchers.Default) { versions.firstOrNull {
                CoverLab71FoundationGate.isOriginalPerformer(it.artist, proposal.artist) &&
                    TitleMeaningResolver.matchesBaseTitle(proposal.title, it.title, setOf(proposal.artist))
            } } ?: withContext(Dispatchers.IO) {
                val reverse = MusicBrainzCoverSource.lookup(proposal.title, proposal.artist)
                if (reverse.covers.none { CoverLab71FoundationGate.isOriginalPerformer(it.artist, artist) &&
                        TitleMeaningResolver.matchesBaseTitle(title, it.title, setOf(artist)) }) null
                else reverse.originalVersions.firstOrNull()?.let { row ->
                    CoverSourceCandidate(row.title, row.artist, listOf("MusicBrainz"), year = row.year,
                        sourceUrl = proposal.document, workRelationConfirmed = true, credits = credits)
                }
            } ?: return@withTimeoutOrNull
            awaitResources()
            if (verifyDocument(proposal)) onCandidates(listOf(linked.copy(
                originalWorkReference = true, firstPublicationConfirmed = true,
                sourceUrl = proposal.document)))
        }
    }

    private suspend fun verifyDocument(proposal: Proposal): Boolean = withContext(Dispatchers.IO) {
        if (!safeDocument(proposal.document)) return@withContext false
        runCatching {
            // Do not follow a model-suggested URL to an unapproved/private redirect.
            val response = Jsoup.connect(proposal.document).timeout(4_000).maxBodySize(512_000)
                .followRedirects(false).ignoreHttpErrors(true).execute()
            response.statusCode() == 200 && documentsFirstPublication(response.parse().text(), proposal)
        }.getOrDefault(false)
    }
}
