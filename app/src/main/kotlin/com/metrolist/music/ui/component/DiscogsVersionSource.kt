package com.metrolist.music.ui.component

import com.metrolist.music.discogs.DiscogsClient
import com.metrolist.music.discogs.DiscogsCompilationDetail
import com.metrolist.music.discogs.DiscogsReleaseSummary
import com.metrolist.music.discogs.DiscogsTrack
import com.metrolist.music.discogs.DiscogsVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    ): Result<DiscogsVersionPage> = coroutineScope {
        runCatching {
            require(token.isNotBlank()) { "Token Discogs mancante" }
            require(criteria.title.isNotBlank()) { "Titolo mancante" }

            val lockedArtist =
                when (mode) {
                    DiscogsDirectMode.ORIGINAL -> originalArtist.trim().ifBlank { criteria.artist.orEmpty().trim() }
                    DiscogsDirectMode.COVER -> criteria.artist.orEmpty().trim()
                }.takeIf(String::isNotBlank)

            val releasePage = DiscogsClient.searchReleases(
                token = token,
                track = criteria.title,
                artist = lockedArtist,
                releaseTitle = criteria.releaseTitle,
                year = criteria.year,
                format = criteria.format,
                country = criteria.country,
                label = criteria.label,
                genre = criteria.genre,
                style = criteria.style,
                catalogNumber = criteria.catalogNumber,
                page = page.coerceAtLeast(1),
                perPage = perPage.coerceIn(1, 100),
                sort = "year",
                sortOrder = "asc",
            ).getOrThrow()

            val details = releasePage.items
                .chunked(DETAIL_BATCH_SIZE)
                .flatMap { batch ->
                    batch.map { summary ->
                        async(Dispatchers.IO) {
                            DiscogsClient.getRelease(token, summary.id).getOrNull()
                        }
                    }.awaitAll().filterNotNull()
                }

            val seeds = details.flatMap { detail ->
                seedsFromRelease(
                    detail = detail,
                    targetTitle = criteria.title,
                    artistFilter =
                        when (mode) {
                            DiscogsDirectMode.ORIGINAL -> originalArtist
                            DiscogsDirectMode.COVER -> lockedArtist
                        },
                )
            }.filter { seed ->
                when (mode) {
                    DiscogsDirectMode.ORIGINAL ->
                        originalArtist.isNotBlank() && sameArtist(seed.artist, originalArtist)
                    DiscogsDirectMode.COVER ->
                        originalArtist.isBlank() || !sameArtist(seed.artist, originalArtist)
                }
            }

            DiscogsVersionPage(
                items = dedupeVersions(seeds),
                page = releasePage.page,
                pages = releasePage.pages,
                perPage = releasePage.perPage,
                totalDiscogsResults = releasePage.totalItems,
            )
        }
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
