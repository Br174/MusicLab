package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverTitleTypographyTest {
    @Test fun searchMatchesAccentedAndPlainButPresentationKeepsProperTitle() {
        assertEquals("perche", CoverTitleTypography.folded("Perché"))
        assertEquals("perche", CoverTitleTypography.folded("Perche"))
        assertEquals("Perché", CoverTitleTypography.prefer("Perche", "Perché"))
        assertTrue(TitleMeaningResolver.matchesBaseTitle("Perché", "Perche"))
    }

    @Test fun composedAndDecomposedAccentsAreEquivalentAndShownInNfc() {
        val composed = "Città"
        val decomposed = "Citta\u0300"
        assertEquals(composed, CoverTitleTypography.display(decomposed))
        assertEquals(composed, CoverTitleTypography.prefer("Citta", decomposed))
    }

    @Test fun preserveLiveAndRemixSuffixWithoutChangingVideoIdentity() {
        assertEquals(
            "Perché (Live 1980)",
            CoverTitleTypography.shown("Perche (Live 1980)", "Perché"),
        )
        assertEquals(
            "Più su - Remix",
            CoverTitleTypography.shown("Piu su - Remix", "Più su"),
        )
    }

    @Test fun genuineDifferentWorkAndForeignAdaptationsAreNeverRenamed() {
        assertEquals(
            "Perche questa vita",
            CoverTitleTypography.shown("Perche questa vita", "Perché"),
        )
        assertEquals(
            "Por qué te vas",
            CoverTitleTypography.shown("Por qué te vas", "Perché"),
        )
        assertEquals(
            "Perche questa vita",
            CoverTitleTypography.prefer("Perche questa vita", "Perché"),
        )
    }

    @Test fun accentlessQueryIsOptionalAndNeverUsedAsDisplayedName() {
        assertEquals("perche", CoverTitleTypography.unaccentedQuery("Perché"))
        assertEquals(null, CoverTitleTypography.unaccentedQuery("Perche"))
        assertEquals("Perché", CoverTitleTypography.display("Perché"))
    }

    @Test fun firstRoundStaysIdenticalAndLastRoundGetsOnlyOneAccentFallback() {
        val initial = CoverRoundSearchPlan.queries("Perché", 1, "Interprete")
        assertFalse(initial.any { it.text == "perche" })
        assertTrue(initial.first().text.contains("Perché"))
        val later = CoverRoundSearchPlan.queries(
            "Perché", CoverRoundSearchPlan.finalRound, "Interprete"
        )
        assertEquals(1, later.count { it.text == "perche" })
        assertTrue(later.any { it.text == "Perché" })
    }
}
