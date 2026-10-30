package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

/** R14: each user-approved seven-second cycle publishes its actual verified batch. */
class CoverR14CycleBatchTest {
    @Test fun twoThenThreeThenFourPublishAsThreeDistinctAtomicCycleBatches() {
        var time = 0L
        val p = CoverRoundPresentation<String>(clock = { time }) { it }
        p.beginPreparation(1)
        repeat(2) { p.stage(1, "first-$it") }
        assertTrue(p.takeAutomatic().isEmpty())
        time = 7_000
        assertTrue(p.closeIfIdle(1))
        assertEquals(listOf("first-0", "first-1"), p.takeTimedCycle())
        assertEquals(2, p.status().shown)
        assertEquals(0, p.status().prepared)
        assertTrue(p.takeTimedCycle().isEmpty())
        assertTrue(p.status().manualContinue)
        assertEquals(2, p.status().nextCycle)

        assertTrue(p.continueEmptyWindow())
        repeat(3) { p.stage(1, "second-$it") }
        time = 14_000
        assertTrue(p.closeIfIdle(1))
        assertEquals((0..2).map { "second-$it" }, p.takeTimedCycle())
        assertEquals(5, p.status().shown)
        assertEquals(3, p.status().nextCycle)
        assertTrue(p.continueEmptyWindow())

        repeat(4) { p.stage(1, "third-$it") }
        time = 21_000
        assertTrue(p.closeIfIdle(1))
        assertEquals((0..3).map { "third-$it" }, p.takeTimedCycle())
        assertEquals(9, p.status().shown)
        assertTrue(p.status().green)
        assertFalse(p.exhausted(1))
        assertTrue(p.advance().isEmpty())
        assertEquals(2, p.visibleRound)
    }

    @Test fun fullFirstTenRemainImmediateWithoutWaitingSevenSeconds() {
        var time = 0L
        val p = CoverRoundPresentation<String>(clock = { time }) { it }
        repeat(9) { p.stage(1, "first-$it") }
        assertTrue(p.takeAutomatic().isEmpty())
        p.stage(1, "first-9")
        assertEquals(10, p.takeAutomatic().size)
        assertEquals(10, p.status().shown)
        assertEquals(0L, time)
        assertFalse(p.status().manualContinue)
    }

    @Test fun fullLaterTwentyStillBecomeAvailableAsOneCompletePacket() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        repeat(10) { p.stage(1, "first-$it") }
        assertEquals(10, p.takeAutomatic().size)
        repeat(20) { p.stage(1, "second-$it") }
        assertEquals(20, p.status().ready)
        assertEquals(20, p.reveal().size)
        assertEquals(30, p.status().shown)
        assertEquals(0, p.status().prepared)
    }

    @Test fun noVerifiedRowsCannotBePublishedAtATimerOrDeleteSources() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        p.beginPreparation(1)
        now = 7_000
        p.closeIfIdle(1)
        assertTrue(p.takeTimedCycle().isEmpty())
        assertEquals(0, p.status().shown)
        assertFalse(p.exhausted(1))
        assertTrue(p.status().manualContinue)
        assertTrue(p.continueEmptyWindow())
        assertFalse(p.exhausted(1))
    }

    @Test fun addingVerifiedRowsAfterCompletedWindowDoesNotPublishAnotherBatchUntilNextCycle() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        p.beginPreparation(1)
        p.stage(1, "a")
        now = 7_000L
        p.closeIfIdle(1)
        assertEquals(listOf("a"), p.takeTimedCycle())
        p.stage(1, "b")
        assertTrue(p.takeTimedCycle().isEmpty())
        assertEquals(1, p.status().prepared)
        assertTrue(p.continueEmptyWindow())
        now = 14_000L
        p.closeIfIdle(1)
        assertEquals(listOf("b"), p.takeTimedCycle())
        assertEquals(2, p.status().shown)
    }

    @Test fun timedCycleRespectsTenAndTwentyLimitsAndRetainsUnpublishedRows() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        p.beginPreparation(1)
        repeat(35) { p.stage(1, "row-$it") }
        assertEquals(10, p.takeAutomatic().size)
        now = 7_000L
        p.closeIfIdle(1)
        assertEquals(20, p.takeTimedCycle().size)
        assertEquals(5, p.status().prepared)
        assertTrue(p.takeTimedCycle().isEmpty())
        assertEquals(30, p.status().shown)
    }

    @Test fun timeoutNeverDemotesTheFirstRoundDocumentaryEvidence() {
        val c = CoverSourceCandidate(
            title="Il mondo", artist="Foreign singer", sources=listOf("COVER.INFO"),
            workRelationConfirmed=true, category=AiCoverCategory.FOREIGN,
            playbackVideoId="abcdefghijk", playbackVideoTitle="El mundo",
        ).copy(title = "El mundo")
        assertTrue(CoverLabTurboRoundAdmission.evaluate(c, "Il mondo", "Jimmy Fontana", 1).admitted)
    }
}
