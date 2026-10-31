package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

/** Positive admission and negative legacy-gate regressions for Bruno's R17 policy. */
class CoverR17QualityProgressiveTest {
    private fun c(title: String = "Il mondo", artist: String = "Unverified Performer",
        source: String = "YouTube") = CoverSourceCandidate(title=title, artist=artist,
        sources=listOf(source), playbackVideoId="candidate001",
        playbackVideoTitle=title)

    @Test fun aSingleMatchingTitleCanEnterEveryRoundWithoutAProofQuota() {
        val candidate=c()
        for (round in 1..6) {
            val result=CoverLabTurboRoundAdmission.evaluate(candidate,"Il mondo","Jimmy Fontana",round)
            assertTrue("round $round cannot defer a matching title",result.admitted)
            assertEquals(1,result.minimumRound)
            assertFalse(result.rejected)
        }
    }

    @Test fun legacyHeldUntilRoundDoesNotSurviveTheNewQueue() {
        val candidate=c()
        val queue=CoverPriorityOrder.Pending()
        queue.offer(candidate)
        queue.deferUntil(candidate,6)
        queue.beginPass()
        assertTrue(queue.eligible(candidate,1))
        assertNotNull(queue.next("Il mondo","Jimmy Fontana",1))
    }

    @Test fun documentaryLeadsCannotStarveYoutubeAfterTheBoundedFallback() {
        val queue = CoverPriorityOrder.Pending()
        repeat(15) { n ->
            queue.offer(c(artist="Documented $n",source="COVER.INFO").copy(
                playbackVideoId=null,workRelationConfirmed=true))
        }
        val ready=c(artist="Ready singer")
        queue.offer(ready)
        queue.beginPass()
        repeat(3) {
            assertNull(queue.next("Il mondo","Jimmy Fontana",1)?.playbackVideoId)
        }
        // R27: the fourth documentary lead still goes first; old unconditional
        // YouTube fast-pass is retired without removing any result.
        assertNull(queue.next("Il mondo","Jimmy Fontana",1)?.playbackVideoId)
        // At the existing bounded source-window deadline the original R17
        // anti-starvation guarantee remains fully active.
        assertEquals("candidate001",queue.next("Il mondo","Jimmy Fontana",1,
            allowYoutubeFallback = true)?.playbackVideoId)
        assertEquals(16,queue.size)
    }

    @Test fun karaokeAndWrongItalianTitleRemainForbiddenInEveryRound() {
        for (round in 1..6) {
            assertFalse(CoverLabTurboRoundAdmission.evaluate(
                c(title="Il mondo karaoke"),"Il mondo","Jimmy Fontana",round).admitted)
            assertFalse(CoverLabTurboRoundAdmission.evaluate(
                c(title="Il mondo che vorrei"),"Il mondo","Jimmy Fontana",round).admitted)
        }
    }

    @Test fun documentaryForeignAdaptationCanEnterFirstWithoutChangingOriginalTitle() {
        val foreign=c("El mundo","Foreign Singer","COVER.INFO").copy(
            category=AiCoverCategory.FOREIGN,workRelationConfirmed=true)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            foreign,"Il mondo","Jimmy Fontana",1).admitted)
    }

    @Test fun documentedAndOriginalRenditionsRankAboveTitleOnlyWithoutRemovingThem() {
        val weak=c()
        val documented=c(artist="Documented Singer",source="COVER.INFO").copy(
            playbackVideoId="document001",workRelationConfirmed=true)
        val original=c(artist="Jimmy Fontana",source="COVER.INFO").copy(
            playbackVideoId="original001",workRelationConfirmed=true,
            originalWorkReference=true,firstPublicationConfirmed=true)
        val ordered=CoverPriorityOrder.order(listOf(weak,documented,original),
            "Il mondo","Jimmy Fontana",1)
        assertEquals(listOf("original001","document001","candidate001"),
            ordered.map { it.playbackVideoId })
        assertEquals(3,ordered.size)
    }
}
