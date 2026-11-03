package com.metrolist.music.ui.component

import com.metrolist.music.discogs.DiscogsTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompilationLightSearchPolicyTest {
    private fun t(title: String, vararg singers: String) =
        DiscogsTrack("A1", title, singers.toList(), null, null)

    @Test fun threeModesHaveOnlyOneAutomaticDefault() {
        assertEquals(3, CompilationLookupMode.entries.size)
        assertEquals(CompilationLookupMode.TITLE_ARTIST, CompilationLookupMode.restored(null))
        assertEquals(CompilationLookupMode.COVER_LITE, CompilationLookupMode.restored("COVER_LITE"))
    }

    @Test fun initialTitleArtistAcceptsVerifiedTrackNotReleaseName() {
        assertTrue(CompilationLightSearchPolicy.accepts(
            t("Brutta", "Canino"), "Brutta", "Canino", CompilationLookupMode.TITLE_ARTIST))
        assertFalse(CompilationLightSearchPolicy.accepts(
            t("Brutta", "Un altro cantante"), "Brutta", "Canino", CompilationLookupMode.TITLE_ARTIST))
        assertTrue(CompilationLightSearchPolicy.accepts(
            t("Brutta", "Various"), "Brutta", "Canino", CompilationLookupMode.TITLE_ARTIST))
    }

    @Test fun titleOnlyDoesNotTreatDifferentSongAsSameWork() {
        assertTrue(CompilationLightSearchPolicy.accepts(
            t("Brutta"), "Brutta", "Canino", CompilationLookupMode.TITLE))
        assertFalse(CompilationLightSearchPolicy.accepts(
            t("Sei davvero brutta"), "Brutta", "Canino", CompilationLookupMode.TITLE))
        assertFalse(CompilationLightSearchPolicy.accepts(
            t("Brutta storia"), "Brutta", "Canino", CompilationLookupMode.TITLE))
    }

    @Test fun lightCoverIncludesUncreditedRemixAndMashup() {
        for (title in listOf("Brutta (DJ XYZ Remix)", "Brutta [Mashup]",
            "Brutta - Extended Mix", "Brutta")) {
            assertTrue("Missing uncredited alternative: $title",
                CompilationLightSearchPolicy.accepts(
                    t(title), "Brutta", "Canino", CompilationLookupMode.COVER_LITE))
        }
    }

    @Test fun lightCoverIncludesDifferentSingerWithoutFullCoverVerification() {
        assertTrue(CompilationLightSearchPolicy.accepts(
            t("Brutta", "Mario Rossi"), "Brutta", "Canino", CompilationLookupMode.COVER_LITE))
    }

    @Test fun lightCoverExcludesOriginalSingerIfCredited() {
        assertFalse(CompilationLightSearchPolicy.accepts(
            t("Brutta", "Canino"), "Brutta", "Canino", CompilationLookupMode.COVER_LITE))
        assertFalse(CompilationLightSearchPolicy.accepts(
            t("Brutta (Mashup)", "Canino"), "Brutta", "Canino",
            CompilationLookupMode.COVER_LITE))
        assertFalse(CompilationLightSearchPolicy.accepts(
            t("Brutta (Canino Remix)"), "Brutta", "Canino",
            CompilationLookupMode.COVER_LITE))
    }

    @Test fun coverMayReturnUnknownCreditWithoutClaimOfVerifiedCover() {
        assertTrue(CompilationLightSearchPolicy.accepts(
            t("Brutta (DJ Edit)", "Various Artists"), "Brutta", "Canino",
            CompilationLookupMode.COVER_LITE))
        assertFalse(CompilationLightSearchPolicy.accepts(
            t("Sei davvero brutta (DJ Edit)"), "Brutta", "Canino",
            CompilationLookupMode.COVER_LITE))
    }

    @Test fun titleKeepsColonAndDoesNotChangeMeaning() {
        assertTrue(CompilationLightSearchPolicy.accepts(
            t("Napoli: Punto Esclamativo!"), "Napoli: Punto Esclamativo!",
            "Renzo Arbore", CompilationLookupMode.TITLE))
        assertFalse(CompilationLightSearchPolicy.accepts(
            t("Napoli: Punto Esclamativo! Parte 2"), "Napoli: Punto Esclamativo!",
            "Renzo Arbore", CompilationLookupMode.TITLE))
    }
}
