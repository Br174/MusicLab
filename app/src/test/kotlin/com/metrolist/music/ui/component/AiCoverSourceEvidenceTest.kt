package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCoverSourceEvidenceTest {
    @Test
    fun `MusicBrainz no-match is neutral and produces no hints`() {
        val evidence = AiCoverSourceEvidence.fromMusicBrainz(
            MusicBrainzLookup(emptyList(), MusicBrainzStatus.NO_MATCH),
        )
        assertEquals(MusicBrainzStatus.NO_MATCH, evidence.status)
        assertTrue(evidence.hints.isEmpty())
        assertTrue(evidence.promptContext.isBlank())
    }

    @Test
    fun `MusicBrainz network error is neutral and produces no hints`() {
        val evidence = AiCoverSourceEvidence.fromMusicBrainz(
            MusicBrainzLookup(emptyList(), MusicBrainzStatus.NETWORK_ERROR),
        )
        assertTrue(evidence.hints.isEmpty())
        assertTrue(evidence.promptContext.isBlank())
    }

    @Test
    fun `MusicBrainz positive result is presented as evidence not verdict`() {
        val evidence = AiCoverSourceEvidence.fromMusicBrainz(
            MusicBrainzLookup(
                covers = listOf(
                    MusicBrainzCover(
                        title = "Il mondo",
                        artist = "Milva",
                        recordingId = "rec-1",
                        workId = "work-1",
                        year = 1966,
                    ),
                ),
                status = MusicBrainzStatus.OK,
            ),
        )
        assertEquals(1, evidence.hints.size)
        assertTrue(evidence.promptContext.contains("MusicBrainz"))
        assertTrue(evidence.promptContext.contains("indizi", ignoreCase = true))
        assertTrue(evidence.promptContext.contains("non", ignoreCase = true))
        assertTrue(evidence.promptContext.contains("Milva"))
    }

    @Test
    fun `AI-kept MusicBrainz candidate receives positive work evidence`() {
        val evidence = AiCoverSourceEvidence.fromMusicBrainz(
            MusicBrainzLookup(
                covers = listOf(
                    MusicBrainzCover("Il mondo", "Milva", "rec-1", "work-1", 1966),
                ),
                status = MusicBrainzStatus.OK,
            ),
        )
        val candidate = AiCoverCandidate(
            title = "Il mondo",
            artist = "Milva",
            category = AiCoverCategory.COVER,
        )
        val enriched = evidence.attachToAiAccepted(listOf(candidate)).single()
        assertTrue(enriched.brainSignals.any { it.kind == "musicbrainz_work_match" && it.direction == "positive" })
        assertTrue((enriched.sameWorkScore ?: 0) >= 90)
    }
}
