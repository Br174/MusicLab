package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedResultAutoplayPolicyTest {
    @Test fun automaticProgressionTraversesEveryRemainingResult() {
        assertEquals(listOf(3,4,5), OrderedResultAutoplayPolicy.candidates(2,6,1,true))
        assertEquals(listOf(3), OrderedResultAutoplayPolicy.candidates(2,6,1,false))
        assertEquals(listOf(1), OrderedResultAutoplayPolicy.candidates(2,6,-1,false))
    }

    @Test fun neverLoopsPastFinalResultOrBeforeFirst() {
        assertEquals(emptyList<Int>(), OrderedResultAutoplayPolicy.candidates(5,6,1,true))
        assertEquals(emptyList<Int>(), OrderedResultAutoplayPolicy.candidates(0,6,-1,false))
        assertEquals(emptyList<Int>(), OrderedResultAutoplayPolicy.candidates(-1,6,1,true))
        assertEquals(emptyList<Int>(), OrderedResultAutoplayPolicy.candidates(0,0,1,true))
    }

    @Test fun respectUserPauseAndSleepTimerAndCurrentSelectionOwnership() {
        val yes = OrderedResultAutoplayPolicy.shouldAdvanceAtEnd(true,true,true,true,true)
        assertTrue(yes)
        assertFalse(OrderedResultAutoplayPolicy.shouldAdvanceAtEnd(false,true,true,true,true))
        assertFalse(OrderedResultAutoplayPolicy.shouldAdvanceAtEnd(true,false,true,true,true))
        assertFalse(OrderedResultAutoplayPolicy.shouldAdvanceAtEnd(true,true,false,true,true))
        assertFalse(OrderedResultAutoplayPolicy.shouldAdvanceAtEnd(true,true,true,false,true))
        assertFalse(OrderedResultAutoplayPolicy.shouldAdvanceAtEnd(true,true,true,true,false))
    }

    @Test fun earlierFailuresAreSkippableWithoutChangingFinalBoundary() {
        val candidates = OrderedResultAutoplayPolicy.candidates(0,5,1,true)
        assertEquals(listOf(1,2,3,4), candidates)
        val unavailable = setOf(1,2)
        assertEquals(3,candidates.first { it !in unavailable })
    }
}
