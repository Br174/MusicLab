package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab78PresentationTest {
    private fun presentation() = CoverRoundPresentation<String> { it }
    private fun prepare(p: CoverRoundPresentation<String>, round: Int, count: Int, prefix: String = "r$round-") {
        repeat(count) { assertTrue(p.stage(round, "$prefix$it")) }
    }

    @Test fun firstFiveDoNotWaitForTwentyFiveOrSourceCompletion() {
        val p = presentation()
        repeat(5) {
            p.stage(1, "video$it")
            assertEquals(listOf("video$it"), p.takeInitial())
        }
        assertEquals(5, p.status().shown)
        assertFalse(p.status().green)
    }

    @Test fun twoBlocksShareOneRoundAndRevealExactlyThePreparedCount() {
        val p = presentation()
        prepare(p, 1, 50)
        assertEquals(10, p.takeInitial().size)
        assertEquals(20, p.status().ready)
        assertEquals(1, p.visibleRound)
        assertEquals(20, p.reveal().size)
        assertEquals(30, p.status().shown)
        assertEquals(20, p.reveal().size)
        assertEquals(50, p.status().shown)
        assertTrue(p.status().green)
        assertEquals(2, p.preparationRound)
    }

    @Test fun unfinishedSmallBufferCannotPromiseFinalCountOrExhaustion() {
        val p = presentation()
        prepare(p, 1, 10)
        p.takeInitial()
        prepare(p, 1, 8, "rest-")
        assertEquals(0, p.status().ready)
        assertFalse(p.status().green)
        assertTrue(p.reveal().isEmpty())
        p.exhaust(1)
        assertEquals(8, p.status().ready)
        assertEquals(8, p.reveal().size)
        assertEquals(18, p.status().shown)
        assertTrue(p.status().green)
    }

    @Test fun threeAvailableRowsBecomeVisibleAndThenGreenWithoutEmptyClick() {
        val p = presentation()
        prepare(p, 1, 3)
        assertEquals(3, p.takeInitial().size)
        assertFalse(p.status().green)
        p.exhaust(1)
        assertTrue(p.status().green)
    }

    @Test fun noReadyRowsAndRetryableWorkAreNotExhaustion() {
        val p = presentation()
        prepare(p, 1, 10)
        p.takeInitial()
        assertEquals(0, p.status().ready)
        assertFalse(p.status().green)
        assertEquals(1, p.preparationRound)
        assertTrue(p.needsPreparation())
    }

    @Test fun nextRoundIsPreparedButInvisibleUntilGreenPress() {
        val p = presentation()
        prepare(p, 1, 50)
        p.takeInitial(); p.reveal(); p.reveal()
        prepare(p, 2, 20)
        assertEquals(1, p.visibleRound)
        assertEquals(50, p.status().shown)
        assertEquals(20, p.status().nextReady)
        assertFalse(p.needsPreparation())
        assertEquals((0 until 10).map { "r2-$it" }, p.advance())
        assertEquals(2, p.visibleRound)
        assertEquals(10, p.status().shown)
        assertTrue(p.needsPreparation())
    }

    @Test fun earlyGreenPressCanStreamAlreadyPreparedNextRoundPrefix() {
        val p = presentation()
        p.exhaust(1)
        prepare(p, 2, 5)
        assertEquals(0, p.status().nextReady)
        assertEquals(5, p.advance().size)
        assertEquals(2, p.visibleRound)
        assertFalse(p.status().green)
        assertTrue(p.needsPreparation())
    }

    @Test fun fiftyIsPresentationCapacityRatherThanDestructiveOverflow() {
        val p = presentation()
        prepare(p, 1, 50)
        assertTrue(p.stage(1, "overflow-retained-by-discovery"))
        assertTrue(p.stage(1, "overflow-retained-by-discovery"))
        assertEquals(50, p.total(1))
        p.takeInitial(); p.reveal(); p.reveal()
        assertEquals(listOf("overflow-retained-by-discovery"), p.advance())
        assertTrue(p.preparedRows().isEmpty())
    }

    @Test fun legacyRestorationKeepsEveryPreviouslyVisibleRow() {
        val p = presentation()
        p.restore(3, (0 until 63).map { "legacy-$it" })
        assertEquals(3, p.visibleRound)
        assertEquals(63, p.status().shown)
        assertTrue(p.status().green)
        assertEquals(4, p.preparationRound)
    }

    @Test fun lateEvidenceCanRefreshHiddenRowsWithoutShowingThem() {
        val p = presentation()
        prepare(p, 1, 10)
        p.takeInitial()
        p.stage(1, "hidden")
        p.mapPrepared { "$it-updated" }
        assertEquals(listOf("hidden-updated"), p.preparedRows(1))
        assertEquals(10, p.status().shown)
    }

    @Test fun resetRemovesOldPreparationAndExhaustion() {
        val p = presentation()
        prepare(p, 1, 3)
        p.exhaust(1)
        p.clear()
        assertEquals(1, p.visibleRound)
        assertEquals(0, p.status().shown)
        assertTrue(p.preparedRows().isEmpty())
        assertFalse(p.status().green)
    }

    @Test fun documentaryCorrectionSynchronizesShownCountWithoutDroppingHiddenRows() {
        val p = presentation()
        prepare(p, 1, 50)
        p.takeInitial()
        p.synchronizeShown(1, (0 until 9).map { "r1-$it" })
        assertEquals(9, p.status().shown)
        assertEquals(40, p.preparedRows(1).size)
        assertTrue(p.stage(1, "replacement"))
        assertEquals(1, p.takeInitial().size)
        assertEquals(20, p.status().ready)
    }

    @Test fun alreadyShownVideoCannotBePublishedAgainByARepeatedSource() {
        val p = presentation()
        p.stage(1, "same-video")
        assertEquals(listOf("same-video"), p.takeInitial())
        assertFalse(p.stage(1, "same-video"))
        assertTrue(p.takeInitial().isEmpty())
        assertEquals(1, p.status().shown)
    }
}
