/**
 * Resolves AI-decided cover/remix/live candidates to playable media.
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

        for ((batchIndex, batch) in candidates.distinctBy { it.stableKey }.chunked(4).withIndex()) {
            val batchResolved = batch.map { candidate ->
                async(Dispatchers.IO) { resolveOne(candidate, currentYouTubeId) }
            }.awaitAll().filterNotNull()

            batchResolved.forEach { playable ->
                if (playable.song.id !in resolved.map { it.song.id }) {
                    resolved += playable
                    if (playable.playbackSource == "YouTube Music") musicHits++ else videoHits++
                }
            }

            if (pauseBetweenBatches && batchIndex < candidates.chunked(4).lastIndex) {
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
    ): AiCoverPlayable? = coroutineScope {
        val queries = when (candidate.category) {
            AiCoverCategory.COVER -> listOf(
                "${candidate.title} ${candidate.artist}",
            )
            AiCoverCategory.REMIX -> listOf(
                "${candidate.title} ${candidate.artist} remix",
                "${candidate.title} ${candidate.artist}",
            )
            AiCoverCategory.LIVE -> listOf(
                "${candidate.title} ${candidate.artist} live",
                "${candidate.title} ${candidate.artist}",
            )
        }

        for (query in queries) {
            val musicDeferred = async(Dispatchers.IO) {
                runCatching { YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow() }.getOrNull()
            }
            val videoDeferred = async(Dispatchers.IO) {
                runCatching { YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO).getOrThrow() }.getOrNull()
            }

            val music = musicDeferred.await()
                ?.items
                ?.filterIsInstance<SongItem>()
                ?.filter { it.id != currentYouTubeId }
                .orEmpty()
            val video = videoDeferred.await()
                ?.items
                ?.filterIsInstance<SongItem>()
                ?.filter { it.id != currentYouTubeId }
                .orEmpty()

            bestMatch(music, candidate)?.let {
                return@coroutineScope AiCoverPlayable(candidate, it, "YouTube Music")
            }
            bestMatch(video, candidate)?.let {
                return@coroutineScope AiCoverPlayable(candidate, it, "YouTube")
            }
        }
        null
    }

    private fun bestMatch(
        songs: List<SongItem>,
        candidate: AiCoverCandidate,
    ): SongItem? {
        if (songs.isEmpty()) return null
        val targetTitle = canonicalTitle(candidate.title)
        val targetArtist = canonicalArtist(candidate.artist)

        return songs
            .map { song -> song to score(song, targetTitle, targetArtist, candidate.category) }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun score(
        song: SongItem,
        targetTitle: String,
        targetArtist: String,
        category: AiCoverCategory,
    ): Int {
        val songTitle = canonicalTitle(song.title)
        val artistNames = song.artists.map { canonicalArtist(it.name) }
        val titleHasArtist = tokenPhrase(songTitle, targetArtist)
        val artistMatch = artistNames.any { artistSimilar(it, targetArtist) } || titleHasArtist

        if (!artistMatch) return 0

        var score = 35
        when {
            songTitle == targetTitle -> score += 55
            tokenPhrase(songTitle, targetTitle) -> score += 42
            tokenPhrase(targetTitle, songTitle) && songTitle.length >= 4 -> score += 30
            else -> return 0
        }

        val raw = song.title.lowercase()
        when (category) {
            AiCoverCategory.LIVE -> if (LIVE_MARKER.containsMatchIn(raw)) score += 12
            AiCoverCategory.REMIX -> if (REMIX_MARKER.containsMatchIn(raw)) score += 12
            AiCoverCategory.COVER -> Unit
        }
        if (DISALLOWED.containsMatchIn(raw)) score -= 80
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
        "\\b(official|music video|video|audio|lyrics?|visualizer|remaster(?:ed)?|version|versione|cover|live|dal vivo|concert|concerto|performance|remix|mix|rework|radio edit|edit|hd|hq)\\b",
    )
    private val LIVE_MARKER = Regex("\\b(live|dal vivo|concert|concerto|performance|session|festival)\\b")
    private val REMIX_MARKER = Regex("\\b(remix|mix|rework|radio edit|club mix|extended mix)\\b")
    private val DISALLOWED = Regex("\\b(karaoke|reaction|tutorial|lesson|backing track|instrumental backing|mashup|medley)\\b")

    private const val MIN_SCORE = 70
    private const val BACKGROUND_PAUSE_MS = 80L
}
