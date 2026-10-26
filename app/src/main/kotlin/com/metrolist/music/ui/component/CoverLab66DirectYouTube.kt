package com.metrolist.music.ui.component

/**
 * LAB66. Only a search PLAN: never owns a second player, UI state machine,
 * Cloud database or catalogue-poster publisher. A manual button advances
 * two independent, lightweight YouTube queries per press.
 *
 * Musical confidence and video availability are DIFFERENT checks. Round 4
 * intentionally tolerates title-only (possibly wrong-work) results.
 */
internal object CoverLab66DirectYouTube {
    const val QUERIES_PER_PRESS = 2
    const val MAX_VISIBLE_PER_ROUND = 50

    fun queries(title: String, artist: String, round: Int): List<String> {
        val work = title.trim()
        val performer = artist.trim()
        if (work.isBlank()) return emptyList()
        val base = if (performer.isNotBlank()) "\"$work\" $performer" else "\"$work\""
        val families = when (round) {
            1 -> listOf(
                "$base official video", "$base studio original",
                "$base official cover", "$base duet live official",
                "$base cover version", "$base foreign adaptation",
                "$base cover live performance", "$base official international cover",
            )
            2 -> listOf(
                "$base live cover", "$base X Factor cover",
                "$base The Voice audition", "$base acoustic version",
                "$base festival concert", "$base radio session",
                "$base remix cover", "$base tribute live",
            )
            3 -> listOf(
                "\"$work\" guitar cover", "\"$work\" piano cover",
                "\"$work\" amateur cover", "\"$work\" home studio",
                "\"$work\" independent singer", "\"$work\" street performance",
                "\"$work\" instrumental", "\"$work\" bedroom acoustic",
            )
            else -> listOf(
                "\"$work\" version", "\"$work\" live",
                "\"$work\" song", "\"$work\" music",
                "\"$work\" cover", "\"$work\" performance",
                "\"$work\" rare", "\"$work\"",
            )
        }
        return families.distinct()
    }

    fun admissionSignals(
        requestedTitle: String,
        originalArtist: String,
        videoTitle: String,
        uploader: String,
        round: Int,
        documentedWorkRelation: Boolean,
    ): Int {
        val title = requestedTitle.trim().lowercase()
        val rendered = videoTitle.lowercase()
        val channel = uploader.lowercase()
        val artist = originalArtist.trim().lowercase()
        val titleMatch = title.isNotBlank() && rendered.contains(title)
        val performerMatch = artist.isNotBlank() &&
            (rendered.contains(artist) || channel.contains(artist))
        val evidence = Regex(
            "\\b(official|cover|version|tribute|live|acoustic|remix|duet|performance|interpretazione|reinterpretation)\\b",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(videoTitle)
        val signals = listOf(titleMatch, performerMatch, evidence).count { it } +
            if (documentedWorkRelation) 3 else 0
        // An exact requested title performed by the known ORIGINAL artist
        // is already a high-confidence official recording, even when the
        // uploader omitted "official" from the video's displayed title.
        val knownOriginalPerformance = titleMatch && performerMatch &&
            originalArtist.isNotBlank()
        val explicitlyOfficial = titleMatch && Regex(
            "\\b(official|vevo|licensed)\\b", RegexOption.IGNORE_CASE,
        ).containsMatchIn(videoTitle)
        return if (round >= 4) {
            if (titleMatch || documentedWorkRelation) maxOf(signals, 1) else 0
        } else if (round == 1 && (knownOriginalPerformance || explicitlyOfficial)) {
            maxOf(signals, 3)
        } else signals
    }

    fun accepted(signals: Int, round: Int): Boolean =
        signals >= when (round) {
            1 -> 3
            2 -> 2
            3 -> 1
            else -> 1
        }

    fun estimatedPrecision(signals: Int, round: Int, documented: Boolean): Int =
        when {
            documented && signals >= 3 -> 95
            documented -> 85
            signals >= 3 -> 88
            signals == 2 -> 70
            signals == 1 && round >= 4 -> 15
            signals == 1 -> 35
            else -> 10
        }

    fun nextCursor(cursor: Int, total: Int) =
        (cursor + QUERIES_PER_PRESS).coerceAtMost(total)

    fun isExhausted(cursor: Int, total: Int, visibleInRound: Int): Boolean =
        cursor >= total || visibleInRound >= MAX_VISIBLE_PER_ROUND
}
