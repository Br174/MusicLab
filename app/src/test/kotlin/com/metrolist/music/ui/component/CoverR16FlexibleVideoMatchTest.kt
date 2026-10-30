package com.metrolist.music.ui.component

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.SongItem
import org.junit.Assert.*
import org.junit.Test

class CoverR16FlexibleVideoMatchTest {
    private fun video(title: String = "Il mondo - Patrizio Buanne",
        artist: String = "Record Label", album: String? = null,
        duration: Int? = 205) = SongItem(
            id = "abc123DEF45", title = title,
            artists = listOf(Artist(artist, null)),
            album = album?.let { Album(it, "release1") },
            duration = duration, thumbnail = "https://img.example/t.jpg",
        )
    private fun source(title: String = "Il mondo", artist: String = "Patrizio Buanne",
        album: String? = null, duration: Int? = null) = CoverSourceCandidate(
        title = title, artist = artist, sources = listOf("COVER.INFO"),
        workRelationConfirmed = true, album = album, durationSeconds = duration,
        playbackVideoId = "abc123DEF45",
    )
    @Test fun italianTitleAndAnyTwoProofsAcceptFirstRound() {
        val verdict = CoverR16FlexibleVideoMatch.evaluate(source(), "Il mondo",
            "Jimmy Fontana", 1, video())
        assertTrue(verdict.admitted)
        assertEquals(setOf("title", "performer", "documented-work"), verdict.evidence)
    }
    @Test fun twoDifferentMatchesCanBeAlbumAndDurationInsteadOfSinger() {
        val verdict = CoverR16FlexibleVideoMatch.evaluate(
            source(artist = "Unknown Performer", album = "Album studio", duration = 205),
            "Il mondo", "Jimmy Fontana", 1,
            video(title = "Il mondo", album = "Album studio", duration = 206))
        assertTrue(verdict.admitted)
        assertTrue(verdict.evidence.containsAll(setOf("title", "album", "duration")))
    }
    @Test fun artistRepeatedAcrossTitleAndArtistFieldsCountsOnce() {
        val raw = CoverSourceCandidate(title = "Il mondo", artist = "Patrizio Buanne",
            sources = listOf("YouTube"), playbackVideoId = "abc123DEF45")
        val result = CoverR16FlexibleVideoMatch.evaluate(raw, "Il mondo", "Jimmy Fontana",
            1, video(artist = "Patrizio Buanne"))
        assertFalse(result.admitted)
        assertEquals(setOf("title", "performer"), result.evidence)
        assertTrue(CoverR16FlexibleVideoMatch.evaluate(raw, "Il mondo", "Jimmy Fontana",
            2, video(artist = "Patrizio Buanne")).admitted)
    }
    @Test fun youtubeDurationCannotSelfConfirmIndependentSourceDuration() {
        val lead = source(duration = null)
        val raw = CoverSourceCandidate(title = "Il mondo", artist = "Patrizio Buanne",
            sources = listOf("YouTube"), playbackVideoId = "abc123DEF45",
            playbackVideoTitle = "Il mondo - Patrizio Buanne", durationSeconds = 205)
        val linked = CoverPriorityOrder.bindVideo(raw, lead)
        assertNull(linked.durationSeconds)
        assertTrue(CoverR16FlexibleVideoMatch.evaluate(linked, "Il mondo", "Jimmy Fontana",
            1, video()).admitted)
    }
    @Test fun italianTitleDoesNotMatchLongerUnrelatedTitle() {
        val result = CoverR16FlexibleVideoMatch.evaluate(source(), "Il mondo",
            "Jimmy Fontana", 1, video(title = "Il mondo che vorrei - Patrizio Buanne"))
        assertTrue(result.rejected)
    }
    @Test fun documentedForeignAdaptationHasSeparateTitleMatching() {
        val lead = source(title = "El mundo", artist = "Cantante Latino")
            .copy(category = AiCoverCategory.FOREIGN)
        assertTrue(CoverR16FlexibleVideoMatch.evaluate(lead, "Il mondo",
            "Jimmy Fontana", 1, video(title = "El mundo - Cantante Latino")).admitted)
        assertTrue(CoverR16FlexibleVideoMatch.evaluate(
            lead.copy(workRelationConfirmed = false), "Il mondo",
            "Jimmy Fontana", 1, video(title = "El mundo - Cantante Latino")).rejected)
    }
    @Test fun karaokeAndMidiRemainAbsolutelyExcluded() {
        assertTrue(CoverR16FlexibleVideoMatch.evaluate(source(), "Il mondo",
            "Jimmy Fontana", 1, video(title = "Il mondo - Patrizio Buanne karaoke")).rejected)
        assertTrue(CoverR16FlexibleVideoMatch.evaluate(source(), "Il mondo",
            "Jimmy Fontana", 1, video(title = "Il mondo - Patrizio Buanne MIDI")).rejected)
    }
    @Test fun oneExternalAlbumProofCannotInventTwoMore() {
        val row = CoverSourceCandidate(title = "Il mondo", artist = "Other Singer",
            sources = listOf("Apple/iTunes"), album = "Album vero",
            playbackVideoId = "abc123DEF45")
        val verdict = CoverR16FlexibleVideoMatch.evaluate(row, "Il mondo", "Jimmy Fontana",
            1, video(title = "Il mondo", album = "Album vero"))
        assertFalse(verdict.admitted)
        assertEquals(setOf("title", "album"), verdict.evidence)
    }
    @Test fun youtubeSearchUsesSeveralSourceMetadataVariants() {
        val q = CoverR16FlexibleVideoMatch.queries(source(album = "Disco d'oro").copy(year = 1972))
        assertTrue(q.any { "Disco d'oro" in it })
        assertTrue(q.any { "1972" in it })
        assertTrue(q.size <= 4)
    }
}
