package com.metrolist.music.ui.component

/** A known recording is skipped; a resemblance is inspected after ordinary work. */
internal object CoverLabTurboTwinGuard {
    internal enum class Relation { DISTINCT, CONFIRMED, UNCERTAIN }

    private val presentation = Regex(
        """(?i)\b(official|audio|music video|video clip|videoclip|lyrics|lyric video|visualizer|high quality|hq|hd|4k|1080p|remastered|remaster)\b"""
    )
    private val punctuation = Regex("""[^\p{L}\p{N}]+""")
    private val years = Regex("""\b(?:19|20)\d{2}\b""")

    internal fun cleanTitle(value: String): String =
        punctuation.replace(presentation.replace(value, " "), " ")
            .trim().lowercase(java.util.Locale.ROOT).replace(Regex("""\s+"""), " ")

    private fun versionClass(seed: DiscogsVersionSeed): Set<String> =
        TitleMeaningResolver.versionDescriptors(
            seed.resolvedVideoTitle.orEmpty() + " " + seed.trackTitle
        ) + seed.kind.name.lowercase()

    internal fun relation(left: DiscogsVersionSeed, right: DiscogsVersionSeed): Relation {
        if (!left.resolvedVideoId.isNullOrBlank() &&
            left.resolvedVideoId == right.resolvedVideoId) return Relation.CONFIRMED
        if (!CoverLab71FoundationGate.sameCreditedPerformer(left.artist, right.artist)) return Relation.DISTINCT
        if (versionClass(left) != versionClass(right)) return Relation.DISTINCT
        val aliases = setOf(left.artist, right.artist)
        if (!TitleMeaningResolver.matchesBaseTitle(left.trackTitle, right.trackTitle, aliases) &&
            cleanTitle(left.trackTitle) != cleanTitle(right.trackTitle)) return Relation.DISTINCT

        val leftPosition = left.track?.position?.takeIf(String::isNotBlank)
        val rightPosition = right.track?.position?.takeIf(String::isNotBlank)
        if (left.releaseId > 0 && left.releaseId == right.releaseId &&
            leftPosition != null && leftPosition == rightPosition) return Relation.CONFIRMED

        val leftTitle = left.resolvedVideoTitle ?: left.trackTitle
        val rightTitle = right.resolvedVideoTitle ?: right.trackTitle
        val sameMaster = left.masterId != null && left.masterId == right.masterId
        val compatiblePosition = leftPosition == null || rightPosition == null ||
            leftPosition == rightPosition
        val closeDuration = left.durationSeconds != null && right.durationSeconds != null &&
            kotlin.math.abs(left.durationSeconds - right.durationSeconds) <= 3
        if (sameMaster && compatiblePosition && closeDuration &&
            cleanTitle(leftTitle) == cleanTitle(rightTitle)) return Relation.CONFIRMED

        val leftYears = years.findAll(leftTitle).map { it.value }.toSet()
        val rightYears = years.findAll(rightTitle).map { it.value }.toSet()
        if (left.kind != DiscogsVersionKind.STUDIO && leftYears.isNotEmpty() &&
            rightYears.isNotEmpty() && leftYears.intersect(rightYears).isEmpty()
        ) return Relation.DISTINCT
        return Relation.UNCERTAIN
    }

    internal fun sameRecording(left: DiscogsVersionSeed, right: DiscogsVersionSeed): Boolean =
        relation(left, right) == Relation.CONFIRMED

    internal fun possibleTwin(seed: DiscogsVersionSeed, history: List<DiscogsVersionSeed>): Boolean =
        history.any { relation(it, seed) == Relation.UNCERTAIN }
}
