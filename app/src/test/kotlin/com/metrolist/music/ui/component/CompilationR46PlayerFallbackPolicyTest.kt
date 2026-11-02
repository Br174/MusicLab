package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompilationR46PlayerFallbackPolicyTest {
    @Test fun fullVideoArtistPrefixIsRemovedOnlyForFallback() {
        assertEquals("Sapore di sale Gino Paoli",
            CompilationFallbackQueryPolicy.query(
                "Gino Paoli - Sapore di sale (Official Video Remastered 2011)", "Gino Paoli"))
        assertEquals("Bohemian Rhapsody Queen",
            CompilationFallbackQueryPolicy.query(
                "Queen - Bohemian Rhapsody [Official Video]", "Queen"))
    }
    @Test fun titleAloneGetsPerformerAppended() {
        assertEquals("Il mondo Jimmy Fontana",
            CompilationFallbackQueryPolicy.query("Il mondo (Remastered 2020)", "Jimmy Fontana"))
        assertEquals("Mentre tutto scorre Negramaro",
            CompilationFallbackQueryPolicy.query("Negramaro - Mentre tutto scorre", "Negramaro"))
    }
    @Test fun completeVideoLabelIsNotUsedAsFallbackTitle() {
        assertEquals("Bohemian Rhapsody",
            CompilationFallbackQueryPolicy.songTitle(
                "Queen - Bohemian Rhapsody (Official Video)", "Queen"))
    }
    @Test fun missingPerformerAndDuplicatePerformerAreNotRetried() {
        assertNull(CompilationFallbackQueryPolicy.query("Il mondo", ""))
        assertNull(CompilationFallbackQueryPolicy.query("", "Jimmy Fontana"))
        assertNull(CompilationFallbackQueryPolicy.query("Felicità Al Bano", "Al Bano"))
    }
}
