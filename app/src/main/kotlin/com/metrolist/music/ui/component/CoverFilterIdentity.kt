package com.metrolist.music.ui.component

import java.util.Locale

/**
 * Read-only, local classification of the already published Cover cards.
 * This must NEVER influence discovery, admission, ranking, media resolution, or persistence.
 */
internal enum class CoverFilterTag { STUDIO, LIVE, REMIX, FOREIGN }

internal object CoverFilterIdentity {
    private val live = Regex(
        """\b(live|dal vivo|dal-vivo|in concerto|concert|concerto|concerti|festival|sanremo|eurovision|x factor|the voice|amici|live lounge|tv performance|stage performance|unplugged)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val remix = Regex(
        """\b(remix|rework|mashup|bootleg|dj|club mix|dance mix|extended mix|radio mix|dub mix|edit mix|ai cover|ai generated|ai version|intelligenza artificiale|artificial intelligence)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val foreign = Regex(
        """\b(english|spanish|french|german|portuguese|japanese|korean|italian|inglese|spagnol[oa]|frances[ea]|tedesc[oa]|portoghes[ea]|giappones[ea]|corean[oa])\s+(version|cover|adaptation|lyrics|versione)\b|\b(version|versione|cover|adaptation|adattamento|cantata|sung|in|en)\s+(in\s+)?(english|spanish|french|german|portuguese|japanese|korean|italian|inglese|spagnol[oa]|frances[ea]|tedesc[oa]|portoghes[ea]|giappones[ea]|corean[oa]|espa[nñ]ol|fran[çc]ais|deutsch|portugu[eê]s)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val studio = Regex(
        """\b(studio version|studio recording|studio session|studio take|recorded in studio|recorded in the studio|in studio|registrazione in studio|versione in studio)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val specialOther = Regex(
        """\b(instrumental|strumentale|karaoke|midi|acoustic|acustic[oa]|piano version|guitar version|demo)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** A service name is proof of documentary provenance only when attached to this seed. */
    private fun cataloguedPublication(seed: DiscogsVersionSeed): Boolean {
        val names = seed.sourceNames.map { it.trim().lowercase(Locale.ROOT) }
        val commercial = names.any { it == "spotify" || it == "apple/itunes" ||
            it == "apple music" || it == "itunes" }
        val discogsRelease = names.any { it == "discogs" } &&
            (seed.discogsVerificationChecked || (seed.releaseId > 0 && seed.releaseTitle.isNotBlank()))
        // A YouTube/YouTube Music URL or a COVER.INFO work relation alone is NOT
        // evidence that this exact recording was commercially published.
        return commercial || discogsRelease || seed.discogsVerificationChecked
    }

    fun tags(seed: DiscogsVersionSeed): Set<CoverFilterTag> {
        val description = buildString {
            append(seed.trackTitle).append(' ')
            append(seed.releaseTitle).append(' ')
            append(seed.resolvedVideoTitle.orEmpty())
            for (entry in seed.formatDescriptions) append(' ').append(entry)
        }
        val isLive = seed.kind == DiscogsVersionKind.LIVE || live.containsMatchIn(description)
        val isRemix = seed.kind == DiscogsVersionKind.REMIX || remix.containsMatchIn(description)
        val isForeign = !seed.language.isNullOrBlank() || foreign.containsMatchIn(description)
        val explicitStudio = studio.containsMatchIn(description)
        val otherVersion = seed.kind == DiscogsVersionKind.ACOUSTIC ||
            specialOther.containsMatchIn(description)
        return buildSet {
            if (isLive) add(CoverFilterTag.LIVE)
            if (isRemix) add(CoverFilterTag.REMIX)
            if (isForeign) add(CoverFilterTag.FOREIGN)
            // Direct YouTube cannot enter Studio by default: require an explicit label.
            // A documented record without an alternative descriptor is Studio by exclusion.
            if (explicitStudio || (cataloguedPublication(seed) &&
                !isLive && !isRemix && !isForeign && !otherVersion)) {
                add(CoverFilterTag.STUDIO)
            }
        }
    }
}
