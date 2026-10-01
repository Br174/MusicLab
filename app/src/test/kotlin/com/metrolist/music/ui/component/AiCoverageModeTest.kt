package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `wide is the default coverage mode`() {
        assertEquals(AiCoverageMode.WIDE, AiCoverageMode.DEFAULT)
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
    fun `coverage projection happens before pagination and does not mutate source items`() {
        data class Result(val candidate: AiCoverCandidate, val id: String)

        val hiddenFirst = Result(candidate(45, 44, AiBrainDecisionStatus.PROBABLE), "hidden")
        val visibleFirst = Result(candidate(90, 88, AiBrainDecisionStatus.PROBABLE), "visible-1")
        val visibleSecond = Result(candidate(72, 65, AiBrainDecisionStatus.PROBABLE), "visible-2")
        val source = listOf(hiddenFirst, visibleFirst, visibleSecond)

        val page = AiCoverageFilter
            .visibleItems(source, AiCoverageMode.WIDE) { it.candidate }
            .take(2)

        assertEquals(listOf("visible-1", "visible-2"), page.map { it.id })
        assertEquals(listOf("hidden", "visible-1", "visible-2"), source.map { it.id })
    }

    @Test
    fun `Originali PRECISE projects retained Brain metadata while preserving legacy results`() {
        data class OriginalResult(val id: String, val brainCandidate: AiCoverCandidate?)

        val source = listOf(
            OriginalResult("strong", candidate(94, 91, AiBrainDecisionStatus.APPROVED)),
            OriginalResult("weak", candidate(54, 52, AiBrainDecisionStatus.PROBABLE)),
            OriginalResult("legacy", null),
        )

        val visible = AiCoverageFilter.visibleItems(source, AiCoverageMode.PRECISE) { it.brainCandidate }

        assertEquals(listOf("strong", "legacy"), visible.map { it.id })
        assertEquals(listOf("strong", "weak", "legacy"), source.map { it.id })
    }

    @Test
    fun `Originali can switch coverage locally without changing resolved pool`() {
        data class OriginalResult(val id: String, val brainCandidate: AiCoverCandidate?)

        val source = listOf(
            OriginalResult("precise", candidate(92, 90, AiBrainDecisionStatus.APPROVED)),
            OriginalResult("wide", candidate(61, 58, AiBrainDecisionStatus.PROBABLE)),
            OriginalResult("legacy", null),
        )

        val precise = AiCoverageFilter.visibleItems(source, AiCoverageMode.PRECISE) { it.brainCandidate }
        val wide = AiCoverageFilter.visibleItems(source, AiCoverageMode.WIDE) { it.brainCandidate }

        assertEquals(listOf("precise", "legacy"), precise.map { it.id })
        assertEquals(listOf("precise", "wide", "legacy"), wide.map { it.id })
        assertEquals(listOf("precise", "wide", "legacy"), source.map { it.id })
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
