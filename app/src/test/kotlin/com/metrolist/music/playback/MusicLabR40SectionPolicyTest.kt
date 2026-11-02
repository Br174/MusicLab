package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLabR40SectionPolicyTest {
    @Test fun contextUsesOrderedVisibleListNotScreenNavigation() {
        val origin = MusicLabSectionPlayback.Snapshot("Hit Parade", 4, 10, "track-5", 10L)
        assertEquals(4, origin.index)
        assertEquals(10, origin.size)
        assertTrue(origin.canPrevious)
        assertTrue(origin.canNext)
        assertEquals("Hit Parade", origin.source)
    }

    @Test fun sectionBoundariesAreNotARadioFallback() {
        val first = MusicLabSectionPlayback.Snapshot("Cover", 0, 6, "cover-1", 11L)
        val last = first.copy(index = 5, mediaId = "cover-6")
        assertFalse(first.canPrevious)
        assertTrue(first.canNext)
        assertTrue(last.canPrevious)
        assertFalse(last.canNext)
    }

    @Test fun listPositionAndSelectedArtworkAreSeparate() {
        val key = "test-r40-list-position"
        MusicLabListPositionStore.clear(key)
        MusicLabListPositionStore.select(key, "discogs-123")
        MusicLabListPositionStore.scroll(key, 67, 16)
        assertEquals(67, MusicLabListPositionStore.get(key).index)
        assertEquals(16, MusicLabListPositionStore.get(key).offset)
        assertEquals("discogs-123", MusicLabListPositionStore.get(key).selectedKey)
        MusicLabListPositionStore.clear(key)
    }
}
