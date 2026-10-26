package com.metrolist.music.ui.component

import com.metrolist.music.discogs.DiscogsCredit
import org.junit.Assert.*
import org.junit.Test

/**
 * Synthetic deterministic end-to-end publication QA. Fictional performers in
 * these tests are NOT 50 real covers of Soli or Sapore di sale.
 */
class CoverLab55VisibleCandidatesTest {
    private fun song(i: Int, title: String = "Sapore di sale", singer: String = "Singer $i") =
        DiscogsVersionSource.externalSeed(
            CoverSourceCandidate(
                title = title, artist = singer,
                sources = listOf("Archivio Cloud"),
                year = 1970 + i % 35,
                evidenceScore = 5,
            ),
            targetTitle = title,
            originalArtist = "Gino Paoli",
        ).copy(resolvedVideoId = "vid" + i.toString().padStart(8, '0'))

    @Test fun sixDozenCatalogCandidatesFillFiftyProvisionalCardsWithoutFabrication() {
        val discovered = (1..68).map { song(it) }
        val output = CoverLab55VisibleCandidates.publish(
            emptyList(), discovered, "Sapore di sale", "Gino Paoli",
        ) { false }
        assertEquals(50, output.size)
        assertEquals(50, output.map(DiscogsVersionSource::recordingIdentityKey).distinct().size)
        assertTrue(output.all {
            CoverLab55VisibleCandidates.isProvisional(it, "Gino Paoli", emptyList())
        })
        assertTrue(output.all { it.artist.startsWith("Singer ") })
    }

    @Test fun videoFirstRejectsPosterOnlyAndUnverifiedVideoIds() {
        val confirmed = song(1)
        val pending = song(2).copy(resolvedVideoId = null)
        val output = CoverLab55VisibleCandidates.publish(
            emptyList(), listOf(pending, confirmed), "Sapore di sale", "Gino Paoli",
            verifiedVideoIds = setOf(confirmed.resolvedVideoId!!),
        ) { false }
        assertEquals(listOf(confirmed), output)
        val unverified = CoverLab55VisibleCandidates.publish(
            emptyList(), listOf(confirmed), "Sapore di sale", "Gino Paoli",
            verifiedVideoIds = emptySet(),
        ) { false }
        assertTrue(unverified.isEmpty())
    }

    @Test fun cloudArchiveCreditsSurviveTheFirstRound() {
        val item = CloudArchiveItem(
            title = "Sapore di sale", artist = "Real Catalog Artist", category = "cover",
            language = "it", year = 1980, album = "Catalog Recording",
            coverUrl = "https://example.com/cover.jpg", playbackVideoId = null,
            playbackVideoTitle = null, playbackVideoSource = null,
            workTitle = "Sapore di sale", originalArtist = "Gino Paoli",
            sameWorkScore = 70, versionTypeScore = 60,
            composers = listOf("Gino Paoli"), sourceNames = listOf("Discogs"),
        )
        val restored = CoverLab55VisibleCandidates.cloudArchiveSeeds(
            listOf(item), "Sapore di sale", "Gino Paoli",
        ).single()
        assertTrue(restored.credits.any { it.name == "Gino Paoli" })
        assertTrue(restored.sourceNames.contains("Discogs"))
        assertEquals("https://example.com/cover.jpg", restored.coverUrl)
        assertTrue(CoverLab55VisibleCandidates.isRealCandidate(
            restored, "Sapore di sale", "Gino Paoli",
        ))
    }

    @Test fun originalPerformerAndUnrelatedTitlesDoNotCountTowardQuota() {
        val original = song(0, "Sapore di sale", "Gino Paoli")
        val unrelated = song(80, "A completely different song")
        val verified = (1..8).map { song(it) }
        val output = CoverLab55VisibleCandidates.publish(
            emptyList(), listOf(original, unrelated) + verified,
            "Sapore di sale", "Gino Paoli",
        ) { false }
        assertEquals(8, output.size)
        assertFalse(output.contains(original))
        assertFalse(output.contains(unrelated))
    }

    @Test fun explicitRejectionAndRepeatedVideosPreventDoubleCount() {
        val one = song(1).copy(resolvedVideoId = "same-vid001")
        val two = song(2).copy(resolvedVideoId = "same-vid001")
        val third = song(3)
        val out = CoverLab55VisibleCandidates.publish(
            emptyList(), listOf(one, two, third, third), "Sapore di sale",
            "Gino Paoli",
        ) { it.artist == "Singer 3" }
        assertEquals(1, out.size)
    }

    @Test fun partialThenFullKeepsOldPosterOrder() {
        val pool = (1..58).map { song(it) }
        val first = CoverLab55VisibleCandidates.publish(
            emptyList(), pool.take(6), "Sapore di sale", "Gino Paoli",
        ) { false }
        val last = CoverLab55VisibleCandidates.publish(
            first, pool, "Sapore di sale", "Gino Paoli",
        ) { false }
        assertEquals(6, first.size)
        assertEquals(50, last.size)
        assertEquals(first, last.take(6))
    }

    @Test fun sameNamedUnconfirmedRecordingIsClearlyLabeledAsPending() {
        val seed = song(1, "Soli")
        val status = CoverLab55VisibleCandidates.labelFor(
            seed, "Adriano Celentano", emptyList(),
        )
        assertTrue(status.startsWith("Da verificare"))
        val definitive = seed.copy(
            workRelationConfirmed = true,
            sourceNames = listOf("MusicBrainz"),
        )
        assertFalse(CoverLab55VisibleCandidates.isProvisional(
            definitive, "Adriano Celentano",
            listOf(DiscogsCredit("Toto Cutugno", "Composer")),
        ))
    }
}
