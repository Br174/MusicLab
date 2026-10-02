package com.metrolist.music.ui.component

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lightweight title-only lane shared by Cover LAB23.
 *
 * It intentionally does not ask AI for every result and never uses artist/album/year
 * as query constraints. TitleMeaningResolver is the semantic gate.
 */
internal object MusicLabTitleSearch {
    suspend fun fast(
        title: String,
        currentYouTubeId: String?,
    ): List<AiCoverPlayable> = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return@withContext emptyList()

        val songs = withTimeoutOrNull(FAST_TIMEOUT_MS) {
            YouTube.search(cleanTitle, YouTube.SearchFilter.FILTER_SONG)
                .getOrNull()
                ?.items
                ?.filterIsInstance<SongItem>()
                .orEmpty()
        }.orEmpty()

        toPlayables(
            targetTitle = cleanTitle,
            songs = songs,
            currentYouTubeId = currentYouTubeId,
            source = "MusicLab titolo · YouTube Music",
        ).take(FAST_RESULT_LIMIT)
    }

    suspend fun expanded(
        title: String,
        currentYouTubeId: String?,
    ): List<AiCoverPlayable> = coroutineScope {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return@coroutineScope emptyList()

        val musicVideo = async(Dispatchers.IO) {
            withTimeoutOrNull(BACKGROUND_TIMEOUT_MS) {
                YouTube.search(cleanTitle, YouTube.SearchFilter.FILTER_VIDEO)
                    .getOrNull()
                    ?.items
                    ?.filterIsInstance<SongItem>()
                    .orEmpty()
            }.orEmpty()
        }
        val web = async(Dispatchers.IO) {
            withTimeoutOrNull(BACKGROUND_TIMEOUT_MS) {
                YouTubeWebSearch.search(cleanTitle)
                    ?.items
                    .orEmpty()
            }.orEmpty()
        }

        val combined = buildList {
            addAll(
                toPlayables(
                    targetTitle = cleanTitle,
                    songs = musicVideo.await(),
                    currentYouTubeId = currentYouTubeId,
                    source = "MusicLab titolo · YouTube Music video",
                ),
            )
            addAll(
                toPlayables(
                    targetTitle = cleanTitle,
                    songs = web.await(),
                    currentYouTubeId = currentYouTubeId,
                    source = "MusicLab titolo · YouTube",
                ),
            )
        }

        combined
            .distinctBy { it.song.id }
            .sortedByDescending {
                TitleMeaningResolver.qualityScore(cleanTitle, it.song.title)
            }
            .take(BACKGROUND_RESULT_LIMIT)
    }

    internal fun toPlayables(
        targetTitle: String,
        songs: List<SongItem>,
        currentYouTubeId: String?,
        source: String,
    ): List<AiCoverPlayable> =
        songs
            .asSequence()
            .filter { it.id != currentYouTubeId }
            .filter {
                TitleMeaningResolver.matchesBaseTitle(
                    targetTitle = targetTitle,
                    value = it.title,
                )
            }
            .filterNot { DISALLOWED.containsMatchIn(it.title.lowercase()) }
            .map { song ->
                val artist = song.artists.firstOrNull()?.name?.trim().orEmpty().ifBlank { "YouTube" }
                val category = classify(song.title)
                AiCoverPlayable(
                    candidate = AiCoverCandidate(
                        title = song.title.trim().ifBlank { targetTitle },
                        artist = artist,
                        category = category,
                        album = song.album?.name?.takeIf(String::isNotBlank),
                        brainAdmission = "title_only_fast",
                        brainSignals = listOf(
                            AiBrainSignal(
                                kind = "title_semantic_match",
                                strength = "weak",
                                direction = "positive",
                            ),
                        ),
                    ),
                    song = song,
                    playbackSource = source,
                )
            }
            .distinctBy { it.song.id }
            .sortedByDescending {
                TitleMeaningResolver.qualityScore(targetTitle, it.song.title)
            }
            .toList()

    internal fun classify(rawTitle: String): AiCoverCategory {
        val raw = rawTitle.lowercase()
        return when {
            REMIX_MARKER.containsMatchIn(raw) -> AiCoverCategory.REMIX
            LIVE_MARKER.containsMatchIn(raw) -> AiCoverCategory.LIVE
            else -> AiCoverCategory.COVER
        }
    }

    private val LIVE_MARKER =
        Regex("\\b(live|dal vivo|concert|concerto|performance|session|festival|unplugged|acoustic|tv|radio)\\b")
    private val REMIX_MARKER =
        Regex("\\b(remix|rework|club mix|extended mix|radio mix|dance mix|dub mix|edit mix|mix)\\b")
    private val DISALLOWED =
        Regex("\\b(karaoke|reaction|tutorial|lesson|backing track|instrumental backing|mashup|medley)\\b")

    private const val FAST_TIMEOUT_MS = 4_500L
    private const val BACKGROUND_TIMEOUT_MS = 8_000L
    private const val FAST_RESULT_LIMIT = 24
    private const val BACKGROUND_RESULT_LIMIT = 60
}
