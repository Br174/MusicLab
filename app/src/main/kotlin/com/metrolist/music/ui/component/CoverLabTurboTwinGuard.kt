package com.metrolist.music.ui.component

/**
 * MusicLab LAB67: video id is authoritative; otherwise remove YouTube upload
 * decorations, not musically meaningful qualifiers (live/remix/acoustic/etc).
 * The first displayed recording stays fixed, later copies are skipped.
 */
internal object CoverLabTurboTwinGuard {
    private val presentation = Regex(
        """(?i)\b(official|audio|music video|video clip|videoclip|lyrics|lyric video|visualizer|high quality|hq|hd|4k|1080p|remastered|remaster)\b"""
    )
    private val punctuation = Regex("""[^\p{L}\p{N}]+""")
    private val performance = Regex(
        """(?i)\b(live|concert|acoustic|unplugged|piano|guitar|remix|rework|demo|session|duet|duetto|cover band|festival|tour|feat|ft)\b"""
    )

    internal fun cleanTitle(value: String): String =
        punctuation.replace(presentation.replace(value, " "), " ")
            .trim().lowercase(java.util.Locale.ROOT).replace(Regex("""\s+"""), " ")

    internal fun sameRecording(left: DiscogsVersionSeed, right: DiscogsVersionSeed): Boolean {
        if (left.resolvedVideoId?.isNotBlank() == true &&
            left.resolvedVideoId == right.resolvedVideoId) return true
        if (!TitleMeaningResolver.sameArtist(left.artist, right.artist)) return false
        if (left.kind != right.kind) return false
        // Always retain distinct live venues, sessions, remixes and arrangements.
        val leftPerformance = left.resolvedVideoTitle.orEmpty() + " " + left.releaseTitle
        val rightPerformance = right.resolvedVideoTitle.orEmpty() + " " + right.releaseTitle
        val lFeatures = performance.findAll(leftPerformance).map { it.value.lowercase() }.toSet()
        val rFeatures = performance.findAll(rightPerformance).map { it.value.lowercase() }.toSet()
        if (lFeatures != rFeatures) return false
        if (left.kind != DiscogsVersionKind.STUDIO &&
            cleanTitle(leftPerformance) != cleanTitle(rightPerformance)) return false
        val lTitle = cleanTitle(left.resolvedVideoTitle ?: left.trackTitle)
        val rTitle = cleanTitle(right.resolvedVideoTitle ?: right.trackTitle)
        if (lTitle == rTitle && lTitle.isNotBlank()) {
            val a = left.durationSeconds
            val b = right.durationSeconds
            if (a != null && b != null && kotlin.math.abs(a - b) > 15) return false
            return true
        }
        return DiscogsVersionSource.sameCrossSourceVersion(left, right)
    }
}
