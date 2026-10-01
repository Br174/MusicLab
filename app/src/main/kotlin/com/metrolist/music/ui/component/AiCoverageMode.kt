package com.metrolist.music.ui.component

/**
 * User-facing breadth of MusicLab Brain results.
 *
 * This never launches a new search: it only changes which already-discovered
 * candidates are visible. Thresholds mirror the cloud Brain contract except
 * ALL, which is intentionally a zero-floor user view of every non-rejected item.
 */
internal enum class AiCoverageMode(
    val minimumScore: Int,
    val label: String,
) {
    PRECISE(85, "Precisa"),
    SELECTED(70, "Selezionata"),
    WIDE(50, "Ampia"),
    EXPLORE(30, "Esplora"),
    ALL(0, "Tutto"),
    ;

    companion object {
        val DEFAULT: AiCoverageMode = WIDE
    }
}

internal object AiCoverageFilter {
    fun score(candidate: AiCoverCandidate): Int? {
        val sameWork = candidate.sameWorkScore?.coerceIn(0, 100)
        val versionType = candidate.versionTypeScore?.coerceIn(0, 100)
        return when {
            sameWork != null && versionType != null -> minOf(sameWork, versionType)
            sameWork != null -> sameWork
            versionType != null -> versionType
            else -> null
        }
    }

    fun isVisible(candidate: AiCoverCandidate, mode: AiCoverageMode): Boolean {
        if (candidate.brainStatus == AiBrainDecisionStatus.REJECTED) return false

        // User/AI approved entries are never hidden by a stricter display mode.
        if (candidate.brainStatus == AiBrainDecisionStatus.APPROVED) return true

        val score = score(candidate)
        // Backward compatibility: candidates produced before LAB20 scores exist
        // stay visible. The Brain may enrich them later instead of deleting them.
        if (score == null) return true
        return score >= mode.minimumScore
    }

    fun visible(candidates: List<AiCoverCandidate>, mode: AiCoverageMode): List<AiCoverCandidate> =
        candidates.filter { isVisible(it, mode) }

    fun <T> visibleItems(
        items: List<T>,
        mode: AiCoverageMode,
        candidateOf: (T) -> AiCoverCandidate?,
    ): List<T> = items.filter { item ->
        candidateOf(item)?.let { candidate -> isVisible(candidate, mode) } ?: true
    }

    fun toReview(candidates: List<AiCoverCandidate>): List<AiCoverCandidate> =
        candidates.filter { it.brainStatus == AiBrainDecisionStatus.UNCERTAIN }
}
