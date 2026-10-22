package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Lab44ScoringAndTitleResolverTest {
    @Test
    fun `decorated title can expose original artist without changing visible input`() {
        assertEquals("Lucio Dalla", TitleMeaningResolver.trailingArtistHint("Balla balla ballerino (Lucio Dalla)"))
        assertEquals("Balla balla ballerino", TitleMeaningResolver.stripTrailingArtistHint("Balla balla ballerino (Lucio Dalla)"))
        assertNull(TitleMeaningResolver.trailingArtistHint("Balla balla ballerino (Live)"))
    }

    // External source already certified the value: UI must preserve it, not invent it.
    @Test
    fun `exact original external recording preserves the certified ten out of ten`() {
        val seed =
            DiscogsVersionSource.externalSeed(
                candidate =
                    CoverSourceCandidate(
                        title = "Balla balla ballerino",
                        artist = "Lucio Dalla",
                        sources = listOf("COVER.INFO"),
                        evidenceScore = 10,
                    ),
                targetTitle = "Balla balla ballerino",
                originalArtist = "Lucio Dalla",
            )

        assertEquals(10, seed.confidenceScore)
        assertTrue(seed.originalWorkReference)
    }

    @Test
    fun `confirmed cover keeps its own certified eight out of ten`() {
        val seed =
            DiscogsVersionSource.externalSeed(
                candidate =
                    CoverSourceCandidate(
                        title = "Balla balla ballerino",
                        artist = "Fausto Papetti",
                        sources = listOf("COVER.INFO"),
                        workRelationConfirmed = true,
                        evidenceScore = 8,
                    ),
                targetTitle = "Balla balla ballerino",
                originalArtist = "Lucio Dalla",
            )

        assertEquals(8, seed.confidenceScore)
        assertTrue(!seed.originalWorkReference)
    }

    @Test
    fun `source-proven original retains score even when starting artist was a cover`() {
        val seed =
            DiscogsVersionSource.externalSeed(
                candidate =
                    CoverSourceCandidate(
                        title = "Balla balla ballerino",
                        artist = "Lucio Dalla",
                        sources = listOf("COVER.INFO"),
                        workRelationConfirmed = true,
                        originalWorkReference = true,
                        evidenceScore = 10,
                    ),
                targetTitle = "Balla balla ballerino",
                originalArtist = "Fausto Papetti",
            )

        assertEquals(10, seed.confidenceScore)
        assertTrue(seed.originalWorkReference)
    }
}
