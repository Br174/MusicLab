package com.metrolist.music.ui.component

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer

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

    internal fun sameItalianTitle(originalTitle: String, candidateTitle: String): Boolean {
        val original = canonicalTitle(originalTitle)
        val candidate = canonicalTitle(candidateTitle)
        return original.isNotBlank() && original == candidate
    }

    private fun sameArtist(left: String, right: String): Boolean {
        val a = canonicalTitle(left)
        val b = canonicalTitle(right)
        return a.isNotBlank() && a == b
    }

    private fun canonicalTitle(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}
