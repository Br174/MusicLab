package com.metrolist.music.ui.component

import com.metrolist.music.discogs.DiscogsTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompilationTitleOnlyPolicyTest {
    @Test fun knownPlayerArtistAndVideoNoiseAreRemovedOnlyOnExplicitTitleAction() {
        assertEquals("Sapore di sale",
            CompilationTitleOnlyPolicy.title(
                "Gino Paoli - Sapore di sale (Official Video)", "Gino Paoli"))
        assertEquals("Bohemian Rhapsody",
            CompilationTitleOnlyPolicy.title(
                "Queen - Bohemian Rhapsody [Official Video]", "Queen"))
    }

    @Test fun titleOnlyManualInputWorksWithoutPlayerMetadata() {
        assertEquals("Napoli: Punto Esclamativo!",
            CompilationTitleOnlyPolicy.title(
                "Renzo Arbore - Napoli: Punto Esclamativo!"))
        assertEquals("Il mondo",
            CompilationTitleOnlyPolicy.title("Il mondo"))
    }

    @Test fun aSongTitleWithAnEditionSuffixDoesNotBecomeOnlyLive() {
        assertEquals("Sapore di sale",
            CompilationTitleOnlyPolicy.title("Sapore di sale - Live"))
    }

    @Test fun verifiedTrackIsSelectedAtItsRealDiscogsPosition() {
        val tracks = listOf(
            DiscogsTrack("1", "Altra canzone", listOf("Altra artista"), null, null),
            DiscogsTrack("2", "Sapore di sale", listOf("Gino Paoli"), null, null),
            DiscogsTrack("3", "Il mondo", listOf("Jimmy Fontana"), null, null),
        )
        assertEquals(1, CompilationTitleOnlyPolicy.selectedTrackIndex(
            tracks, "Sapore di sale", "Gino Paoli"))
        assertEquals(2, CompilationTitleOnlyPolicy.selectedTrackIndex(
            tracks, "Il mondo", null))
        assertNull(CompilationTitleOnlyPolicy.selectedTrackIndex(
            tracks, "Un brano inesistente", null))
    }

    @Test fun titleOnlySelectionRespectsArtistWhenProvided() {
        val tracks = listOf(
            DiscogsTrack("A1", "Sapore di sale", listOf("Cantante diversa"), null, null),
            DiscogsTrack("A2", "Sapore di sale", listOf("Gino Paoli"), null, null),
        )
        assertEquals(1, CompilationTitleOnlyPolicy.selectedTrackIndex(
            tracks, "Sapore di sale", "Gino Paoli"))
        assertEquals(0, CompilationTitleOnlyPolicy.selectedTrackIndex(
            tracks, "Sapore di sale", null))
    }
}
