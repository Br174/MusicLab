package com.metrolist.music.ui.component

/**
 * LAB72: a music video and a released recording are two different facts.
 * A "Live", "Official", "Topic", YouTube view count or artist fame is NOT
 * evidence that an actual single/album/EP/compilation was published.
 *
 * Called only at the Turbo admission boundary, after bounded discovery.
 * Never performs network I/O, starts playback or mutates Cloud storage.
 */
internal object CoverLab72PublicationProof {
    private val professional = Regex(
        """(?i)\b(sanremo|festival di sanremo|rai[1-5]?|eurovision|""" +
            """x[\s-]*factor|the[\s-]*voice|talent show|tv show|television|""" +
            """televisione|studio session|live session|bbc radio|live lounge|""" +
            """radio session|official performance|official live|""" +
            """live at|concerto ufficiale|festival official|""" +
            """unplugged official|mtv unplugged)\b"""
    )
    private val live = Regex("""(?i)\b(live|dal vivo|concerto|concert|festival|tour|performance)\b""")
    private val remix = Regex("""(?i)\b(remix|rework|club mix|extended mix|radio mix)\b""")

    /** The exact recording must be catalogued under a named album/release. */
    internal fun isReleaseProof(candidate: CoverSourceCandidate): Boolean {
        if (!candidate.releaseVerified || candidate.album.isNullOrBlank() ||
            candidate.releaseEvidence.isNullOrBlank()) return false
        return candidate.sources.any {
            it.equals("Apple/iTunes", true) ||
                it.equals("Spotify", true) || it.equals("Discogs", true)
        }
    }

    private fun sameRecordingCategory(proof: CoverSourceCandidate, video: CoverSourceCandidate): Boolean {
        val videoTitle = video.playbackVideoTitle ?: video.title
        val videoLive = live.containsMatchIn(videoTitle) ||
            video.category == AiCoverCategory.LIVE
        val proofLive = live.containsMatchIn(proof.title + " " + proof.album.orEmpty()) ||
            proof.category == AiCoverCategory.LIVE
        // Unreleased footage of a concert is NOT the published studio recording.
        if (videoLive && !proofLive) return false
        val videoRemix = remix.containsMatchIn(videoTitle) ||
            video.category == AiCoverCategory.REMIX
        val proofRemix = remix.containsMatchIn(proof.title + " " + proof.album.orEmpty()) ||
            proof.category == AiCoverCategory.REMIX
        if (videoRemix && !proofRemix) return false
        return true
    }

    /**
     * Release evidence must match THIS performer and THIS recorded song.
     * COVER.INFO establishes the identity of translated works, not by itself
     * a physical/digital release for the linked recording.
     */
    internal fun corroboratesRecording(
        candidate: CoverSourceCandidate,
        proof: CoverSourceCandidate,
    ): Boolean {
        if (!isReleaseProof(proof) || !sameRecordingCategory(proof, candidate)) return false
        if (!TitleMeaningResolver.sameArtist(candidate.artist, proof.artist)) return false
        val aliases = setOf(proof.artist, candidate.artist)
        if (!TitleMeaningResolver.matchesBaseTitle(
                proof.title, candidate.title, aliases
            )) return false
        return TitleMeaningResolver.matchesBaseTitle(
            proof.title, candidate.playbackVideoTitle.orEmpty().ifBlank { candidate.title },
            aliases,
        )
    }

    internal fun withDocumentedRelease(
        candidate: CoverSourceCandidate,
        proofs: List<CoverSourceCandidate>,
    ): CoverSourceCandidate? {
        if (isReleaseProof(candidate)) return candidate
        val proof = proofs.firstOrNull { corroboratesRecording(candidate, it) } ?: return null
        return candidate.copy(
            sources = (candidate.sources + proof.sources).distinct(),
            album = proof.album,
            year = candidate.year ?: proof.year,
            releaseVerified = true,
            releaseEvidence = proof.releaseEvidence,
            // Release proof never invents a musical-Work relationship.
            workRelationConfirmed = candidate.workRelationConfirmed,
        )
    }

    /**
     * Second round: professionally produced TV, festival, radio or live
     * sessions without a confirmed discographic publication. YouTube
     * channel uploads and unsourced amateur concerts are not enough.
     */
    internal fun professionalUnreleased(candidate: CoverSourceCandidate): Boolean {
        val description = listOf(
            candidate.title, candidate.playbackVideoTitle.orEmpty(),
            candidate.album.orEmpty(), candidate.playbackVideoSource.orEmpty(),
        ).joinToString(" ")
        return professional.containsMatchIn(description)
    }

    /** Keep genuine documented foreign adaptations near the top. */
    internal fun firstRoundPriority(candidate: CoverSourceCandidate, originalArtist: String): Int {
        val coverInfo = candidate.sources.any { it.equals("COVER.INFO", true) } &&
            candidate.workRelationConfirmed
        return when {
            coverInfo -> 0
            originalArtist.isNotBlank() &&
                TitleMeaningResolver.sameArtist(candidate.artist, originalArtist) -> 1
            candidate.sources.count {
                it.equals("Apple/iTunes", true) || it.equals("Spotify", true) ||
                    it.equals("Discogs", true)
            } >= 2 -> 2
            else -> 3
        }
    }
}
