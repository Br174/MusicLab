package com.metrolist.music.ui.component

import com.metrolist.music.discogs.DiscogsCredit
import org.junit.Assert.*
import org.junit.Test

/** LAB54: exercises the exact candidate->author evidence->poster admission path.
 * All 68 fabricated identities below are DETERMINISTIC TEST FIXTURES, never
 * misrepresented as real "Soli" performances available on the Internet.
 */
class CoverLab54FullFunnelTest {
    private val composers = listOf(
        DiscogsCredit("Toto Cutugno", "Composer"),
        DiscogsCredit("Cristiano Minellono", "Lyricist"),
    )
    private fun candidate(index: Int, credits: List<DiscogsCredit> = emptyList()) =
        DiscogsVersionSource.externalSeed(
            CoverSourceCandidate(
                title = "Soli",
                artist = "Test Singer $index",
                sources = listOf("Discogs"),
                credits = credits,
                workRelationConfirmed = false,
            ),
            targetTitle = "Soli",
            originalArtist = "Adriano Celentano",
        )

    private fun certified(seed: DiscogsVersionSeed): Boolean =
        CoverSameWorkGate.hasWorkIdentity(
            seed, "Adriano Celentano", composers,
        ) && TitleMeaningResolver.matchesBaseTitle("Soli", seed.trackTitle)

    @Test fun reproducesSixtyEightCatalogSummariesButZeroPreviouslyVisibleCovers() {
        val summaries = (1..68).map { candidate(it) }
        assertEquals(68, summaries.size)
        assertEquals(0, CoverLab53Publication.fill(emptyList(), summaries, ::certified).size)
    }

    @Test fun detailedCreditsTurnPreviouslyRejectedSummariesIntoFiftyVisibleCovers() {
        val summaries = (1..68).map { candidate(it) }
        val detailed = summaries.mapIndexed { idx, seed ->
            if (idx < 52) seed.copy(credits = composers) else seed
        }
        val refilled = CoverLab53Publication.fill(emptyList(), detailed, ::certified)
        assertEquals(50, refilled.size)
        assertEquals(50, refilled.map(DiscogsVersionSource::recordingIdentityKey).distinct().size)
        assertTrue(refilled.all(::certified))
    }

    @Test fun firstTwelveThenNextTwentyThenLastEighteenRemainStable() {
        val catalog = (1..68).map { candidate(it, composers) }
        val first = CoverLab53Publication.fill(emptyList(), catalog.take(12), ::certified)
        val second = CoverLab53Publication.fill(first, catalog.take(32), ::certified)
        val third = CoverLab53Publication.fill(second, catalog.take(50), ::certified)
        assertEquals(listOf(12, 32, 50), listOf(first.size, second.size, third.size))
        assertEquals(first, third.take(12))
        assertEquals(second, third.take(32))
    }

    @Test fun actualDocumentedSoliWorkAuthorsAndUnrelatedHomonymStaySeparated() {
        val patrizioDocumentary = DiscogsVersionSource.externalSeed(
            CoverSourceCandidate(
                title = "Soli", artist = "Patrizio Buanne",
                sources = listOf("MusicBrainz"),
                credits = composers, workRelationConfirmed = true,
            ), targetTitle = "Soli", originalArtist = "Adriano Celentano",
        )
        val unrelated = candidate(99, listOf(DiscogsCredit("Unrelated Author", "Composer")))
        assertTrue(certified(patrizioDocumentary))
        assertFalse(certified(unrelated))
        assertTrue(CoverMultiProofVerifier.verify(
            patrizioDocumentary, "Soli", "Adriano Celentano", composers,
        ).certified)
        assertFalse(CoverMultiProofVerifier.verify(
            unrelated, "Soli", "Adriano Celentano", composers,
        ).certified)
    }

    @Test fun sameOriginalPerformerAndIdenticalVideoCannotConsumeCoverPlaces() {
        val original = candidate(99, composers).copy(artist = "Adriano Celentano")
        val one = candidate(1, composers).copy(resolvedVideoId = "shared-video")
        val two = candidate(2, composers).copy(resolvedVideoId = "shared-video")
        val pool = listOf(original, one, two, candidate(3, composers))
        val output = CoverLab53Publication.fill(emptyList(), pool) { seed ->
            !TitleMeaningResolver.sameArtist(seed.artist, "Adriano Celentano") && certified(seed)
        }
        assertEquals(2, output.size)
    }

    @Test fun unverifiedRealCandidateCountCannotBeClaimedAsPublishedFifty() {
        val found = (1..68).map { candidate(it) }
        val verified = found.take(7).map { it.copy(credits = composers) }
        val pool = CoverLab53Publication.retain(found, verified)
        val posted = CoverLab53Publication.fill(emptyList(), pool, ::certified)
        assertTrue(posted.size <= 7)
        val stats = CoverLab53Publication.funnel(pool, posted, ::certified)
        assertEquals(posted.size, stats.visible)
        assertEquals(50 - posted.size, stats.missing)
    }
}
