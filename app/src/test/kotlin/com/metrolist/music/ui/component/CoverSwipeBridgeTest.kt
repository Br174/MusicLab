package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R61 real JVM class: Cover art may only belong to the outgoing/incoming ID. */
class CoverSwipeBridgeTest {
    @Test
    fun twoCorrectCoverFramesAndNoPlaylistGhost() {
        val screen = "r61-frame"
        CoverSwipeBridge.bind(screen) { _, _ -> }
        try {
            CoverSwipeBridge.activate(screen, "VIDEO_COVER_A")
            assertTrue(CoverSwipeBridge.isCoverArtwork("VIDEO_COVER_A"))
            assertFalse(CoverSwipeBridge.isCoverArtwork("QUEUE_GHOST"))

            CoverSwipeBridge.beginSelection(screen)
            assertTrue(CoverSwipeBridge.isCoverArtwork("VIDEO_COVER_A"))
            CoverSwipeBridge.expectIncoming(screen, "VIDEO_COVER_B")
            assertTrue(CoverSwipeBridge.isCoverArtwork("VIDEO_COVER_A"))
            assertTrue(CoverSwipeBridge.isCoverArtwork("VIDEO_COVER_B"))
            assertFalse(CoverSwipeBridge.isCoverArtwork("QUEUE_GHOST"))

            CoverSwipeBridge.activate(screen, "VIDEO_COVER_B")
            assertFalse(CoverSwipeBridge.isCoverArtwork("VIDEO_COVER_A"))
            assertTrue(CoverSwipeBridge.isCoverArtwork("VIDEO_COVER_B"))
        } finally {
            CoverSwipeBridge.clear(screen)
        }
        assertFalse(CoverSwipeBridge.isCoverArtwork("VIDEO_COVER_B"))
    }

    @Test
    fun secondSwipeCannotRaceAnUnfinishedSelection() {
        val screen = "r61-swipe"
        val directions = mutableListOf<Int>()
        CoverSwipeBridge.bind(screen) { dir, _ -> directions += dir }
        try {
            CoverSwipeBridge.activate(screen, "REC_A")
            assertTrue(CoverSwipeBridge.trySwipe("REC_A", 1))
            assertEquals(listOf(1), directions)
            CoverSwipeBridge.beginSelection(screen)
            assertTrue(CoverSwipeBridge.trySwipe("REC_A", 1))
            assertEquals(listOf(1), directions)
            assertFalse(CoverSwipeBridge.trySwipe("NOT_COVER", 1))
            CoverSwipeBridge.cancelSelection(screen)
            assertTrue(CoverSwipeBridge.trySwipe("REC_A", -1))
            assertEquals(listOf(1, -1), directions)
        } finally {
            CoverSwipeBridge.clear(screen)
        }
    }

    @Test
    fun otherOwnerAndEndOfSessionCannotBorrowCoverArt() {
        val screen = "r61-owner"
        val other = "r61-other"
        CoverSwipeBridge.bind(screen) { _, _ -> }
        CoverSwipeBridge.activate(screen, "REC_A")
        CoverSwipeBridge.expectIncoming(other, "WRONG")
        assertFalse(CoverSwipeBridge.isCoverArtwork("WRONG"))
        CoverSwipeBridge.stop(screen)
        assertFalse(CoverSwipeBridge.isCoverArtwork("REC_A"))
        CoverSwipeBridge.bind(other) { _, _ -> }
        assertFalse(CoverSwipeBridge.isCoverArtwork("REC_A"))
        CoverSwipeBridge.clear(other)
    }
}
