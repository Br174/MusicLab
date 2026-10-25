package com.metrolist.music.ui.component

/**
 * LAB55: do not confuse "not fully certified yet" with "not found".
 * A visible provisional poster always comes from a real provider/cached record.
 * It is NOT asserted to be a certified cover and is never fabricated.
 * The exact original performer is excluded and a same-title homonym is flagged
 * provisional until editorial work identity can be checked.
 */
internal object CoverLab55VisibleCandidates {
    const val QUOTA = 50

    internal fun isRealCandidate(
        seed: DiscogsVersionSeed,
        songTitle: String,
        originalArtist: String,
    ): Boolean {
        if (seed.trackTitle.isBlank() || seed.artist.isBlank()) return false
        if (originalArtist.isNotBlank() &&
            TitleMeaningResolver.sameArtist(seed.artist, originalArtist)) return false
        if (seed.sourceNames.isEmpty()) return false
        // A relationship anchored to the same Work permits translated titles.
        if (CoverSameWorkGate.hasDocumentaryRelation(seed)) return true
        return TitleMeaningResolver.matchesBaseTitle(songTitle, seed.trackTitle)
    }

    internal fun isProvisional(
        seed: DiscogsVersionSeed,
        originalArtist: String,
        originalCredits: List<com.metrolist.music.discogs.DiscogsCredit>,
    ): Boolean = !CoverSameWorkGate.hasWorkIdentity(
        seed, originalArtist, originalCredits,
    )

    internal fun publish(
        alreadyVisible: List<DiscogsVersionSeed>,
        candidates: List<DiscogsVersionSeed>,
        songTitle: String,
        originalArtist: String,
        isRejected: (DiscogsVersionSeed) -> Boolean,
    ): List<DiscogsVersionSeed> {
        val seen = LinkedHashSet<String>()
        val videos = HashSet<String>()
        val output = ArrayList<DiscogsVersionSeed>(QUOTA)
        // Existing card positions are stable; do not mutate their audio/video.
        for (seed in alreadyVisible + candidates) {
            if (output.size >= QUOTA) break
            if (isRejected(seed) ||
                !isRealCandidate(seed, songTitle, originalArtist)) continue
            val identity = DiscogsVersionSource.recordingIdentityKey(seed)
            if (!seen.add(identity)) continue
            val video = seed.resolvedVideoId?.trim().orEmpty()
            if (video.isNotBlank() && !videos.add(video)) continue
            output += seed
        }
        return output
    }

    internal fun labelFor(
        seed: DiscogsVersionSeed,
        originalArtist: String,
        originalCredits: List<com.metrolist.music.discogs.DiscogsCredit>,
    ): String = if (isProvisional(seed, originalArtist, originalCredits)) {
        "Da verificare · ${seed.sourceNames.firstOrNull().orEmpty()}"
    } else {
        "Cover confermata"
    }
}
