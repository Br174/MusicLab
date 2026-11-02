package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLabR42SectionSelectionTest {
    @Test fun lastSelectedListIsIndependentFromCurrentScreen() {
        val selected = MusicLabSectionPlayback.Snapshot(
            source = "Hit Parade · 15 giugno 1985", index = 4, size = 25,
            mediaId = "verified-video", revision = 42,
            selectionKey = "hitparade:487654320000", selectedKey = "5"
        )
        assertEquals("hitparade:487654320000", selected.selectionKey)
        assertEquals("5", selected.selectedKey)
        assertTrue(selected.canNext)
        assertTrue(selected.canPrevious)
        assertEquals(selected.selectionKey,
            selected.copy(index = 5, selectedKey = "6").selectionKey)
    }

    @Test fun contextualEdgesNeverEscapeToRadio() {
        val first = MusicLabSectionPlayback.Snapshot(
            source = "Compilation", index = 0, size = 3,
            mediaId = "a", revision = 42, selectedKey = "0"
        )
        val last = first.copy(index = 2, mediaId = "c", selectedKey = "2")
        assertFalse(first.canPrevious)
        assertFalse(last.canNext)
        assertEquals(0, first.index)
        assertEquals("2", last.selectedKey)
    }
}
