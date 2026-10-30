package com.metrolist.music.ui.component

import com.metrolist.music.discogs.DiscogsCredit
import org.junit.Assert.*
import org.junit.Test

/** LAB52: independent proof must not become a title or video guessing gate. */
class CoverLab52MultiProofTest {
    private val originalCredits = listOf(DiscogsCredit("Lucio Dalla", "Composer"))

    private fun seed(
        title: String = "Caruso",
        artist: String = "Different Singer",
        sources: List<String> = listOf("Apple/iTunes"),
        credits: List<DiscogsCredit> = originalCredits,
        documentary: Boolean = false,
    ) = DiscogsVersionSource.externalSeed(
        CoverSourceCandidate(
            title = title,
            artist = artist,
            sources = sources,
            credits = credits,
            workRelationConfirmed = documentary,
        ), targetTitle = "Caruso", originalArtist = "Lucio Dalla",
    )

    private fun verify(record: DiscogsVersionSeed) = CoverMultiProofVerifier.verify(
        record, "Caruso", "Lucio Dalla", originalCredits,
    )

    @Test fun verifiedDirectWorkAllowsForeignTitle() {
        val result = verify(seed(
            title = "Translated work title",
            sources = listOf("MusicBrainz"), documentary = true,
            credits = emptyList(),
        ))
        assertTrue(result.certified)
        assertTrue(result.methods.contains(CoverMultiProofVerifier.Method.TRANSLATED_WORK))
    }

    @Test fun independentlyCreditedCompositionAdmitsCatalogedCover() {
        val result = verify(seed())
        assertTrue(result.certified)
        assertTrue(result.methods.contains(CoverMultiProofVerifier.Method.CREDITED_COMPOSITION))
        assertTrue("lucio dalla" in result.sharedAuthors)
    }

    @Test fun catalogAgreementRaisesDocumentaryEvidenceOnlyWhenWriterMatches() {
        val result = verify(seed(sources = listOf("Apple/iTunes", "Spotify")))
        assertTrue(result.certified)
        assertTrue(result.methods.contains(CoverMultiProofVerifier.Method.CATALOG_CONVERGENCE))
        assertFalse(verify(seed(
            sources = listOf("Apple/iTunes", "Spotify"),
            credits = listOf(DiscogsCredit("Other Writer", "Composer"))
        )).certified)
    }

    @Test fun titleAndVideoAndCloudAreNotProof() {
        val result = verify(seed(
            sources = listOf("YouTube/Video", "Archivio Cloud", "AI Scout"),
        ))
        assertFalse(result.certified)
        assertTrue(result.keepForRetry)
    }

    @Test fun wrongWorkAuthorIsNotProof() {
        assertFalse(verify(seed(
            credits = listOf(DiscogsCredit("Different Composer", "Composer"))
        )).certified)
    }

    @Test fun authorMatchingWithoutTitleCannotInventTranslatedWork() {
        assertFalse(verify(seed(title = "Unrelated Song")).certified)
    }

    @Test fun originalPerformerIsNotPublishedAsCover() {
        assertFalse(verify(seed(artist = "Lucio Dalla")).certified)
    }

    @Test fun pendingCandidateCanAcquireVerifiableCreditsLater() {
        val first = CoverSourceCandidate(
            title = "Caruso", artist = "Other Singer",
            sources = listOf("YouTube/Video"),
        )
        val later = CoverSourceCandidate(
            title = "Caruso", artist = "Other Singer",
            sources = listOf("Apple/iTunes"),
            credits = originalCredits,
        )
        val merged = CoverMultiProofVerifier.accumulate(listOf(first), listOf(later))
        assertEquals(1, merged.size)
        assertTrue(verify(DiscogsVersionSource.externalSeed(merged.single())).certified)
    }

    @Test fun separateStudioAndLiveRecordingsStaySeparate() {
        val studio = CoverSourceCandidate(
            title = "Caruso", artist = "Other Singer", sources = listOf("Apple/iTunes"),
        )
        val live = studio.copy(category = AiCoverCategory.LIVE)
        assertEquals(2, CoverMultiProofVerifier.accumulate(listOf(studio), listOf(live)).size)
    }

    @Test fun sixtySyntheticItalianAndInternationalCompositionChecks() {
        val cases = (1..60).map { index ->
            val title = if (index <= 30) "Canzone italiana $index" else "International song $index"
            val original = "Original Composer $index"
            val candidate = DiscogsVersionSource.externalSeed(
                CoverSourceCandidate(
                    title = title, artist = "Cover Artist $index",
                    sources = listOf("Apple/iTunes"),
                    credits = listOf(DiscogsCredit(original, "Composer")),
                ), targetTitle = title, originalArtist = "Original Performer $index",
            )
            CoverMultiProofVerifier.verify(
                candidate, title, "Original Performer $index",
                listOf(DiscogsCredit(original, "Composer")),
            )
        }
        assertEquals(60, cases.count { it.certified })
        // All 60 would be excluded by a direct Work-only documentary gate.
        assertTrue(cases.all { CoverMultiProofVerifier.Method.DIRECT_WORK !in it.methods })
    }
}
