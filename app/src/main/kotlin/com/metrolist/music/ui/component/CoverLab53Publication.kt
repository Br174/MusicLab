package com.metrolist.music.ui.component

/**
 * LAB53: source discovery and UI publication are separate concerns.
 * This helper never invents a row, video or proof, and is used only by Turbo's
 * existing one-writer Cover publisher. The 50-item quota counts COVERS only.
 */
internal object CoverLab53Publication {
    const val COVER_QUOTA = 50
    const val MAX_RETAINED_CANDIDATES = 750

    internal data class Funnel(
        val found: Int,
        val certified: Int,
        val excluded: Int,
        val visible: Int,
        val missing: Int,
    ) {
        fun diagnostic(): String =
            "trovate=$found · certificate=$certified · escluse=$excluded · " +
                "mostrate=$visible/$COVER_QUOTA · mancanti=$missing"
    }

    internal fun retain(
        previous: List<DiscogsVersionSeed>,
        newCandidates: List<DiscogsVersionSeed>,
    ): List<DiscogsVersionSeed> =
        DiscogsVersionSource.dedupeVersions(
            previous + newCandidates, preserveInputOrder = true,
        ).take(MAX_RETAINED_CANDIDATES)

    /**
     * Append only confirmed, distinct cover recordings. Original-performer
     * versions do not consume cover quota or change existing card positions.
     * A missing video ID leaves the genuine recording as a visible candidate;
     * playback verification is handled separately by LAB49's coordinator.
     */
    internal fun fill(
        published: List<DiscogsVersionSeed>,
        candidates: List<DiscogsVersionSeed>,
        eligibleCover: (DiscogsVersionSeed) -> Boolean,
    ): List<DiscogsVersionSeed> {
        val selected = mutableListOf<DiscogsVersionSeed>()
        val seenRecordings = HashSet<String>()
        val usedVideos = HashSet<String>()

        fun accept(seed: DiscogsVersionSeed, eligibilityRequired: Boolean) {
            if (selected.size >= COVER_QUOTA) return
            if (eligibilityRequired && !eligibleCover(seed)) return
            val recordingKey = DiscogsVersionSource.recordingIdentityKey(seed)
            if (recordingKey in seenRecordings) return
            val videoId = seed.resolvedVideoId?.trim().orEmpty()
            if (videoId.isNotEmpty() && videoId in usedVideos) return
            seenRecordings.add(recordingKey)
            if (videoId.isNotEmpty()) usedVideos.add(videoId)
            selected += seed
        }

        // Cards already published are immutable; newer evidence can append.
        published.forEach { accept(it, eligibilityRequired = false) }
        candidates.forEach { accept(it, eligibilityRequired = true) }
        return selected
    }

    internal fun funnel(
        candidates: List<DiscogsVersionSeed>,
        visible: List<DiscogsVersionSeed>,
        eligibleCover: (DiscogsVersionSeed) -> Boolean,
    ): Funnel {
        val unique = candidates.distinctBy(DiscogsVersionSource::recordingIdentityKey)
        val certified = unique.count(eligibleCover)
        return Funnel(
            found = unique.size,
            certified = certified,
            excluded = unique.size - certified,
            visible = visible.size,
            missing = (COVER_QUOTA - visible.size).coerceAtLeast(0),
        )
    }
}
