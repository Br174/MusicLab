package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverR12VideoThroughputTest {
    @Test fun failedMetadataForOneVideoCannotFreezeAllOtherVideoIds() {
        assertFalse(CoverPreparationPolicy.metadataAttemptAllowed("video000001", true))
        assertTrue(CoverPreparationPolicy.metadataAttemptAllowed("video000002", false))
        assertTrue(CoverPreparationPolicy.metadataAttemptAllowed("video000003", false))
        assertFalse(CoverPreparationPolicy.metadataAttemptAllowed("", false))
    }

    @Test fun firstTenStillAppearTogetherOnlyWhenTenVerifiedRowsAreAvailable() {
        val p = CoverRoundPresentation<String> { it }
        p.beginPreparation(1)
        p.stage(1, "video-a")
        p.stage(1, "video-b")
        assertEquals(2, p.status().prepared)
        assertTrue(p.takeAutomatic().isEmpty())
        assertEquals(0, p.status().shown)
        repeat(8) { p.stage(1, "more-video-$it") }
        assertEquals(10, p.takeAutomatic().size)
        assertEquals(10, p.status().shown)
        repeat(19) { p.stage(1, "next-video-$it") }
        assertEquals(0, p.status().ready)
        p.stage(1, "next-video-19")
        assertEquals(20, p.reveal().size)
        assertEquals(30, p.status().shown)
    }

    @Test fun titleOnlyOrReleasedButUnprovenVersionsRemainDeferredInFirstRound() {
        val weak = CoverSourceCandidate(title="Il mondo", artist="Unverified Singer",
            sources=listOf("YouTube"), playbackVideoId="abcdefghijk", playbackVideoTitle="Il mondo")
        val publishedButUnlinked = weak.copy(sources=listOf("Apple/iTunes"), album="Album", releaseVerified=true)
        assertFalse(CoverLabTurboRoundAdmission.evaluate(weak,"Il mondo","Jimmy Fontana",1).admitted)
        assertFalse(CoverLabTurboRoundAdmission.evaluate(publishedButUnlinked,"Il mondo","Jimmy Fontana",1).admitted)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(publishedButUnlinked,"Il mondo","Jimmy Fontana",6).admitted)
    }

    @Test fun documentedForeignCoverStillQualifiesInFirstRound() {
        val documented = CoverSourceCandidate(title="El mundo", artist="Published Foreign Singer",
            sources=listOf("COVER.INFO"), workRelationConfirmed=true,
            category=AiCoverCategory.FOREIGN, playbackVideoId="abcdefghijk", playbackVideoTitle="El mundo")
        val verdict = CoverLabTurboRoundAdmission.evaluate(documented,"Il mondo","Jimmy Fontana",1)
        assertTrue(verdict.admitted)
        assertEquals(3,verdict.evidence.size)
    }

    @Test fun videoMetadataBatchStaysBoundedAndProtectsPlayback() {
        val batch = CoverPreparationPolicy.metadataIds("abcdefghijk", listOf("next1234567"),
            emptySet(), protectPlayback=false)
        assertEquals("abcdefghijk",batch.first())
        assertTrue(batch.size <= 5)
        // A single bounded queue call may include two IDs here; it never
        // means two independent simultaneous network requests.
        assertEquals(listOf("abcdefghijk", "next1234567"),
            CoverPreparationPolicy.metadataIds("abcdefghijk",
                listOf("next1234567"), emptySet(), protectPlayback=true))
    }
}
