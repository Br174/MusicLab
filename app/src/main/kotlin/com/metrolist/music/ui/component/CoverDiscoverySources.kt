package com.metrolist.music.ui.component

import com.metrolist.music.BuildConfig
import com.metrolist.music.discogs.DiscogsCredit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLDecoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * LAB38A source layer.
 *
 * Every provider is a DISCOVERY/EVIDENCE lane only. No provider is allowed to
 * veto a candidate and AI is never a judge. Candidates are merged, assigned a
 * weak-to-strong 1..10 evidence score, and handed to the common Cover/Originali
 * browser. A user rejection is handled later by the cloud memory layer (LAB38B).
 */
internal data class CoverSourceCandidate(
    val title: String,
    val artist: String,
    val sources: List<String>,
    val year: Int? = null,
    val album: String? = null,
    val coverUrl: String? = null,
    val durationSeconds: Int? = null,
    val language: String? = null,
    val category: AiCoverCategory = AiCoverCategory.COVER,
    val sourceUrl: String? = null,
    val playbackVideoId: String? = null,
    val playbackVideoTitle: String? = null,
    val playbackVideoSource: String? = null,
    val workRelationConfirmed: Boolean = false,
    val originalWorkReference: Boolean = false,
    val credits: List<DiscogsCredit> = emptyList(),
    val evidenceScore: Int = 1,
)

internal data class CoverSourceDiagnostic(
    val name: String,
    val available: Boolean,
    val found: Int,
    val note: String = "",
)

internal data class CoverSourceOutcome(
    val candidates: List<CoverSourceCandidate>,
    val diagnostics: List<CoverSourceDiagnostic>,
    val originalYear: Int? = null,
    val workId: String? = null,
    val workCredits: List<DiscogsCredit> = emptyList(),
)

internal object CoverDiscoverySources {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()

    private data class CacheEntry(
        val expiresAtMs: Long,
        val outcome: CoverSourceOutcome,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private const val MAX_CACHED_DISCOVERIES = 24

    // LAB60 Pollicino: keep only a small number of recent searches in memory.
    private fun rememberDiscovery(key: String, now: Long, outcome: CoverSourceOutcome) {
        synchronized(cache) {
            cache.entries.toList().forEach { (existingKey, entry) ->
                if (entry.expiresAtMs <= now) cache.remove(existingKey)
            }
            while (cache.size >= MAX_CACHED_DISCOVERIES && !cache.containsKey(key)) {
                val oldestKey = cache.entries.minByOrNull { it.value.expiresAtMs }?.key ?: break
                cache.remove(oldestKey)
            }
            cache[key] = CacheEntry(now + CACHE_TTL_MS, outcome)
        }
    }

    suspend fun discover(
        title: String,
        originalArtist: String,
        mode: DiscogsDirectMode,
        aiConfig: GeminiCoverVerificationConfig?,
        onEarlyVideoCandidates: suspend (List<CoverSourceCandidate>) -> Unit = {},
    ): CoverSourceOutcome = coroutineScope {
        val cleanTitle = title.trim()
        val cleanArtist = originalArtist.trim()
        if (cleanTitle.isBlank()) return@coroutineScope CoverSourceOutcome(emptyList(), emptyList())

        val key =
            listOf(
                mode.name,
                canonical(cleanTitle),
                canonical(cleanArtist),
                "lab60-lastfm-wikidata-disabled",
                if (aiConfig == null) "no-ai" else "ai",
            ).joinToString("|")
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAtMs > now }?.let { hit ->
            // LAB59: even cache hits must yield video previews immediately.
            onEarlyVideoCandidates(hit.outcome.candidates.filter { !it.playbackVideoId.isNullOrBlank() })
            return@coroutineScope hit.outcome
        }

        // LAB57: bounded fan-out. All sources still participate, but a Cover
        // search must not create an unbounded network/CPU burst beside playback.
        val sourceGate = Semaphore(3)
        suspend fun <T> sourceLane(block: suspend () -> T): T = sourceGate.withPermit { block() }

        // LAB59: prioritize cover.info direct videos over auxiliary metadata.
        // Each lane still runs; slow services never gate early thumbnail emission.
        val coverInfo = async(Dispatchers.IO) {
            val lane = sourceLane { discoverCoverInfo(cleanTitle, cleanArtist, mode) }
            onEarlyVideoCandidates(
                lane.first.filter { candidate ->
                    !candidate.playbackVideoId.isNullOrBlank() &&
                        sameBaseTitle(cleanTitle, candidate.title)
                },
            )
            lane
        }
        val musicBrainzLookup =
            async(Dispatchers.IO) {
                sourceLane {
                    runCatching { MusicBrainzCoverSource.lookup(cleanTitle, cleanArtist) }
                        .getOrElse { MusicBrainzLookup(emptyList(), MusicBrainzStatus.NETWORK_ERROR) }
                }
            }
        val iTunes = async(Dispatchers.IO) { sourceLane { discoverITunes(cleanTitle, cleanArtist, mode) } }
        val spotify = async(Dispatchers.IO) { sourceLane { discoverSpotify(cleanTitle, cleanArtist, mode) } }
        // LAB60 Pollicino: Last.fm is disabled for Cover/Originali discovery.
        val lrcLib = async(Dispatchers.IO) { sourceLane { discoverLrcLib(cleanTitle, cleanArtist, mode) } }
        // LAB60 Pollicino: Wikidata (not Wikipedia pages) is disabled in discovery.
        val ai = async(Dispatchers.IO) { sourceLane { discoverAi(cleanTitle, cleanArtist, mode, aiConfig) } }

        val resolvedMusicBrainz = musicBrainzLookup.await()
        val musicBrainz = discoverMusicBrainz(cleanTitle, mode, resolvedMusicBrainz)
        val lanes =
            listOf(
                coverInfo.await(),
                musicBrainz,
                iTunes.await(),
                lrcLib.await(),
                spotify.await(),
                ai.await(),
            )

        val workCredits =
            resolvedMusicBrainz.work
                ?.credits
                .orEmpty()
                .map { credit -> DiscogsCredit(name = credit.name, role = credit.role) }
        val musicBrainzOriginalYear = resolvedMusicBrainz.work?.originalYear
        val workCreditNames =
            workCredits
                .map { canonical(it.name) }
                .filter(String::isNotBlank)
                .toSet()

        val merged = linkedMapOf<String, CoverSourceCandidate>()
        lanes.flatMap { it.first }.forEach { candidate ->
            val identity = identity(candidate.title, candidate.artist, candidate.category)
            val previous = merged[identity]
            if (previous == null) {
                merged[identity] = candidate
            } else {
                val sources = (previous.sources + candidate.sources).distinct()
                val extraSourceBonus =
                    (sources.size - maxOf(previous.sources.size, candidate.sources.size)).coerceAtLeast(0)
                merged[identity] =
                    previous.copy(
                        sources = sources,
                        year = previous.year ?: candidate.year,
                        album = previous.album ?: candidate.album,
                        coverUrl = previous.coverUrl ?: candidate.coverUrl,
                        durationSeconds = previous.durationSeconds ?: candidate.durationSeconds,
                        language = previous.language ?: candidate.language,
                        sourceUrl = previous.sourceUrl ?: candidate.sourceUrl,
                        playbackVideoId = previous.playbackVideoId ?: candidate.playbackVideoId,
                        playbackVideoTitle = previous.playbackVideoTitle ?: candidate.playbackVideoTitle,
                        playbackVideoSource = previous.playbackVideoSource ?: candidate.playbackVideoSource,
                        workRelationConfirmed =
                            previous.workRelationConfirmed || candidate.workRelationConfirmed,
                        originalWorkReference =
                            previous.originalWorkReference || candidate.originalWorkReference,
                        credits =
                            (previous.credits + candidate.credits)
                                .distinctBy { credit ->
                                    credit.name.lowercase() + "|" + credit.role.lowercase()
                                },
                        evidenceScore =
                            (maxOf(previous.evidenceScore, candidate.evidenceScore) + extraSourceBonus)
                                .coerceIn(1, 20),
                    )
            }
        }

        // LAB57: reuse any exact-recording musical publication date learned by
        // another source. Never use a YouTube/social upload date as publication.
        val publicationYearByExactRecording =
            lanes
                .flatMap { it.first }
                .filter { it.year != null }
                .groupBy { candidate ->
                    canonical(candidate.title) + "|" + canonicalArtist(candidate.artist)
                }
                .mapValues { (_, candidates) ->
                    candidates.mapNotNull { it.year }.minOrNull()
                }

        val publicationEnriched =
            merged.values.map { candidate ->
                val recordingKey =
                    canonical(candidate.title) + "|" + canonicalArtist(candidate.artist)
                if (candidate.year == null) {
                    candidate.copy(year = publicationYearByExactRecording[recordingKey])
                } else {
                    candidate
                }
            }

        val fallbackOriginalYear =
            publicationEnriched
                .filter { candidate ->
                    candidate.originalWorkReference ||
                        (
                            cleanArtist.isNotBlank() &&
                                sameArtist(candidate.artist, cleanArtist) &&
                                sameBaseTitle(cleanTitle, candidate.title)
                            )
                }
                .mapNotNull { it.year }
                .minOrNull()
        val originalYear = musicBrainzOriginalYear ?: fallbackOriginalYear

        val identityAccepted =
            publicationEnriched.filter { candidate ->
                val strictTitleMatch = sameBaseTitle(cleanTitle, candidate.title)
                val aiTrusted = candidate.sources.any { it.equals("AI Scout", ignoreCase = true) }
                val sharedCreditEvidence =
                    candidate.credits.any { credit ->
                        canonical(credit.name) in workCreditNames
                    }
                val musicBrainzWork =
                    candidate.workRelationConfirmed &&
                        sharedCreditEvidence &&
                        candidate.sources.any { it.equals("MusicBrainz", ignoreCase = true) }
                val independentSourceConsensus = candidate.sources.distinct().size >= 2
                val crossVerifiedRelation =
                    candidate.workRelationConfirmed && independentSourceConsensus

                strictTitleMatch ||
                    aiTrusted ||
                    musicBrainzWork ||
                    crossVerifiedRelation ||
                    independentSourceConsensus
            }

        // LAB59: the inferred original publication year can be wrong (for
        // instance, an album reissue). Keep every identity-confirmed cover.
        val chronologicallyPossible = identityAccepted

        fun certifiedScore(candidate: CoverSourceCandidate): Int {
            val titleMatch =
                TitleMeaningResolver.classify(
                    targetTitle = cleanTitle,
                    value = candidate.title,
                    artistAliases = setOf(cleanArtist).filter(String::isNotBlank).toSet(),
                )
            val sourceCount = candidate.sources.distinct().size
            val aiOnly =
                sourceCount == 1 &&
                    candidate.sources.any { it.equals("AI Scout", ignoreCase = true) }
            val coverInfoOnly =
                sourceCount == 1 &&
                    candidate.sources.any { it.equals("COVER.INFO", ignoreCase = true) }
            val sharedCreditEvidence =
                candidate.credits.any { credit ->
                    canonical(credit.name) in workCreditNames
                }
            val musicBrainzWork =
                candidate.workRelationConfirmed &&
                    sharedCreditEvidence &&
                    candidate.sources.any { it.equals("MusicBrainz", ignoreCase = true) }

            var score = 1
            score += when (titleMatch) {
                TitleMeaningMatch.EXACT -> 6
                TitleMeaningMatch.DECORATED -> 5
                TitleMeaningMatch.DIFFERENT -> 0
            }
            if (candidate.workRelationConfirmed) score += 4
            if (musicBrainzWork) score += 2
            if (candidate.originalWorkReference) score += 3
            if (candidate.year != null) score += 2
            if (sharedCreditEvidence) score += 3
            score += ((sourceCount - 1).coerceAtLeast(0) * 2).coerceAtMost(4)

            // AI foreign admission is trusted by Bruno, but trust-to-admit is not
            // the same as 20/20 documentary confidence.
            if (aiOnly) score = score.coerceAtMost(12)
            // COVER.INFO remains valuable discovery/documentation, but a single
            // source can no longer manufacture a 16/20 or 18/20 score by itself.
            if (coverInfoOnly) score = score.coerceAtMost(13)
            // A missing musical publication date survives, but cannot rank as if
            // that fundamental piece of evidence had been certified.
            if (candidate.year == null) score = score.coerceAtMost(11)
            if (candidate.year != null && originalYear != null && candidate.year < originalYear) {
                // Chronology uncertainty lowers evidence; it never removes a cover.
                score = score.coerceAtMost(6)
            }

            return score.coerceIn(1, 20)
        }

        val certified =
            chronologicallyPossible.map { candidate ->
                candidate.copy(
                    evidenceScore = certifiedScore(candidate),
                )
            }

        val ordered =
            certified.sortedWith(
                compareByDescending<CoverSourceCandidate> { it.evidenceScore }
                    .thenBy { it.year ?: Int.MAX_VALUE }
                    .thenBy { canonical(it.artist) },
            )

        val identityGateDiagnostic =
            CoverSourceDiagnostic(
                name = "Opera Identity Gate",
                available = true,
                found = ordered.size,
                note =
                    "ammessi ${ordered.size}/${publicationEnriched.size} · " +
                        "titolo rigoroso; AI straniera fidata; servizi con doppia prova",
            )

        val outcome = CoverSourceOutcome(
            candidates = ordered,
            diagnostics = lanes.map { it.second } + listOf(
                CoverSourceDiagnostic("Last.fm", false, 0, "Disattivato in LAB60"),
                CoverSourceDiagnostic("Wikidata", false, 0, "Disattivato in LAB60"),
            ) + identityGateDiagnostic,
            originalYear = originalYear,
            workId = resolvedMusicBrainz.work?.id,
            workCredits = workCredits,
        )
        rememberDiscovery(key, now, outcome)
        outcome
    }

    private fun discoverMusicBrainz(
        title: String,
        mode: DiscogsDirectMode,
        lookup: MusicBrainzLookup,
    ): Pair<List<CoverSourceCandidate>, CoverSourceDiagnostic> {
        val musicBrainzWorkCredits =
            lookup.work
                ?.credits
                .orEmpty()
                .map { credit -> DiscogsCredit(name = credit.name, role = credit.role) }

        val originalVersions =
            lookup.originalVersions.mapNotNull { version ->
                val artist = version.artist.trim()
                val candidateTitle = version.title.trim()
                if (artist.isBlank() || candidateTitle.isBlank()) return@mapNotNull null
                CoverSourceCandidate(
                    title = candidateTitle,
                    artist = artist,
                    sources = listOf("MusicBrainz"),
                    year = version.year,
                    category = categoryFromTitle(candidateTitle),
                    sourceUrl = lookup.sourceUrl,
                    workRelationConfirmed = true,
                    originalWorkReference = true,
                    credits = musicBrainzWorkCredits,
                    evidenceScore = 8,
                )
            }

        val covers =
            if (mode == DiscogsDirectMode.ORIGINAL) {
                emptyList()
            } else {
                lookup.covers.mapNotNull { cover ->
                    val artist = cover.artist.trim()
                    val candidateTitle = cover.title.trim()
                    if (artist.isBlank() || candidateTitle.isBlank()) return@mapNotNull null
                    CoverSourceCandidate(
                        title = candidateTitle,
                        artist = artist,
                        sources = listOf("MusicBrainz"),
                        year = cover.year,
                        category =
                            if (sameBaseTitle(title, candidateTitle)) {
                                categoryFromTitle(candidateTitle)
                            } else {
                                AiCoverCategory.FOREIGN
                            },
                        language =
                            if (sameBaseTitle(title, candidateTitle)) null
                            else "titolo/adattamento alternativo",
                        sourceUrl = lookup.sourceUrl,
                        workRelationConfirmed = true,
                        credits = musicBrainzWorkCredits,
                        evidenceScore = 8,
                    )
                }
            }

        val candidates =
            (originalVersions + covers)
                .distinctBy { identity(it.title, it.artist, it.category) }

        return candidates to CoverSourceDiagnostic(
            name = "MusicBrainz",
            available = lookup.status != MusicBrainzStatus.NETWORK_ERROR,
            found = candidates.size,
            note =
                buildString {
                    append(lookup.status.name.lowercase())
                    lookup.work?.let { work ->
                        append(" · Work ")
                        append(work.id.take(8))
                        work.originalYear?.let { append(" · originale ").append(it) }
                    }
                },
        )
    }

    private suspend fun discoverITunes(
        title: String,
        originalArtist: String,
        mode: DiscogsDirectMode,
    ): Pair<List<CoverSourceCandidate>, CoverSourceDiagnostic> {
        val url =
            "https://itunes.apple.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("term", title)
                .addQueryParameter("media", "music")
                .addQueryParameter("entity", "song")
                .addQueryParameter("limit", "100")
                .build()
                .toString()
        val root = fetchObject(url)
            ?: return emptyList<CoverSourceCandidate>() to CoverSourceDiagnostic(
                "Apple/iTunes",
                false,
                0,
                "rete/non disponibile",
            )
        val array = root.optJSONArray("results") ?: JSONArray()
        val candidates = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val candidateTitle = item.optString("trackName").trim()
                val artist = item.optString("artistName").trim()
                if (candidateTitle.isBlank() || artist.isBlank()) continue
                if (!modeAcceptsArtist(mode, artist, originalArtist)) continue
                val titleMatches = sameBaseTitle(title, candidateTitle)
                add(
                    CoverSourceCandidate(
                        title = candidateTitle,
                        artist = artist,
                        sources = listOf("Apple/iTunes"),
                        year = parseYear(item.optString("releaseDate")),
                        album = item.optString("collectionName").trim().takeIf(String::isNotBlank),
                        coverUrl =
                            item.optString("artworkUrl100")
                                .replace("100x100", "600x600")
                                .takeIf(String::isNotBlank),
                        durationSeconds =
                            item.optLong("trackTimeMillis")
                                .takeIf { it > 0L }
                                ?.div(1000L)
                                ?.toInt(),
                        category =
                            if (titleMatches) categoryFromTitle(candidateTitle)
                            else AiCoverCategory.FOREIGN,
                        sourceUrl = item.optString("trackViewUrl").takeIf(String::isNotBlank),
                        evidenceScore = if (titleMatches) 6 else 1,
                    ),
                )
            }
        }.distinctBy { identity(it.title, it.artist, it.category) }
        return candidates to CoverSourceDiagnostic("Apple/iTunes", true, candidates.size, "catalogo senza chiave")
    }

    private suspend fun discoverLastFm(
        title: String,
        originalArtist: String,
        mode: DiscogsDirectMode,
    ): Pair<List<CoverSourceCandidate>, CoverSourceDiagnostic> {
        val apiKey = BuildConfig.LASTFM_API_KEY.trim()
        if (apiKey.isBlank()) {
            return discoverLastFmPublic(
                title = title,
                originalArtist = originalArtist,
                mode = mode,
                notePrefix = "ricerca pubblica no-key · chiave build assente",
            )
        }

        val url =
            "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
                .addQueryParameter("method", "track.search")
                .addQueryParameter("track", title)
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("format", "json")
                .addQueryParameter("limit", "100")
                .build()
                .toString()
        val root =
            fetchObject(url)
                ?: return discoverLastFmPublic(
                    title = title,
                    originalArtist = originalArtist,
                    mode = mode,
                    notePrefix = "fallback pubblico · API/rete non disponibile",
                )

        if (root.optInt("error", 0) != 0) {
            val message = root.optString("message").trim().ifBlank { "errore API" }
            return discoverLastFmPublic(
                title = title,
                originalArtist = originalArtist,
                mode = mode,
                notePrefix = "fallback pubblico · API non autorizzata: $message",
            )
        }

        val array =
            root.optJSONObject("results")
                ?.optJSONObject("trackmatches")
                ?.optJSONArray("track")
                ?: JSONArray()
        val candidates = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val candidateTitle = item.optString("name").trim()
                val artist = item.optString("artist").trim()
                if (candidateTitle.isBlank() || artist.isBlank()) continue
                if (!modeAcceptsArtist(mode, artist, originalArtist)) continue
                val titleMatches = sameBaseTitle(title, candidateTitle)
                val images = item.optJSONArray("image")
                var cover: String? = null
                if (images != null) {
                    for (j in 0 until images.length()) {
                        val value = images.optJSONObject(j)?.optString("#text")?.trim().orEmpty()
                        if (value.isNotBlank()) cover = value
                    }
                }
                add(
                    CoverSourceCandidate(
                        title = candidateTitle,
                        artist = artist,
                        sources = listOf("Last.fm"),
                        coverUrl = cover,
                        category =
                            if (titleMatches) categoryFromTitle(candidateTitle)
                            else AiCoverCategory.FOREIGN,
                        sourceUrl = item.optString("url").takeIf(String::isNotBlank),
                        evidenceScore = if (titleMatches) 6 else 1,
                    ),
                )
            }
        }.distinctBy { identity(it.title, it.artist, it.category) }

        if (candidates.isNotEmpty()) {
            return candidates to CoverSourceDiagnostic("Last.fm", true, candidates.size, "API track.search")
        }

        return discoverLastFmPublic(
            title = title,
            originalArtist = originalArtist,
            mode = mode,
            notePrefix = "API track.search 0 · fallback pubblico",
        )
    }

    private suspend fun discoverLastFmPublic(
        title: String,
        originalArtist: String,
        mode: DiscogsDirectMode,
        notePrefix: String,
    ): Pair<List<CoverSourceCandidate>, CoverSourceDiagnostic> {
        val url =
            "https://www.last.fm/search/tracks".toHttpUrl().newBuilder()
                .addQueryParameter("q", title)
                .build()
                .toString()
        val html =
            fetchHtml(url)
                ?: return emptyList<CoverSourceCandidate>() to CoverSourceDiagnostic(
                    "Last.fm",
                    false,
                    0,
                    "$notePrefix · ricerca pubblica non disponibile",
                )

        val document = runCatching { Jsoup.parse(html, "https://www.last.fm") }.getOrNull()
            ?: return emptyList<CoverSourceCandidate>() to CoverSourceDiagnostic(
                "Last.fm",
                false,
                0,
                "$notePrefix · HTML non leggibile",
            )

        val candidates =
            document.select("a[href*='/_/']")
                .mapNotNull { anchor ->
                    val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                    val cleanUrl = href.substringBefore('?').substringBefore('#')
                    val match = LASTFM_PUBLIC_TRACK_URL.find(cleanUrl) ?: return@mapNotNull null
                    val artist = decodeLastFmPath(match.groupValues[1])
                    val candidateTitle = decodeLastFmPath(match.groupValues[2])
                    if (artist.isBlank() || candidateTitle.isBlank()) return@mapNotNull null
                    if (!modeAcceptsArtist(mode, artist, originalArtist)) return@mapNotNull null

                    val titleMatches = sameBaseTitle(title, candidateTitle)
                    CoverSourceCandidate(
                        title = candidateTitle,
                        artist = artist,
                        sources = listOf("Last.fm"),
                        category =
                            if (titleMatches) categoryFromTitle(candidateTitle)
                            else AiCoverCategory.FOREIGN,
                        sourceUrl = cleanUrl,
                        evidenceScore = if (titleMatches) 6 else 1,
                    )
                }
                .distinctBy { identity(it.title, it.artist, it.category) }

        val note =
            if (candidates.isEmpty()) {
                "$notePrefix · raggiungibile, nessuna corrispondenza"
            } else {
                "$notePrefix · ${candidates.size} risultati"
            }
        return candidates to CoverSourceDiagnostic("Last.fm", true, candidates.size, note)
    }

    private fun decodeLastFmPath(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }
            .getOrDefault(value)
            .replace(Regex("\\s+"), " ")
            .trim()

    private suspend fun discoverLrcLib(
        title: String,
        originalArtist: String,
        mode: DiscogsDirectMode,
    ): Pair<List<CoverSourceCandidate>, CoverSourceDiagnostic> {
        val url =
            "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", title)
                .build()
                .toString()
        val array = fetchArrayWithRetry(url)
            ?: return emptyList<CoverSourceCandidate>() to CoverSourceDiagnostic(
                "LRCLIB",
                false,
                0,
                "HTTP/rete non disponibile dopo 2 tentativi",
            )
        val candidates = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val candidateTitle = item.optString("trackName").trim()
                val artist = item.optString("artistName").trim()
                if (candidateTitle.isBlank() || artist.isBlank()) continue
                if (!modeAcceptsArtist(mode, artist, originalArtist)) continue
                val titleMatches = sameBaseTitle(title, candidateTitle)
                add(
                    CoverSourceCandidate(
                        title = candidateTitle,
                        artist = artist,
                        sources = listOf("LRCLIB"),
                        album = item.optString("albumName").trim().takeIf(String::isNotBlank),
                        durationSeconds = item.optDouble("duration").takeIf { it > 0.0 }?.toInt(),
                        category =
                            if (titleMatches) categoryFromTitle(candidateTitle)
                            else AiCoverCategory.FOREIGN,
                        sourceUrl = "https://lrclib.net",
                        evidenceScore = if (titleMatches) 6 else 1,
                    ),
                )
            }
        }.distinctBy { identity(it.title, it.artist, it.category) }
        return candidates to CoverSourceDiagnostic(
            "LRCLIB",
            true,
            candidates.size,
            if (candidates.isEmpty()) {
                "raggiungibile · nessuna corrispondenza"
            } else {
                "catalogo/testi senza chiave · data musicale arricchita da altre fonti quando disponibile"
            },
        )
    }

    private suspend fun discoverSpotify(
        title: String,
        originalArtist: String,
        mode: DiscogsDirectMode,
    ): Pair<List<CoverSourceCandidate>, CoverSourceDiagnostic> {
        return if (mode == DiscogsDirectMode.COVER) {
            val result =
                runCatching {
                    SpotifyMusicAssist.assistCover(
                        originalTitle = title,
                        originalArtist = originalArtist,
                        existing = emptyList(),
                    )
                }.getOrNull()
            if (result == null) {
                emptyList<CoverSourceCandidate>() to CoverSourceDiagnostic("Spotify", false, 0, "errore")
            } else {
                val candidates =
                    result.candidates.mapNotNull { candidate ->
                        if (!modeAcceptsArtist(mode, candidate.artist, originalArtist)) return@mapNotNull null
                        CoverSourceCandidate(
                            title = candidate.title,
                            artist = candidate.artist,
                            sources = listOf("Spotify"),
                            year = candidate.year,
                            album = candidate.album,
                            durationSeconds = candidate.spotifyDurationSec,
                            language = candidate.language,
                            category = candidate.category,
                            sourceUrl = candidate.spotifyTrackId?.let { "https://open.spotify.com/track/$it" },
                            evidenceScore = 6,
                        )
                    }
                candidates to CoverSourceDiagnostic(
                    "Spotify",
                    result.stats.available,
                    candidates.size,
                    if (result.stats.available) "collegato" else "non autenticato",
                )
            }
        } else {
            val hints =
                runCatching {
                    SpotifyMusicAssist.originalHints(title, listOf(originalArtist))
                }.getOrDefault(emptyList())
            val candidates =
                hints.map {
                    CoverSourceCandidate(
                        title = it.title,
                        artist = it.artist,
                        sources = listOf("Spotify"),
                        year = it.year,
                        album = it.album,
                        durationSeconds = it.durationSec,
                        category = categoryFromTitle(it.title),
                        evidenceScore = 6,
                    )
                }
            candidates to CoverSourceDiagnostic(
                "Spotify",
                available = candidates.isNotEmpty(),
                found = candidates.size,
                note = if (candidates.isNotEmpty()) "originali collegati" else "nessun hint / non autenticato",
            )
        }
    }

    private suspend fun discoverCoverInfo(
        title: String,
        originalArtist: String,
        mode: DiscogsDirectMode,
    ): Pair<List<CoverSourceCandidate>, CoverSourceDiagnostic> {
        val cleanTitle = title.replace('"', ' ').trim()
        val cleanArtist = originalArtist.replace('"', ' ').trim()
        val searchTerms =
            listOfNotNull(
                if (cleanArtist.isNotBlank()) {
                    """song="$cleanTitle" performer="$cleanArtist""""
                } else {
                    null
                },
                """song="$cleanTitle"""",
            ).distinct()

        val searchDocuments = mutableListOf<Pair<String, org.jsoup.nodes.Document>>()
        var publicSearchReachable = false
        for (term in searchTerms) {
            val url =
                "https://cover.info/en/search".toHttpUrl().newBuilder()
                    .addQueryParameter("find", term)
                    // COVER.INFO exposes up to 200 rows through this public selector.
                    // Pull the widest page so MusicLab does not silently stop at the
                    // first 10/25 entries when a work has many covers/adaptations.
                    .addQueryParameter("per-page-songs", "200")
                    .build()
                    .toString()
            val html = fetchHtml(url) ?: continue
            val parsed = runCatching { Jsoup.parse(html, "https://cover.info") }.getOrNull() ?: continue
            publicSearchReachable = true
            if (parsed.select(COVER_INFO_SONG_SELECTOR).isNotEmpty()) {
                searchDocuments += term to parsed
            }
        }

        if (searchDocuments.isEmpty()) {
            return emptyList<CoverSourceCandidate>() to CoverSourceDiagnostic(
                "COVER.INFO",
                available = publicSearchReachable,
                found = 0,
                note =
                    if (publicSearchReachable) {
                        "raggiungibile · nessuna corrispondenza"
                    } else {
                        "ricerca pubblica non disponibile"
                    },
            )
        }

        // LAB43: merge all successful query lanes instead of stopping at the
        // first precise hit. This preserves precise results while also recovering
        // broader covers, foreign adaptations and alternate performers.
        val searchSeeds =
            searchDocuments
                .flatMap { (_, document) -> parseCoverInfoDocument(document) }
                .distinctBy { it.songId }
        val usedSearch = searchDocuments.joinToString(" + ") { it.first }
        val relationRoots =
            searchSeeds
                .filter { seed ->
                    sameBaseTitle(title, seed.title) &&
                        (
                            originalArtist.isBlank() ||
                                sameArtist(originalArtist, seed.artist)
                            )
                }
                .take(COVER_INFO_RELATION_ROOT_LIMIT)

        val related = mutableListOf<CoverInfoSeed>()
        relationRoots.forEach { root ->
            val html = fetchHtml(root.url) ?: return@forEach
            val page = runCatching { Jsoup.parse(html, "https://cover.info") }.getOrNull() ?: return@forEach
            related += parseCoverInfoDocument(page).map { it.copy(directRelation = true) }
        }

        val mergedSeeds =
            (related + searchSeeds.filter { seed -> sameBaseTitle(title, seed.title) })
                .distinctBy { it.songId }
                .filter { seed ->
                    seed.title.isNotBlank() &&
                        seed.artist.isNotBlank() &&
                        modeAcceptsArtist(mode, seed.artist, originalArtist)
                }

        val candidates =
            mergedSeeds.map { seed ->
                val sameTitle = sameBaseTitle(title, seed.title)
                CoverSourceCandidate(
                    title = seed.title,
                    artist = seed.artist,
                    sources = listOf("COVER.INFO"),
                    year = seed.year,
                    language = seed.language,
                    category =
                        if (!sameTitle) AiCoverCategory.FOREIGN
                        else categoryFromTitle(seed.title),
                    sourceUrl = seed.url,
                    coverUrl = seed.playbackVideoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
                    playbackVideoId = seed.playbackVideoId,
                    playbackVideoTitle = seed.playbackVideoId?.let { seed.title },
                    playbackVideoSource = seed.playbackVideoId?.let { "COVER.INFO" },
                    workRelationConfirmed =
                        seed.directRelation || seed.relationRole != CoverInfoRelationRole.NONE,
                    originalWorkReference =
                        seed.relationRole == CoverInfoRelationRole.INITIAL,
                    // LAB57: source-native evidence is deliberately modest.
                    // Final 1..20 confidence is assigned only by the global certifier.
                    evidenceScore =
                        when {
                            seed.relationRole == CoverInfoRelationRole.INITIAL -> 8
                            seed.directRelation -> 7
                            sameTitle -> 6
                            else -> 2
                        },
                )
            }.distinctBy { identity(it.title, it.artist, it.category) }

        return candidates to CoverSourceDiagnostic(
            name = "COVER.INFO",
            available = true,
            found = candidates.size,
            note =
                "fonte documentaria · query precise ${searchDocuments.size}/${searchTerms.size}" +
                    " · video diretti ${candidates.count { !it.playbackVideoId.isNullOrBlank() }}" +
                    " · $usedSearch",
        )
    }

    private data class CoverInfoSeed(
        val songId: String,
        val title: String,
        val artist: String,
        val url: String,
        val year: Int?,
        val language: String?,
        val playbackVideoId: String?,
        val relationRole: CoverInfoRelationRole = CoverInfoRelationRole.NONE,
        val directRelation: Boolean = false,
    )

    private enum class CoverInfoRelationRole {
        NONE,
        INITIAL,
        FOLLOW_UP,
    }

    private fun parseCoverInfoDocument(document: org.jsoup.nodes.Document): List<CoverInfoSeed> {
        val seenIds = linkedSetOf<String>()
        return document.select(COVER_INFO_SONG_SELECTOR)
            .mapNotNull { anchor ->
                val absolute = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                val pathUrl = absolute.substringBefore('?').substringBefore('#')
                val match = COVER_INFO_SONG_URL.find(pathUrl) ?: return@mapNotNull null
                val relationMatch = COVER_INFO_RELATION_TARGET.find(absolute)
                val relationRole =
                    when {
                        absolute.contains("initial-details=", ignoreCase = true) -> CoverInfoRelationRole.INITIAL
                        absolute.contains("follow-up-details=", ignoreCase = true) -> CoverInfoRelationRole.FOLLOW_UP
                        else -> CoverInfoRelationRole.NONE
                    }
                val songId = relationMatch?.groupValues?.getOrNull(1).orEmpty().ifBlank { match.groupValues[1] }
                if (!seenIds.add(songId)) return@mapNotNull null

                val slugTitle = match.groupValues[2]
                val slugArtist = match.groupValues.getOrNull(3).orEmpty()
                val row =
                    anchor.parents().firstOrNull { parent ->
                        parent.hasClass("youtube-parent")
                    }
                val container = row ?: anchor.parent()
                val contextText = container?.text().orEmpty()
                val titleFromText =
                    anchor.text()
                        .replace(Regex("\\s*\\((?:18|19|20)\\d{2}\\)\\s*$"), "")
                        .trim()
                val candidateTitle =
                    titleFromText.ifBlank {
                        slugTitle.replace('-', ' ').replace(Regex("\\s+"), " ").trim()
                    }
                // Relation links point through the root song URL. Therefore the slug
                // artist may describe the root, not this row. Prefer the row performer.
                val rowArtist =
                    row?.selectFirst(".field-artists a[href*='/artist/']")
                        ?.text()
                        ?.trim()
                        .orEmpty()
                val artistFromSlug =
                    slugArtist.replace("-and-", " & ")
                        .replace('-', ' ')
                        .replace(Regex("\\s+"), " ")
                        .trim()
                val artist = rowArtist.ifBlank { artistFromSlug }
                if (candidateTitle.isBlank() || artist.isBlank()) return@mapNotNull null
                if (candidateTitle.matches(Regex("""\\d+"""))) return@mapNotNull null

                val playbackVideoId =
                    row?.selectFirst(".youtube-id")
                        ?.text()
                        ?.trim()
                        ?.takeIf { COVER_INFO_YOUTUBE_ID.matches(it) }

                CoverInfoSeed(
                    songId = songId,
                    title = candidateTitle,
                    artist = artist,
                    url = if (relationRole == CoverInfoRelationRole.NONE) pathUrl else absolute.substringBefore('#'),
                    year = COVER_INFO_YEAR.find(contextText)?.value?.toIntOrNull(),
                    language =
                        COVER_INFO_LANGUAGES.firstOrNull { languageName ->
                            contextText.contains(languageName, ignoreCase = true)
                        },
                    playbackVideoId = playbackVideoId,
                    relationRole = relationRole,
                )
            }
    }
    private fun discoverWikidata(
        title: String,
        originalArtist: String,
    ): Pair<List<CoverSourceCandidate>, CoverSourceDiagnostic> {
        val lookup =
            runCatching { WikidataCoverSource.lookup(title, originalArtist) }
                .getOrElse { WikidataLookup(null, WikidataStatus.NETWORK_ERROR) }
        val aliasCount = lookup.work?.aliases.orEmpty().size
        return emptyList<CoverSourceCandidate>() to CoverSourceDiagnostic(
            name = "Wikidata",
            available = lookup.status != WikidataStatus.NETWORK_ERROR,
            found = aliasCount,
            note = if (aliasCount > 0) "$aliasCount alias/titoli alternativi" else lookup.status.name.lowercase(),
        )
    }

    private suspend fun discoverAi(
        title: String,
        originalArtist: String,
        mode: DiscogsDirectMode,
        config: GeminiCoverVerificationConfig?,
    ): Pair<List<CoverSourceCandidate>, CoverSourceDiagnostic> {
        if (mode != DiscogsDirectMode.COVER || config == null) {
            return emptyList<CoverSourceCandidate>() to CoverSourceDiagnostic(
                "AI Scout",
                false,
                0,
                "solo discovery Cover",
            )
        }

        val directRefs =
            runCatching {
                GeminiCoverVerification.discover(
                    originalTitle = title,
                    originalArtist = originalArtist,
                    config = config,
                )
            }.getOrDefault(emptyList())

        val directCandidates =
            directRefs.mapNotNull { ref ->
                if (ref.title.isBlank() || ref.artist.isBlank()) return@mapNotNull null
                val samePerformerAsOriginal =
                    originalArtist.isNotBlank() && sameArtist(ref.artist, originalArtist)
                val foreignOrAdapted =
                    ref.translatedOrAdaptedTitle || !sameBaseTitle(title, ref.title)
                CoverSourceCandidate(
                    title = ref.title,
                    artist = ref.artist,
                    sources = listOf("AI Scout"),
                    language = if (foreignOrAdapted) "straniera / adattamento AI" else null,
                    category =
                        if (foreignOrAdapted) AiCoverCategory.FOREIGN
                        else categoryFromTitle(ref.title),
                    workRelationConfirmed = true,
                    evidenceScore = if (samePerformerAsOriginal) 2 else 4,
                )
            }

        val cloudCandidates =
            if (config.cloudEndpoint.isNotBlank()) {
                runCatching {
                    CloudMusicDiscovery.discoverCover(
                        title = title,
                        artist = originalArtist,
                        config = config,
                        phase = "initial",
                        focus =
                            "Trova anche tutte le cover straniere/adattamenti della stessa composizione, " +
                                "inclusi titoli completamente diversi dalla traduzione letterale.",
                    )
                }.getOrNull()
                    ?.versions
                    .orEmpty()
                    .mapNotNull { candidate ->
                        if (candidate.title.isBlank() || candidate.artist.isBlank()) return@mapNotNull null
                        val foreignOrAdapted =
                            candidate.category == AiCoverCategory.FOREIGN ||
                                !sameBaseTitle(title, candidate.title)
                        CoverSourceCandidate(
                            title = candidate.title,
                            artist = candidate.artist,
                            sources = listOf("AI Scout"),
                            year = candidate.year,
                            album = candidate.album,
                            coverUrl = candidate.coverUrl,
                            language =
                                candidate.language
                                    ?: if (foreignOrAdapted) "straniera / adattamento AI" else null,
                            category =
                                if (foreignOrAdapted) AiCoverCategory.FOREIGN
                                else categoryFromTitle(candidate.title),
                            playbackVideoId = candidate.playbackVideoId,
                            playbackVideoTitle = candidate.playbackVideoTitle,
                            playbackVideoSource = candidate.playbackVideoSource,
                            workRelationConfirmed = true,
                            evidenceScore = 4,
                        )
                    }
            } else {
                emptyList()
            }

        val candidates =
            (directCandidates + cloudCandidates)
                .distinctBy { identity(it.title, it.artist, it.category) }

        return candidates to CoverSourceDiagnostic(
            "AI Scout",
            available = directRefs.isNotEmpty() || config.cloudEndpoint.isNotBlank(),
            found = candidates.size,
            note = "AI ammessa per identità opera/cover straniere; punteggio separato",
        )
    }

    private suspend fun fetchHtml(url: String): String? {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
        return runCatching {
            executeCancellable(request).use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.takeIf(String::isNotBlank)
            }
        }.getOrNull()
    }

    private suspend fun fetchObject(url: String): JSONObject? =
        fetchText(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private suspend fun fetchArray(url: String): JSONArray? =
        fetchText(url)?.let { runCatching { JSONArray(it) }.getOrNull() }

    private suspend fun fetchArrayWithRetry(url: String): JSONArray? {
        fetchArray(url)?.let { return it }
        delay(180)
        return fetchArray(url)
    }

    private suspend fun fetchText(url: String): String? {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
        return runCatching {
            executeCancellable(request).use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.takeIf(String::isNotBlank)
            }
        }.getOrNull()
    }

    private suspend fun executeCancellable(request: Request): okhttp3.Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, error: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(error))
                        }
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.success(response))
                        } else {
                            response.close()
                        }
                    }
                },
            )
        }

    private fun modeAcceptsArtist(
        mode: DiscogsDirectMode,
        artist: String,
        originalArtist: String,
    ): Boolean =
        when (mode) {
            DiscogsDirectMode.COVER -> true
            DiscogsDirectMode.ORIGINAL -> sameArtist(artist, originalArtist)
        }

    private fun sameBaseTitle(left: String, right: String): Boolean =
        TitleMeaningResolver.matchesBaseTitle(left, right)

    private fun sameArtist(left: String, right: String): Boolean {
        val a = canonicalArtist(left)
        val b = canonicalArtist(right)
        if (a.isBlank() || b.isBlank()) return false
        return a == b ||
            a.startsWith("$b ") ||
            a.endsWith(" $b") ||
            b.startsWith("$a ") ||
            b.endsWith(" $a")
    }

    private fun categoryFromTitle(title: String): AiCoverCategory {
        val text = title.lowercase()
        return when {
            REMIX.containsMatchIn(text) -> AiCoverCategory.REMIX
            LIVE.containsMatchIn(text) -> AiCoverCategory.LIVE
            else -> AiCoverCategory.COVER
        }
    }

    private fun identity(
        title: String,
        artist: String,
        category: AiCoverCategory,
    ): String = "${canonicalBaseTitle(title)}|${canonicalArtist(artist)}|${category.name}"

    private fun canonicalBaseTitle(value: String): String =
        canonical(value)
            .replace(VERSION_NOISE, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun canonicalArtist(value: String): String = canonical(value).removePrefix("the ")

    private fun canonical(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun parseYear(value: String?): Int? =
        value?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1800..2100 }

    private val LIVE = Regex("\\b(live|dal vivo|concert|concerto|performance|session|festival|unplugged|acoustic|tv|radio)\\b")
    private val REMIX = Regex("\\b(remix|rework|club mix|extended mix|radio mix|dance mix|dub mix|edit mix)\\b")
    private val VERSION_NOISE =
        Regex("\\b(official|music video|video|audio|lyrics?|lyric|visualizer|remaster(?:ed)?|version|versione|cover|live|dal vivo|concert|concerto|performance|session|festival|remix|mix|rework|radio edit|extended mix|club mix|edit|acoustic|unplugged|mono|stereo|hd|hq)\\b")

    private val LASTFM_PUBLIC_TRACK_URL =
        Regex("""(?:https?://(?:www\.)?last\.fm)?/music/([^/?#]+)/_/([^/?#]+)""")

    private const val COVER_INFO_RELATION_ROOT_LIMIT = 12
    private const val COVER_INFO_SONG_SELECTOR =
        ".field-title a[href^=/en/song/], .field-title a[href^=https://cover.info/en/song/]"
    private val COVER_INFO_YOUTUBE_ID = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val COVER_INFO_RELATION_TARGET =
        Regex("""[?&](?:follow-up-details|initial-details)=(\d+)""", RegexOption.IGNORE_CASE)
    private val COVER_INFO_SONG_URL =
        Regex("""https?://cover\.info/en/song/(\d+)/([^/?#]+)(?:/([^/?#]+))?""")
    private val COVER_INFO_YEAR = Regex("""\b(?:18|19|20)\d{2}\b""")
    private val COVER_INFO_LANGUAGES =
        listOf(
            "Italian", "English", "French", "Spanish", "German", "Portuguese",
            "Dutch", "Swedish", "Norwegian", "Danish", "Finnish", "Greek",
            "Japanese", "Korean", "Instrumental",
        )

    private const val USER_AGENT = "MusicLab-LAB40/1.0"
    private const val CACHE_TTL_MS = 30L * 60L * 1000L
}
