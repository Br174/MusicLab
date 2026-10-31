package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

/** R26: ordering does not filter, change cycle admission, or alter playback. */
class CoverR26SourcePositionOnlyTest {
    private fun candidate(source: String, id: String, documentary: Boolean = false) =
        CoverSourceCandidate(title = "Il mondo", artist = "Singer",
            sources = listOf(source), playbackVideoId = id,
            playbackVideoTitle = "Il mondo", workRelationConfirmed = documentary)

    @Test fun coverInfoIsFirstAndAccreditedSourcesStayAboveYoutubeMusic() {
        val sources = listOf("YouTube", "YouTube Music", "Discogs", "MusicLab",
            "Archivio Cloud", "Spotify", "MusicBrainz", "SoundCloud",
            "Apple/iTunes", "COVER.INFO")
        val input = sources.mapIndexed { i, s -> candidate(s, "video-$i") }
        val ranked = CoverPriorityOrder.order(input, "Il mondo", "", 1)
        assertEquals(input.toSet(), ranked.toSet())
        assertEquals(input.size, ranked.size)
        assertEquals("COVER.INFO", ranked.first().sources.single())
        assertEquals("YouTube Music", ranked[ranked.lastIndex - 1].sources.single())
        assertEquals("YouTube", ranked.last().sources.single())
        for (source in sources.drop(2).dropLast(1)) {
            assertTrue(ranked.indexOfFirst { source in it.sources } <
                ranked.indexOfFirst { "YouTube Music" in it.sources })
        }
    }

    @Test fun sourceOverridesVideoHostAndVideoReadinessDoesNotOverrideSource() {
        val youtube = candidate("YouTube", "standalone")
        val catalogued = youtube.copy(sources = listOf("Discogs", "YouTube"))
        val coverInfo = candidate("COVER.INFO", "linked", true).copy(playbackVideoId = null,
            sourceUrl = "https://cover.info/example")
        val results = CoverPriorityOrder.order(listOf(youtube, catalogued, coverInfo),
            "Il mondo", "", 1)
        assertEquals(listOf(coverInfo, catalogued, youtube), results)
    }

    @Test fun youtubeOnlyResultsStillFillTheFirstPacket() {
        val presentation = CoverRoundPresentation<DiscogsVersionSeed> {
            it.resolvedVideoId ?: it.fingerprint
        }
        repeat(10) { i ->
            val video = "youtube-$i"
            val row = DiscogsVersionSource.externalSeed(
                candidate("YouTube", video), "Il mondo", "")
                .copy(resolvedVideoId = video, videoResolutionChecked = true)
            assertTrue(presentation.stage(1, row))
        }
        presentation.prioritizePreparedWith(CoverPriorityOrder.publicationComparator)
        assertEquals(10, presentation.takeAutomatic().size)
        assertEquals(10, presentation.status().shown)
    }

    @Test fun publishedRankingDoesNotDeleteMusicLabOrYoutubeCandidates() {
        val raw = listOf("YouTube", "Spotify", "YouTube Music", "Archivio Cloud",
            "COVER.INFO", "Discogs").mapIndexed { i, s ->
            DiscogsVersionSource.externalSeed(candidate(s, "ready-$i"), "Il mondo", "")
                .copy(resolvedVideoId = "ready-$i", videoResolutionChecked = true)
        }
        val sorted = raw.sortedWith(CoverPriorityOrder.publicationComparator)
        assertEquals(raw.map { it.fingerprint }.toSet(),
            sorted.map { it.fingerprint }.toSet())
        assertEquals("COVER.INFO", sorted.first().sourceNames.first())
        assertEquals("YouTube Music", sorted[sorted.lastIndex - 1].sourceNames.first())
        assertEquals("YouTube", sorted.last().sourceNames.first())
    }
}
