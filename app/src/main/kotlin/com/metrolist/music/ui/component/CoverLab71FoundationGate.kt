package com.metrolist.music.ui.component

/**
 * LAB71 foundational vetoes: shared by every Turbo publication tier.
 * Search breadth may increase per round; musical identity and forbidden
 * recording types never become optional. This gate does not probe playback.
 */
internal object CoverLab71FoundationGate {
    private val forbidden = Regex(
        """(?i)\b(karaoke|kar[aà]oke|midi(?:[\s-]*files?)?|kar)\b|\.(?:mid|midi|kar)(?:$|[?#\s])""",
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
        allowExactTitleOnly: Boolean = false,
    ): Boolean {
        val video = metadataTitle.ifBlank { candidate.playbackVideoTitle.orEmpty() }
        if (video.isBlank() || isForbidden(video, candidate.title, candidate.artist,
                candidate.album.orEmpty())) return false
        val aliases = setOf(candidate.artist, originalArtist)
            .filter(String::isNotBlank).toSet()
        fun isExactOrDecorated(title: String, text: String): Boolean =
            TitleMeaningResolver.matchesBaseTitle(title, text, aliases) ||
                (allowExactTitleOnly && CoverLab76BroadTitle.matches(title, text, aliases))

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
            // A non-identical musical title is allowed ONLY for a documented
            // FOREIGN-language interpretation of the same Work. No other
            // different-title recording enters, even in broad late rounds.
            if (!requestedCatalogTitle && candidate.category != AiCoverCategory.FOREIGN) {
                return false
            }
            return (requestedVideoTitle && requestedCatalogTitle) ||
                (candidate.category == AiCoverCategory.FOREIGN &&
                    isExactOrDecorated(candidate.title, video))
        }
        if (!requestedVideoTitle || !requestedCatalogTitle) return false
        // By user request, later rounds can admit a genuine exact-title
        // result even when uploader metadata lacks the original artist.
        // This does not relax the forbidden-content veto above.
        if (allowExactTitleOnly) return true

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

    /** Historical metadata must follow the same R2 exclusions as new rows. */
    internal fun historicalIsEligible(
        seed: DiscogsVersionSeed,
        targetTitle: String,
        originalArtist: String,
    ): Boolean =
        seed.resolvedVideoId.orEmpty().matches(Regex("[A-Za-z0-9_-]{11}")) &&
            !isForbidden(
                seed.trackTitle, seed.resolvedVideoTitle.orEmpty(),
                seed.releaseTitle, seed.formats.joinToString(" "),
                seed.formatDescriptions.joinToString(" "), seed.sourceUrl.orEmpty(),
            )

    private fun creditedName(value: String): String = TitleMeaningResolver.canonical(value)
        .removeSuffix(" topic").trim()

    internal fun sameCreditedPerformer(left: String, right: String): Boolean =
        creditedName(left).isNotBlank() && creditedName(left) == creditedName(right)

    internal fun isOriginalPerformer(artist: String, originalArtist: String): Boolean {
        val original = creditedName(originalArtist)
        if (original.isBlank()) return false
        if (creditedName(artist) == original) return true
        return artist.split(Regex("""(?i)\s*(?:[&+,/]|\b(?:feat\.?|ft\.?|featuring|with|and|con|e)\b)\s*"""))
            .any { creditedName(it) == original }
    }

    internal fun withinOriginalCaps(
        seed: DiscogsVersionSeed,
        originalArtist: String,
        history: List<DiscogsVersionSeed>,
    ): Boolean {
        if (originalArtist.isBlank() ||
            !isOriginalPerformer(seed.artist, originalArtist)) return true
        return history.count {
            isOriginalPerformer(it.artist, originalArtist)
        } < 5
    }

    /** Equal duration/title is a possible twin, never proof of identity. */
    internal fun isDuplicate(
        seed: DiscogsVersionSeed,
        history: List<DiscogsVersionSeed>,
    ): Boolean = history.any { CoverLabTurboTwinGuard.sameRecording(it, seed) }
}
