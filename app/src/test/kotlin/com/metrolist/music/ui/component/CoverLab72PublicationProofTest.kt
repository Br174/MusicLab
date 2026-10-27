package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

/** Negative and positive evidence tests: a video is not a published record. */
class CoverLab72PublicationProofTest {
    private fun row(
        artist: String = "Lucio Dalla",
        title: String = "Felicità",
        source: String = "YouTube",
        videoTitle: String = title,
        album: String? = null,
        released: Boolean = false,
        evidence: String? = null,
        work: Boolean = false,
        category: AiCoverCategory = AiCoverCategory.COVER,
    ) = CoverSourceCandidate(
        title = title,
        artist = artist,
        sources = listOf(source),
        playbackVideoTitle = videoTitle,
        playbackVideoId = "MNvH8BzIXGY",
        album = album,
        releaseVerified = released,
        releaseEvidence = evidence,
        workRelationConfirmed = work,
        category = category,
    )

    @Test fun officialVideoWithoutAlbumProofNeverCountsAsPublished() {
        val video = row(videoTitle = "Lucio Dalla - Felicità official")
        val gate = CoverLab72PublicationProof
        assertFalse(gate.isReleaseProof(video))
        assertNull(gate.withDocumentedRelease(video, emptyList()))
        assertNull(gate.withDocumentedRelease(video, listOf(
            row(source = "Apple/iTunes", album = "Felicità", released = false)
        )))
        assertNull(gate.withDocumentedRelease(video, listOf(
            row(source = "Apple/iTunes", album = "Felicità", released = true)
        )))
    }

    @Test fun releasedOriginalFromCatalogIsEligibleWithoutSpecialOfficialWords() {
        val youtube = row(videoTitle = "Lucio Dalla - Felicità")
        val apple = row(
            source = "Apple/iTunes", album = "Dalla/Morandi",
            released = true, evidence = "https://music.apple.com/it/album/123",
        )
        val admitted = CoverLab72PublicationProof.withDocumentedRelease(youtube, listOf(apple))
        assertNotNull(admitted)
        assertTrue(CoverLab72PublicationProof.isReleaseProof(admitted!!))
        assertTrue(CoverLab71FoundationGate.matchesRequestedWork(
            admitted, "Felicità", "Lucio Dalla", "Lucio Dalla - Felicità"
        ))
    }

    @Test fun coverInfoForeignWorkKeepsFirstRankWhenTheRecordIsPublished() {
        val foreign = row(
            artist = "Interprete spagnolo", title = "El mundo", source = "COVER.INFO",
            work = true, videoTitle = "El mundo",
        )
        val apple = row(
            artist = "Interprete spagnolo", title = "El mundo", source = "Apple/iTunes",
            album = "Disco publicado", released = true,
            evidence = "https://music.apple.com/it/album/456",
        )
        val completed = CoverLab72PublicationProof.withDocumentedRelease(foreign, listOf(apple))
        assertNotNull(completed)
        assertTrue(CoverLab71FoundationGate.matchesRequestedWork(
            completed!!, "Il mondo", "Jimmy Fontana", "El mundo"
        ))
        assertEquals(0, CoverLab72PublicationProof.firstRoundPriority(completed, "Jimmy Fontana"))
        assertFalse(CoverLab72PublicationProof.isReleaseProof(foreign))
    }

    @Test fun twoCatalogsAndDocumentedCoverAreCombinedWithoutManufacturingWorkProof() {
        val cover = row(artist = "Artista famoso", title = "Felicità", source = "YouTube")
        val apple = row(artist = "Artista famoso", title = "Felicità", source = "Apple/iTunes",
            album = "Album", released = true, evidence = "https://music.apple.com/a")
        val spotify = row(artist = "Artista famoso", title = "Felicità", source = "Spotify",
            album = "Album", released = true, evidence = "https://open.spotify.com/track/id")
        val mb = row(artist = "Artista famoso", title = "Felicità",
            source = "MusicBrainz", work = true)
        val completed = CoverLab72PublicationProof.withDocumentedRelease(
            cover, listOf(apple, spotify), listOf(mb)
        )
        assertNotNull(completed)
        assertTrue(completed!!.workRelationConfirmed)
        assertTrue("Release proof must retain iTunes", "Apple/iTunes" in completed.sources)
        assertTrue("Release proof must retain Spotify", "Spotify" in completed.sources)
        assertTrue("Work proof must retain MusicBrainz", "MusicBrainz" in completed.sources)
        assertEquals(2, CoverLab72PublicationProof.firstRoundPriority(completed, "Lucio Dalla"))
    }

    @Test fun studioRecordDoesNotCertifyUnreleasedLiveFootageOrRemix() {
        val apple = row(source = "Apple/iTunes", album = "Studio album",
            released = true, evidence = "https://music.apple.com/1")
        assertNull(CoverLab72PublicationProof.withDocumentedRelease(
            row(videoTitle = "Felicità (Live 1998)"), listOf(apple)
        ))
        assertNull(CoverLab72PublicationProof.withDocumentedRelease(
            row(videoTitle = "Felicità (Remix)"), listOf(apple)
        ))
        val liveAlbum = row(
            source = "Discogs", album = "Lucio Dalla dal vivo",
            released = true, evidence = "https://www.discogs.com/release/5",
            category = AiCoverCategory.LIVE,
        )
        assertNotNull(CoverLab72PublicationProof.withDocumentedRelease(
            row(videoTitle = "Felicità (Live 1998)", category = AiCoverCategory.LIVE),
            listOf(liveAlbum),
        ))
    }

    @Test fun mereSameTitleOfAnotherArtistIsNotReleaseEvidence() {
        val foreignYoutube = row(artist = "Altra band", videoTitle = "Felicità")
        val originalRecord = row(source = "Apple/iTunes", album = "Dalla/Morandi",
            released = true, evidence = "https://music.apple.com/2")
        assertNull(CoverLab72PublicationProof.withDocumentedRelease(
            foreignYoutube, listOf(originalRecord)
        ))
        assertNull(CoverLab72PublicationProof.withDocumentedRelease(
            row(artist = "Altra band", title = "Felicità puttana"),
            listOf(originalRecord)
        ))
    }

    @Test fun tvFestivalAndRecordedTalentBelongInSecondRoundWithoutReleasedAlbum() {
        assertTrue(CoverLab72PublicationProof.professionalUnreleased(
            row(videoTitle = "Felicità - Festival di Sanremo")
        ))
        assertTrue(CoverLab72PublicationProof.professionalUnreleased(
            row(videoTitle = "Felicità - The Voice Italy")
        ))
        assertTrue(CoverLab72PublicationProof.professionalUnreleased(
            row(videoTitle = "Felicità - X Factor")
        ))
        assertTrue(CoverLab72PublicationProof.professionalUnreleased(
            row(videoTitle = "Felicità (BBC Radio Live Lounge)")
        ))
        assertFalse(CoverLab72PublicationProof.professionalUnreleased(
            row(videoTitle = "Felicità - amateur live webcam")
        ))
        assertFalse(CoverLab72PublicationProof.professionalUnreleased(
            row(videoTitle = "Felicità - karaoke MIDI")
        ))
    }

    @Test fun discographyNeverOverridesGlobalNoKaraokeRule() {
        val releasedKaraoke = row(source = "Apple/iTunes", album = "A compilation",
            videoTitle = "Felicità karaoke", released = true,
            evidence = "https://music.apple.com/3")
        assertTrue(CoverLab72PublicationProof.isReleaseProof(releasedKaraoke))
        assertFalse(CoverLab71FoundationGate.matchesRequestedWork(
            releasedKaraoke, "Felicità", "Lucio Dalla", "Felicità karaoke"
        ))
    }
}
