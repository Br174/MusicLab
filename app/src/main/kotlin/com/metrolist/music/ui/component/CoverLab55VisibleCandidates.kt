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

    /** Read-only Cloud archive rows carry credits in BOTH round one and later. */
    internal fun cloudArchiveSeeds(
        rows: List<CloudArchiveItem>,
        songTitle: String,
        originalArtist: String,
    ): List<DiscogsVersionSeed> = rows.mapNotNull { item ->
        val effectiveTitle = item.workTitle.ifBlank { item.title }
        if (!TitleMeaningResolver.matchesBaseTitle(songTitle, effectiveTitle) &&
            !TitleMeaningResolver.matchesBaseTitle(songTitle, item.title)) {
            return@mapNotNull null
        }
        val kind = when (item.category.lowercase()) {
            "live", "dal vivo" -> AiCoverCategory.LIVE
            "remix", "mix", "rework" -> AiCoverCategory.REMIX
            "foreign", "straniera", "adattamento" -> AiCoverCategory.FOREIGN
            else -> AiCoverCategory.COVER
        }
        val credits = item.creditDetails.ifEmpty {
            buildList {
                item.songwriters.forEach { add(com.metrolist.music.discogs.DiscogsCredit(it, "Songwriter")) }
                item.composers.forEach { add(com.metrolist.music.discogs.DiscogsCredit(it, "Composer")) }
                item.lyricists.forEach { add(com.metrolist.music.discogs.DiscogsCredit(it, "Lyricist")) }
                item.producers.forEach { add(com.metrolist.music.discogs.DiscogsCredit(it, "Producer")) }
            }
        }
        DiscogsVersionSource.externalSeed(
            CoverSourceCandidate(
                title = item.title, artist = item.artist,
                sources = (listOf("Archivio Cloud") + item.sourceNames).distinct(),
                year = item.year, album = item.album,
                coverUrl = item.coverUrl, language = item.language,
                category = kind,
                playbackVideoId = item.playbackVideoId,
                playbackVideoTitle = item.playbackVideoTitle,
                playbackVideoSource = item.playbackVideoSource,
                credits = credits,
                evidenceScore = (((item.sameWorkScore ?: 30) +
                    (item.versionTypeScore ?: 30)) / 10).coerceIn(1, 20),
                workRelationConfirmed = false,
            ), targetTitle = songTitle, originalArtist = originalArtist,
        ).copy(
            credits = credits,
            sourceUrl = item.sourceUrl,
            releaseDate = item.releaseDate,
            country = item.country,
            musiclabScore100 = item.musiclabScore100,
            coverRound = item.coverRound,
            roundPosition = item.roundPosition,
        )
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
