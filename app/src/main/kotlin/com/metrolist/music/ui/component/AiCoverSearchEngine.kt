/**
 * Resolves AI-decided cover/remix/live/foreign candidates to playable media.
 * YouTube and YouTube Music are playback locators only; editorial metadata stays AI-owned.
 *
 * LAB21 Deep Locator:
 * - searches the easy first-page path first;
 * - expands queries with album/year/credits already known by the Brain;
 * - follows YouTube/YTM continuation pages only when the fast path fails;
 * - treats a YouTube uploader/channel mismatch as a recoverable locator condition for exact-title videos,
 *   instead of an automatic veto.
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

        val uniqueCandidates = candidates.distinctBy { it.stableKey }
        val batches = uniqueCandidates.chunked(5)
        for ((batchIndex, batch) in batches.withIndex()) {
            val batchResolved = batch.map { candidate ->
                async(Dispatchers.IO) { resolveOne(candidate, currentYouTubeId) }
            }.awaitAll().filterNotNull()

            batchResolved.forEach { playable ->
                if (playable.song.id !in resolved.map { it.song.id }) {
                    resolved += playable
                    if (playable.playbackSource == "YouTube Music") musicHits++ else videoHits++
                }
            }

            if (pauseBetweenBatches && batchIndex < batches.lastIndex) {
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
        val queries = locatorQueries(candidate)
        if (queries.isEmpty()) return null

        // FAST PATH: preserve the cheap/common case. One canonical query against YTM and YouTube.
        val canonicalQuery = queries.first()
        searchAndPick(
            query = canonicalQuery,
            filter = YouTube.SearchFilter.FILTER_SONG,
            candidate = candidate,
            currentYouTubeId = currentYouTubeId,
            maxPages = 1,
            allowUploaderFallback = false,
        )?.let { return datedPlayable(candidate, it, "YouTube Music") }

        searchAndPick(
            query = canonicalQuery,
            filter = YouTube.SearchFilter.FILTER_VIDEO,
            candidate = candidate,
            currentYouTubeId = currentYouTubeId,
            maxPages = 1,
            allowUploaderFallback = true,
        )?.let { return datedPlayable(candidate, it, "YouTube") }

        // METADATA PATH: title/artist alone is often insufficient for old, user-uploaded or obscure covers.
        // Reuse the Brain metadata as search hints, never as a YouTube editorial verdict.
        for (query in queries.drop(1).take(METADATA_QUERY_LIMIT)) {
            searchAndPick(
                query = query,
                filter = YouTube.SearchFilter.FILTER_SONG,
                candidate = candidate,
                currentYouTubeId = currentYouTubeId,
                maxPages = 1,
                allowUploaderFallback = false,
            )?.let { return datedPlayable(candidate, it, "YouTube Music") }

            searchAndPick(
                query = query,
                filter = YouTube.SearchFilter.FILTER_VIDEO,
                candidate = candidate,
                currentYouTubeId = currentYouTubeId,
                maxPages = 1,
                allowUploaderFallback = true,
            )?.let { return datedPlayable(candidate, it, "YouTube") }
        }

        // DEEP PATH: only after the normal path failed. Follow continuation tokens instead of
        // pretending the first result page is the whole YouTube catalogue.
        for (query in queries.take(DEEP_QUERY_LIMIT)) {
            searchAndPick(
                query = query,
                filter = YouTube.SearchFilter.FILTER_VIDEO,
                candidate = candidate,
                currentYouTubeId = currentYouTubeId,
                maxPages = DEEP_VIDEO_PAGES,
                allowUploaderFallback = true,
            )?.let { return datedPlayable(candidate, it, "YouTube") }
        }

        searchAndPick(
            query = canonicalQuery,
            filter = YouTube.SearchFilter.FILTER_SONG,
            candidate = candidate,
            currentYouTubeId = currentYouTubeId,
            maxPages = DEEP_MUSIC_PAGES,
            allowUploaderFallback = false,
        )?.let { return datedPlayable(candidate, it, "YouTube Music") }

        return null
    }

    /**
     * Query expansion is deterministic and testable. The AI/Brain already owns the musical identity;
     * these fields are only retrieval hints for YouTube/YTM.
     */
    internal fun locatorQueries(candidate: AiCoverCandidate): List<String> {
        val title = candidate.title.trim()
        val artist = candidate.artist.trim()
        if (title.isBlank() || artist.isBlank()) return emptyList()

        val categoryTerm = when (candidate.category) {
            AiCoverCategory.COVER -> "cover"
            AiCoverCategory.FOREIGN -> candidate.language?.trim()?.takeIf { it.isNotBlank() } ?: "version"
            AiCoverCategory.REMIX -> "remix"
            AiCoverCategory.LIVE -> "live"
        }
        val creditHints = (candidate.songwriters + candidate.composers + candidate.lyricists)
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

            if (candidate.category == AiCoverCategory.COVER) {
                add("$title $artist official audio")
                add("$title $artist lyrics")
            } else if (candidate.category == AiCoverCategory.LIVE) {
                add("$title $artist performance")
                add("$title $artist session")
            } else if (candidate.category == AiCoverCategory.REMIX) {
                add("$title $artist rework")
                add("$title $artist mix")
            }
        }
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_QUERY_VARIANTS)
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
    ): Boolean =
        TitleMeaningResolver.matchesBaseTitle(
            targetTitle = candidate.title,
            value = rawSongTitle,
            artistAliases = setOf(candidate.artist),
        )

    private suspend fun searchAndPick(
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
            val songs = page.items
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

        // LAB20 used artist mismatch as a hard veto. That discards many real YouTube uploads where
        // the "artist" field is actually the uploader/channel. LAB21 keeps strong identity checks,
        // but permits an exact-title video among the top search results as a recovery path.
        val identitySupported =
            artistMatch ||
                titleHasArtist ||
                albumMatch ||
                (allowUploaderFallback && exactTitle)
        if (!identitySupported) return 0

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

        var score = when {
            exactTitle -> 52
            tokenPhrase(songTitle, targetTitle) -> 42
            tokenPhrase(targetTitle, songTitle) && songTitle.length >= 4 -> 30
            else -> return 0
        }

        if (artistMatch) score += 34
        if (titleHasArtist) score += 30
        if (albumMatch) score += 20

        // A channel/uploader fallback is deliberately weaker than a real artist match and is
        // accepted only for an exact title near the top of FILTER_VIDEO results.
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
    private const val MAX_QUERY_VARIANTS = 8
    private const val METADATA_QUERY_LIMIT = 5
    private const val DEEP_QUERY_LIMIT = 3
    private const val DEEP_VIDEO_PAGES = 3
    private const val DEEP_MUSIC_PAGES = 2
    private const val UPLOADER_FALLBACK_TOP_RESULTS = 5
}
