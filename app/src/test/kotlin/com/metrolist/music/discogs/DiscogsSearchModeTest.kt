package com.metrolist.music.discogs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscogsSearchModeTest {
    @Test fun albumResultsNeverContainCompilationOnlyOrMixedFormats() {
        assertTrue(DiscogsSearchMode.ALBUM.accepts(listOf("CD", "Album")))
        assertFalse(DiscogsSearchMode.ALBUM.accepts(listOf("CD", "Compilation")))
        assertFalse(DiscogsSearchMode.ALBUM.accepts(listOf("CD", "Album", "Compilation")))
    }

    @Test fun compilationResultsAllowDiscogsDoubleTaggedReleases() {
        assertTrue(DiscogsSearchMode.COMPILATION.accepts(listOf("CD", "Compilation")))
        assertTrue(DiscogsSearchMode.COMPILATION.accepts(listOf("Album", "Compilation")))
        assertFalse(DiscogsSearchMode.COMPILATION.accepts(listOf("CD", "Album")))
    }

    @Test fun bothReturnsOnlyDiscographicAlbumsAndCompilations() {
        assertTrue(DiscogsSearchMode.BOTH.accepts(listOf("Album")))
        assertTrue(DiscogsSearchMode.BOTH.accepts(listOf("Compilation")))
        assertFalse(DiscogsSearchMode.BOTH.accepts(listOf("Single")))
        assertFalse(DiscogsSearchMode.BOTH.accepts(emptyList()))
    }

    @Test fun legacySettingsDefaultToCompilationNotMixed() {
        assertEquals(DiscogsSearchMode.COMPILATION, DiscogsSearchMode.restored(null))
        assertEquals(DiscogsSearchMode.COMPILATION, DiscogsSearchMode.restored(""))
        assertEquals(DiscogsSearchMode.BOTH, DiscogsSearchMode.restored("BOTH"))
        assertEquals(DiscogsSearchMode.ALBUM, DiscogsSearchMode.restored("album"))
    }
}
