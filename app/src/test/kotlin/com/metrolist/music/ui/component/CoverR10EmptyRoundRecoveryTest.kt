package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverR10EmptyRoundRecoveryTest {
    @Test fun zeroPostersStillPermitsSecondThenThirdCycleAndNextRound() {
        var time = 0L
        val p = CoverRoundPresentation<String>(clock = { time }) { it }
        p.beginPreparation(1)
        time = 7_000
        assertTrue(p.closeIfIdle(1))
        assertFalse(p.status().green)
        assertTrue(p.status().manualContinue)
        assertEquals(2, p.status().nextCycle)
        assertTrue(p.continueEmptyWindow())
        assertFalse(p.exhausted(1))
        time = 14_000
        assertTrue(p.closeIfIdle(1))
        assertEquals(3, p.status().nextCycle)
        assertTrue(p.continueEmptyWindow())
        time = 21_000
        assertTrue(p.closeIfIdle(1))
        assertTrue(p.status().green)
        assertFalse(p.exhausted(1))
        assertTrue(p.advance().isEmpty())
        assertEquals(2, p.visibleRound)
    }

    @Test fun partialPacketStillHasManualContinueAndRetainsAllPreparedRows() {
        var time = 0L
        val p = CoverRoundPresentation<String>(clock = { time }) { it }
        p.beginPreparation(1)
        repeat(7) { p.stage(1, "candidate$it") }
        time = 7_000
        assertTrue(p.closeIfIdle(1))
        assertTrue(p.status().manualContinue)
        assertEquals(7, p.status().prepared)
        assertTrue(p.continueEmptyWindow())
        time = 14_000
        p.closeIfIdle(1)
        assertTrue(p.continueEmptyWindow())
        time = 21_000
        p.closeIfIdle(1)
        assertTrue(p.status().green)
        p.advance()
        assertEquals(7, p.total(2))
        assertFalse(p.exhausted(1))
    }

    @Test fun fullTenPublishesImmediatelyWhileTheNextTwentyRemainManual() {
        var time = 0L
        val p = CoverRoundPresentation<String>(clock = { time }) { it }
        repeat(10) { p.stage(1, "row$it") }
        assertEquals(10, p.takeAutomatic().size)
        assertFalse(p.status().manualContinue)
        time = 7_000
        p.closeIfIdle(1)
        assertTrue(p.status().manualContinue)
        assertEquals(3, p.status().nextCycle)
        repeat(20) { p.stage(1, "next$it") }
        assertEquals(20, p.status().ready)
        assertEquals(10, p.status().shown)
        assertEquals(20, p.reveal().size)
    }

    @Test fun twoHundredNinetyUnlinkedLeadsCannotStarveDocumentedReadyVideo() {
        val queue = CoverPriorityOrder.Pending()
        repeat(290) { n ->
            queue.offer(CoverSourceCandidate(
                title = "Il mondo", artist = "Discogs Singer $n",
                sources = listOf("Discogs"), album = "Release $n",
                releaseVerified = true, playbackVideoId = null))
        }
        val ready = CoverSourceCandidate(
            title = "Il mondo", artist = "Documented Singer",
            sources = listOf("COVER.INFO"), workRelationConfirmed = true,
            playbackVideoId = "ABCD1234567", playbackVideoTitle = "Il mondo")
        queue.offer(ready)
        assertEquals(291, queue.size)
        queue.beginPass()
        assertEquals("ABCD1234567", queue.next("Il mondo", "Jimmy Fontana", 1)?.playbackVideoId)
        assertEquals(291, queue.size)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(ready, "Il mondo", "Jimmy Fontana", 1).admitted)
    }
}
