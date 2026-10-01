package com.metrolist.music.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiCoverageModeTest {
    private fun candidate(
        sameWork: Int?,
        versionType: Int?,
        status: AiBrainDecisionStatus?,
    ) = AiCoverCandidate(
        title = "Titolo",
        artist = "Artista ${sameWork ?: -1}",
        category = AiCoverCategory.COVER,
        sameWorkScore = sameWork,
        versionTypeScore = versionType,
        brainStatus = status,
    )

    @Test
    fun `coverage thresholds are monotonic`() {
        assertEquals(85, AiCoverageMode.PRECISE.minimumScore)
        assertEquals(70, AiCoverageMode.SELECTED.minimumScore)
        assertEquals(50, AiCoverageMode.WIDE.minimumScore)
        assertEquals(30, AiCoverageMode.EXPLORE.minimumScore)
        assertEquals(15, AiCoverageMode.ALL.minimumScore)
    }

    @Test
    fun `changing coverage only filters the existing candidate pool`() {
        val pool = listOf(
            candidate(92, 91, AiBrainDecisionStatus.APPROVED),
            candidate(76, 73, AiBrainDecisionStatus.PROBABLE),
            candidate(58, 56, AiBrainDecisionStatus.PROBABLE),
            candidate(38, 35, AiBrainDecisionStatus.UNCERTAIN),
            candidate(22, 20, AiBrainDecisionStatus.UNCERTAIN),
            candidate(99, 99, AiBrainDecisionStatus.REJECTED),
        )

        assertEquals(1, AiCoverageFilter.visible(pool, AiCoverageMode.PRECISE).size)
        assertEquals(2, AiCoverageFilter.visible(pool, AiCoverageMode.SELECTED).size)
        assertEquals(3, AiCoverageFilter.visible(pool, AiCoverageMode.WIDE).size)
        assertEquals(4, AiCoverageFilter.visible(pool, AiCoverageMode.EXPLORE).size)
        assertEquals(5, AiCoverageFilter.visible(pool, AiCoverageMode.ALL).size)
    }

    @Test
    fun `rejected candidates stay hidden even in All`() {
        val rejected = candidate(100, 100, AiBrainDecisionStatus.REJECTED)
        assertFalse(AiCoverageFilter.isVisible(rejected, AiCoverageMode.ALL))
    }

    @Test
    fun `uncertain candidates remain available for review even below current visible threshold`() {
        val uncertain = candidate(36, 32, AiBrainDecisionStatus.UNCERTAIN)
        assertFalse(AiCoverageFilter.isVisible(uncertain, AiCoverageMode.PRECISE))
        assertTrue(uncertain in AiCoverageFilter.toReview(listOf(uncertain)))
    }

    @Test
    fun `legacy candidates without Brain scores are preserved instead of disappearing`() {
        val legacy = candidate(null, null, null)
        assertTrue(AiCoverageFilter.isVisible(legacy, AiCoverageMode.WIDE))
        assertFalse(legacy in AiCoverageFilter.toReview(listOf(legacy)))
    }
}
