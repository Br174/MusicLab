package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverR12VerifiedResidualTest {
    @Test fun twoVerifiedPostersArePublishedTogetherOnSecondCyclePressWithoutExhaustingSources() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        p.beginPreparation(1)
        p.stage(1, "verified-video-a")
        p.stage(1, "verified-video-b")
        assertTrue(p.takeAutomatic().isEmpty())
        now = 7_000L
        assertTrue(p.closeIfIdle(1))
        assertEquals(2, p.status().prepared)
        assertEquals(2, p.status().nextCycle)
        val published = p.takeManualVerifiedPacket()
        assertEquals(listOf("verified-video-a", "verified-video-b"), published)
        assertEquals(2, p.status().shown)
        assertEquals(0, p.status().prepared)
        assertFalse(p.exhausted(1))
        assertEquals(1, p.preparationRound)
    }

    @Test fun thirdCycleCanReleaseItsOwnSmallVerifiedPacketAndAllRowsRemainUnique() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        p.beginPreparation(1)
        p.stage(1, "a")
        now = 7_000L
        p.closeIfIdle(1)
        assertEquals(listOf("a"), p.takeManualVerifiedPacket())
        p.stage(1, "b")
        p.stage(1, "c")
        now = 14_000L
        p.closeIfIdle(1)
        assertEquals(3, p.status().nextCycle)
        assertEquals(listOf("b","c"), p.takeManualVerifiedPacket())
        assertEquals(3, p.status().shown)
        now = 21_000L
        p.closeIfIdle(1)
        assertTrue(p.status().green)
        assertTrue(p.advance().isEmpty())
        assertEquals(2, p.visibleRound)
    }

    @Test fun automaticTenAndManualTwentyRemainAtomicWithoutArtificialDelays() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        p.beginPreparation(1)
        repeat(9) { p.stage(1, "first$it") }
        assertTrue(p.takeAutomatic().isEmpty())
        p.stage(1, "first9")
        assertEquals(10, p.takeAutomatic().size)
        repeat(19) { p.stage(1, "second$it") }
        assertEquals(0,p.status().ready)
        p.stage(1, "second19")
        assertEquals(20,p.status().ready)
        assertEquals(20,p.reveal().size)
        assertEquals(30,p.status().shown)
    }

    @Test fun unverifiedCandidatesNeverBecomeVisibleFromWindowTimeout() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        p.beginPreparation(1)
        now = 7_000L
        p.closeIfIdle(1)
        assertTrue(p.takeManualVerifiedPacket().isEmpty())
        assertEquals(0,p.status().shown)
        assertFalse(p.exhausted(1))
        now = 14_000L
        p.closeIfIdle(1)
        assertTrue(p.takeManualVerifiedPacket().isEmpty())
        now = 21_000L
        p.closeIfIdle(1)
        assertTrue(p.status().green)
    }
}
