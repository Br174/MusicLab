package com.metrolist.music.ui.component

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Orchestrates Cover discovery lanes without making any external source a veto.
 *
 * AI/Cloud remains the editorial lane. Structured sources are allowed to seed the
 * candidate pool only as UNCERTAIN evidence when the AI pool is still sparse.
 * YouTube/YTM stay downstream and are used only by AiCoverSearchEngine for playback.
 */
internal object AiCoverFlowResolver {
    private const val STRUCTURED_EXPANSION_TARGET = 80

    suspend fun discoverInitial(
        originalTitle: String,
        originalArtist: String,
        config: GeminiCoverVerificationConfig,
        sourceEvidence: AiCoverSourceEvidence? = null,
    ): AiCoverDiscoveryResult {
        val primary = GeminiAiCoverDiscovery.discoverInitial(
            originalTitle = originalTitle,
            originalArtist = originalArtist,
            config = config,
        )
        val evidence = sourceEvidence ?: AiCoverSourceEvidence.fromMusicBrainz(
            MusicBrainzCoverSource.lookup(originalTitle, originalArtist),
        )
        val seeds = musicBrainzSeeds(originalTitle, originalArtist, evidence)
        return primary.copy(
            versions = mergeCandidates(primary.versions, seeds),
        )
    }

    suspend fun discoverExpandedBatches(
        originalTitle: String,
        originalArtist: String,
        existing: List<AiCoverCandidate>,
        config: GeminiCoverVerificationConfig,
        sourceEvidence: AiCoverSourceEvidence? = null,
        onBatch: suspend (List<AiCoverCandidate>) -> Unit,
    ) {
        val collected = linkedMapOf<String, AiCoverCandidate>()
        existing.forEach { collected[it.stableKey] = it }

        suspend fun emitFresh(batch: List<AiCoverCandidate>) {
            val fresh = batch
                .filter { !collected.containsKey(it.stableKey) }
                .distinctBy { it.stableKey }
            if (fresh.isEmpty()) return
            fresh.forEach { collected[it.stableKey] = it }
            onBatch(fresh)
        }

        val evidence = sourceEvidence ?: AiCoverSourceEvidence.fromMusicBrainz(
            MusicBrainzCoverSource.lookup(originalTitle, originalArtist),
        )
        emitFresh(musicBrainzSeeds(originalTitle, originalArtist, evidence))

        GeminiAiCoverDiscovery.discoverExpandedBatches(
            originalTitle = originalTitle,
            originalArtist = originalArtist,
            existing = collected.values.toList(),
            config = config,
        ) { batch ->
            emitFresh(batch)
        }

        val coverCount = collected.values.count { it.category == AiCoverCategory.COVER }
        if (coverCount < STRUCTURED_EXPANSION_TARGET) {
            emitFresh(
                discoverSecondaryStructuredSeeds(
                    originalTitle = originalTitle,
                    originalArtist = originalArtist,
                    config = config,
                ),
            )
        }
    }

    suspend fun discoverRecoveryBatch(
        originalTitle: String,
        originalArtist: String,
        existing: List<AiCoverCandidate>,
        config: GeminiCoverVerificationConfig,
        round: Int,
    ): List<AiCoverCandidate> =
        GeminiAiCoverDiscovery.discoverRecoveryBatch(
            originalTitle = originalTitle,
            originalArtist = originalArtist,
            existing = existing,
            config = config,
            round = round,
        )

    private suspend fun discoverSecondaryStructuredSeeds(
        originalTitle: String,
        originalArtist: String,
        config: GeminiCoverVerificationConfig,
    ): List<AiCoverCandidate> = coroutineScope {
        val secondHandSongs = async(Dispatchers.IO) {
            runCatching { SecondHandSongsCoverSource.lookup(originalTitle, originalArtist) }
                .getOrNull()
                ?.covers
                .orEmpty()
                .mapNotNull { cover ->
                    structuredSeed(
                        originalTitle = originalTitle,
                        originalArtist = originalArtist,
                        title = cover.title,
                        artist = cover.artist,
                        year = cover.year,
                        source = "secondhandsongs",
                        sameWorkScore = 92,
                        versionTypeScore = 78,
                    )
                }
        }
        val whoSampled = async(Dispatchers.IO) {
            runCatching { WhoSampledCoverSource.lookup(originalTitle, originalArtist) }
                .getOrNull()
                ?.covers
                .orEmpty()
                .mapNotNull { cover ->
                    structuredSeed(
                        originalTitle = originalTitle,
                        originalArtist = originalArtist,
                        title = cover.title,
                        artist = cover.artist,
                        year = null,
                        source = "whosampled",
                        sameWorkScore = 88,
                        versionTypeScore = 76,
                    )
                }
        }
        val indexedWhoSampled = async(Dispatchers.IO) {
            runCatching {
                WhoSampledIndexedDiscovery.discover(
                    originalTitle = originalTitle,
                    originalArtist = originalArtist,
                    config = config,
                )
            }.getOrDefault(emptyList())
                .mapNotNull { cover ->
                    structuredSeed(
                        originalTitle = originalTitle,
                        originalArtist = originalArtist,
                        title = cover.title,
                        artist = cover.artist,
                        year = null,
                        source = "whosampled_index",
                        sameWorkScore = 82,
                        versionTypeScore = 72,
                    )
                }
        }

        mergeCandidates(
            secondHandSongs.await(),
            whoSampled.await(),
            indexedWhoSampled.await(),
        )
    }

    private fun musicBrainzSeeds(
        originalTitle: String,
        originalArtist: String,
        evidence: AiCoverSourceEvidence,
    ): List<AiCoverCandidate> =
        evidence.hints.mapNotNull { cover ->
            structuredSeed(
                originalTitle = originalTitle,
                originalArtist = originalArtist,
                title = cover.title,
                artist = cover.artist,
                year = cover.year,
                source = "musicbrainz",
                sameWorkScore = 94,
                versionTypeScore = 76,
            )
        }

    private fun structuredSeed(
        originalTitle: String,
        originalArtist: String,
        title: String,
        artist: String,
        year: Int?,
        source: String,
        sameWorkScore: Int,
        versionTypeScore: Int,
    ): AiCoverCandidate? {
        if (!sameItalianTitle(originalTitle, title)) return null
        if (sameArtist(originalArtist, artist)) return null
        if (artist.isBlank()) return null

        return AiCoverCandidate(
            title = title.trim(),
            artist = artist.trim(),
            category = AiCoverCategory.COVER,
            year = year,
            sameWorkScore = sameWorkScore,
            versionTypeScore = versionTypeScore,
            brainStatus = AiBrainDecisionStatus.UNCERTAIN,
            brainAdmission = "structured_seed:$source",
            brainSignals = listOf(
                AiBrainSignal(
                    kind = "${source}_same_work",
                    strength = "strong",
                    direction = "positive",
                ),
            ),
        )
    }

    private fun mergeCandidates(vararg groups: List<AiCoverCandidate>): List<AiCoverCandidate> {
        val merged = linkedMapOf<String, AiCoverCandidate>()
        groups.forEach { group ->
            group.forEach { candidate ->
                merged.putIfAbsent(candidate.stableKey, candidate)
            }
        }
        return merged.values.toList()
    }

    /**
     * Applies the editorial admission policy without turning missing metadata into a veto.
     *
     * Same-language Cover/Live/Remix: canonical title must remain an autonomous phrase.
     * Foreign adaptations: title may differ, but one strong same-work evidence is enough.
     * Missing evidence becomes UNCERTAIN so the candidate can still be localized/reviewed.
     */
    internal fun applyEvidencePolicy(
        originalTitle: String,
        originalInfo: AiCoverOriginalInfo?,
        candidates: List<AiCoverCandidate>,
    ): List<AiCoverCandidate> = candidates.map { raw ->
        val titleMatches = TitleMeaningResolver.matchesBaseTitle(originalTitle, raw.title)
        val languageDiffers = languagesClearlyDiffer(originalInfo?.language, raw.language)
        val candidate = if (
            raw.category != AiCoverCategory.FOREIGN &&
            !titleMatches &&
            languageDiffers
        ) {
            raw.copy(category = AiCoverCategory.FOREIGN)
        } else {
            raw
        }

        if (candidate.brainStatus == AiBrainDecisionStatus.REJECTED) {
            return@map candidate
        }

        val strongEvidence = hasStrongSameWorkEvidence(originalInfo, candidate)
        val isForeign = candidate.category == AiCoverCategory.FOREIGN

        when {
            isForeign && strongEvidence ->
                candidate.copy(
                    brainStatus = candidate.brainStatus ?: AiBrainDecisionStatus.PROBABLE,
                    brainAdmission = candidate.brainAdmission ?: "foreign_one_strong_evidence",
                )

            isForeign ->
                candidate.copy(
                    brainStatus = AiBrainDecisionStatus.UNCERTAIN,
                    brainAdmission = candidate.brainAdmission ?: "foreign_waiting_same_work_evidence",
                )

            titleMatches && strongEvidence ->
                candidate.copy(
                    brainStatus = candidate.brainStatus ?: AiBrainDecisionStatus.PROBABLE,
                    brainAdmission = candidate.brainAdmission ?: "title_plus_same_work_evidence",
                )

            titleMatches ->
                candidate.copy(
                    brainStatus = AiBrainDecisionStatus.UNCERTAIN,
                    brainAdmission = candidate.brainAdmission ?: "title_ok_waiting_same_work_evidence",
                )

            else ->
                candidate.copy(
                    brainStatus = AiBrainDecisionStatus.REJECTED,
                    brainAdmission = listOf("title_meaning_mismatch", candidate.brainAdmission)
                        .filterNotNull()
                        .joinToString("|"),
                )
        }
    }

    internal fun sameItalianTitle(originalTitle: String, candidateTitle: String): Boolean =
        TitleMeaningResolver.matchesBaseTitle(originalTitle, candidateTitle)

    private fun hasStrongSameWorkEvidence(
        originalInfo: AiCoverOriginalInfo?,
        candidate: AiCoverCandidate,
    ): Boolean {
        if ((candidate.sameWorkScore ?: 0) >= 60) return true
        if (candidate.brainStatus == AiBrainDecisionStatus.APPROVED ||
            candidate.brainStatus == AiBrainDecisionStatus.PROBABLE
        ) return true
        if (!candidate.brainAdmission.isNullOrBlank() &&
            !candidate.brainAdmission.startsWith("title_ok_waiting") &&
            !candidate.brainAdmission.startsWith("foreign_waiting")
        ) return true
        if (candidate.brainSignals.any { signal ->
                signal.direction?.lowercase() != "negative" &&
                    signal.strength.lowercase() in setOf("medium", "strong", "very_strong")
            }
        ) return true

        val originalCredits = buildSet {
            originalInfo?.songwriters?.forEach { add(canonicalTitle(it)) }
            originalInfo?.composers?.forEach { add(canonicalTitle(it)) }
            originalInfo?.lyricists?.forEach { add(canonicalTitle(it)) }
        }.filter(String::isNotBlank).toSet()
        if (originalCredits.isEmpty()) return false

        val candidateCredits = buildSet {
            candidate.songwriters.forEach { add(canonicalTitle(it)) }
            candidate.composers.forEach { add(canonicalTitle(it)) }
            candidate.lyricists.forEach { add(canonicalTitle(it)) }
        }.filter(String::isNotBlank).toSet()

        return candidateCredits.any { it in originalCredits }
    }

    private fun languagesClearlyDiffer(original: String?, candidate: String?): Boolean {
        val a = original?.trim()?.lowercase().orEmpty()
        val b = candidate?.trim()?.lowercase().orEmpty()
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return false
        val italian = setOf("it", "ita", "italian", "italiano", "italiana")
        return (a in italian) != (b in italian) || (a.length <= 3 && b.length <= 3 && a != b)
    }

    private fun sameArtist(left: String, right: String): Boolean {
        val a = canonicalTitle(left)
        val b = canonicalTitle(right)
        return a.isNotBlank() && a == b
    }

    private fun canonicalTitle(value: String): String =
        TitleMeaningResolver.canonical(value)
}
