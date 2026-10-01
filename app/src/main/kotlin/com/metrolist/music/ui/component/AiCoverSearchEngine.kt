/**
 * Resolves AI-decided cover/remix/live/foreign candidates to playable media.
 * YouTube and YouTube Music are playback locators only; editorial metadata stays AI-owned.
 */
package com.metrolist.music.ui.component

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.text.Normalizer

internal data class AiCoverPlayable(
    val candidate: AiCoverCandidate,
    val song: SongItem,
    val playbackSource: String,
)

internal data class AiCoverResolveStats(
    val requested: Int = 0,
    val playable: Int = 0,
    val youtubeMusicHits: Int = 0,
    val youtubeHits: Int = 0,
)

internal data class AiCoverResolveResult(
    val playables: List<AiCoverPlayable>,
    val stats: AiCoverResolveStats,
)

internal object AiCoverSearchEngine {
    suspend fun resolveCandidates(
        candidates: List<AiCoverCandidate>,
        currentYouTubeId: String?,
        pauseBetweenBatches: Boolean,
    ): AiCoverResolveResult = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope AiCoverResolveResult(emptyList(), AiCoverResolveStats())

        val resolved = mutableListOf<AiCoverPlayable>()
        var musicHits = 0
        var videoHits = 0

        for ((batchIndex, batch) in candidates.distinctBy { it.stableKey }.chunked(5).withIndex()) {
            val batchResolved = batch.map { candidate ->
                async(Dispatchers.IO) { resolveOne(candidate, currentYouTubeId) }
            }.awaitAll().filterNotNull()

            batchResolved.forEach { playable ->
                if (playable.song.id !in resolved.map { it.song.id }) {
                    resolved += playable
                    if (playable.playbackSource == "YouTube Music") musicHits++ else videoHits++
                }
            }

            if (pauseBetweenBatches && batchIndex < candidates.chunked(5).lastIndex) {
                delay(BACKGROUND_PAUSE_MS)
            }
        }

        AiCoverResolveResult(
            playables = resolved.sortedWith(playableOrder),
            stats = AiCoverResolveStats(
                requested = candidates.size,
                playable = resolved.size,
                youtubeMusicHits = musicHits,
                youtubeHits = videoHits,
            ),
        )
    }

    private suspend fun resolveOne(
        candidate: AiCoverCandidate,
        currentYouTubeId: String?,
    ): AiCoverPlayable? {
        val languageSuffix = candidate.language?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        val queries = when (candidate.category) {
            AiCoverCategory.COVER -> listOf(
                "${candidate.title} ${candidate.artist}",
                "${candidate.artist} ${candidate.title}",
                "${candidate.title} ${candidate.artist} official audio",
                "${candidate.title} ${candidate.artist} cover",
                "${candidate.title} ${candidate.artist} lyrics",
            )
            AiCoverCategory.FOREIGN -> listOf(
                "${candidate.title} ${candidate.artist}$languageSuffix",
                "${candidate.artist} ${candidate.title}$languageSuffix",
                "${candidate.title} ${candidate.artist} official audio$languageSuffix",
                "${candidate.title} ${candidate.artist} lyrics$languageSuffix",
            )
            AiCoverCategory.REMIX -> listOf(
                "${candidate.title} ${candidate.artist} remix",
                "${candidate.artist} ${candidate.title} remix",
                "${candidate.title} ${candidate.artist} rework",
                "${candidate.title} ${candidate.artist} mix",
            )
            AiCoverCategory.LIVE -> listOf(
                "${candidate.title} ${candidate.artist} live",
                "${candidate.artist} ${candidate.title} live",
                "${candidate.title} ${candidate.artist} performance",
                "${candidate.title} ${candidate.artist} session",
            )
        }.distinct()

        // STEP playback 1: YouTube Music su tutte le query, perché offre album/cover più puliti.
        for (query in queries) {
            searchAndPick(query, YouTube.SearchFilter.FILTER_SONG, candidate, currentYouTubeId)?.let {
                return datedPlayable(candidate, it, "YouTube Music")
            }
        }

        // STEP playback 2: se YTM non localizza la versione, usa il catalogo video YouTube.
        for (query in queries) {
            searchAndPick(query, YouTube.SearchFilter.FILTER_VIDEO, candidate, currentYouTubeId)?.let {
                return datedPlayable(candidate, it, "YouTube")
            }
        }
        return null
    }

    /**
     * Se Gemini non conosce l'anno, prova il metadato reale dell'album YouTube Music.
     * Non inventa mai una data: se entrambe le fonti non la espongono resta null.
     */
    private suspend fun datedPlayable(
        candidate: AiCoverCandidate,
        song: SongItem,
        playbackSource: String,
    ): AiCoverPlayable {
        val datedCandidate = if (candidate.year != null) {
            candidate
        } else {
            candidate.copy(year = CoverYearResolver.resolve(song))
        }
        return AiCoverPlayable(datedCandidate, song, playbackSource)
    }

    internal fun isPlaybackTitleCompatible(
        candidate: AiCoverCandidate,
        rawSongTitle: String,
    ): Boolean {
        val targetTitle = canonicalTitle(candidate.title)
        if (targetTitle.isBlank()) return false

        var resolvedTitle = canonicalTitle(rawSongTitle)
        val targetArtist = canonicalArtist(candidate.artist)
        if (targetArtist.isNotBlank()) {
            resolvedTitle = when {
                resolvedTitle.startsWith("$targetArtist ") -> resolvedTitle.removePrefix("$targetArtist ").trim()
                resolvedTitle.endsWith(" $targetArtist") -> resolvedTitle.removeSuffix(" $targetArtist").trim()
                else -> resolvedTitle
            }
        }
        return resolvedTitle == targetTitle
    }

    private suspend fun searchAndPick(
        query: String,
        filter: YouTube.SearchFilter,
        candidate: AiCoverCandidate,
        currentYouTubeId: String?,
    ): SongItem? {
        val page = runCatching { YouTube.search(query, filter).getOrThrow() }.getOrNull() ?: return null
        val songs = page.items
            .filterIsInstance<SongItem>()
            .filter { it.id != currentYouTubeId }
        return bestMatch(songs, candidate)
    }

    private fun bestMatch(
        songs: List<SongItem>,
        candidate: AiCoverCandidate,
    ): SongItem? {
        if (songs.isEmpty()) return null
        val targetTitle = canonicalTitle(candidate.title)
        val targetArtist = canonicalArtist(candidate.artist)

        return songs
            .map { song -> song to score(song, targetTitle, targetArtist, candidate) }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun score(
        song: SongItem,
        targetTitle: String,
        targetArtist: String,
        candidate: AiCoverCandidate,
    ): Int {
        if (!isPlaybackTitleCompatible(candidate, song.title)) return 0

        val songTitle = canonicalTitle(song.title)
        val artistNames = song.artists.map { canonicalArtist(it.name) }
        val titleHasArtist = tokenPhrase(songTitle, targetArtist)
        val artistMatch = artistNames.any { artistSimilar(it, targetArtist) } || titleHasArtist
        if (!artistMatch) return 0

        val raw = song.title.lowercase()
        val liveLike = LIVE_MARKER.containsMatchIn(raw)
        val remixLike = REMIX_MARKER.containsMatchIn(raw)

        // La categoria è decisa dall'AI. I metadati YouTube possono soltanto scartare
        // una contraddizione evidente, non stabilire la categoria editoriale.
        when (candidate.category) {
            AiCoverCategory.COVER,
            AiCoverCategory.FOREIGN,
            -> if (liveLike || remixLike) return 0
            AiCoverCategory.LIVE -> if (remixLike) return 0
            AiCoverCategory.REMIX -> if (liveLike && !remixLike) return 0
        }

        var score = 35
        when {
            songTitle == targetTitle -> score += 55
            tokenPhrase(songTitle, targetTitle) -> score += 42
            tokenPhrase(targetTitle, songTitle) && songTitle.length >= 4 -> score += 30
            else -> return 0
        }

        when (candidate.category) {
            AiCoverCategory.LIVE -> if (liveLike) score += 16 else score += 5
            AiCoverCategory.REMIX -> if (remixLike) score += 16 else score += 5
            AiCoverCategory.FOREIGN -> score += 10
            AiCoverCategory.COVER -> score += 8
        }
        if (DISALLOWED.containsMatchIn(raw)) score -= 90
        return score
    }

    private fun canonicalTitle(value: String): String =
        canonical(value)
            .replace(VERSION_WORDS, " ")
            .trim()
            .replace(Regex("\\s+"), " ")

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

    private fun tokenPhrase(value: String, phrase: String): Boolean {
        if (value.isBlank() || phrase.isBlank()) return false
        if (value == phrase) return true
        return value.startsWith("$phrase ") || value.endsWith(" $phrase") || value.contains(" $phrase ")
    }

    private val playableOrder =
        compareBy<AiCoverPlayable> { if (it.candidate.year == null) 1 else 0 }
            .thenBy { it.candidate.year ?: Int.MAX_VALUE }
            .thenBy { it.candidate.artist.lowercase() }

    private val VERSION_WORDS = Regex(
        "\\b(official|music video|video|audio|lyrics?|visualizer|remaster(?:ed)?|version|versione|cover|live|dal vivo|concert|concerto|performance|session|festival|remix|mix|rework|radio edit|extended mix|club mix|edit|hd|hq)\\b",
    )
    private val LIVE_MARKER = Regex(
        "\\b(live|dal vivo|concert|concerto|performance|session|festival|unplugged|tiny desk|kexp|bbc session|mtv unplugged)\\b",
    )
    private val REMIX_MARKER = Regex(
        "\\b(remix|rework|club mix|extended mix|radio mix|dance mix|dub mix|edit mix)\\b",
    )
    private val DISALLOWED = Regex(
        "\\b(karaoke|reaction|tutorial|lesson|backing track|instrumental backing|mashup|medley)\\b",
    )

    private const val MIN_SCORE = 68
    private const val BACKGROUND_PAUSE_MS = 45L
}
