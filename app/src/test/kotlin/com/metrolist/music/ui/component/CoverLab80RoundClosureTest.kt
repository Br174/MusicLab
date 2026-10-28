package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab80RoundClosureTest {
    @Test fun idleClosesOnlyAfterSevenSecondsWithoutNewPreparedResults() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        p.beginPreparation(1)
        now = 6_999
        assertFalse(p.closeIfIdle(1))
        p.stage(1, "one")
        now = 13_998
        assertFalse(p.closeIfIdle(1))
        now = 14_000
        assertTrue(p.closeIfIdle(1))
        assertEquals(listOf("one"), p.reveal())
        assertTrue(p.status().green)
        assertFalse(p.stage(1, "late-row-retained-by-discovery"))
    }

    @Test fun audioPauseDoesNotConsumeIdleAllowance() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        p.beginPreparation(1)
        now = 60_000
        p.pauseIdleClock(1)
        assertFalse(p.closeIfIdle(1))
        now += 7_000
        assertTrue(p.closeIfIdle(1))
    }

    @Test fun threeResultsAreOneFinalPacketAndSkipRemainingCycles() {
        val p = CoverRoundPresentation<String> { it }
        repeat(3) { p.stage(1, "row$it") }
        assertTrue(p.takeInitial().isEmpty())
        p.exhaust(1)
        assertEquals(listOf("row0", "row1", "row2"), p.takeInitial())
        assertTrue(p.status().green)
        assertTrue(p.advance().isEmpty())
        assertEquals(2, p.visibleRound)
    }

    @Test fun fifteenRemainingResultsArePublishedTogetherBeforeGreen() {
        val p = CoverRoundPresentation<String> { it }
        repeat(25) { p.stage(1, "row$it") }
        assertEquals(10, p.takeInitial().size)
        assertEquals(0, p.status().ready)
        p.exhaust(1)
        assertEquals(15, p.reveal().size)
        assertTrue(p.status().green)
    }

    @Test fun timeoutIsRetryableAndOnlySuccessfulFinalSearchMayReportNoResults() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        p.finalRound = 1
        p.beginPreparation(1)
        now = 7_000
        p.closeIfIdle(1)
        assertTrue(p.status().retryRequired)
        assertFalse(p.status().noResults)
        assertFalse(p.status().green)
        p.retry(1)
        p.finalChecked(1)
        p.exhaust(1)
        assertTrue(p.status().terminal)
        assertTrue(p.status().noResults)
        assertFalse(p.needsPreparation())
    }

    @Test fun foundOriginalPreventsFalseZeroAndCurrentPlayerAloneDoesNotCount() {
        val p = CoverRoundPresentation<String> { it }
        p.finalRound = 1
        assertEquals(0, p.total(1))
        p.stage(1, "found-original-thumbnail")
        p.finalChecked(1)
        p.exhaust(1)
        assertEquals(1, p.reveal().size)
        assertTrue(p.status().terminal)
        assertFalse(p.status().noResults)
    }

    @Test fun finalQueriesContainOnlyTitleAfterEveryExistingFamily() {
        val last = CoverRoundSearchPlan.finalRound
        assertTrue(last > 4)
        assertTrue(CoverRoundSearchPlan.queries("Il mondo", last - 1, "Jimmy Fontana").size > 1)
        assertEquals(listOf("Il mondo"), CoverRoundSearchPlan.queries("Il mondo", last, "Jimmy Fontana").map { it.text })
        assertEquals(listOf("Il mondo"), CoverRoundSearchPlan.queries("Il mondo", last + 1, "Jimmy Fontana").map { it.text })
    }
}
