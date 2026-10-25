package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

/**
 * LAB53 deterministic output-funnel comparison, NOT a claim that the network
 * contains fifty performances for every real song.
 */
class CoverLab53PublicationTest {
    private fun recording(n: Int, original: Boolean = false): DiscogsVersionSeed =
        DiscogsVersionSource.externalSeed(
            CoverSourceCandidate(
                title = "Verified same musical work",
                artist = if (original) "Reference Artist" else "Singer $n",
                sources = listOf("MusicBrainz"),
                workRelationConfirmed = true,
            ),
            targetTitle = "Verified same musical work",
            originalArtist = "Reference Artist",
        )

    @Test fun fiftyCoversAreVisibleEvenWhenTenOriginalVersionsExist() {
        val originalVersions = (1..10).map { recording(it, original = true) }
        val covers = (1..60).map(::recording)
        val baseline = (originalVersions + covers).take(50)
            .count { it.artist != "Reference Artist" }
        val lab53 = CoverLab53Publication.fill(emptyList(), originalVersions + covers) {
            it.artist != "Reference Artist" && it.workRelationConfirmed
        }
        assertEquals(40, baseline)
        assertEquals(50, lab53.size)
        assertEquals(50, lab53.count { it.artist != "Reference Artist" })
        assertEquals(50, lab53.map(DiscogsVersionSource::recordingIdentityKey).distinct().size)
    }

    @Test fun partialVerifiedGroupsArePublishedImmediatelyAndNeverReordered() {
        val pool = (1..60).map(::recording)
        val first = CoverLab53Publication.fill(emptyList(), pool.take(10)) {
            it.workRelationConfirmed
        }
        val next = CoverLab53Publication.fill(first, pool.take(26)) {
            it.workRelationConfirmed
        }
        val last = CoverLab53Publication.fill(next, pool) {
            it.workRelationConfirmed
        }
        assertEquals(10, first.size)
        assertEquals(26, next.size)
        assertEquals(50, last.size)
        assertEquals(first, next.take(10))
        assertEquals(next, last.take(26))
    }

    @Test fun aSparseCatalogNeverProducesFabricatedCovers() {
        val found = (1..3).map(::recording)
        val selected = CoverLab53Publication.fill(emptyList(), found) {
            it.workRelationConfirmed
        }
        assertEquals(3, selected.size)
        val funnel = CoverLab53Publication.funnel(found, selected) {
            it.workRelationConfirmed
        }
        assertEquals(3, funnel.found)
        assertEquals(3, funnel.certified)
        assertEquals(0, funnel.excluded)
        assertEquals(3, funnel.visible)
        assertEquals(47, funnel.missing)
    }

    @Test fun unrelatedTitleOrUnverifiedSourceCannotFillCoverQuota() {
        val good = (1..12).map(::recording)
        val bad = (13..100).map { i ->
            DiscogsVersionSource.externalSeed(
                CoverSourceCandidate(title = "Unrelated", artist = "Singer $i",
                    sources = listOf("YouTube/Video"), workRelationConfirmed = false)
            )
        }
        val selected = CoverLab53Publication.fill(emptyList(), good + bad) {
            it.workRelationConfirmed
        }
        assertEquals(12, selected.size)
        assertEquals(50 - 12, CoverLab53Publication.funnel(good + bad, selected) {
            it.workRelationConfirmed
        }.missing)
    }

    @Test fun originalRecordingsAndRepeatedVideosNeverConsumeNewCoverSlots() {
        val original = recording(0, original = true)
        val first = recording(1).copy(resolvedVideoId = "video-abc")
        val duplicateVideo = recording(2).copy(resolvedVideoId = "video-abc")
        val result = CoverLab53Publication.fill(
            emptyList(), listOf(original, first, duplicateVideo, recording(3))
        ) { it.artist != "Reference Artist" && it.workRelationConfirmed }
        assertEquals(2, result.size)
        assertTrue(result.all { it.artist != "Reference Artist" })
    }

    @Test fun sixtySyntheticMixedSongsVerifyPresentationMathWithoutInventingResults() {
        for (n in 1..60) {
            val available = n % 61
            val originals = (1..5).map { recording(it, original = true) }
            val verified = (1..available).map(::recording)
            val output = CoverLab53Publication.fill(emptyList(), originals + verified) {
                it.artist != "Reference Artist" && it.workRelationConfirmed
            }
            assertEquals(minOf(available, 50), output.size)
        }
    }
}
