package com.metrolist.music.ui.component

import com.metrolist.music.discogs.DiscogsClient
import com.metrolist.music.discogs.DiscogsCompilationDetail
import com.metrolist.music.discogs.DiscogsCredit
import com.metrolist.music.discogs.DiscogsReleaseSummary
import com.metrolist.music.discogs.DiscogsTrack
import com.metrolist.music.discogs.DiscogsVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

internal enum class DiscogsVersionKind {
    STUDIO,
    LIVE,
    REMIX,
    ACOUSTIC,
}

internal enum class DiscogsDirectMode {
    COVER,
    ORIGINAL,
}

internal data class DiscogsVersionSearchCriteria(
    val title: String,
    val artist: String?,
    val releaseTitle: String?,
    val year: Int?,
    val format: String?,
    val country: String?,
    val label: String?,
    val genre: String?,
    val style: String?,
    val catalogNumber: String?,
)

internal data class DiscogsVersionPage(
    val items: List<DiscogsVersionSeed>,
    val page: Int,
    val pages: Int,
    val perPage: Int,
    val totalDiscogsResults: Int,
) {
    val hasNextPage: Boolean
        get() = page < pages
}

internal data class DiscogsVersionSeed(
    val trackTitle: String,
    val artist: String,
    val releaseTitle: String,
    val releaseId: Int,
    val masterId: Int?,
    val year: Int?,
    val releaseDate: String?,
    val kind: DiscogsVersionKind,
    val country: String?,
    val formats: List<String>,
    val formatDescriptions: List<String>,
    val labels: List<String>,
    val coverUrl: String?,
    val durationSeconds: Int?,
    val fingerprint: String,
    val track: DiscogsTrack? = null,
    val videos: List<DiscogsVideo> = emptyList(),
    val credits: List<DiscogsCredit> = emptyList(),
    val language: String? = null,
    val confidenceScore: Int = 1,
    val confidenceReasons: List<String> = emptyList(),
    val resolvedVideoId: String? = null,
    val resolvedVideoTitle: String? = null,
    val resolvedVideoSource: String? = null,
    val videoResolutionChecked: Boolean = false,
    val sourceNames: List<String> = listOf("Discogs"),
    val sourceUrl: String? = null,
    val discogsVerificationChecked: Boolean = false,
) {
    val discogsUrl: String
        get() = "https://www.discogs.com/release/$releaseId"

    val displayDate: String?
        get() = formatDiscogsPublicationDate(releaseDate, year)
}

internal object DiscogsVersionSource {
    private data class CacheEntry(
        val expiresAtMs: Long,
        val value: List<DiscogsVersionSeed>,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun loadVersionPage(
        token: String,
        mode: DiscogsDirectMode,
        criteria: DiscogsVersionSearchCriteria,
        originalArtist: String,
        page: Int,
        perPage: Int = DIRECT_PAGE_SIZE,
        sort: String? = "year",
        sortOrder: String? = "asc",
    ): Result<DiscogsVersionPage> = coroutineScope {
        runCatching {
            require(token.isNotBlank()) { "Token Discogs mancante" }
            require(criteria.title.isNotBlank()) { "Titolo mancante" }

            val releasePage = DiscogsClient.searchReleases(
                token = token,
                track = criteria.title,
                artist = null,
                page = page.coerceAtLeast(1),
                perPage = perPage.coerceIn(1, 100),
                sort = sort,
                sortOrder = sortOrder,
            ).getOrThrow()

            // LAB38A: no candidate is thrown away merely because detailed verification
            // has not happened yet. Search summaries enter at low confidence (1/10)
            // and are verified later by the background Discogs verifier. Originali
            // remains strict about the performer when the search summary names one.
            val candidates =
                releasePage.items.mapNotNull { summary ->
                    val seed = seedFromSearchSummary(
                        summary = summary,
                        targetTitle = criteria.title,
                        mode = mode,
                        originalArtist = originalArtist,
                    ) ?: return@mapNotNull null
                    if (
                        mode == DiscogsDirectMode.ORIGINAL &&
                        !isGenericArtist(seed.artist) &&
                        !sameArtist(seed.artist, originalArtist)
                    ) {
                        null
                    } else {
                        seed
                    }
                }

            DiscogsVersionPage(
                items = dedupeVersions(candidates),
                page = releasePage.page,
                pages = releasePage.pages,
                perPage = releasePage.perPage,
                totalDiscogsResults = releasePage.totalItems,
            )
        }
    }


    private suspend fun verifyReleaseCandidates(
        token: String,
        summaries: List<DiscogsReleaseSummary>,
        targetTitle: String,
        mode: DiscogsDirectMode,
        originalArtist: String,
        requiredTrackArtist: String?,
    ): List<DiscogsVersionSeed> = coroutineScope {
        if (summaries.isEmpty()) return@coroutineScope emptyList()

        val verified = mutableListOf<DiscogsVersionSeed>()
        summaries
            .chunked(DIRECT_VERIFY_BATCH_SIZE)
            .forEachIndexed { batchIndex, batch ->
                val details =
                    batch.map { summary ->
                        async(Dispatchers.IO) {
                            DiscogsClient.getRelease(
                                token = token,
                                releaseId = summary.id,
                                includeMasterVideos = false,
                            ).getOrNull()
                        }
                    }.awaitAll().filterNotNull()

                details.forEach { detail ->
                    seedsFromRelease(
                        detail = detail,
                        targetTitle = targetTitle,
                        artistFilter = requiredTrackArtist,
                    ).forEach { detailed ->
                        val scored = recalculateConfidence(
                            seed = detailed,
                            mode = mode,
                            originalArtist = originalArtist,
                        )
                        if (isVerifiedDirectSeed(scored) && directModeAdmits(scored, mode, originalArtist)) {
                            verified += scored
                        }
                    }
                }

                if ((batchIndex + 1) * DIRECT_VERIFY_BATCH_SIZE < summaries.size) {
                    delay(DIRECT_VERIFY_PACE_MS)
                }
            }

        dedupeVersions(verified)
    }

    internal fun isVerifiedDirectSeed(seed: DiscogsVersionSeed): Boolean =
        seed.track != null && seed.confidenceScore > 0

    internal fun isDisplayableDirectSeed(seed: DiscogsVersionSeed): Boolean =
        seed.confidenceScore in 1..10

    private fun directModeAdmits(
        seed: DiscogsVersionSeed,
        mode: DiscogsDirectMode,
        originalArtist: String,
    ): Boolean =
        when (mode) {
            DiscogsDirectMode.COVER ->
                !sameArtist(seed.artist, originalArtist) ||
                    seed.kind != DiscogsVersionKind.STUDIO
            DiscogsDirectMode.ORIGINAL ->
                sameArtist(seed.artist, originalArtist)
        }

    suspend fun loadOriginalWorkCredits(
        token: String,
        title: String,
        originalArtist: String,
    ): List<DiscogsCredit> {
        if (token.isBlank() || title.isBlank() || originalArtist.isBlank()) return emptyList()
        val page = DiscogsClient.searchReleases(
            token = token,
            track = title,
            artist = originalArtist,
            page = 1,
            perPage = 8,
            sort = "year",
            sortOrder = "asc",
        ).getOrNull() ?: return emptyList()

        page.items.forEach { summary ->
            val detail = DiscogsClient.getRelease(
                token = token,
                releaseId = summary.id,
                includeMasterVideos = false,
            ).getOrNull() ?: return@forEach
            val seed = seedsFromRelease(
                detail = detail,
                targetTitle = title,
                artistFilter = originalArtist,
            ).firstOrNull() ?: return@forEach
            if (seed.credits.isNotEmpty()) return seed.credits
        }
        return emptyList()
    }

    suspend fun loadReferenceSeeds(
        token: String,
        referenceTitle: String,
        referenceArtist: String,
        originalArtist: String,
        adaptedTitle: Boolean,
        page: Int = 1,
        perPage: Int = 12,
    ): List<DiscogsVersionSeed> {
        if (token.isBlank() || referenceTitle.isBlank() || referenceArtist.isBlank()) return emptyList()
        val releasePage = DiscogsClient.searchReleases(
            token = token,
            track = referenceTitle,
            artist = referenceArtist,
            page = page.coerceAtLeast(1),
            perPage = perPage.coerceIn(1, 30),
            sort = null,
            sortOrder = null,
        ).getOrNull() ?: return emptyList()

        // AI is only a scout: each suggested artist/title pair must exist as an exact
        // Discogs track before it can become a visible MusicLab result.
        return verifyReleaseCandidates(
            token = token,
            summaries = releasePage.items,
            targetTitle = referenceTitle,
            mode = DiscogsDirectMode.COVER,
            originalArtist = originalArtist,
            requiredTrackArtist = referenceArtist,
        ).map { verified ->
            verified.copy(
                language = if (adaptedTitle) "altra lingua / adattamento" else null,
                confidenceReasons = (
                    verified.confidenceReasons +
                        if (adaptedTitle) {
                            "AI scout ha proposto un adattamento; Discogs conferma solo l'esistenza della registrazione"
                        } else {
                            "AI scout ha proposto il candidato; Discogs conferma l'esistenza della registrazione"
                        }
                    ).distinct(),
            )
        }
    }

    suspend fun enrichSeedMetadata(
        token: String,
        seed: DiscogsVersionSeed,
        targetTitle: String,
        mode: DiscogsDirectMode,
        originalArtist: String,
    ): DiscogsVersionSeed {
        if (seed.releaseId <= 0) return seed.copy(discogsVerificationChecked = true)

        val detail = DiscogsClient.getRelease(
            token = token,
            releaseId = seed.releaseId,
            includeMasterVideos = false,
        ).getOrNull() ?: return seed.copy(
            discogsVerificationChecked = true,
            confidenceScore = seed.confidenceScore.coerceIn(1, 10),
            confidenceReasons = (seed.confidenceReasons + "Verifica Discogs temporaneamente non disponibile").distinct(),
        )

        val candidates = seedsFromRelease(
            detail = detail,
            targetTitle = targetTitle,
            artistFilter = if (mode == DiscogsDirectMode.ORIGINAL) originalArtist else null,
        )
        val detailed =
            candidates.firstOrNull { candidate -> sameArtist(candidate.artist, seed.artist) }
                ?: candidates.firstOrNull()

        return if (detailed != null) {
            val recalculated =
                recalculateConfidence(
                    seed = detailed.copy(
                        language = seed.language ?: detailed.language,
                        sourceNames = (seed.sourceNames + detailed.sourceNames).distinct(),
                        sourceUrl = seed.sourceUrl ?: detailed.sourceUrl,
                        confidenceReasons = (seed.confidenceReasons + detailed.confidenceReasons).distinct(),
                        discogsVerificationChecked = true,
                    ),
                    mode = mode,
                    originalArtist = originalArtist,
                )
            mergeEvidence(seed, recalculated)
        } else {
            seed.copy(
                releaseDate = detail.releaseDate ?: seed.releaseDate,
                year = detail.year ?: seed.year,
                country = detail.country ?: seed.country,
                formats = detail.formats.ifEmpty { seed.formats },
                formatDescriptions = detail.formatDescriptions.ifEmpty { seed.formatDescriptions },
                labels = detail.labels.ifEmpty { seed.labels },
                coverUrl = detail.coverUrl ?: seed.coverUrl,
                videos = detail.videos,
                credits = detail.credits,
                discogsVerificationChecked = true,
                confidenceScore = seed.confidenceScore.coerceAtLeast(1),
                confidenceReasons =
                    (seed.confidenceReasons + "Tracklist controllata: corrispondenza non confermata").distinct(),
            )
        }
    }

    suspend fun resolveSeedForPlayback(
        token: String,
        seed: DiscogsVersionSeed,
        targetTitle: String,
        mode: DiscogsDirectMode,
        originalArtist: String,
    ): DiscogsVersionSeed? {
        val enriched = enrichSeedMetadata(
            token = token,
            seed = seed,
            targetTitle = targetTitle,
            mode = mode,
            originalArtist = originalArtist,
        )
        return enriched.takeIf { it.track != null }
    }

    private fun seedFromSearchSummary(
        summary: DiscogsReleaseSummary,
        targetTitle: String,
        mode: DiscogsDirectMode,
        originalArtist: String,
    ): DiscogsVersionSeed? {
        val (parsedArtist, parsedReleaseTitle) = splitSearchTitle(summary.title)
        val artist =
            when (mode) {
                DiscogsDirectMode.ORIGINAL ->
                    parsedArtist.takeIf(String::isNotBlank)
                        ?: originalArtist.trim()
                DiscogsDirectMode.COVER -> parsedArtist
            }.trim()
        if (artist.isBlank()) return null

        val kind = classify(
            trackTitle = targetTitle,
            releaseTitle = parsedReleaseTitle,
            formats = summary.formats,
            descriptions = emptyList(),
            styles = summary.styles,
        )
        val fingerprint = summaryVersionFingerprint(
            trackTitle = targetTitle,
            artist = artist,
            releaseTitle = parsedReleaseTitle,
            masterId = summary.masterId,
            kind = kind,
        )
        val confidence = initialConfidence(
            mode = mode,
            artist = artist,
            originalArtist = originalArtist,
            kind = kind,
            hasMaster = summary.masterId != null,
        )

        return DiscogsVersionSeed(
            trackTitle = targetTitle,
            artist = artist,
            releaseTitle = parsedReleaseTitle,
            releaseId = summary.id,
            masterId = summary.masterId,
            year = summary.year,
            releaseDate = summary.releaseDate,
            kind = kind,
            country = summary.country,
            formats = summary.formats,
            formatDescriptions = emptyList(),
            labels = summary.labels,
            coverUrl = summary.coverUrl ?: summary.thumbnailUrl,
            durationSeconds = null,
            fingerprint = fingerprint,
            track = null,
            videos = emptyList(),
            confidenceScore = 1,
            confidenceReasons = confidence.second + "Candidato Discogs: in attesa di verifica tracklist",
            sourceNames = listOf("Discogs"),
            discogsVerificationChecked = false,
        )
    }

    private fun splitSearchTitle(value: String): Pair<String, String> {
        val marker = " - "
        val index = value.indexOf(marker)
        return if (index > 0) {
            value.substring(0, index).trim() to value.substring(index + marker.length).trim()
        } else {
            "" to value.trim()
        }
    }

    private fun summaryVersionFingerprint(
        trackTitle: String,
        artist: String,
        releaseTitle: String,
        masterId: Int?,
        kind: DiscogsVersionKind,
    ): String {
        val releaseGroup =
            if (kind == DiscogsVersionKind.STUDIO) {
                ""
            } else {
                masterId?.let { "m$it" } ?: canonicalReleaseContext(releaseTitle)
            }
        val qualifier = versionQualifier(releaseTitle)
        return listOf(
            canonicalBaseTitle(trackTitle),
            canonicalArtist(artist),
            kind.name.lowercase(),
            releaseGroup,
            qualifier,
        ).joinToString("|")
    }


    suspend fun discoverCoverVersions(
        token: String,
        title: String,
        originalArtist: String,
        maxDetails: Int = COVER_DETAIL_LIMIT,
    ): List<DiscogsVersionSeed> {
        if (token.isBlank() || title.isBlank()) return emptyList()
        val key = "cover|${canonical(title)}|${canonical(originalArtist)}|$maxDetails"
        cache[key]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let { return it.value }

        val result = discover(
            token = token,
            title = title,
            artistFilter = null,
            maxDetails = maxDetails,
        ).filter { seed ->
            !sameArtist(seed.artist, originalArtist)
        }

        cache[key] = CacheEntry(System.currentTimeMillis() + CACHE_TTL_MS, result)
        return result
    }

    suspend fun discoverOriginalVersions(
        token: String,
        title: String,
        originalArtist: String,
        maxDetails: Int = ORIGINAL_DETAIL_LIMIT,
    ): List<DiscogsVersionSeed> {
        if (token.isBlank() || title.isBlank() || originalArtist.isBlank()) return emptyList()
        val key = "original|${canonical(title)}|${canonical(originalArtist)}|$maxDetails"
        cache[key]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let { return it.value }

        val result = discover(
            token = token,
            title = title,
            artistFilter = originalArtist,
            maxDetails = maxDetails,
        ).filter { seed ->
            sameArtist(seed.artist, originalArtist)
        }

        cache[key] = CacheEntry(System.currentTimeMillis() + CACHE_TTL_MS, result)
        return result
    }

    suspend fun discoverTitleHints(
        token: String,
        title: String,
        maxDetails: Int = IDENTITY_DETAIL_LIMIT,
    ): List<DiscogsVersionSeed> {
        if (token.isBlank() || title.isBlank()) return emptyList()
        val key = "identity|${canonical(title)}|$maxDetails"
        cache[key]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let { return it.value }

        val result = discover(
            token = token,
            title = title,
            artistFilter = null,
            maxDetails = maxDetails,
        )
        cache[key] = CacheEntry(System.currentTimeMillis() + CACHE_TTL_MS, result)
        return result
    }

    fun toCoverCandidate(seed: DiscogsVersionSeed): AiCoverCandidate =
        AiCoverCandidate(
            title = seed.trackTitle,
            artist = seed.artist,
            category = when (seed.kind) {
                DiscogsVersionKind.LIVE -> AiCoverCategory.LIVE
                DiscogsVersionKind.REMIX -> AiCoverCategory.REMIX
                DiscogsVersionKind.ACOUSTIC,
                DiscogsVersionKind.STUDIO,
                -> AiCoverCategory.COVER
            },
            year = seed.year,
            yearSource = "discogs",
            album = seed.releaseTitle,
            label = seed.labels.firstOrNull(),
            sameWorkScore = 98,
            versionTypeScore = 94,
            brainStatus = AiBrainDecisionStatus.PROBABLE,
            brainAdmission = "discogs_release:${seed.releaseId}",
            brainSignals = listOf(
                AiBrainSignal(
                    kind = "discogs_release_track_match",
                    strength = "very_strong",
                    direction = "positive",
                ),
            ),
            releaseDate = seed.releaseDate,
            discogsReleaseId = seed.releaseId,
            discogsMasterId = seed.masterId,
            discogsReleaseTitle = seed.releaseTitle,
            versionFingerprint = seed.fingerprint,
        )

    private suspend fun discover(
        token: String,
        title: String,
        artistFilter: String?,
        maxDetails: Int,
    ): List<DiscogsVersionSeed> = coroutineScope {
        val firstPage = DiscogsClient.searchReleases(
            token = token,
            track = title,
            artist = artistFilter,
            page = 1,
            perPage = 100,
            sort = "year",
            sortOrder = "asc",
        ).getOrNull() ?: return@coroutineScope emptyList()

        val summaries = buildList {
            addAll(firstPage.items)
            if (size < maxDetails && firstPage.hasNextPage) {
                DiscogsClient.searchReleases(
                    token = token,
                    track = title,
                    artist = artistFilter,
                    page = 2,
                    perPage = 100,
                    sort = "year",
                    sortOrder = "asc",
                ).getOrNull()?.items?.let(::addAll)
            }
        }

        val selected = chooseDetailCandidates(summaries, maxDetails)
        val details = mutableListOf<DiscogsCompilationDetail>()
        for (batch in selected.chunked(DETAIL_BATCH_SIZE)) {
            details += batch.map { summary ->
                async(Dispatchers.IO) {
                    DiscogsClient.getRelease(token, summary.id).getOrNull()
                }
            }.awaitAll().filterNotNull()
        }

        dedupeVersions(
            details.flatMap { detail ->
                seedsFromRelease(
                    detail = detail,
                    targetTitle = title,
                    artistFilter = artistFilter,
                )
            },
        )
    }

    private fun chooseDetailCandidates(
        summaries: List<DiscogsReleaseSummary>,
        maxDetails: Int,
    ): List<DiscogsReleaseSummary> {
        val seenGroups = mutableSetOf<String>()
        val selected = mutableListOf<DiscogsReleaseSummary>()

        summaries
            .sortedWith(
                compareBy<DiscogsReleaseSummary> { it.year ?: Int.MAX_VALUE }
                    .thenBy { it.masterId ?: Int.MAX_VALUE }
                    .thenBy { canonical(it.title) },
            )
            .forEach { summary ->
                val group = summary.masterId?.let { "master:$it" }
                    ?: "release:${canonical(summary.title)}|${summary.year ?: 0}"
                if (seenGroups.add(group) || selected.size < MIN_UNGROUPED_DETAIL_COUNT) {
                    selected += summary
                }
                if (selected.size >= maxDetails.coerceIn(1, 50)) return@forEach
            }

        return selected.take(maxDetails.coerceIn(1, 50))
    }

    private fun seedsFromRelease(
        detail: DiscogsCompilationDetail,
        targetTitle: String,
        artistFilter: String?,
    ): List<DiscogsVersionSeed> {
        val target = canonicalBaseTitle(targetTitle)
        if (target.isBlank()) return emptyList()

        return detail.tracks.mapNotNull { track ->
            val trackBase = canonicalBaseTitle(track.title)
            if (!titlesMatch(trackBase, target)) return@mapNotNull null

            val artist = track.artists.firstOrNull()?.trim().orEmpty()
            if (artist.isBlank()) return@mapNotNull null
            if (!artistFilter.isNullOrBlank() && !sameArtist(artist, artistFilter)) return@mapNotNull null

            val kind = classify(
                trackTitle = track.title,
                releaseTitle = detail.title,
                formats = detail.formats,
                descriptions = detail.formatDescriptions,
                styles = detail.styles,
            )
            val fingerprint = versionFingerprint(
                trackTitle = track.title,
                artist = artist,
                releaseTitle = detail.title,
                masterId = detail.masterId,
                kind = kind,
                durationSeconds = track.durationSeconds,
            )

            DiscogsVersionSeed(
                trackTitle = track.title,
                artist = artist,
                releaseTitle = detail.title,
                releaseId = detail.id,
                masterId = detail.masterId,
                year = detail.year,
                releaseDate = detail.releaseDate,
                kind = kind,
                country = detail.country,
                formats = detail.formats,
                formatDescriptions = detail.formatDescriptions,
                labels = detail.labels,
                coverUrl = detail.coverUrl,
                durationSeconds = track.durationSeconds,
                fingerprint = fingerprint,
                track = track,
                videos = detail.videos,
                sourceNames = listOf("Discogs"),
                discogsVerificationChecked = true,
                credits = (track.credits + detail.credits).distinctBy { credit ->
                    listOf(credit.name.lowercase(), credit.role.lowercase(), credit.tracks.orEmpty().lowercase())
                        .joinToString("|")
                },
            )
        }
    }

    internal fun dedupeVersions(
        seeds: List<DiscogsVersionSeed>,
    ): List<DiscogsVersionSeed> {
        if (seeds.isEmpty()) return emptyList()
        return seeds
            .groupBy { it.fingerprint }
            .values
            .map { sameVersion ->
                sameVersion.minWithOrNull(
                    compareBy<DiscogsVersionSeed> { it.year ?: Int.MAX_VALUE }
                        .thenByDescending { publicationPrecision(it.releaseDate) }
                        .thenBy { it.releaseDate ?: "9999-99-99" }
                        .thenByDescending { metadataRichness(it) },
                ) ?: sameVersion.first()
            }
            .sortedWith(
                compareBy<DiscogsVersionSeed> { it.year ?: Int.MAX_VALUE }
                    .thenBy { it.releaseDate ?: "9999-99-99" }
                    .thenBy { canonical(it.artist) }
                    .thenBy { canonical(it.releaseTitle) },
            )
    }

    private fun versionFingerprint(
        trackTitle: String,
        artist: String,
        releaseTitle: String,
        masterId: Int?,
        kind: DiscogsVersionKind,
        durationSeconds: Int?,
    ): String {
        val baseTitle = canonicalBaseTitle(trackTitle)
        val artistKey = canonical(artist)
        val qualifier = versionQualifier(trackTitle)
        val durationBucket = durationSeconds?.let { (it / 3).toString() }.orEmpty()
        val distinctVersionContext =
            when (kind) {
                DiscogsVersionKind.STUDIO -> ""
                DiscogsVersionKind.LIVE,
                DiscogsVersionKind.REMIX,
                DiscogsVersionKind.ACOUSTIC,
                -> canonicalReleaseContext(releaseTitle)
            }

        return listOf(
            baseTitle,
            artistKey,
            kind.name.lowercase(),
            qualifier,
            durationBucket,
            distinctVersionContext,
        ).joinToString("|")
    }

    private fun classify(
        trackTitle: String,
        releaseTitle: String,
        formats: List<String>,
        descriptions: List<String>,
        styles: List<String>,
    ): DiscogsVersionKind {
        val text = listOf(trackTitle, releaseTitle)
            .plus(formats)
            .plus(descriptions)
            .plus(styles)
            .joinToString(" ")
            .lowercase()

        return when {
            REMIX_REGEX.containsMatchIn(text) -> DiscogsVersionKind.REMIX
            LIVE_REGEX.containsMatchIn(text) -> DiscogsVersionKind.LIVE
            ACOUSTIC_REGEX.containsMatchIn(text) -> DiscogsVersionKind.ACOUSTIC
            else -> DiscogsVersionKind.STUDIO
        }
    }

    private fun versionQualifier(value: String): String {
        val canonicalValue = canonical(value)
        return VERSION_QUALIFIER_REGEX.findAll(canonicalValue)
            .joinToString("_") { it.value.trim().replace(' ', '_') }
    }

    private fun canonicalReleaseContext(value: String): String =
        canonical(value)
            .replace(REISSUE_NOISE_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun canonicalBaseTitle(value: String): String =
        canonical(value)
            .replace(VERSION_NOISE_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun titlesMatch(candidate: String, target: String): Boolean {
        if (candidate.isBlank() || target.isBlank()) return false
        if (candidate == target) return true
        return candidate.startsWith("$target ") ||
            candidate.endsWith(" $target") ||
            target.startsWith("$candidate ") ||
            target.endsWith(" $candidate")
    }

    private fun sameArtist(left: String, right: String): Boolean {
        val a = canonicalArtist(left)
        val b = canonicalArtist(right)
        if (a.isBlank() || b.isBlank()) return false
        return a == b ||
            a.startsWith("$b ") ||
            a.endsWith(" $b") ||
            b.startsWith("$a ") ||
            b.endsWith(" $a")
    }

    private fun canonicalArtist(value: String): String =
        canonical(value)
            .removePrefix("the ")
            .replace(ARTIST_SUFFIX_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    internal fun externalSeed(
        candidate: CoverSourceCandidate,
    ): DiscogsVersionSeed {
        val kind =
            when (candidate.category) {
                AiCoverCategory.LIVE -> DiscogsVersionKind.LIVE
                AiCoverCategory.REMIX -> DiscogsVersionKind.REMIX
                AiCoverCategory.COVER,
                AiCoverCategory.FOREIGN,
                -> DiscogsVersionKind.STUDIO
            }
        val fingerprint =
            listOf(
                "external",
                canonicalBaseTitle(candidate.title),
                canonicalArtist(candidate.artist),
                kind.name.lowercase(),
            ).joinToString("|")
        return DiscogsVersionSeed(
            trackTitle = candidate.title,
            artist = candidate.artist,
            releaseTitle = candidate.album ?: candidate.sources.joinToString(" + "),
            releaseId = 0,
            masterId = null,
            year = candidate.year,
            releaseDate = candidate.year?.toString(),
            kind = kind,
            country = null,
            formats = emptyList(),
            formatDescriptions = emptyList(),
            labels = emptyList(),
            coverUrl = candidate.coverUrl,
            durationSeconds = candidate.durationSeconds,
            fingerprint = fingerprint,
            track = null,
            videos = emptyList(),
            credits = emptyList(),
            language = candidate.language,
            confidenceScore = candidate.evidenceScore.coerceIn(1, 10),
            confidenceReasons =
                listOf("Trovata da: " + candidate.sources.joinToString(", ")),
            sourceNames = candidate.sources.distinct(),
            sourceUrl = candidate.sourceUrl,
            discogsVerificationChecked = true,
        )
    }

    internal fun identityKey(seed: DiscogsVersionSeed): String =
        listOf(
            canonicalBaseTitle(seed.trackTitle),
            canonicalArtist(seed.artist),
            seed.kind.name.lowercase(),
        ).joinToString("|")

    internal fun mergeEvidence(
        existing: DiscogsVersionSeed,
        incoming: DiscogsVersionSeed,
    ): DiscogsVersionSeed {
        val sources = (existing.sourceNames + incoming.sourceNames).distinct()
        val newlyIndependent =
            (sources.size - maxOf(existing.sourceNames.size, incoming.sourceNames.size))
                .coerceAtLeast(0)
        val incomingIsRicher =
            (incoming.track != null && existing.track == null) ||
                (incoming.releaseId > 0 && existing.releaseId <= 0)

        val base = if (incomingIsRicher) incoming else existing
        val other = if (incomingIsRicher) existing else incoming
        val mergedScore =
            (maxOf(existing.confidenceScore, incoming.confidenceScore) + newlyIndependent)
                .coerceIn(1, 10)

        return base.copy(
            fingerprint = existing.fingerprint,
            releaseId = base.releaseId.takeIf { it > 0 } ?: other.releaseId,
            masterId = base.masterId ?: other.masterId,
            releaseTitle = base.releaseTitle.ifBlank { other.releaseTitle },
            year = base.year ?: other.year,
            releaseDate = base.releaseDate ?: other.releaseDate,
            country = base.country ?: other.country,
            formats = if (base.formats.isNotEmpty()) base.formats else other.formats,
            formatDescriptions =
                if (base.formatDescriptions.isNotEmpty()) base.formatDescriptions else other.formatDescriptions,
            labels = if (base.labels.isNotEmpty()) base.labels else other.labels,
            coverUrl = base.coverUrl ?: other.coverUrl,
            durationSeconds = base.durationSeconds ?: other.durationSeconds,
            track = base.track ?: other.track,
            videos = if (base.videos.isNotEmpty()) base.videos else other.videos,
            credits = (base.credits + other.credits).distinctBy {
                listOf(it.name.lowercase(), it.role.lowercase(), it.tracks.orEmpty().lowercase()).joinToString("|")
            },
            language = base.language ?: other.language,
            confidenceScore = mergedScore,
            confidenceReasons = (existing.confidenceReasons + incoming.confidenceReasons).distinct(),
            resolvedVideoId = existing.resolvedVideoId ?: incoming.resolvedVideoId,
            resolvedVideoTitle = existing.resolvedVideoTitle ?: incoming.resolvedVideoTitle,
            resolvedVideoSource = existing.resolvedVideoSource ?: incoming.resolvedVideoSource,
            videoResolutionChecked = existing.videoResolutionChecked || incoming.videoResolutionChecked,
            sourceNames = sources,
            sourceUrl = existing.sourceUrl ?: incoming.sourceUrl,
            discogsVerificationChecked =
                existing.discogsVerificationChecked || incoming.discogsVerificationChecked,
        )
    }

    internal fun applySharedWorkCreditEvidence(
        seed: DiscogsVersionSeed,
        originalCredits: List<DiscogsCredit>,
    ): DiscogsVersionSeed {
        if (originalCredits.isEmpty() || seed.credits.isEmpty()) return seed

        fun relevant(credit: DiscogsCredit): Boolean =
            CREDIT_IDENTITY_REGEX.containsMatchIn(credit.role.lowercase())

        val originalNames = originalCredits
            .filter(::relevant)
            .map { canonical(it.name) }
            .filter(String::isNotBlank)
            .toSet()
        val candidateNames = seed.credits
            .filter(::relevant)
            .map { canonical(it.name) }
            .filter(String::isNotBlank)
            .toSet()
        val shared = originalNames.intersect(candidateNames)
        if (shared.isEmpty()) return seed

        return seed.copy(
            // Evidence may become available after the row is already visible. Keep the
            // score stable so background enrichment never makes the card jump position.
            confidenceReasons = (
                seed.confidenceReasons +
                    "Crediti dell'opera coincidenti: " + shared.joinToString(", ")
                ).distinct(),
        )
    }

    internal fun markVideoResolved(
        seed: DiscogsVersionSeed,
        videoId: String,
        videoTitle: String,
        source: String,
    ): DiscogsVersionSeed =
        seed.copy(
            resolvedVideoId = videoId,
            resolvedVideoTitle = videoTitle,
            resolvedVideoSource = source,
            videoResolutionChecked = true,
            confidenceReasons = (seed.confidenceReasons + "Video unico verificato: $source").distinct(),
        )

    internal fun markVideoUnavailable(seed: DiscogsVersionSeed): DiscogsVersionSeed =
        seed.copy(
            resolvedVideoId = null,
            resolvedVideoTitle = null,
            resolvedVideoSource = null,
            videoResolutionChecked = true,
        )

    private fun initialConfidence(
        mode: DiscogsDirectMode,
        artist: String,
        originalArtist: String,
        kind: DiscogsVersionKind,
        hasMaster: Boolean,
    ): Pair<Int, List<String>> {
        var score = 1
        val reasons = mutableListOf<String>()
        val genericArtist = isGenericArtist(artist)

        if (!genericArtist) {
            score += 2
            reasons += "Interprete nominativo"
        } else {
            reasons += "Interprete generico/Various: da verificare nella tracklist"
        }

        when (mode) {
            DiscogsDirectMode.COVER -> {
                if (!genericArtist && !sameArtist(artist, originalArtist)) {
                    score += 3
                    reasons += "Interprete diverso dall'originale"
                } else if (!genericArtist && kind != DiscogsVersionKind.STUDIO) {
                    score += 2
                    reasons += "Stesso interprete ma variante esplicita"
                }
            }
            DiscogsDirectMode.ORIGINAL -> {
                if (!genericArtist && sameArtist(artist, originalArtist)) {
                    score += 3
                    reasons += "Interprete originale coincidente"
                }
            }
        }

        if (kind != DiscogsVersionKind.STUDIO) {
            score += 1
            reasons += "Tipo di versione distinto: ${kind.name.lowercase()}"
        }
        if (hasMaster) {
            score += 1
            reasons += "Relazione Master Discogs"
        }
        return score.coerceIn(1, 8) to reasons.distinct()
    }

    private fun recalculateConfidence(
        seed: DiscogsVersionSeed,
        mode: DiscogsDirectMode,
        originalArtist: String,
    ): DiscogsVersionSeed {
        if (seed.track == null) {
            return seed.copy(
                confidenceScore = seed.confidenceScore.coerceIn(1, 10),
                confidenceReasons = (seed.confidenceReasons + "Candidato non ancora confermato dalla tracklist").distinct(),
            )
        }

        var score = 5
        val reasons = seed.confidenceReasons.toMutableList()
        reasons += "Traccia esatta verificata nella release"

        if (!isGenericArtist(seed.artist)) {
            score += 1
            reasons += "Interprete verificato nella tracklist"
        }

        when (mode) {
            DiscogsDirectMode.COVER -> {
                if (!isGenericArtist(seed.artist) && !sameArtist(seed.artist, originalArtist)) {
                    score += 2
                    reasons += "Cover: interprete realmente diverso"
                } else if (
                    !isGenericArtist(seed.artist) &&
                    sameArtist(seed.artist, originalArtist) &&
                    seed.kind != DiscogsVersionKind.STUDIO
                ) {
                    score += 1
                    reasons += "Versione alternativa dell'interprete originale"
                }
            }
            DiscogsDirectMode.ORIGINAL -> {
                if (!isGenericArtist(seed.artist) && sameArtist(seed.artist, originalArtist)) {
                    score += 2
                    reasons += "Originali: interprete verificato nella tracklist"
                }
            }
        }

        if (seed.kind != DiscogsVersionKind.STUDIO) {
            score += 1
            reasons += "Tipo di versione distinto: ${seed.kind.name.lowercase()}"
        }

        val strongCredit = seed.credits.any { credit ->
            CREDIT_IDENTITY_REGEX.containsMatchIn(credit.role.lowercase())
        }
        if (strongCredit) {
            score += 1
            reasons += "Crediti autore/compositore/adattamento presenti"
        }

        return seed.copy(
            confidenceScore = score.coerceIn(1, 10),
            confidenceReasons = reasons.distinct(),
        )
    }

    private fun isGenericArtist(value: String): Boolean {
        val normalized = canonical(value)
        return normalized.isBlank() ||
            normalized == "various" ||
            normalized == "various artists" ||
            normalized == "unknown" ||
            normalized == "artisti vari"
    }

    private fun metadataRichness(seed: DiscogsVersionSeed): Int =
        (if (!seed.releaseDate.isNullOrBlank()) 4 else 0) +
            (if (!seed.coverUrl.isNullOrBlank()) 2 else 0) +
            seed.labels.size.coerceAtMost(2) +
            seed.formats.size.coerceAtMost(2) +
            seed.formatDescriptions.size.coerceAtMost(2)

    private fun publicationPrecision(value: String?): Int =
        when {
            value.isNullOrBlank() -> 0
            Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value) -> 3
            Regex("^\\d{4}-\\d{2}$").matches(value) -> 2
            Regex("^\\d{4}$").matches(value) -> 1
            else -> 0
        }

    private fun canonical(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private val CREDIT_IDENTITY_REGEX =
        Regex("\\b(written|writer|songwriter|composed|composer|lyrics|lyricist|adapted|adapter|translated|translator|music by|words by)\\b")
    private val VERSION_NOISE_REGEX =
        Regex("\\b(official|music video|video|audio|lyrics?|lyric|visualizer|remaster(?:ed)?|version|versione|cover|live|dal vivo|concert|concerto|performance|session|festival|remix|mix|rework|radio edit|extended mix|club mix|edit|acoustic|unplugged|mono|stereo|hd|hq)\\b")
    private val VERSION_QUALIFIER_REGEX =
        Regex("\\b(live|remix|mix|rework|radio edit|extended mix|club mix|edit|acoustic|unplugged|mono|stereo|remaster(?:ed)?|session|performance)\\b")
    private val LIVE_REGEX =
        Regex("\\b(live|dal vivo|concert|concerto|performance|session|festival|unplugged|bbc|radio|tv)\\b")
    private val REMIX_REGEX =
        Regex("\\b(remix|rework|club mix|extended mix|radio mix|dance mix|dub mix|edit mix)\\b")
    private val ACOUSTIC_REGEX = Regex("\\b(acoustic|unplugged|acustic[oa])\\b")
    private val REISSUE_NOISE_REGEX =
        Regex("\\b(reissue|repress|remaster(?:ed)?|anniversary|deluxe|edition|edizione|promo|stereo|mono|vinyl|cd|cassette|digital)\\b")
    private val ARTIST_SUFFIX_REGEX = Regex("\\s+\\(\\d+\\)$")

    private const val DIRECT_PAGE_SIZE = 20
    private const val DIRECT_VERIFY_BATCH_SIZE = 4
    private const val DIRECT_VERIFY_PACE_MS = 1_050L
    private const val DETAIL_BATCH_SIZE = 4
    private const val MIN_UNGROUPED_DETAIL_COUNT = 12
    private const val COVER_DETAIL_LIMIT = 30
    private const val ORIGINAL_DETAIL_LIMIT = 24
    private const val IDENTITY_DETAIL_LIMIT = 10
    private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
}

internal fun formatDiscogsPublicationDate(
    releaseDate: String?,
    fallbackYear: Int?,
): String? {
    val value = releaseDate?.trim().orEmpty()
    return when {
        Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value) -> {
            val parts = value.split("-")
            "${parts[2]}/${parts[1]}/${parts[0]}"
        }
        Regex("^\\d{4}-\\d{2}$").matches(value) -> {
            val parts = value.split("-")
            "${parts[1]}/${parts[0]}"
        }
        Regex("^\\d{4}$").matches(value) -> value
        fallbackYear != null -> fallbackYear.toString()
        else -> null
    }
}
