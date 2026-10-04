package com.metrolist.music.ui.component

import com.metrolist.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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

    suspend fun discover(
        title: String,
        originalArtist: String,
        mode: DiscogsDirectMode,
        aiConfig: GeminiCoverVerificationConfig?,
    ): CoverSourceOutcome = coroutineScope {
        val cleanTitle = title.trim()
        val cleanArtist = originalArtist.trim()
        if (cleanTitle.isBlank()) return@coroutineScope CoverSourceOutcome(emptyList(), emptyList())

        val key =
            listOf(
                mode.name,
                canonical(cleanTitle),
                canonical(cleanArtist),
                if (BuildConfig.LASTFM_API_KEY.isBlank()) "no-lastfm" else "lastfm",
                if (aiConfig == null) "no-ai" else "ai",
            ).joinToString("|")
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAtMs > now }?.let { return@coroutineScope it.outcome }

        val musicBrainz = async(Dispatchers.IO) { discoverMusicBrainz(cleanTitle, cleanArtist, mode) }
        val iTunes = async(Dispatchers.IO) { discoverITunes(cleanTitle, cleanArtist, mode) }
        val lastFm = async(Dispatchers.IO) { discoverLastFm(cleanTitle, cleanArtist, mode) }
        val lrcLib = async(Dispatchers.IO) { discoverLrcLib(cleanTitle, cleanArtist, mode) }
        val spotify = async(Dispatchers.IO) { discoverSpotify(cleanTitle, cleanArtist, mode) }
        val wikidata = async(Dispatchers.IO) { discoverWikidata(cleanTitle, cleanArtist) }
        val ai = async(Dispatchers.IO) { discoverAi(cleanTitle, cleanArtist, mode, aiConfig) }

        val lanes =
            listOf(
                musicBrainz.await(),
                iTunes.await(),
                lastFm.await(),
                lrcLib.await(),
                spotify.await(),
                wikidata.await(),
                ai.await(),
            )

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
                        evidenceScore =
                            (maxOf(previous.evidenceScore, candidate.evidenceScore) + extraSourceBonus)
                                .coerceIn(1, 10),
                    )
            }
        }

        val ordered =
            merged.values
                .sortedWith(
                    compareByDescending<CoverSourceCandidate> { it.evidenceScore }
                        .thenBy { it.year ?: Int.MAX_VALUE }
                        .thenBy { canonical(it.artist) },
                )

        val outcome = CoverSourceOutcome(
            candidates = ordered,
            diagnostics = lanes.map { it.second },
        )
        cache[key] = CacheEntry(now + CACHE_TTL_MS, outcome)
        outcome
    }

    private fun discoverMusicBrainz(
        title: String,
        originalArtist: String,
        mode: DiscogsDirectMode,
    ): Pair<List<CoverSourceCandidate>, CoverSourceDiagnostic> {
        if (mode == DiscogsDirectMode.ORIGINAL) {
            return emptyList<CoverSourceCandidate>() to CoverSourceDiagnostic(
                name = "MusicBrainz",
                available = true,
                found = 0,
                note = "usato come Work resolver; nessuna cover in Originali",
            )
        }
        val lookup =
            runCatching { MusicBrainzCoverSource.lookup(title, originalArtist) }
                .getOrElse { MusicBrainzLookup(emptyList(), MusicBrainzStatus.NETWORK_ERROR) }
        val candidates =
            lookup.covers.mapNotNull { cover ->
                val artist = cover.artist.trim()
                val candidateTitle = cover.title.trim()
                if (artist.isBlank() || candidateTitle.isBlank()) return@mapNotNull null
                if (sameArtist(artist, originalArtist)) return@mapNotNull null
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
                        if (sameBaseTitle(title, candidateTitle)) null else "titolo/adattamento alternativo",
                    sourceUrl = lookup.sourceUrl,
                    evidenceScore = 5,
                )
            }
        return candidates to CoverSourceDiagnostic(
            name = "MusicBrainz",
            available = lookup.status != MusicBrainzStatus.NETWORK_ERROR,
            found = candidates.size,
            note = lookup.status.name.lowercase(),
        )
    }

    private fun discoverITunes(
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
                if (!sameBaseTitle(title, candidateTitle)) continue
                if (!modeAcceptsArtist(mode, artist, originalArtist)) continue
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
                        category = categoryFromTitle(candidateTitle),
                        sourceUrl = item.optString("trackViewUrl").takeIf(String::isNotBlank),
                        evidenceScore = 2,
                    ),
                )
            }
        }.distinctBy { identity(it.title, it.artist, it.category) }
        return candidates to CoverSourceDiagnostic("Apple/iTunes", true, candidates.size, "catalogo senza chiave")
    }

    private fun discoverLastFm(
        title: String,
        originalArtist: String,
        mode: DiscogsDirectMode,
    ): Pair<List<CoverSourceCandidate>, CoverSourceDiagnostic> {
        val apiKey = BuildConfig.LASTFM_API_KEY.trim()
        if (apiKey.isBlank()) {
            return emptyList<CoverSourceCandidate>() to CoverSourceDiagnostic(
                name = "Last.fm",
                available = false,
                found = 0,
                note = "chiave non presente nella build",
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
        val root = fetchObject(url)
            ?: return emptyList<CoverSourceCandidate>() to CoverSourceDiagnostic(
                "Last.fm",
                false,
                0,
                "rete/non disponibile",
            )
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
                if (!sameBaseTitle(title, candidateTitle)) continue
                if (!modeAcceptsArtist(mode, artist, originalArtist)) continue
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
                        category = categoryFromTitle(candidateTitle),
                        sourceUrl = item.optString("url").takeIf(String::isNotBlank),
                        evidenceScore = 2,
                    ),
                )
            }
        }.distinctBy { identity(it.title, it.artist, it.category) }
        return candidates to CoverSourceDiagnostic("Last.fm", true, candidates.size, "track.search")
    }

    private fun discoverLrcLib(
        title: String,
        originalArtist: String,
        mode: DiscogsDirectMode,
    ): Pair<List<CoverSourceCandidate>, CoverSourceDiagnostic> {
        val url =
            "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", title)
                .build()
                .toString()
        val array = fetchArray(url)
            ?: return emptyList<CoverSourceCandidate>() to CoverSourceDiagnostic(
                "LRCLIB",
                false,
                0,
                "rete/non disponibile",
            )
        val candidates = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val candidateTitle = item.optString("trackName").trim()
                val artist = item.optString("artistName").trim()
                if (candidateTitle.isBlank() || artist.isBlank()) continue
                if (!sameBaseTitle(title, candidateTitle)) continue
                if (!modeAcceptsArtist(mode, artist, originalArtist)) continue
                add(
                    CoverSourceCandidate(
                        title = candidateTitle,
                        artist = artist,
                        sources = listOf("LRCLIB"),
                        album = item.optString("albumName").trim().takeIf(String::isNotBlank),
                        durationSeconds = item.optDouble("duration").takeIf { it > 0.0 }?.toInt(),
                        category = categoryFromTitle(candidateTitle),
                        sourceUrl = "https://lrclib.net",
                        evidenceScore = 2,
                    ),
                )
            }
        }.distinctBy { identity(it.title, it.artist, it.category) }
        return candidates to CoverSourceDiagnostic("LRCLIB", true, candidates.size, "catalogo/testi senza chiave")
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
                            evidenceScore = 2,
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
                        evidenceScore = 2,
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
        val refs =
            runCatching {
                GeminiCoverVerification.discover(
                    originalTitle = title,
                    originalArtist = originalArtist,
                    config = config,
                )
            }.getOrDefault(emptyList())
        val candidates =
            refs.mapNotNull { ref ->
                if (ref.title.isBlank() || ref.artist.isBlank()) return@mapNotNull null
                if (sameArtist(ref.artist, originalArtist)) return@mapNotNull null
                CoverSourceCandidate(
                    title = ref.title,
                    artist = ref.artist,
                    sources = listOf("AI Scout"),
                    language = if (ref.translatedOrAdaptedTitle) "straniera / adattamento" else null,
                    category =
                        if (ref.translatedOrAdaptedTitle) AiCoverCategory.FOREIGN
                        else categoryFromTitle(ref.title),
                    evidenceScore = 1,
                )
            }.distinctBy { identity(it.title, it.artist, it.category) }
        return candidates to CoverSourceDiagnostic(
            "AI Scout",
            available = true,
            found = candidates.size,
            note = "propone soltanto; non giudica",
        )
    }

    private fun fetchObject(url: String): JSONObject? =
        fetchText(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun fetchArray(url: String): JSONArray? =
        fetchText(url)?.let { runCatching { JSONArray(it) }.getOrNull() }

    private fun fetchText(url: String): String? {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.takeIf(String::isNotBlank)
            }
        }.getOrNull()
    }

    private fun modeAcceptsArtist(
        mode: DiscogsDirectMode,
        artist: String,
        originalArtist: String,
    ): Boolean =
        when (mode) {
            DiscogsDirectMode.COVER -> !sameArtist(artist, originalArtist)
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

    private const val USER_AGENT = "MusicLab-LAB38A/1.0"
    private const val CACHE_TTL_MS = 30L * 60L * 1000L
}
