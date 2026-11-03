package com.metrolist.music.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLabHitParadeVideoMemoryTest {
    @Test
    fun sameRecordingAcrossWeeksHasSameKey() {
        val week1982 = MusicLabHitParadeVideoMemory.key("L'amore è", "Niccolò")
        val week1983 = MusicLabHitParadeVideoMemory.key("L AMORE E", "Niccolo")
        assertEquals(week1982, week1983)
    }

    @Test
    fun artistIsRequiredForSongIdentity() {
        assertNotEquals(
            MusicLabHitParadeVideoMemory.key("Il mondo", "Jimmy Fontana"),
            MusicLabHitParadeVideoMemory.key("Il mondo", "Altro Artista"),
        )
    }

    @Test
    fun manualRecordingAlwaysBeatsAutomaticCache() {
        val old = MusicLabHitParadeVideoMemory.Choice(
            "dQw4w9WgXcQ", "La canzone", "Il cantante", null, manual = true)
        assertFalse(MusicLabHitParadeVideoMemory.shouldSaveAutomatic(old))
        assertTrue(MusicLabHitParadeVideoMemory.shouldSaveAutomatic(old.copy(manual = false)))
        assertTrue(MusicLabHitParadeVideoMemory.shouldSaveAutomatic(null))
    }

    @Test
    fun rejectsInvalidVideoIdsBeforePersistence() {
        assertTrue(MusicLabHitParadeVideoMemory.validVideoId("dQw4w9WgXcQ"))
        assertFalse(MusicLabHitParadeVideoMemory.validVideoId("not-a-youtube-url"))
        assertFalse(MusicLabHitParadeVideoMemory.validVideoId(""))
    }
}
