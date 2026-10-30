package com.metrolist.music.ui.component

import com.metrolist.music.discogs.DiscogsCredit
import org.junit.Assert.*
import org.junit.Test

class CoverR15RootCauseEvidenceTest {
    private val credit = DiscogsCredit("Gianni Meccia", "Written By")

    @Test fun officialAppleTrackWithWorkWriterAndArtistIsEligibleInRoundOne() {
        val candidate=CoverSourceCandidate(
            title="Il mondo",artist="Cantante documentato",
            sources=listOf("Apple/iTunes"),album="Singolo ufficiale",
            sourceUrl="https://music.apple.com/it/album/il-mondo/123456?i=987654",
            credits=listOf(DiscogsCredit("Gianni Meccia","Composer")),
            playbackVideoId="abc123DEF45",playbackVideoTitle="Il mondo",
        )
        val verdict=CoverLabTurboRoundAdmission.evaluate(candidate,"Il mondo","Jimmy Fontana",1,
            referenceCredits=listOf(credit))
        assertTrue("work writer + authenticated catalogue credited performer + title",verdict.admitted)
        assertTrue(verdict.evidence.contains("catalogue-credited-performance"))
        assertEquals(3,verdict.evidence.size)
    }

    @Test fun unverifiedAlbumOrUploaderDoesNotBecomeASecondIndependentProof() {
        val candidate=CoverSourceCandidate(
            title="Il mondo",artist="Altro cantante",
            sources=listOf("YouTube Music"),album="Album generico",releaseVerified=true,
            credits=listOf(credit), playbackVideoId="abc123DEF45",playbackVideoTitle="Il mondo",
        )
        val verdict=CoverLabTurboRoundAdmission.evaluate(candidate,"Il mondo","Jimmy Fontana",1,
            referenceCredits=listOf(credit))
        assertFalse(verdict.admitted)
        assertEquals(2,verdict.evidence.size)
    }

    @Test fun matchingAlbumWithWrongWorkWriterCannotEnterFirstRound() {
        val candidate=CoverSourceCandidate(
            title="Il mondo",artist="Cantante omonimo",sources=listOf("Apple/iTunes"),
            album="Singolo",sourceUrl="https://music.apple.com/it/song/998877",
            credits=listOf(DiscogsCredit("Altro Autore","Composer")),
            playbackVideoId="abc123DEF45",playbackVideoTitle="Il mondo",
        )
        val verdict=CoverLabTurboRoundAdmission.evaluate(candidate,"Il mondo","Jimmy Fontana",1,
            referenceCredits=listOf(credit))
        assertFalse(verdict.admitted)
        assertEquals(setOf("title"),verdict.evidence)
    }

    @Test fun distinctCoverInfoSongIdsRemainDistinctEvenWhenSharingTheSameWorkTarget() {
        val a="https://cover.info/en/song/1001/Il-Mondo/Cantante-A?initial-details=700"
        val b="https://cover.info/en/song/1002/Il-Mondo/Cantante-B?initial-details=700"
        val c="https://cover.info/en/song/1003/El-Mundo/Cantante-C?follow-up-details=700"
        val songs=listOf(a,b,c).mapNotNull(CoverDiscoverySources::coverInfoSongIdentity)
        assertEquals(listOf("1001","1002","1003"),songs)
        assertEquals(3,songs.distinct().size)
    }

    @Test fun videoMetadataRetriesArePerIdNotPerRound() {
        var now=0L
        val retry=CoverPreparationPolicy.MetadataRetry(clock={now})
        assertTrue(retry.allowed("abc123DEF45"))
        retry.failed("abc123DEF45")
        assertFalse(retry.allowed("abc123DEF45"))
        assertTrue(retry.allowed("xyz123DEF45"))
        now=2_001L
        assertTrue(retry.allowed("abc123DEF45"))
    }

    @Test fun oneMetadataRequestBatchesThreeIDsEvenIfAudioIsPlaying() {
        val ids=CoverPreparationPolicy.metadataIds(
            "abc123DEF45",listOf("xyz123DEF45","third123ABC","fourth123ABC"),
            emptySet(),protectPlayback=true)
        assertEquals(listOf("abc123DEF45","xyz123DEF45","third123ABC"),ids)
    }
    @Test fun trustedDocumentarySingerInVideoTitleCanBindEvenWhenUploaderIsDifferent() {
        val lead = CoverSourceCandidate(
            title="Il mondo", artist="Patrizio Buanne",
            sources=listOf("COVER.INFO"), workRelationConfirmed=true,
        )
        val video = CoverSourceCandidate(
            title="Il mondo - Patrizio Buanne", artist="Record Label Channel",
            sources=listOf("YouTube"), playbackVideoId="abc123DEF45",
            playbackVideoTitle="Il mondo - Patrizio Buanne",
        )
        val joined = CoverPriorityOrder.bindVideo(video, lead)
        assertTrue("the explicitly credited artist must match the verified work",
            joined.workRelationConfirmed)
        assertEquals("Patrizio Buanne",joined.artist)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(joined,
            "Il mondo", "Jimmy Fontana", 1).admitted)
        val unrelated = video.copy(title="Il mondo - Altra Cantante",
            playbackVideoTitle="Il mondo - Altra Cantante")
        assertEquals(unrelated,CoverPriorityOrder.bindVideo(unrelated,lead))
    }

}
