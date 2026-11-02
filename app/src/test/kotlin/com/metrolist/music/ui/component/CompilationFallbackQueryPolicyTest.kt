package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompilationFallbackQueryPolicyTest {
    @Test fun fallbackUsesTitleAndArtist() {
        assertEquals("Sapore di sale Gino Paoli",
            CompilationFallbackQueryPolicy.query("Sapore di sale [2024 Remaster]", " Gino Paoli "))
    }
    @Test fun noEmptyRetry() {
        assertNull(CompilationFallbackQueryPolicy.query("Il mondo", ""))
        assertNull(CompilationFallbackQueryPolicy.query("", "Jimmy Fontana"))
    }
    @Test fun noDuplicatedArtist() {
        assertNull(CompilationFallbackQueryPolicy.query("Felicità Al Bano", "Al Bano"))
    }
}
