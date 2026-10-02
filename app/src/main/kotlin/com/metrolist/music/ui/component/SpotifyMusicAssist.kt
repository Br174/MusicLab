package com.metrolist.music.ui.component

import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

internal data class SpotifyCoverAssistStats(
    val available: Boolean = false,
    val discovered: Int = 0,
    val enriched: Int = 0,
)

internal data class SpotifyCoverAssistResult(
    val candidates: List<AiCoverCandidate>,
    val stats: SpotifyCoverAssistStats,
)

internal data class SpotifyOriginalHint(
    val title: String,
    val artist: String,
    val album: String?,
    val year: Int?,
    val durationSec: Int?,
    val isrc: String?,
) {
    val query: String
        get() = listOfNotNull(
            title.takeIf(String::isNotBlank),
            artist.takeIf(String::isNotBlank),
            album?.takeIf(String::isNotBlank),
            year?.toString(),
        ).joinToString(" ")
}

internal object SpotifyMusicAssist {
    private data class CachedTracks(
        val tracks: List<SpotifyTrack>,
        val expiresAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, CachedTracks>()

    suspend fun assistCover(
        originalTitle: String,
        originalArtist: String,
        existing: List<AiCoverCandidate>,
    ): SpotifyCoverAssistResult = withContext(Dispatchers.IO) {
        if (!Spotify.isAuthenticated() || originalTitle.isBlank()) {
            return@withContext SpotifyCoverAssistResult(
                candidates = existing,
                stats = SpotifyCoverAssistStats(available = false),
            )
        }

        val tracks = searchTracksCached(
            key = "cover|${canonical(originalTitle)}|${canonical(originalArtist)}",
            query = originalTitle,
            limit = COVER_SEARCH_LIMIT,
        )
        if (tracks.isEmpty()) {
            return@withContext SpotifyCoverAssistResult(
                candidates = existing,
                stats = SpotifyCoverAssistStats(available = true),
            )
        }

        val enrichedExisting = enrichCandidates(existing, tracks)
        val enrichedCount = existing.zip(enrichedExisting).count { (before, after) ->
            before.spotifyTrackId != after.spotifyTrackId ||
                before.spotifyIsrc != after.spotifyIsrc ||
                before.spotifyDurationSec != after.spotifyDurationSec ||
                before.album != after.album ||
                before.year != after.year
        }

        val existingKeys = enrichedExisting.map { it.stableKey }.toHashSet()
        val discovered = discoverCoverCandidates(originalTitle, originalArtist, tracks)
            .filter { it.stableKey !in existingKeys }

        SpotifyCoverAssistResult(
            candidates = (enrichedExisting + discovered).distinctBy { it.stableKey },
            stats = SpotifyCoverAssistStats(
                available = true,
                discovered = discovered.size,
                enriched = enrichedCount,
            ),
        )
    }

    suspend fun originalHints(
        title: String,
        originalArtists: List<String>,
    ): List<SpotifyOriginalHint> = withContext(Dispatchers.IO) {
        if (!Spotify.isAuthenticated() || title.isBlank() || originalArtists.isEmpty()) {
            return@withContext emptyList()
        }

        val leadArtist = originalArtists.firstOrNull().orEmpty()
        val query = listOf(title, leadArtist).filter(String::isNotBlank).joinToString(" ")
        val tracks = searchTracksCached(
            key = "originals|${canonical(title)}|${canonical(leadArtist)}",
            query = query,
            limit = ORIGINAL_SEARCH_LIMIT,
        )

        originalHintsFromTracks(
            title = title,
            originalArtists = originalArtists,
            tracks = tracks,
        )
    }

    internal fun discoverCoverCandidates(
        originalTitle: String,
        originalArtist: String,
        tracks: List<SpotifyTrack>,
    ): List<AiCoverCandidate> {
        val originalArtistCanonical = canonicalArtist(originalArtist)

        return tracks
            .asSequence()
            .filter { track -> TitleMeaningResolver.matchesBaseTitle(originalTitle, track.name) }
            .filter { track ->
                val trackArtists = track.artists.map { canonicalArtist(it.name) }
                trackArtists.none { artistSimilar(it, originalArtistCanonical) }
            }
            .filterNot { track -> DISALLOWED.containsMatchIn(track.name.lowercase()) }
            .mapNotNull { track ->
                val artist = track.artists.firstOrNull()?.name?.trim().orEmpty()
                if (artist.isBlank()) return@mapNotNull null

                val category = categoryFor(track.name)
                AiCoverCandidate(
                    title = track.name.trim(),
                    artist = artist,
                    category = category,
                    year = parseYear(track.album?.releaseDate),
                    album = track.album?.name?.takeIf(String::isNotBlank),
                    sameWorkScore = SPOTIFY_DISCOVERY_SAME_WORK_SCORE,
                    versionTypeScore = SPOTIFY_DISCOVERY_VERSION_SCORE,
                    brainStatus = AiBrainDecisionStatus.UNCERTAIN,
                    brainAdmission = "spotify_discovery",
                    brainSignals = listOf(
                        AiBrainSignal(
                            kind = "spotify_exact_title_discovery",
                            strength = "medium",
                            direction = "positive",
                        ),
                    ),
                    spotifyTrackId = track.id.takeIf(String::isNotBlank),
                    spotifyIsrc = track.isrc?.takeIf(String::isNotBlank),
                    spotifyDurationSec = track.durationMs.takeIf { it > 0 }?.div(1000),
                )
            }
            .distinctBy { it.stableKey }
            .toList()
    }

    internal fun enrichCandidates(
        candidates: List<AiCoverCandidate>,
        tracks: List<SpotifyTrack>,
    ): List<AiCoverCandidate> {
        if (candidates.isEmpty() || tracks.isEmpty()) return candidates

        return candidates.map { candidate ->
            val best = tracks
                .map { track ->
                    val trackArtist = track.artists.firstOrNull()?.name.orEmpty()
                    track to SpotifyMapper.matchScore(
                        spotifyTitle = track.name,
                        spotifyArtist = trackArtist,
                        spotifyDurationMs = track.durationMs,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artist,
                        candidateDurationSec = candidate.spotifyDurationSec,
                    )
                }
                .filter { (_, score) -> score >= SPOTIFY_ENRICH_THRESHOLD }
                .maxByOrNull { it.second }
                ?.first
                ?: return@map candidate

            candidate.copy(
                year = candidate.year ?: parseYear(best.album?.releaseDate),
                album = candidate.album ?: best.album?.name?.takeIf(String::isNotBlank),
                spotifyTrackId = best.id.takeIf(String::isNotBlank),
                spotifyIsrc = best.isrc?.takeIf(String::isNotBlank),
                spotifyDurationSec = best.durationMs.takeIf { it > 0 }?.div(1000),
                brainSignals = (
                    candidate.brainSignals +
                        AiBrainSignal(
                            kind = "spotify_metadata_match",
                            strength = "medium",
                            direction = "positive",
                        )
                    ).distinctBy { "${it.kind}|${it.strength}|${it.direction}" },
            )
        }
    }

    internal fun originalHintsFromTracks(
        title: String,
        originalArtists: List<String>,
        tracks: List<SpotifyTrack>,
    ): List<SpotifyOriginalHint> {
        val artistCanonicals = originalArtists.map(::canonicalArtist).filter(String::isNotBlank)

        return tracks
            .asSequence()
            .filter { TitleMeaningResolver.matchesBaseTitle(title, it.name) }
            .filter { track ->
                track.artists
                    .map { canonicalArtist(it.name) }
                    .any { candidate -> artistCanonicals.any { original -> artistSimilar(candidate, original) } }
            }
            .filterNot { DISALLOWED.containsMatchIn(it.name.lowercase()) }
            .mapNotNull { track ->
                val artist = track.artists.firstOrNull()?.name?.trim().orEmpty()
                if (artist.isBlank()) return@mapNotNull null
                SpotifyOriginalHint(
                    title = track.name.trim(),
                    artist = artist,
                    album = track.album?.name?.takeIf(String::isNotBlank),
                    year = parseYear(track.album?.releaseDate),
                    durationSec = track.durationMs.takeIf { it > 0 }?.div(1000),
                    isrc = track.isrc?.takeIf(String::isNotBlank),
                )
            }
            .distinctBy { "${canonical(it.title)}|${canonicalArtist(it.artist)}|${it.album.orEmpty()}|${it.year}" }
            .take(MAX_ORIGINAL_HINTS)
            .toList()
    }

    private suspend fun searchTracksCached(
        key: String,
        query: String,
        limit: Int,
    ): List<SpotifyTrack> {
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAtMs > now }?.let { return it.tracks }

        val tracks = withTimeoutOrNull(SPOTIFY_TIMEOUT_MS) {
            Spotify.search(
                query = query,
                types = listOf("track"),
                limit = limit,
            ).getOrNull()?.tracks?.items.orEmpty()
        }.orEmpty()

        if (tracks.isNotEmpty()) {
            cache[key] = CachedTracks(tracks, now + CACHE_TTL_MS)
        }
        return tracks
    }

    private fun categoryFor(title: String): AiCoverCategory {
        val raw = title.lowercase()
        return when {
            REMIX_MARKER.containsMatchIn(raw) -> AiCoverCategory.REMIX
            LIVE_MARKER.containsMatchIn(raw) -> AiCoverCategory.LIVE
            else -> AiCoverCategory.COVER
        }
    }

    private fun parseYear(value: String?): Int? =
        value
            ?.trim()
            ?.take(4)
            ?.toIntOrNull()
            ?.takeIf { it in 1900..2100 }

    private fun canonicalArtist(value: String): String = canonical(value).removePrefix("the ")

    private fun canonical(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun artistSimilar(candidate: String, target: String): Boolean {
        if (candidate.isBlank() || target.isBlank()) return false
        if (candidate == target) return true
        return candidate.startsWith("$target ") ||
            candidate.endsWith(" $target") ||
            candidate.contains(" $target ") ||
            target.startsWith("$candidate ") ||
            target.endsWith(" $candidate")
    }

    private val LIVE_MARKER =
        Regex("\\b(live|dal vivo|concert|concerto|performance|session|festival|unplugged|acoustic|tv|radio)\\b")
    private val REMIX_MARKER =
        Regex("\\b(remix|rework|club mix|extended mix|radio mix|dance mix|dub mix|edit mix)\\b")
    private val DISALLOWED =
        Regex("\\b(karaoke|reaction|tutorial|lesson|backing track|instrumental backing|mashup|medley)\\b")

    private const val SPOTIFY_TIMEOUT_MS = 4_500L
    private const val CACHE_TTL_MS = 10L * 60L * 1000L
    private const val COVER_SEARCH_LIMIT = 50
    private const val ORIGINAL_SEARCH_LIMIT = 40
    private const val MAX_ORIGINAL_HINTS = 20
    private const val SPOTIFY_DISCOVERY_SAME_WORK_SCORE = 62
    private const val SPOTIFY_DISCOVERY_VERSION_SCORE = 68
    private const val SPOTIFY_ENRICH_THRESHOLD = 0.78
}
