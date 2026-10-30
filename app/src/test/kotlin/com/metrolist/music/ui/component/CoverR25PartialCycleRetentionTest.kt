package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

/** R25: 10+20+20 are maximum capacities, never minimum admission quotas. */
class CoverR25PartialCycleRetentionTest {
    @Test fun threePlusTwoPlusFourAreAllVisibleAndNothingIsLost() {
        var clock = 0L
        val p = CoverRoundPresentation<String>(clock = { clock }) { it }
        p.beginPreparation(1)
        repeat(3) { assertTrue(p.stage(1, "cycle1-$it")) }
        assertEquals(0, p.status().ready)
        clock = 7_000L
        assertTrue(p.closeIfIdle(1))
        assertEquals(3, p.status().ready)
        assertEquals((0..2).map { "cycle1-$it" }, p.takeTimedCycle())
        assertEquals(3, p.status().shown)
        assertEquals(2, p.status().nextCycle)
        assertTrue(p.continueEmptyWindow())

        repeat(2) { assertTrue(p.stage(1, "cycle2-$it")) }
        clock = 14_000L
        assertTrue(p.closeIfIdle(1))
        assertEquals(2, p.status().ready)
        assertEquals(listOf("cycle2-0","cycle2-1"),p.takeTimedCycle())
        assertEquals(5, p.status().shown)
        assertFalse(p.status().green)
        assertTrue(p.continueEmptyWindow())

        repeat(4) { assertTrue(p.stage(1, "cycle3-$it")) }
        clock = 21_000L
        assertTrue(p.closeIfIdle(1))
        assertEquals(4, p.status().ready)
        assertEquals(4, p.takeTimedCycle().size)
        assertEquals(9, p.status().shown)
        assertTrue(p.status().green)
        assertTrue(p.preparedRows().isEmpty())
    }

    @Test fun fullSecondPacketAfterShortFirstDoesNotSkipThirdCycle() {
        var clock = 0L
        val p = CoverRoundPresentation<String>(clock = { clock }) { it }
        p.beginPreparation(1)
        repeat(3) { p.stage(1, "a$it") }
        clock = 7_000L
        p.closeIfIdle(1)
        assertEquals(3, p.takeTimedCycle().size)
        assertTrue(p.continueEmptyWindow())
        repeat(20) { p.stage(1, "b$it") }
        assertEquals(20, p.status().ready)
        assertEquals(20, p.reveal().size)
        assertEquals(23, p.status().shown)
        assertFalse(p.status().green)
        assertEquals(3, p.status().nextCycle)
        assertTrue(p.continueEmptyWindow())
        p.stage(1,"last")
        clock = 14_000L
        assertTrue(p.closeIfIdle(1))
        assertEquals(listOf("last"), p.takeTimedCycle())
        assertEquals(24, p.status().shown)
        assertTrue(p.status().green)
    }

    @Test fun lateVerifiedRowsAfterSourceExhaustionAreNotRejected() {
        val p = CoverRoundPresentation<String> { it }
        p.stage(1,"first")
        p.exhaust(1)
        assertTrue(p.stage(1,"arrived-late"))
        assertEquals(setOf("first","arrived-late"),p.preparedRows(1).toSet())
        assertFalse(p.status().green)
        assertEquals(2,p.reveal().size)
        assertTrue(p.status().green)
    }

    @Test fun lateEvidenceAfterThreeShortCyclesKeepsCurrentRoundOpenUntilShown() {
        var time=0L
        val p=CoverRoundPresentation<String>(clock={time}) { it }
        p.beginPreparation(1)
        repeat(3) { cycle ->
            p.stage(1,"row-$cycle")
            time += 7_000L
            p.closeIfIdle(1)
            assertEquals(1,p.takeTimedCycle().size)
            if (cycle < 2) assertTrue(p.continueEmptyWindow())
        }
        assertTrue(p.status().green)
        assertTrue(p.stage(1,"late-but-valid"))
        assertFalse(p.status().green)
        assertEquals(1,p.status().ready)
        assertEquals(listOf("late-but-valid"),p.reveal())
        assertTrue(p.status().green)
        assertEquals(4,p.status().shown)
    }

    @Test fun verifiedCallbackFromOldRoundFollowsTheVisibleRoundWithoutDuplicates() {
        val p = CoverRoundPresentation<String> { it }
        p.stage(1, "first-verified")
        p.exhaust(1)
        assertEquals(listOf("first-verified"), p.reveal())
        assertTrue(p.status().green)
        p.advance()
        assertEquals(2, p.visibleRound)
        assertTrue(p.stage(1, "late-from-first"))
        assertEquals(listOf("late-from-first"), p.preparedRows(2))
        assertFalse(p.stage(1, "first-verified"))
        assertEquals(listOf("late-from-first"), p.preparedRows(2))
    }

    @Test fun overflowAndPreviousRoundRemainRetainedAcrossGreen() {
        val p=CoverRoundPresentation<String> { it }
        repeat(52) { p.stage(1,"row-$it") }
        assertEquals(50,p.total(1))
        assertEquals(10,p.takeInitial().size)
        assertEquals(20,p.reveal().size)
        assertEquals(20,p.reveal().size)
        assertTrue(p.status().green)
        assertTrue(p.advance().isEmpty())
        assertEquals(2,p.visibleRound)
        assertEquals(setOf("row-50","row-51"),p.preparedRows(2).toSet())
    }
}
