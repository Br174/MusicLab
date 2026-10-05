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

    @Test
    fun `exact original external recording owns ten out of ten`() {
        val seed =
            DiscogsVersionSource.externalSeed(
                candidate =
                    CoverSourceCandidate(
                        title = "Balla balla ballerino",
                        artist = "Lucio Dalla",
                        sources = listOf("COVER.INFO"),
                    ),
                targetTitle = "Balla balla ballerino",
                originalArtist = "Lucio Dalla",
            )

        assertEquals(10, seed.confidenceScore)
        assertTrue(seed.originalWorkReference)
    }

    @Test
    fun `confirmed cover remains below original even with exact title`() {
        val seed =
            DiscogsVersionSource.externalSeed(
                candidate =
                    CoverSourceCandidate(
                        title = "Balla balla ballerino",
                        artist = "Fausto Papetti",
                        sources = listOf("COVER.INFO"),
                        workRelationConfirmed = true,
                    ),
                targetTitle = "Balla balla ballerino",
                originalArtist = "Lucio Dalla",
            )

        assertEquals(8, seed.confidenceScore)
        assertTrue(!seed.originalWorkReference)
    }

    @Test
    fun `cover info initial relation can identify original even if starting artist was a cover`() {
        val seed =
            DiscogsVersionSource.externalSeed(
                candidate =
                    CoverSourceCandidate(
                        title = "Balla balla ballerino",
                        artist = "Lucio Dalla",
                        sources = listOf("COVER.INFO"),
                        workRelationConfirmed = true,
                        originalWorkReference = true,
                    ),
                targetTitle = "Balla balla ballerino",
                originalArtist = "Fausto Papetti",
            )

        assertEquals(10, seed.confidenceScore)
        assertTrue(seed.originalWorkReference)
    }
}
