package com.metrolist.music.ui.component

/**
 * LAB71 foundational vetoes: shared by every Turbo publication tier.
 * Search breadth may increase per round; musical identity and forbidden
 * recording types never become optional. This gate does not probe playback.
 */
internal object CoverLab71FoundationGate {
    private val forbidden = Regex(
        """\b(karaoke|kar[aà]oke|midi|midifile|midifiles|minus[\s-]*one|""" +
            """backing[\s-]*track|accompaniment[\s-]*track|sing[\s-]*along|""" +
            """base[\s-]*musicale|basi[\s-]*musicali|vocal[\s-]*removed|""" +
            """instrumental[\s-]*karaoke|karaoke[\s-]*instrumental|""" +
            """learn[\s-]*to[\s-]*play|music[\s-]*tutorial|piano[\s-]*tutorial|""" +
            """guitar[\s-]*tutorial|lezione[\s-]*di[\s-]*pianoforte)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val explicitCover = Regex(
        """\b(cover|reinterpretation|interpretazione|tribute|tributo|omaggio|""" +
            """reprise|versione[\s-]*di)\b""", RegexOption.IGNORE_CASE,
    )
    private val originalQualifier = Regex(
        """\b(official|original|studio|remaster|remastered|live)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val liveMarker = Regex(
        """\b(live|dal vivo|concerto|concert|festival|tour|performance)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val remixMarker = Regex(
        """\b(remix|rework|club mix|radio mix|extended mix|dance mix)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val duetMarker = Regex(
        """\b(duet|duetto|duetti|feat|featuring|with|insieme a)\b""",
        RegexOption.IGNORE_CASE,
    )

    internal fun isForbidden(vararg text: String): Boolean =
        text.any { forbidden.containsMatchIn(it) }

    internal fun matchesRequestedWork(
        candidate: CoverSourceCandidate,
        targetTitle: String,
        originalArtist: String,
        metadataTitle: String,
    ): Boolean {
        val video = metadataTitle.ifBlank { candidate.playbackVideoTitle.orEmpty() }
        if (video.isBlank() || isForbidden(video, candidate.title, candidate.artist,
                candidate.album.orEmpty())) return false
        val aliases = setOf(candidate.artist, originalArtist)
            .filter(String::isNotBlank).toSet()
        fun isExactOrDecorated(title: String, text: String): Boolean =
            TitleMeaningResolver.matchesBaseTitle(title, text, aliases)

        // The ACTUAL YouTube video must agree with the source's song title.
        // Never trust "Felicità" from a catalog row if the linked video says
        // "Felicità puttana" or an unrelated full title.
        val documentaryWork = candidate.workRelationConfirmed &&
            candidate.sources.any {
                it.equals("COVER.INFO", true) || it.equals("MusicBrainz", true)
            }
        val requestedVideoTitle = isExactOrDecorated(targetTitle, video)
        val requestedCatalogTitle = isExactOrDecorated(targetTitle, candidate.title)
        if (documentaryWork) {
            // Reliable source links a translated title to the SAME Work.
            // The returned video must still name that documented recording.
            return (requestedVideoTitle && requestedCatalogTitle) ||
                isExactOrDecorated(candidate.title, video)
        }
        if (!requestedVideoTitle || !requestedCatalogTitle) return false

        // Same name does NOT mean same song ("Felicità" is a homonym).
        // For direct third-party uploads require a named source artist and
        // an explicit attribution to the original work, or reject as unknown.
        if (originalArtist.isNotBlank() &&
            TitleMeaningResolver.sameArtist(candidate.artist, originalArtist)
        ) return true
        if (originalArtist.isBlank()) {
            return candidate.artist.isNotBlank() &&
                !candidate.artist.equals("YouTube", true) &&
                (explicitCover.containsMatchIn(video) ||
                    originalQualifier.containsMatchIn(video))
        }
        return explicitCover.containsMatchIn(video) &&
            TitleMeaningResolver.canonical(video).contains(
                TitleMeaningResolver.canonical(originalArtist)
            )
    }

    private enum class OriginalType { STUDIO, LIVE, REMIX, DUET }

    private fun originalType(seed: DiscogsVersionSeed): OriginalType {
        val details = listOf(seed.resolvedVideoTitle.orEmpty(), seed.trackTitle,
            seed.releaseTitle).joinToString(" ")
        return when {
            duetMarker.containsMatchIn(details) -> OriginalType.DUET
            remixMarker.containsMatchIn(details) ||
                seed.kind == DiscogsVersionKind.REMIX -> OriginalType.REMIX
            liveMarker.containsMatchIn(details) ||
                seed.kind == DiscogsVersionKind.LIVE -> OriginalType.LIVE
            else -> OriginalType.STUDIO
        }
    }

    /**
     * Caps apply ONLY to the reference interpreter, across all manual rounds.
     * Distinct credited duet/collaboration takes are handled by deduplication
     * rather than an invented universal numeric duet quota.
     */
    internal fun withinOriginalCaps(
        seed: DiscogsVersionSeed,
        originalArtist: String,
        history: List<DiscogsVersionSeed>,
    ): Boolean {
        if (originalArtist.isBlank() ||
            !TitleMeaningResolver.sameArtist(seed.artist, originalArtist)) return true
        val type = originalType(seed)
        if (type == OriginalType.DUET) return true
        val ceiling = when (type) {
            OriginalType.STUDIO -> 1
            OriginalType.LIVE -> 2
            OriginalType.REMIX -> 2
            OriginalType.DUET -> Int.MAX_VALUE
        }
        return history.count {
            TitleMeaningResolver.sameArtist(it.artist, originalArtist) &&
                originalType(it) == type
        } < ceiling
    }

    /**
     * Conservative cross-video recording twins: preserve real live/duet takes.
     * Never replace the existing TwinGuard, only supplement its same-ID/
     * cross-provider evidence with available recorded durations.
     */
    internal fun isDuplicate(
        seed: DiscogsVersionSeed,
        history: List<DiscogsVersionSeed>,
    ): Boolean = history.any { previous ->
        if (previous.resolvedVideoId != null &&
            previous.resolvedVideoId == seed.resolvedVideoId) return@any true
        if (CoverLabTurboTwinGuard.sameRecording(previous, seed)) return@any true
        if (!TitleMeaningResolver.sameArtist(previous.artist, seed.artist)) return@any false
        if (originalType(previous) != originalType(seed)) return@any false
        val first = previous.durationSeconds
        val second = seed.durationSeconds
        first != null && second != null &&
            kotlin.math.abs(first - second) <= 3 &&
            TitleMeaningResolver.matchesBaseTitle(previous.trackTitle, seed.trackTitle)
    }
}
