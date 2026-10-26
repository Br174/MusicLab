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
        verifiedVideoIds: Set<String>? = null,
        history: List<DiscogsVersionSeed> = emptyList(),
        originalYear: Int? = null,
        originalCredits: List<com.metrolist.music.discogs.DiscogsCredit> = emptyList(),
        coverRound: Int = 1,
        isRejected: (DiscogsVersionSeed) -> Boolean,
    ): List<DiscogsVersionSeed> {
        // Only prior rounds in this visit: never a persistent recording ban.
        val priorRecordings = history.mapTo(HashSet<String>()) {
            DiscogsVersionSource.recordingIdentityKey(it)
        }
        val priorVideos = history.mapNotNullTo(HashSet<String>()) {
            it.resolvedVideoId?.trim()?.takeIf(String::isNotBlank)
        }
        // LAB63: a matching title does not prove the same musical Work.
        // A trustworthy Work chronology can reject older homonyms (1924 vs
        // the documented 1980s Work) without banning older recordings blindly.
        // Where the original date has not arrived yet, a source-linked
        // performance supplies a provisional chronological lower bound.
        val referenceYear = originalYear ?: (alreadyVisible + candidates)
            .asSequence()
            .filter(CoverSameWorkGate::hasDocumentaryRelation)
            .mapNotNull { it.year }
            .minOrNull()
        val seen = LinkedHashSet<String>()
        val videos = HashSet<String>()
        val output = ArrayList<DiscogsVersionSeed>(QUOTA)
        // Existing card positions are stable; do not mutate their audio/video.
        for (seed in alreadyVisible + candidates) {
            if (output.size >= QUOTA) break
            val sharedWorkCredits = if (coverRound >= 2) {
                DiscogsVersionSource.sharedWorkCreditNames(seed, originalCredits).isNotEmpty()
            } else false
            val sameTitle = TitleMeaningResolver.matchesBaseTitle(songTitle, seed.trackTitle)
            val documentedWork = CoverSameWorkGate.hasDocumentaryRelation(seed)
            if (isRejected(seed) ||
                !(isRealCandidate(seed, songTitle, originalArtist) ||
                    (coverRound >= 2 && sharedWorkCredits))) continue
            // If a candidate predates the established Work, it is most likely
            // an unrelated same-title composition. Require work-level proof
            // instead of publishing a speculative dated record as a Cover.
            if (referenceYear != null && seed.year != null &&
                seed.year < referenceYear && !seed.manuallyApproved &&
                !documentedWork && !sharedWorkCredits
            ) continue
            // LAB64: a real documented recording is NOT a video. Keep video
            // first for round one, but never erase genuine Work-linked cards
            // merely because the video has not yet been found. Later turns
            // widen admission, without admitting an unsupported different Work.
            val rawVideo = seed.resolvedVideoId?.trim().orEmpty()
            val videoVerified = rawVideo.matches(Regex("[A-Za-z0-9_-]{11}")) &&
                (verifiedVideoIds == null || rawVideo in verifiedVideoIds)
            val independentSources = seed.sourceNames.map { it.trim().lowercase() }
                .filter { it.isNotBlank() && it != "archivio cloud" && it != "musiclab interno" }
                .distinct().size
            val catalogBacked = seed.sourceNames.any {
                it.equals("Discogs", true) || it.equals("MusicBrainz", true) ||
                    it.equals("COVER.INFO", true) || it.equals("Apple/iTunes", true) ||
                    it.equals("Spotify", true)
            }
            val chronological = referenceYear == null || seed.year == null ||
                seed.year >= referenceYear
            val evidenceEnoughWithoutVideo = documentedWork || sharedWorkCredits ||
                (coverRound >= 2 && sameTitle && chronological && independentSources >= 2) ||
                (coverRound >= 3 && sameTitle && chronological && catalogBacked)
            if (!videoVerified && !evidenceEnoughWithoutVideo) continue
            val identity = DiscogsVersionSource.recordingIdentityKey(seed)
            if (identity in priorRecordings || (videoVerified && rawVideo in priorVideos)) continue
            if (!seen.add(identity) || (videoVerified && !videos.add(rawVideo))) continue
            // An unverified URL is never an audio-ready/video-ready promise.
            output += if (videoVerified) seed else seed.copy(resolvedVideoId = null)
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
