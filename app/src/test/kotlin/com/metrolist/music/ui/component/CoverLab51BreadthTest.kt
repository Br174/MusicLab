package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic synthetic window coverage; real-source probes are separate. */
class CoverLab51BreadthTest {
    private val variants = (1..320).map { index ->
        MusicBrainzCover(
            title = "Recorded song",
            artist = "Distinct interpreter $index",
            recordingId = "recording-$index",
            workId = "verified-work"
        )
    }

    @Test fun laterRoundsExposeNewRecordedPerformances() {
        val first = MusicBrainzCoverSource.selectCoverWindow(variants, true, 1)
        val second = MusicBrainzCoverSource.selectCoverWindow(variants, true, 2)
        val third = MusicBrainzCoverSource.selectCoverWindow(variants, true, 3)
        assertEquals(250, first.size)
        assertEquals(250, second.size)
        assertEquals(250, third.size)
        val firstIds = first.map { it.recordingId }.toSet()
        val secondIds = second.map { it.recordingId }.toSet()
        assertTrue(second.any { it.recordingId !in firstIds })
        assertTrue(third.any { it.recordingId !in secondIds })
    }

    @Test fun initialRoundKeepsItsHistoricalWindow() {
        val first = MusicBrainzCoverSource.selectCoverWindow(variants, false, 1)
        assertEquals(100, first.size)
        assertEquals("recording-1", first.first().recordingId)
        assertEquals(first, MusicBrainzCoverSource.selectCoverWindow(variants, false, 4))
    }

    @Test fun similarlyNamedUnrelatedSongIsNotDocumentedCover() {
        val wrong = CoverSourceCandidate(
            title = "Another song", artist = "Different performer",
            sources = listOf("MusicBrainz"), workRelationConfirmed = false
        )
        assertFalse(CoverDiscoverySources.admitsAnchoredAlternateTitle(wrong, true))
    }
}
