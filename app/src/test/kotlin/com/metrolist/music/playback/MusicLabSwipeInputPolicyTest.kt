package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicLabSwipeInputPolicyTest {
    @Test fun singleSwipeMovesExactlyOnePublishedResult() {
        assertEquals(4, MusicLabSwipeInputPolicy.nextTarget(3, 50, 1))
        assertEquals(2, MusicLabSwipeInputPolicy.nextTarget(3, 50, -1))
    }

    @Test fun rapidSwipeAccumulatesIntendedStepsWithoutLosingInput() {
        val first = MusicLabSwipeInputPolicy.nextTarget(3, 50, 1)
        val second = MusicLabSwipeInputPolicy.nextTarget(3, 50, 1, first)
        assertEquals(4, first)
        assertEquals(5, second)
        assertEquals(6, MusicLabSwipeInputPolicy.nextTarget(3, 50, 1, second))
    }

    @Test fun oppositeSwipeCancelsPendingMoveBackToCurrent() {
        val first = MusicLabSwipeInputPolicy.nextTarget(3, 50, 1)
        assertEquals(3, MusicLabSwipeInputPolicy.nextTarget(3, 50, -1, first))
    }

    @Test fun noPhantomWrapAtEitherResultBoundary() {
        assertNull(MusicLabSwipeInputPolicy.nextTarget(49, 50, 1))
        assertNull(MusicLabSwipeInputPolicy.nextTarget(0, 50, -1))
        assertNull(MusicLabSwipeInputPolicy.nextTarget(49, 50, 1, 49))
        assertNull(MusicLabSwipeInputPolicy.nextTarget(-1, 50, 1))
    }
}
