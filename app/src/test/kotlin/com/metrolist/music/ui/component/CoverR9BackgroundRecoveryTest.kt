package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverR9BackgroundRecoveryTest {
    @Test fun presentationDeadlineDoesNotExhaustSourcesOrRejectLateRows() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        p.beginPreparation(1)
        now = 7_000
        assertTrue(p.closeIfIdle(1))
        assertFalse(p.exhausted(1))
        assertEquals(1, p.preparationRound)
        assertTrue(p.needsPreparation())
        repeat(9) { p.stage(1, "row$it"); assertTrue(p.takeAutomatic().isEmpty()) }
        assertTrue(p.stage(1, "row9"))
        assertEquals(10, p.takeAutomatic().size)
    }

    @Test fun readyPacketAppearsImmediatelyWithoutWaitingForDeadline() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        p.beginPreparation(1)
        repeat(10) { p.stage(1, "row$it") }
        now = 1
        assertEquals(10, p.takeAutomatic().size)
        repeat(20) { p.stage(1, "second$it") }
        assertEquals(20, p.status().ready)
        assertEquals(20, p.reveal().size)
    }

    @Test fun everyCycleDeadlineIsMeasuredFromItsStartNotLastArrival() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        p.beginPreparation(1)
        now = 6_000
        p.stage(1, "row")
        now = 7_000
        assertTrue(p.closeIfIdle(1))
        assertEquals(0, p.status().ready)
        assertFalse(p.exhausted(1))
    }
}
