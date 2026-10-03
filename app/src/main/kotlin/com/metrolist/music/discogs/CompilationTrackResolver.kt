package com.metrolist.music.discogs

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.ui.component.YouTubeWebSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import kotlin.math.abs

internal data class CompilationResolvedTrack(
    val track: DiscogsTrack,
    val song: SongItem,
    val source: String,
    val usedDiscogsVideo: Boolean,
)

internal data class CompilationFullAudio(
    val song: SongItem,
    val source: String,
    val discogsVideo: DiscogsVideo?,
)

internal object CompilationTrackResolver {
    suspend fun resolveTrack(
        track: DiscogsTrack,
        discogsVideos: List<DiscogsVideo>,
        fastFirst: Boolean = true,
    ): CompilationResolvedTrack? = withContext(Dispatchers.IO) {
        // Fast lane: direct Discogs video first, then a bounded race between the
        // two MusicLab sources that most often resolve a playable track quickly.
        val directVideo = bestDiscogsTrackVideo(track, discogsVideos)
        if (directVideo != null) {
            val directId = extractYouTubeId(directVideo.uri)
            val directSong = directId?.let { id ->
                withTimeoutOrNull(if (fastFirst) 2_200L else 5_000L) {
                    YouTube.queue(videoIds = listOf(id)).getOrNull()?.firstOrNull()
                }
            }
            if (directSong != null && scoreSong(track, directSong) >= DIRECT_VIDEO_MIN_SCORE) {
                return@withContext CompilationResolvedTrack(
                    track = track,
                    song = directSong,
                    source = "Discogs → YouTube",
                    usedDiscogsVideo = true,
                )
            }
        }

        val artist = track.artists.firstOrNull().orEmpty()
        val query = listOf(artist, track.title).filter(String::isNotBlank).joinToString(" ")

        if (fastFirst) {
            fastMusicLabRace(track, query)?.let { return@withContext it }
        }

        val summarySongs = withTimeoutOrNull(if (fastFirst) 2_500L else 5_500L) {
            YouTube.searchSummary(query).getOrNull()
                ?.summaries
                ?.flatMap { it.items }
                ?.filterIsInstance<SongItem>()
                .orEmpty()
        }.orEmpty()

        bestSong(track, summarySongs)?.let {
            return@withContext CompilationResolvedTrack(track, it, "YouTube Music", false)
        }

        val ytmSongs = withTimeoutOrNull(if (fastFirst) 3_000L else 7_000L) {
            YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                .getOrNull()
                ?.items
                ?.filterIsInstance<SongItem>()
                .orEmpty()
        }.orEmpty()

        bestSong(track, ytmSongs)?.let {
            return@withContext CompilationResolvedTrack(track, it, "YouTube Music", false)
        }

        val webSongs = withTimeoutOrNull(if (fastFirst) 3_500L else 8_500L) {
            YouTubeWebSearch.search(query)?.items.orEmpty()
        }.orEmpty()

        bestSong(track, webSongs, relaxedArtist = true)?.let {
            return@withContext CompilationResolvedTrack(track, it, "YouTube", false)
        }

        val videoSongs = withTimeoutOrNull(if (fastFirst) 3_000L else 7_000L) {
            YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO)
                .getOrNull()
                ?.items
                ?.filterIsInstance<SongItem>()
                .orEmpty()
        }.orEmpty()

        bestSong(track, videoSongs, relaxedArtist = true)?.let {
            return@withContext CompilationResolvedTrack(track, it, "YouTube Music video", false)
        }

        null
    }

    private suspend fun fastMusicLabRace(
        track: DiscogsTrack,
        query: String,
    ): CompilationResolvedTrack? = coroutineScope {
        val winner = CompletableDeferred<CompilationResolvedTrack?>()
        val jobs = mutableListOf<Job>()

        fun launchCandidate(block: suspend () -> CompilationResolvedTrack?) {
            jobs += launch(Dispatchers.IO) {
                val candidate = runCatching { block() }.getOrNull()
                if (candidate != null) winner.complete(candidate)
            }
        }

        launchCandidate {
            val items = withTimeoutOrNull(2_600L) {
                YouTube.searchSummary(query).getOrNull()
                    ?.summaries
                    ?.flatMap { it.items }
                    ?.filterIsInstance<SongItem>()
                    .orEmpty()
            }.orEmpty()
            bestSong(track, items)?.let {
                CompilationResolvedTrack(track, it, "YouTube Music · rapido", false)
            }
        }

        launchCandidate {
            val items = withTimeoutOrNull(2_900L) {
                YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                    .getOrNull()
                    ?.items
                    ?.filterIsInstance<SongItem>()
                    .orEmpty()
            }.orEmpty()
            bestSong(track, items)?.let {
                CompilationResolvedTrack(track, it, "YouTube Music · rapido", false)
            }
        }

        val result = withTimeoutOrNull(3_050L) { winner.await() }
        jobs.forEach(Job::cancel)
        result
    }

    suspend fun findFullAudio(
        detail: DiscogsCompilationDetail,
    ): CompilationFullAudio? = withContext(Dispatchers.IO) {
        bestFullDiscogsVideo(detail)?.let { video ->
            extractYouTubeId(video.uri)?.let { id ->
                val song = withTimeoutOrNull(6_000L) {
                    YouTube.queue(videoIds = listOf(id)).getOrNull()?.firstOrNull()
                }
                if (song != null && isPlausibleFullAudio(detail, song)) {
                    return@withContext CompilationFullAudio(
                        song = song,
                        source = "Discogs → YouTube · audio completo",
                        discogsVideo = video,
                    )
                }
            }
        }

        val query = buildString {
            append(detail.title)
            detail.year?.let { append(" ").append(it) }
            append(" full album compilation")
        }
        val web = withTimeoutOrNull(10_000L) {
            YouTubeWebSearch.search(query)?.items.orEmpty()
        }.orEmpty()

        web
            .map { song -> song to scoreFullSong(detail, song) }
            .filter { it.second >= FULL_SEARCH_MIN_SCORE }
            .maxByOrNull { it.second }
            ?.first
            ?.let {
                CompilationFullAudio(
                    song = it,
                    source = "MusicLab → YouTube · audio completo",
                    discogsVideo = null,
                )
            }
    }

    internal fun extractYouTubeId(uri: String): String? {
        val value = uri.trim()
        if (value.isBlank()) return null

        Regex("""(?:youtube\.com/(?:watch\?[^#]*v=|embed/|shorts/)|youtu\.be/)([A-Za-z0-9_-]{11})""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }

        return Regex("""(?:^|[?&])v=([A-Za-z0-9_-]{11})(?:[&#]|$)""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun bestDiscogsTrackVideo(
        track: DiscogsTrack,
        videos: List<DiscogsVideo>,
    ): DiscogsVideo? =
        videos
            .asSequence()
            .filter { extractYouTubeId(it.uri) != null }
            .filterNot { looksLikeFullAudioTitle(it.title) }
            .map { it to scoreDiscogsVideo(track, it) }
            .filter { it.second >= DIRECT_VIDEO_MIN_SCORE }
            .maxByOrNull { it.second }
            ?.first

    private fun scoreDiscogsVideo(track: DiscogsTrack, video: DiscogsVideo): Int {
        val title = normalize(video.title)
        val targetTitle = normalize(track.title)
        val artists = track.artists.map(::normalize).filter(String::isNotBlank)

        var score = 0
        if (targetTitle.isNotBlank() && title == targetTitle) score += 70
        else if (targetTitle.isNotBlank() && title.contains(targetTitle)) score += 48
        else if (tokenOverlap(targetTitle, title) >= 0.72) score += 36

        if (artists.any { artist -> artist.length >= 3 && title.contains(artist) }) score += 22

        val expected = track.durationSeconds
        val actual = video.durationSeconds
        if (expected != null && actual != null) {
            val delta = abs(expected - actual)
            score += when {
                delta <= 5 -> 18
                delta <= 12 -> 12
                delta <= 25 -> 5
                else -> -10
            }
        }

        if (BAD_VIDEO_MARKERS.containsMatchIn(title)) score -= 55
        return score
    }

    private fun bestSong(
        track: DiscogsTrack,
        songs: List<SongItem>,
        relaxedArtist: Boolean = false,
    ): SongItem? =
        songs
            .asSequence()
            .filterNot { BAD_VIDEO_MARKERS.containsMatchIn(normalize(it.title)) }
            .map { it to scoreSong(track, it, relaxedArtist) }
            .filter { it.second >= if (relaxedArtist) RELAXED_MIN_SCORE else NORMAL_MIN_SCORE }
            .maxByOrNull { it.second }
            ?.first

    internal fun scoreSong(
        track: DiscogsTrack,
        song: SongItem,
        relaxedArtist: Boolean = false,
    ): Int {
        val targetTitle = normalize(track.title)
        val songTitle = normalize(song.title)
        val targetArtists = track.artists.map(::normalize).filter(String::isNotBlank)
        val songArtists = song.artists.map { normalize(it.name) }.filter(String::isNotBlank)

        var score = 0
        if (songTitle == targetTitle) score += 70
        else if (songTitle.contains(targetTitle) || targetTitle.contains(songTitle)) score += 50
        else {
            score += (tokenOverlap(targetTitle, songTitle) * 50).toInt()
        }

        val artistMatch = targetArtists.any { wanted ->
            songArtists.any { actual ->
                actual == wanted || actual.contains(wanted) || wanted.contains(actual)
            } || songTitle.contains(wanted)
        }
        if (targetArtists.isEmpty()) score += 5
        else if (artistMatch) score += 28
        else if (!relaxedArtist) score -= 25

        val expected = track.durationSeconds
        val actual = song.duration
        if (expected != null && actual != null) {
            val delta = abs(expected - actual)
            score += when {
                delta <= 5 -> 18
                delta <= 12 -> 12
                delta <= 25 -> 5
                delta >= 90 -> -30
                else -> 0
            }
        }

        if (BAD_VIDEO_MARKERS.containsMatchIn(songTitle)) score -= 60
        if (looksLikeFullAudioTitle(songTitle)) score -= 45
        return score
    }

    private fun bestFullDiscogsVideo(detail: DiscogsCompilationDetail): DiscogsVideo? {
        val total = knownTrackDuration(detail)
        return detail.videos
            .asSequence()
            .filter { extractYouTubeId(it.uri) != null }
            .map { video -> video to scoreFullVideo(detail, video, total) }
            .filter { it.second >= FULL_DISCOGS_MIN_SCORE }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun scoreFullVideo(
        detail: DiscogsCompilationDetail,
        video: DiscogsVideo,
        expectedSeconds: Int?,
    ): Int {
        val videoTitle = normalize(video.title)
        val releaseTitle = normalize(detail.title)
        var score = 0

        if (releaseTitle.length >= 4 && videoTitle.contains(releaseTitle)) score += 55
        else if (tokenOverlap(releaseTitle, videoTitle) >= 0.65) score += 35

        if (FULL_AUDIO_MARKERS.containsMatchIn(videoTitle)) score += 38

        val duration = video.durationSeconds
        if (duration != null) {
            if (duration >= 1_200) score += 18
            if (expectedSeconds != null && expectedSeconds > 0) {
                val ratio = duration.toDouble() / expectedSeconds.toDouble()
                score += when {
                    ratio in 0.92..1.08 -> 30
                    ratio in 0.82..1.18 -> 18
                    ratio in 0.70..1.30 -> 5
                    else -> -12
                }
            }
        }
        return score
    }

    private fun isPlausibleFullAudio(
        detail: DiscogsCompilationDetail,
        song: SongItem,
    ): Boolean = scoreFullSong(detail, song) >= FULL_SEARCH_MIN_SCORE

    private fun scoreFullSong(detail: DiscogsCompilationDetail, song: SongItem): Int {
        val title = normalize(song.title)
        val releaseTitle = normalize(detail.title)
        var score = 0

        if (releaseTitle.length >= 4 && title.contains(releaseTitle)) score += 55
        else if (tokenOverlap(releaseTitle, title) >= 0.65) score += 35
        if (FULL_AUDIO_MARKERS.containsMatchIn(title)) score += 35
        if ((song.duration ?: 0) >= 1_200) score += 20

        knownTrackDuration(detail)?.let { expected ->
            song.duration?.let { actual ->
                val ratio = actual.toDouble() / expected.toDouble()
                score += when {
                    ratio in 0.92..1.08 -> 30
                    ratio in 0.82..1.18 -> 15
                    else -> 0
                }
            }
        }
        return score
    }

    private fun knownTrackDuration(detail: DiscogsCompilationDetail): Int? {
        val known = detail.tracks.mapNotNull { it.durationSeconds }
        if (known.size < detail.tracks.size.coerceAtMost(4)) return null
        return known.sum().takeIf { it > 0 }
    }

    private fun looksLikeFullAudioTitle(value: String): Boolean =
        FULL_AUDIO_MARKERS.containsMatchIn(normalize(value))

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun tokenOverlap(a: String, b: String): Double {
        val left = a.split(" ").filter { it.length > 1 }.toSet()
        val right = b.split(" ").filter { it.length > 1 }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.size.coerceAtLeast(1)
    }

    private val BAD_VIDEO_MARKERS =
        Regex("\\b(karaoke|reaction|tutorial|lesson|backing track|instrumental backing)\\b")
    private val FULL_AUDIO_MARKERS =
        Regex("\\b(full album|full compilation|complete album|full lp|album completo|compilation completa|intero album|album intero)\\b")

    private const val DIRECT_VIDEO_MIN_SCORE = 58
    private const val NORMAL_MIN_SCORE = 62
    private const val RELAXED_MIN_SCORE = 58
    private const val FULL_DISCOGS_MIN_SCORE = 70
    private const val FULL_SEARCH_MIN_SCORE = 75
}
