/**
 * Resolves AI-decided cover/remix/live/foreign candidates to playable media.
 * The MusicLab Brain owns musical identity; playback providers only locate a playable video id.
 *
 * LAB23 Spotify + Fast First Results (extends LAB22 Cover Web Locator):
 * - keeps YouTube Music for clean catalogue matches;
 * - adds a genuinely separate www.youtube.com WEB search lane;
 * - uses the real YouTube WEB continuation tokens for rare/user-uploaded versions;
 * - supports an exhaustive mode used by the Cover "Tutto" control.
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
        exhaustive: Boolean = false,
        fastOnly: Boolean = false,
    ): AiCoverResolveResult = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope AiCoverResolveResult(emptyList(), AiCoverResolveStats())

        val resolved = mutableListOf<AiCoverPlayable>()
        var musicHits = 0
        var videoHits = 0

        val uniqueCandidates = candidates.distinctBy { it.stableKey }
        val batchSize = if (exhaustive) EXHAUSTIVE_BATCH_SIZE else NORMAL_BATCH_SIZE
        val batches = uniqueCandidates.chunked(batchSize)

        for ((batchIndex, batch) in batches.withIndex()) {
            val batchResolved =
                batch.map { candidate ->
                    async(Dispatchers.IO) { resolveOne(candidate, currentYouTubeId, exhaustive, fastOnly) }
                }.awaitAll().filterNotNull()

            batchResolved.forEach { playable ->
                if (playable.song.id !in resolved.map { it.song.id }) {
                    resolved += playable
                    if (playable.playbackSource.startsWith("YouTube Music")) {
                        musicHits++
                    } else {
                        videoHits++
                    }
                }
            }

            if (pauseBetweenBatches && batchIndex < batches.lastIndex) {
                delay(if (exhaustive) EXHAUSTIVE_BACKGROUND_PAUSE_MS else BACKGROUND_PAUSE_MS)
            }
        }

        AiCoverResolveResult(
            playables = resolved.sortedWith(playableOrder),
            stats =
                AiCoverResolveStats(
                    requested = uniqueCandidates.size,
                    playable = resolved.size,
                    youtubeMusicHits = musicHits,
                    youtubeHits = videoHits,
                ),
        )
    }

    private suspend fun resolveOne(
        candidate: AiCoverCandidate,
        currentYouTubeId: String?,
        exhaustive: Boolean,
        fastOnly: Boolean,
    ): AiCoverPlayable? {
        val queries = locatorQueries(candidate)
        if (queries.isEmpty()) return null
        val canonicalQuery = queries.first()

        // 1) Clean catalogue hit from YouTube Music.
        searchMusicAndPick(
            query = canonicalQuery,
            filter = YouTube.SearchFilter.FILTER_SONG,
            candidate = candidate,
            currentYouTubeId = currentYouTubeId,
            maxPages = 1,
            allowUploaderFallback = false,
        )?.let { return datedPlayable(candidate, it, "YouTube Music") }

        // 2) Real youtube.com WEB lane. This is deliberately NOT music.youtube.com.
        searchWebAndPick(
            query = canonicalQuery,
            candidate = candidate,
            currentYouTubeId = currentYouTubeId,
            maxPages = 1,
        )?.let { return datedPlayable(candidate, it, "YouTube") }

        // 3) Retain the historical YTM video shelf as an additional source, but name it honestly.
        searchMusicAndPick(
            query = canonicalQuery,
            filter = YouTube.SearchFilter.FILTER_VIDEO,
            candidate = candidate,
            currentYouTubeId = currentYouTubeId,
            maxPages = 1,
            allowUploaderFallback = true,
        )?.let { return datedPlayable(candidate, it, "YouTube Music video") }

        // LAB23 first-paint lane: never make the user wait for metadata variants or deep pages.
        // The same candidate is retried by the normal background resolver immediately afterwards.
        if (fastOnly) return null

        val metadataLimit = if (exhaustive) EXHAUSTIVE_METADATA_QUERY_LIMIT else METADATA_QUERY_LIMIT
        for (query in queries.drop(1).take(metadataLimit)) {
            searchWebAndPick(
                query = query,
                candidate = candidate,
                currentYouTubeId = currentYouTubeId,
                maxPages = 1,
            )?.let { return datedPlayable(candidate, it, "YouTube") }

            searchMusicAndPick(
                query = query,
                filter = YouTube.SearchFilter.FILTER_SONG,
                candidate = candidate,
                currentYouTubeId = currentYouTubeId,
                maxPages = 1,
                allowUploaderFallback = false,
            )?.let { return datedPlayable(candidate, it, "YouTube Music") }
        }

        // 4) Deep web recovery for rare Italian/archival/user-uploaded versions.
        val webQueryLimit = if (exhaustive) EXHAUSTIVE_WEB_QUERY_LIMIT else WEB_QUERY_LIMIT
        val webPages = if (exhaustive) EXHAUSTIVE_WEB_PAGES else WEB_PAGES
        for (query in queries.take(webQueryLimit)) {
            searchWebAndPick(
                query = query,
                candidate = candidate,
                currentYouTubeId = currentYouTubeId,
                maxPages = webPages,
            )?.let { return datedPlayable(candidate, it, "YouTube") }
        }

        // 5) Final YTM continuation fallback.
        searchMusicAndPick(
            query = canonicalQuery,
            filter = YouTube.SearchFilter.FILTER_VIDEO,
            candidate = candidate,
            currentYouTubeId = currentYouTubeId,
            maxPages = if (exhaustive) EXHAUSTIVE_MUSIC_VIDEO_PAGES else DEEP_VIDEO_PAGES,
            allowUploaderFallback = true,
        )?.let { return datedPlayable(candidate, it, "YouTube Music video") }

        searchMusicAndPick(
            query = canonicalQuery,
            filter = YouTube.SearchFilter.FILTER_SONG,
            candidate = candidate,
            currentYouTubeId = currentYouTubeId,
            maxPages = if (exhaustive) EXHAUSTIVE_MUSIC_PAGES else DEEP_MUSIC_PAGES,
            allowUploaderFallback = false,
        )?.let { return datedPlayable(candidate, it, "YouTube Music") }

        return null
    }

    /**
     * Retrieval hints only. The Brain has already decided that the musical version exists.
     */
    internal fun locatorQueries(candidate: AiCoverCandidate): List<String> {
        val title = candidate.title.trim()
        val artist = candidate.artist.trim()
        if (title.isBlank() || artist.isBlank()) return emptyList()

        val categoryTerm =
            when (candidate.category) {
                AiCoverCategory.COVER -> "cover"
                AiCoverCategory.FOREIGN -> candidate.language?.trim()?.takeIf { it.isNotBlank() } ?: "version"
                AiCoverCategory.REMIX -> "remix"
                AiCoverCategory.LIVE -> "live"
            }
        val creditHints =
            (candidate.songwriters + candidate.composers + candidate.lyricists)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(2)

        return buildList {
            add("$title $artist")
            add("$artist $title")

            candidate.album?.trim()?.takeIf { it.isNotBlank() }?.let {
                add("$title $artist $it")
            }
            candidate.year?.let {
                add("$title $artist $it")
            }

            add("$title $artist $categoryTerm")

            creditHints.forEach { credit ->
                add("$title $artist $credit")
            }

            when (candidate.category) {
                AiCoverCategory.COVER -> {
                    add("$title $artist official audio")
                    add("$title $artist studio")
                    add("$title $artist lyrics")
                }
                AiCoverCategory.LIVE -> {
                    add("$title $artist performance")
                    add("$title $artist session")
                }
                AiCoverCategory.REMIX -> {
                    add("$title $artist rework")
                    add("$title $artist mix")
                }
                AiCoverCategory.FOREIGN -> Unit
            }
        }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_QUERY_VARIANTS)
    }

    private suspend fun datedPlayable(
        candidate: AiCoverCandidate,
        song: SongItem,
        playbackSource: String,
    ): AiCoverPlayable {
        val datedCandidate =
            if (candidate.year != null) {
                candidate
            } else {
                val resolvedYear = CoverYearResolver.resolve(song)
                candidate.copy(
                    year = resolvedYear,
                    yearSource = resolvedYear?.let { "youtube_music" },
                )
            }
        return AiCoverPlayable(datedCandidate, song, playbackSource)
    }

    internal fun isPlaybackTitleCompatible(
        candidate: AiCoverCandidate,
        rawSongTitle: String,
    ): Boolean =
        TitleMeaningResolver.matchesBaseTitle(
            targetTitle = candidate.title,
            value = rawSongTitle,
            artistAliases = setOf(candidate.artist),
        )

    private suspend fun searchMusicAndPick(
        query: String,
        filter: YouTube.SearchFilter,
        candidate: AiCoverCandidate,
        currentYouTubeId: String?,
        maxPages: Int,
        allowUploaderFallback: Boolean,
    ): SongItem? {
        if (maxPages <= 0) return null

        var page = runCatching { YouTube.search(query, filter).getOrThrow() }.getOrNull() ?: return null
        repeat(maxPages) { pageIndex ->
            val songs =
                page.items
                    .filterIsInstance<SongItem>()
                    .filter { it.id != currentYouTubeId }

            bestMatch(
                songs = songs,
                candidate = candidate,
                allowUploaderFallback = allowUploaderFallback,
            )?.let { return it }

            if (pageIndex >= maxPages - 1) return null
            val continuation = page.continuation ?: return null
            page = runCatching { YouTube.searchContinuation(continuation).getOrThrow() }.getOrNull() ?: return null
        }
        return null
    }

    private suspend fun searchWebAndPick(
        query: String,
        candidate: AiCoverCandidate,
        currentYouTubeId: String?,
        maxPages: Int,
    ): SongItem? {
        if (maxPages <= 0) return null

        var continuation: String? = null
        repeat(maxPages) { pageIndex ->
            val page =
                runCatching { YouTubeWebSearch.search(query, continuation) }
                    .getOrNull()
                    ?: return null
            val songs = page.items.filter { it.id != currentYouTubeId }
            bestMatch(
                songs = songs,
                candidate = candidate,
                allowUploaderFallback = true,
            )?.let { return it }

            if (pageIndex >= maxPages - 1) return null
            continuation = page.continuation ?: return null
        }
        return null
    }

    private fun bestMatch(
        songs: List<SongItem>,
        candidate: AiCoverCandidate,
        allowUploaderFallback: Boolean,
    ): SongItem? {
        if (songs.isEmpty()) return null
        val targetTitle = canonicalTitle(candidate.title)
        val targetArtist = canonicalArtist(candidate.artist)

        return songs
            .mapIndexed { index, song ->
                val safeUploaderFallback = allowUploaderFallback && index < UPLOADER_FALLBACK_TOP_RESULTS
                song to score(song, targetTitle, targetArtist, candidate, safeUploaderFallback)
            }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { it.second }
            ?.first
    }

    internal fun scoreForTest(
        song: SongItem,
        candidate: AiCoverCandidate,
        allowUploaderFallback: Boolean,
    ): Int =
        score(
            song = song,
            targetTitle = canonicalTitle(candidate.title),
            targetArtist = canonicalArtist(candidate.artist),
            candidate = candidate,
            allowUploaderFallback = allowUploaderFallback,
        )

    private fun score(
        song: SongItem,
        targetTitle: String,
        targetArtist: String,
        candidate: AiCoverCandidate,
        allowUploaderFallback: Boolean,
    ): Int {
        if (!isPlaybackTitleCompatible(candidate, song.title)) return 0

        val songTitle = canonicalTitle(song.title)
        val rawSongTitle = canonical(song.title)
        val artistNames = song.artists.map { canonicalArtist(it.name) }
        val titleHasArtist = tokenPhrase(rawSongTitle, targetArtist)
        val artistMatch = artistNames.any { artistSimilar(it, targetArtist) }
        val targetAlbum = canonical(candidate.album.orEmpty())
        val songAlbum = canonical(song.album?.name.orEmpty())
        val albumMatch = targetAlbum.isNotBlank() && metadataSimilar(songAlbum, targetAlbum)
        val exactTitle = songTitle == targetTitle

        val identitySupported =
            artistMatch ||
                titleHasArtist ||
                albumMatch ||
                (allowUploaderFallback && exactTitle)
        if (!identitySupported) return 0

        val raw = song.title.lowercase()
        val liveLike = LIVE_MARKER.containsMatchIn(raw)
        val remixLike = REMIX_MARKER.containsMatchIn(raw)

        when (candidate.category) {
            AiCoverCategory.COVER,
            AiCoverCategory.FOREIGN,
            -> if (liveLike || remixLike) return 0
            AiCoverCategory.LIVE -> if (remixLike) return 0
            AiCoverCategory.REMIX -> if (liveLike && !remixLike) return 0
        }

        var score =
            when {
                exactTitle -> 52
                tokenPhrase(songTitle, targetTitle) -> 42
                tokenPhrase(targetTitle, songTitle) && songTitle.length >= 4 -> 30
                else -> return 0
            }

        if (artistMatch) score += 34
        if (titleHasArtist) score += 30
        if (albumMatch) score += 20

        val spotifyDuration = candidate.spotifyDurationSec
        val songDuration = song.duration
        if (spotifyDuration != null && songDuration != null) {
            val durationDiff = kotlin.math.abs(spotifyDuration - songDuration)
            if (durationDiff <= 3) score += 10
            else if (durationDiff <= 8) score += 6
            else if (durationDiff <= 15) score += 3
        }

        if (allowUploaderFallback && exactTitle && !artistMatch && !titleHasArtist && !albumMatch) {
            score += 18
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

    private fun metadataSimilar(candidate: String, target: String): Boolean {
        if (candidate.isBlank() || target.isBlank()) return false
        if (candidate == target) return true
        return tokenPhrase(candidate, target) || tokenPhrase(target, candidate)
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

    private val VERSION_WORDS =
        Regex(
            "\\b(official|music video|video|audio|lyrics?|visualizer|remaster(?:ed)?|version|versione|cover|live|dal vivo|concert|concerto|performance|session|festival|remix|mix|rework|radio edit|extended mix|club mix|edit|hd|hq)\\b",
        )
    private val LIVE_MARKER =
        Regex(
            "\\b(live|dal vivo|concert|concerto|performance|session|festival|unplugged|tiny desk|kexp|bbc session|mtv unplugged)\\b",
        )
    private val REMIX_MARKER =
        Regex(
            "\\b(remix|rework|club mix|extended mix|radio mix|dance mix|dub mix|edit mix)\\b",
        )
    private val DISALLOWED =
        Regex(
            "\\b(karaoke|reaction|tutorial|lesson|backing track|instrumental backing|mashup|medley)\\b",
        )

    private const val MIN_SCORE = 68
    private const val NORMAL_BATCH_SIZE = 5
    private const val EXHAUSTIVE_BATCH_SIZE = 3
    private const val BACKGROUND_PAUSE_MS = 45L
    private const val EXHAUSTIVE_BACKGROUND_PAUSE_MS = 80L
    private const val MAX_QUERY_VARIANTS = 9
    private const val METADATA_QUERY_LIMIT = 3
    private const val EXHAUSTIVE_METADATA_QUERY_LIMIT = 5
    private const val WEB_QUERY_LIMIT = 3
    private const val EXHAUSTIVE_WEB_QUERY_LIMIT = 4
    private const val WEB_PAGES = 2
    private const val EXHAUSTIVE_WEB_PAGES = 3
    private const val DEEP_VIDEO_PAGES = 2
    private const val EXHAUSTIVE_MUSIC_VIDEO_PAGES = 3
    private const val DEEP_MUSIC_PAGES = 2
    private const val EXHAUSTIVE_MUSIC_PAGES = 3
    private const val UPLOADER_FALLBACK_TOP_RESULTS = 5
}
