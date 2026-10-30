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


    @Test fun earlierSameNamedRecordIsNotPublishedAsEightyYearsLaterWork() {
        val confirmed1981 = song(10, "L'addio", "Verified Artist").copy(
            year = 1981,
            workRelationConfirmed = true,
            sourceNames = listOf("COVER.INFO"),
        )
        val unrelated1924 = song(11, "L'addio", "Unrelated Catalog Artist").copy(
            year = 1924, sourceNames = listOf("Discogs"),
        )
        val later2003 = song(12, "L'addio", "Later Recording").copy(year = 2003)
        val output = CoverLab55VisibleCandidates.publish(
            emptyList(), listOf(confirmed1981, unrelated1924, later2003),
            "L'addio", "Franco Battiato", originalYear = 1981,
        ) { false }
        assertEquals(listOf(confirmed1981, later2003), output)
    }

    @Test fun confirmedWorkYearCanAnchorPublicationWithoutEarlySourceYear() {
        val linked1981 = song(21, "L'addio", "Work Linked Artist").copy(
            year = 1981, workRelationConfirmed = true,
            sourceNames = listOf("MusicBrainz"),
        )
        val homonym1924 = song(22, "L'addio", "Unrelated Old Singer").copy(year = 1924)
        val output = CoverLab55VisibleCandidates.publish(
            emptyList(), listOf(homonym1924, linked1981),
            "L'addio", "Franco Battiato",
        ) { false }
        assertEquals(listOf(linked1981), output)
    }

    @Test fun unconfirmedDateNeverCreatesAUniversalBlacklist() {
        val older = song(30, "Historic Work", "Catalog Singer").copy(year = 1924)
        val output = CoverLab55VisibleCandidates.publish(
            emptyList(), listOf(older), "Historic Work", "Reference Singer",
        ) { false }
        assertEquals(listOf(older), output)
    }


    @Test fun documentaryCoverWithoutVideoNeverBecomesVisibleInAnyRound() {
        val documented = song(150, "L'addio", "Work-linked Singer").copy(
            year = 1981, resolvedVideoId = null,
            workRelationConfirmed = true, sourceNames = listOf("COVER.INFO"),
        )
        for (round in 1..5) {
            val published = CoverLab55VisibleCandidates.publish(
                emptyList(), listOf(documented), "L'addio", "Franco Battiato",
                originalYear = 1981, coverRound = round,
                verifiedVideoIds = emptySet(),
            ) { false }
            assertTrue("No video-less posters in round $round", published.isEmpty())
        }
    }

    @Test fun aVideoIdAloneCannotBypassVideoMetadataVerification() {
        val candidate = song(151, "L'addio", "Documented Singer").copy(
            year = 1991, sourceNames = listOf("Discogs", "MusicBrainz"),
        )
        val missingProof = CoverLab55VisibleCandidates.publish(
            emptyList(), listOf(candidate), "L'addio", "Franco Battiato",
            originalYear = 1981, coverRound = 3, verifiedVideoIds = emptySet(),
        ) { false }
        assertTrue(missingProof.isEmpty())
        val confirmed = CoverLab55VisibleCandidates.publish(
            emptyList(), listOf(candidate), "L'addio", "Franco Battiato",
            originalYear = 1981, coverRound = 3,
            verifiedVideoIds = setOf(candidate.resolvedVideoId!!),
        ) { false }
        assertEquals(listOf(candidate), confirmed)
    }

    @Test fun thirdRoundCanAdmitARealNewVideoButNotOlderHomonym() {
        val eligible = song(152, "L'addio", "Catalog Artist").copy(
            year = 1990, sourceNames = listOf("Discogs"),
        )
        val unrelated = song(153, "L'addio", "1924 Unrelated Artist").copy(
            year = 1924, sourceNames = listOf("Discogs"),
        )
        val verifiedIds = setOf(eligible.resolvedVideoId!!, unrelated.resolvedVideoId!!)
        val published = CoverLab55VisibleCandidates.publish(
            emptyList(), listOf(eligible, unrelated), "L'addio", "Franco Battiato",
            originalYear = 1981, coverRound = 3,
            verifiedVideoIds = verifiedIds,
        ) { false }
        assertEquals(listOf(eligible), published)
    }

    @Test fun secondRoundDoesNotRepeatAPreviouslyPublishedRecordingOrVideo() {
        val original = song(155, "L'addio", "Historical Cover")
        val replay = original.copy(artist = "Alternate Catalog Entry")
        val newCandidate = song(156, "L'addio", "Fresh Cover")
        val published = CoverLab55VisibleCandidates.publish(
            emptyList(), listOf(original, replay, newCandidate),
            "L'addio", "Franco Battiato",
            history = listOf(original), coverRound = 2,
            verifiedVideoIds = setOf(original.resolvedVideoId!!, newCandidate.resolvedVideoId!!),
        ) { false }
        assertEquals(listOf(newCandidate), published)
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
