/** MusicLab: AI-first search for the original performer and all of that performer's versions. */
package com.metrolist.music.ui.component

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.text.Normalizer

internal enum class OriginalSearchStageStatus { OK, NO_RESULTS, ERROR, NOT_RUN }
internal enum class OriginalAiStatus { OK, NOT_CONFIGURED, NO_ANSWER, ERROR }

internal data class OriginalVersionDiagnostics(
    val aiStatus: OriginalAiStatus = OriginalAiStatus.NOT_CONFIGURED,
    val aiMode: GeminiOriginalMode? = null,
    val aiWebSources: Int = 0,
    val aiTitle: String = "",
    val aiArtists: List<String> = emptyList(),
    val aiYear: Int? = null,
    val youtubeMusicStatus: OriginalSearchStageStatus = OriginalSearchStageStatus.NOT_RUN,
    val youtubeMusicFound: Int = 0,
    val youtubeMusicPages: Int = 0,
    val youtubeStatus: OriginalSearchStageStatus = OriginalSearchStageStatus.NOT_RUN,
    val youtubeFound: Int = 0,
    val youtubePages: Int = 0,
    val finalVersions: Int = 0,
    val initialVisible: Int = 0,
    val backgroundComplete: Boolean = false,
    val liveFound: Int = 0,
    val withOthersFound: Int = 0,
    val remixFound: Int = 0,
    val spotifyHintsFound: Int = 0,
    val discogsFound: Int = 0,
)

internal data class OriginalVersionSearchResult(
    val original: CoverHubResult?,
    val versions: List<CoverHubResult>,
    val liveVersions: List<CoverHubResult> = emptyList(),
    val withOthersVersions: List<CoverHubResult> = emptyList(),
    val remixVersions: List<CoverHubResult> = emptyList(),
    val aiIdentity: GeminiOriginalIdentity? = null,
    val diagnostics: OriginalVersionDiagnostics = OriginalVersionDiagnostics(),
)

private data class OriginalArtistSearchOutcome(
    val results: List<CoverHubResult>,
    val pages: Int,
    val status: OriginalSearchStageStatus,
)

private data class QueryOutcome(
    val results: List<CoverHubResult>,
    val pages: Int,
    val failed: Boolean,
    val succeeded: Boolean,
)

internal object OriginalVersionSearchEngine {
    suspend fun identifyOriginal(
        title: String,
        currentArtist: String,
        geminiConfig: GeminiCoverVerificationConfig?,
        discogsToken: String = "",
    ): OriginalVersionSearchResult = coroutineScope {
        val discogsHints = if (discogsToken.isBlank()) {
            emptyList()
        } else {
            runCatching {
                DiscogsVersionSource.discoverTitleHints(
                    token = discogsToken,
                    title = title,
                )
            }.getOrDefault(emptyList())
        }

        fun identityFromDiscogs(seed: DiscogsVersionSeed): GeminiOriginalIdentity =
            GeminiOriginalIdentity(
                title = seed.trackTitle,
                originalArtists = listOf(seed.artist),
                year = seed.year,
                releaseDate = seed.releaseDate,
                songwriters = emptyList(),
                composers = emptyList(),
                lyricists = emptyList(),
                producers = emptyList(),
                label = seed.labels.firstOrNull(),
                album = seed.releaseTitle,
                mode = GeminiOriginalMode.DISCOGS,
                webSourceCount = 0,
            )

        if (geminiConfig == null) {
            val identity = discogsHints.firstOrNull()?.let(::identityFromDiscogs)
                ?: return@coroutineScope OriginalVersionSearchResult(
                    original = null,
                    versions = emptyList(),
                    diagnostics = OriginalVersionDiagnostics(
                        aiStatus = OriginalAiStatus.NOT_CONFIGURED,
                        discogsFound = discogsHints.size,
                    ),
                )
            return@coroutineScope OriginalVersionSearchResult(
                original = null,
                versions = emptyList(),
                aiIdentity = identity,
                diagnostics = diagnosticsForIdentity(identity).copy(discogsFound = discogsHints.size),
            )
        }

        val aiAttempt = runCatching {
            GeminiOriginalDiscovery.identify(
                currentTitle = title,
                currentArtist = currentArtist,
                config = geminiConfig,
            )
        }

        val aiIdentity = aiAttempt.getOrNull()
        val identity = if (aiIdentity != null) {
            val discogsMatch = discogsHints.firstOrNull { seed ->
                aiIdentity.originalArtists.any { artist -> discogsArtistMatches(seed.artist, artist) }
            }
            if (discogsMatch != null) {
                aiIdentity.copy(
                    year = discogsMatch.year ?: aiIdentity.year,
                    releaseDate = discogsMatch.releaseDate ?: aiIdentity.releaseDate,
                    album = discogsMatch.releaseTitle.ifBlank { aiIdentity.album.orEmpty() }.ifBlank { null },
                )
            } else {
                aiIdentity
            }
        } else {
            discogsHints.firstOrNull()?.let(::identityFromDiscogs)
        }

        if (identity == null) {
            return@coroutineScope OriginalVersionSearchResult(
                original = null,
                versions = emptyList(),
                diagnostics = OriginalVersionDiagnostics(
                    aiStatus = if (aiAttempt.isFailure) OriginalAiStatus.ERROR else OriginalAiStatus.NO_ANSWER,
                    discogsFound = discogsHints.size,
                ),
            )
        }

        OriginalVersionSearchResult(
            original = null,
            versions = emptyList(),
            aiIdentity = identity,
            diagnostics = diagnosticsForIdentity(identity).copy(discogsFound = discogsHints.size),
        )
    }

    suspend fun findInitialVersions(
        identity: GeminiOriginalIdentity,
        currentYouTubeId: String,
        discogsToken: String = "",
    ): OriginalVersionSearchResult = coroutineScope {
        val targetTitle = exactBaseTitle(identity.title)
        val originalArtists = canonicalOriginalArtists(identity)
        val leadArtist = identity.originalArtists.firstOrNull().orEmpty()
        if (targetTitle.isBlank() || originalArtists.isEmpty() || leadArtist.isBlank()) {
            return@coroutineScope OriginalVersionSearchResult(
                original = null,
                versions = emptyList(),
                aiIdentity = identity,
                diagnostics = diagnosticsForIdentity(identity).copy(aiStatus = OriginalAiStatus.NO_ANSWER),
            )
        }

        val merged = linkedMapOf<String, CoverHubResult>()
        val discogsSeeds = if (discogsToken.isBlank()) {
            emptyList()
        } else {
            runCatching {
                DiscogsVersionSource.discoverOriginalVersions(
                    token = discogsToken,
                    title = identity.title,
                    originalArtist = leadArtist,
                )
            }.getOrDefault(emptyList())
        }
        val discogsResults = resolveDiscogsOriginalVersions(discogsSeeds, currentYouTubeId)
        discogsResults.forEach { mergeInto(merged, it) }

        val query = "${identity.title} $leadArtist"
        val music = if (merged.size < INITIAL_RESULTS_LIMIT) {
            searchFirstPage(query, targetTitle, originalArtists, YouTube.SearchFilter.FILTER_SONG, "YouTube Music")
                .also { outcome -> outcome.results.forEach { mergeInto(merged, it) } }
        } else {
            QueryOutcome(emptyList(), 0, false, false)
        }
        includeCurrentIfOriginal(currentYouTubeId, targetTitle, originalArtists, merged)

        val video = if (merged.size < INITIAL_RESULTS_LIMIT) {
            searchFirstPage(query, targetTitle, originalArtists, YouTube.SearchFilter.FILTER_VIDEO, "YouTube")
                .also { outcome -> outcome.results.forEach { mergeInto(merged, it) } }
        } else QueryOutcome(emptyList(), 0, false, false)

        val pool = resolveMissingYears(merged.values.toList())
        val chosen = chooseAiOriginal(pool, identity)
        val visible = linkedMapOf<String, CoverHubResult>()
        chosen?.let { visible[it.song.id] = it }
        pool.forEach { candidate -> if (visible.size < INITIAL_RESULTS_LIMIT) visible[candidate.song.id] = candidate }

        val original = chosen?.copy(
            year = identity.year ?: chosen.year,
            releaseDate = identity.releaseDate ?: chosen.releaseDate,
            releaseTitle = chosen.releaseTitle ?: identity.album,
            source = if (chosen.source.contains("Discogs")) chosen.source else aiSource(chosen.source),
            confirmed = true,
        )
        val versions = visible.values.asSequence()
            .filter { it.song.id != original?.song?.id }
            .distinctBy { it.versionFingerprint ?: it.song.id }
            .sortedWith(versionOrder)
            .toList()

        buildCategorizedResult(
            identity = identity,
            original = original,
            versions = versions,
            diagnostics = diagnosticsForIdentity(identity).copy(
                youtubeMusicStatus = statusFor(music),
                youtubeMusicFound = music.results.size,
                youtubeMusicPages = music.pages,
                youtubeStatus = statusFor(video),
                youtubeFound = video.results.size,
                youtubePages = video.pages,
                finalVersions = versions.size + if (original != null) 1 else 0,
                initialVisible = versions.size + if (original != null) 1 else 0,
                backgroundComplete = false,
                discogsFound = discogsResults.size,
            ),
        )
    }

    suspend fun findExpandedVersions(
        identity: GeminiOriginalIdentity,
        currentYouTubeId: String,
        seed: OriginalVersionSearchResult,
        geminiConfig: GeminiCoverVerificationConfig?,
        discogsToken: String = "",
    ): OriginalVersionSearchResult = coroutineScope {
        val targetTitle = exactBaseTitle(identity.title)
        val originalArtists = canonicalOriginalArtists(identity)
        val leadArtist = identity.originalArtists.firstOrNull().orEmpty()
        if (targetTitle.isBlank() || originalArtists.isEmpty()) return@coroutineScope seed

        val merged = linkedMapOf<String, CoverHubResult>()
        seed.original?.let { mergeInto(merged, it) }
        seed.versions.forEach { mergeInto(merged, it) }

        if (discogsToken.isNotBlank() && leadArtist.isNotBlank()) {
            val knownFingerprints = merged.values.mapNotNull { it.versionFingerprint }.toSet()
            val freshDiscogs = runCatching {
                DiscogsVersionSource.discoverOriginalVersions(
                    token = discogsToken,
                    title = identity.title,
                    originalArtist = leadArtist,
                ).filter { it.fingerprint !in knownFingerprints }
            }.getOrDefault(emptyList())
            resolveDiscogsOriginalVersions(freshDiscogs, currentYouTubeId).forEach { mergeInto(merged, it) }
        }

        val memoryCandidates = if (
            geminiConfig != null &&
            geminiConfig.cloudEndpoint.isNotBlank() &&
            geminiConfig.useCloudMemory &&
            leadArtist.isNotBlank()
        ) {
            runCatching {
                CloudMusicDiscovery.discoverMemory(
                    title = identity.title,
                    artist = leadArtist,
                    config = geminiConfig,
                    mode = "originals",
                    limit = ORIGINAL_MEMORY_QUERY_LIMIT,
                )
            }.getOrNull()?.versions.orEmpty().distinctBy { it.stableKey }
        } else {
            emptyList()
        }

        val memoryQueries = memoryCandidates
            .map { candidate -> "${candidate.title} ${candidate.artist}".trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // LAB23: Spotify may contribute additional known recordings/metadata, but it is
        // never a veto. Failure or logout simply produces an empty hint list.
        val spotifyHints = runCatching {
            SpotifyMusicAssist.originalHints(
                title = identity.title,
                originalArtists = identity.originalArtists,
            )
        }.getOrDefault(emptyList())
        val spotifyQueries = spotifyHints
            .map { it.query }
            .filter { it.isNotBlank() }
            .distinct()

        val usedQueries = (memoryQueries + spotifyQueries).distinct().toMutableList()
        var musicOutcome = OriginalArtistSearchOutcome(emptyList(), 0, OriginalSearchStageStatus.NOT_RUN)

        if (spotifyQueries.isNotEmpty()) {
            val spotifyMusic = searchAiArtistVersions(
                identity,
                targetTitle,
                originalArtists,
                YouTube.SearchFilter.FILTER_SONG,
                "YouTube Music · Spotify assist",
                spotifyQueries,
            )
            spotifyMusic.results.forEach { mergeInto(merged, it) }
            musicOutcome = mergeOutcomes(musicOutcome, spotifyMusic)
        }

        if (memoryCandidates.isNotEmpty()) {
            val memoryMusic = searchRememberedOriginalVersions(
                candidates = memoryCandidates,
                originalArtists = originalArtists,
                filter = YouTube.SearchFilter.FILTER_SONG,
                source = "YouTube Music · memoria MusicLab",
            )
            memoryMusic.results.forEach { mergeInto(merged, it) }
            musicOutcome = mergeOutcomes(musicOutcome, memoryMusic)
        }

        if (merged.size < MIN_ORIGINAL_PLAYABLE_TARGET) {
            val genericQueries = defaultVersionQueries(identity).filterNot { it in usedQueries }
            usedQueries += genericQueries
            val genericMusic = searchAiArtistVersions(
                identity,
                targetTitle,
                originalArtists,
                YouTube.SearchFilter.FILTER_SONG,
                "YouTube Music",
                genericQueries,
            )
            genericMusic.results.forEach { mergeInto(merged, it) }
            musicOutcome = mergeOutcomes(musicOutcome, genericMusic)
        }

        var videoOutcome = OriginalArtistSearchOutcome(emptyList(), 0, OriginalSearchStageStatus.NOT_RUN)
        if (merged.size < MIN_ORIGINAL_PLAYABLE_TARGET) {
            videoOutcome = searchAiArtistVersions(
                identity, targetTitle, originalArtists, YouTube.SearchFilter.FILTER_VIDEO, "YouTube", usedQueries,
            )
            videoOutcome.results.forEach { mergeInto(merged, it) }
        }

        var noProgressPasses = 0
        for (round in 0 until MAX_AI_SEARCH_ROUNDS) {
            if (merged.size >= MIN_ORIGINAL_PLAYABLE_TARGET || noProgressPasses >= 2) break
            val before = merged.size
            val planned = GeminiOriginalDiscovery.planVersionQueries(identity, usedQueries, round, geminiConfig)
            if (planned.isEmpty()) {
                noProgressPasses++
                continue
            }
            usedQueries += planned

            val musicExtra = searchAiArtistVersions(
                identity, targetTitle, originalArtists, YouTube.SearchFilter.FILTER_SONG, "YouTube Music", planned,
            )
            musicExtra.results.forEach { mergeInto(merged, it) }
            musicOutcome = mergeOutcomes(musicOutcome, musicExtra)

            if (merged.size < MIN_ORIGINAL_PLAYABLE_TARGET) {
                val videoExtra = searchAiArtistVersions(
                    identity, targetTitle, originalArtists, YouTube.SearchFilter.FILTER_VIDEO, "YouTube", planned,
                )
                videoExtra.results.forEach { mergeInto(merged, it) }
                videoOutcome = mergeOutcomes(videoOutcome, videoExtra)
            }
            noProgressPasses = if (merged.size > before) 0 else noProgressPasses + 1
        }

        includeCurrentIfOriginal(currentYouTubeId, targetTitle, originalArtists, merged)
        val raw = resolveMissingYears(merged.values.toList())
        val chosen = chooseAiOriginal(raw, identity)
        val original = chosen?.copy(
            year = identity.year ?: chosen.year,
            releaseDate = identity.releaseDate ?: chosen.releaseDate,
            releaseTitle = chosen.releaseTitle ?: identity.album,
            source = if (chosen.source.contains("Discogs")) chosen.source else aiSource(chosen.source),
            confirmed = true,
        )
        val alternatives = raw.asSequence()
            .filter { it.song.id != original?.song?.id }
            .distinctBy { it.versionFingerprint ?: it.song.id }
            .sortedWith(versionOrder)
            .toList()

        buildCategorizedResult(
            identity = identity,
            original = original,
            versions = alternatives,
            diagnostics = diagnosticsForIdentity(identity).copy(
                youtubeMusicStatus = musicOutcome.status,
                youtubeMusicFound = musicOutcome.results.size,
                youtubeMusicPages = musicOutcome.pages,
                youtubeStatus = videoOutcome.status,
                youtubeFound = videoOutcome.results.size,
                youtubePages = videoOutcome.pages,
                finalVersions = alternatives.size + if (original != null) 1 else 0,
                initialVisible = seed.diagnostics.initialVisible,
                backgroundComplete = true,
                spotifyHintsFound = spotifyHints.size,
                discogsFound = alternatives.count { it.source.contains("Discogs") } +
                    if (original?.source?.contains("Discogs") == true) 1 else 0,
            ),
        )
    }

    suspend fun findVersions(
        title: String,
        currentArtist: String,
        durationSec: Int,
        currentYouTubeId: String,
        geminiConfig: GeminiCoverVerificationConfig?,
        discogsToken: String = "",
    ): OriginalVersionSearchResult {
        val identified = identifyOriginal(title, currentArtist, geminiConfig, discogsToken)
        val identity = identified.aiIdentity ?: return identified
        val initial = findInitialVersions(identity, currentYouTubeId, discogsToken)
        return findExpandedVersions(identity, currentYouTubeId, initial, geminiConfig, discogsToken)
    }

    private fun defaultVersionQueries(identity: GeminiOriginalIdentity): List<String> {
        val leadArtist = identity.originalArtists.firstOrNull().orEmpty()
        return listOf(
            "${identity.title} $leadArtist",
            "${identity.title} $leadArtist studio",
            "${identity.title} $leadArtist remastered",
            "${identity.title} $leadArtist album",
            "${identity.title} $leadArtist live",
            "${identity.title} $leadArtist concert",
            "${identity.title} $leadArtist session",
            "${identity.title} $leadArtist tv",
            "${identity.title} $leadArtist radio",
            "${identity.title} $leadArtist duet",
            "${identity.title} $leadArtist feat",
            "${identity.title} $leadArtist acoustic",
            "${identity.title} $leadArtist unplugged",
            "${identity.title} $leadArtist remix",
            "${identity.title} $leadArtist official",
            "${identity.title} $leadArtist performance",
        ).filter { leadArtist.isNotBlank() }.distinct()
    }

    private suspend fun searchRememberedOriginalVersions(
        candidates: List<AiCoverCandidate>,
        originalArtists: Set<String>,
        filter: YouTube.SearchFilter,
        source: String,
    ): OriginalArtistSearchOutcome = coroutineScope {
        if (candidates.isEmpty() || originalArtists.isEmpty()) {
            return@coroutineScope OriginalArtistSearchOutcome(emptyList(), 0, OriginalSearchStageStatus.NOT_RUN)
        }

        val queryOutcomes = mutableListOf<QueryOutcome>()
        val batches = candidates.distinctBy { it.stableKey }.chunked(2)
        for ((batchIndex, batch) in batches.withIndex()) {
            val outcomes = batch.map { candidate ->
                async(Dispatchers.IO) {
                    val outcome = searchQueryPages(
                        query = "${candidate.title} ${candidate.artist}".trim(),
                        targetTitle = exactBaseTitle(candidate.title),
                        originalArtists = originalArtists,
                        filter = filter,
                        source = source,
                    )
                    outcome.copy(
                        results = outcome.results.map { result -> result.copy(brainCandidate = candidate) },
                    )
                }
            }.awaitAll()
            queryOutcomes += outcomes
            if (batchIndex < batches.lastIndex) delay(BACKGROUND_BATCH_PAUSE_MS)
        }

        val merged = linkedMapOf<String, CoverHubResult>()
        queryOutcomes.flatMap { it.results }.forEach { mergeInto(merged, it) }
        val pages = queryOutcomes.sumOf { it.pages }
        val anyFailure = queryOutcomes.any { it.failed }
        val anySuccess = queryOutcomes.any { it.succeeded }
        val status = when {
            merged.isNotEmpty() -> OriginalSearchStageStatus.OK
            anyFailure -> OriginalSearchStageStatus.ERROR
            anySuccess -> OriginalSearchStageStatus.NO_RESULTS
            else -> OriginalSearchStageStatus.NOT_RUN
        }
        OriginalArtistSearchOutcome(merged.values.take(MAX_RESULTS_PER_SOURCE), pages, status)
    }

    private suspend fun searchAiArtistVersions(
        identity: GeminiOriginalIdentity,
        targetTitle: String,
        originalArtists: Set<String>,
        filter: YouTube.SearchFilter,
        source: String,
        queries: List<String>,
    ): OriginalArtistSearchOutcome = coroutineScope {
        if (identity.title.isBlank() || identity.originalArtists.firstOrNull().isNullOrBlank() || queries.isEmpty()) {
            return@coroutineScope OriginalArtistSearchOutcome(emptyList(), 0, OriginalSearchStageStatus.NOT_RUN)
        }

        val queryOutcomes = mutableListOf<QueryOutcome>()
        val batches = queries.distinct().chunked(2)
        for ((batchIndex, batch) in batches.withIndex()) {
            val outcomes = batch.map { query ->
                async(Dispatchers.IO) { searchQueryPages(query, targetTitle, originalArtists, filter, source) }
            }.awaitAll()
            queryOutcomes += outcomes
            if (batchIndex < batches.lastIndex) delay(BACKGROUND_BATCH_PAUSE_MS)
        }

        val merged = linkedMapOf<String, CoverHubResult>()
        queryOutcomes.flatMap { it.results }.forEach { mergeInto(merged, it) }
        val pages = queryOutcomes.sumOf { it.pages }
        val anyFailure = queryOutcomes.any { it.failed }
        val anySuccess = queryOutcomes.any { it.succeeded }
        val status = when {
            merged.isNotEmpty() -> OriginalSearchStageStatus.OK
            anyFailure -> OriginalSearchStageStatus.ERROR
            anySuccess -> OriginalSearchStageStatus.NO_RESULTS
            else -> OriginalSearchStageStatus.NOT_RUN
        }
        OriginalArtistSearchOutcome(merged.values.take(MAX_RESULTS_PER_SOURCE), pages, status)
    }

    private fun mergeOutcomes(
        first: OriginalArtistSearchOutcome,
        second: OriginalArtistSearchOutcome,
    ): OriginalArtistSearchOutcome {
        val merged = linkedMapOf<String, CoverHubResult>()
        first.results.forEach { mergeInto(merged, it) }
        second.results.forEach { mergeInto(merged, it) }
        val status = when {
            merged.isNotEmpty() -> OriginalSearchStageStatus.OK
            first.status == OriginalSearchStageStatus.ERROR || second.status == OriginalSearchStageStatus.ERROR -> OriginalSearchStageStatus.ERROR
            first.status == OriginalSearchStageStatus.NO_RESULTS || second.status == OriginalSearchStageStatus.NO_RESULTS -> OriginalSearchStageStatus.NO_RESULTS
            else -> OriginalSearchStageStatus.NOT_RUN
        }
        return OriginalArtistSearchOutcome(merged.values.toList(), first.pages + second.pages, status)
    }

    private suspend fun resolveDiscogsOriginalVersions(
        seeds: List<DiscogsVersionSeed>,
        currentYouTubeId: String,
    ): List<CoverHubResult> {
        if (seeds.isEmpty()) return emptyList()
        val candidates = seeds.map(DiscogsVersionSource::toCoverCandidate)
        val resolved = runCatching {
            AiCoverSearchEngine.resolveCandidates(
                candidates = candidates,
                currentYouTubeId = currentYouTubeId,
                pauseBetweenBatches = false,
            )
        }.getOrDefault(AiCoverResolveResult(emptyList(), AiCoverResolveStats()))

        return resolved.playables.map { playable ->
            val candidate = playable.candidate
            CoverHubResult(
                song = playable.song,
                year = candidate.year,
                releaseDate = candidate.releaseDate,
                releaseTitle = candidate.discogsReleaseTitle ?: candidate.album,
                discogsReleaseId = candidate.discogsReleaseId,
                discogsMasterId = candidate.discogsMasterId,
                versionFingerprint = candidate.versionFingerprint,
                source = "Discogs → ${playable.playbackSource}",
                confirmed = true,
                score = 1.15,
                brainCandidate = candidate,
            )
        }
    }

    private suspend fun searchFirstPage(
        query: String,
        targetTitle: String,
        originalArtists: Set<String>,
        filter: YouTube.SearchFilter,
        source: String,
    ): QueryOutcome {
        val attempt = runCatching { YouTube.search(query, filter).getOrThrow() }
        val page = attempt.getOrNull() ?: return QueryOutcome(emptyList(), 0, true, false)
        val found = linkedMapOf<String, CoverHubResult>()
        page.items.filterIsInstance<SongItem>().forEach { addIfValid(it, targetTitle, originalArtists, source, found) }
        return QueryOutcome(found.values.toList(), 1, false, true)
    }

    private suspend fun searchQueryPages(
        query: String,
        targetTitle: String,
        originalArtists: Set<String>,
        filter: YouTube.SearchFilter,
        source: String,
    ): QueryOutcome {
        val firstAttempt = runCatching { YouTube.search(query, filter).getOrThrow() }
        if (firstAttempt.isFailure) return QueryOutcome(emptyList(), 0, true, false)
        var page = firstAttempt.getOrNull() ?: return QueryOutcome(emptyList(), 0, true, false)
        var pages = 0
        var failed = false
        val found = linkedMapOf<String, CoverHubResult>()
        val seenContinuations = mutableSetOf<String>()
        while (true) {
            pages++
            page.items.filterIsInstance<SongItem>().forEach { addIfValid(it, targetTitle, originalArtists, source, found) }
            val continuation = page.continuation
            if (
                continuation == null || pages >= MAX_PAGES_PER_QUERY ||
                found.size >= MAX_RESULTS_PER_QUERY || !seenContinuations.add(continuation)
            ) break
            val continuationAttempt = runCatching { YouTube.searchContinuation(continuation).getOrThrow() }
            if (continuationAttempt.isFailure) { failed = true; break }
            page = continuationAttempt.getOrNull() ?: break
        }
        return QueryOutcome(found.values.toList(), pages, failed, true)
    }

    private fun addIfValid(
        song: SongItem,
        targetTitle: String,
        originalArtists: Set<String>,
        source: String,
        target: MutableMap<String, CoverHubResult>,
    ) {
        if (!matchesAiTitle(song.title, targetTitle, originalArtists)) return
        if (!hasAnyOriginalArtist(song, originalArtists)) return
        if (DISALLOWED_REGEX.containsMatchIn(song.title.lowercase())) return
        mergeInto(
            target,
            CoverHubResult(
                song = song,
                source = source,
                confirmed = true,
                score = if (isPlainVersionTitle(song.title)) 1.0 else 0.85,
            ),
        )
    }

    private suspend fun includeCurrentIfOriginal(
        currentYouTubeId: String,
        targetTitle: String,
        originalArtists: Set<String>,
        target: MutableMap<String, CoverHubResult>,
    ) {
        if (currentYouTubeId.isBlank()) return
        runCatching { YouTube.queue(listOf(currentYouTubeId)).getOrNull()?.firstOrNull() }
            .getOrNull()?.let { song ->
                if (matchesAiTitle(song.title, targetTitle, originalArtists) && hasAnyOriginalArtist(song, originalArtists)) {
                    mergeInto(target, CoverHubResult(song = song, source = "Versione di partenza", confirmed = true))
                }
            }
    }

    private suspend fun resolveMissingYears(results: List<CoverHubResult>): List<CoverHubResult> = coroutineScope {
        if (results.isEmpty()) return@coroutineScope results
        val resolved = mutableListOf<CoverHubResult>()
        for (batch in results.chunked(YEAR_LOOKUP_BATCH_SIZE)) {
            resolved += batch.map { result ->
                async(Dispatchers.IO) {
                    if (result.year != null) result
                    else result.copy(year = CoverYearResolver.resolve(result.song))
                }
            }.awaitAll()
        }
        resolved
    }

    private fun chooseAiOriginal(results: List<CoverHubResult>, identity: GeminiOriginalIdentity): CoverHubResult? {
        if (results.isEmpty()) return null
        val plain = results.filter { isPlainVersionTitle(it.song.title) }
        identity.releaseDate?.let { exactDate ->
            plain.firstOrNull { it.releaseDate == exactDate }?.let { return it }
            results.firstOrNull { it.releaseDate == exactDate }?.let { return it }
        }
        identity.year?.let { aiYear ->
            plain.firstOrNull { it.year == aiYear }?.let { return it }
            results.firstOrNull { it.year == aiYear }?.let { return it }
        }
        plain.firstOrNull()?.let { return it }
        return results.firstOrNull()
    }

    private fun buildCategorizedResult(
        identity: GeminiOriginalIdentity,
        original: CoverHubResult?,
        versions: List<CoverHubResult>,
        diagnostics: OriginalVersionDiagnostics,
    ): OriginalVersionSearchResult {
        val originalArtists = canonicalOriginalArtists(identity)
        val live = versions.filter { isLiveVersion(it.song.title) }
        val withOthers = versions.filter { isWithOthersVersion(it.song, originalArtists) }
        val remix = versions.filter { isRemixVersion(it.song.title) }
        return OriginalVersionSearchResult(
            original = original,
            versions = versions,
            liveVersions = live,
            withOthersVersions = withOthers,
            remixVersions = remix,
            aiIdentity = identity,
            diagnostics = diagnostics.copy(liveFound = live.size, withOthersFound = withOthers.size, remixFound = remix.size),
        )
    }

    private fun diagnosticsForIdentity(identity: GeminiOriginalIdentity) = OriginalVersionDiagnostics(
        aiStatus = OriginalAiStatus.OK,
        aiMode = identity.mode,
        aiWebSources = identity.webSourceCount,
        aiTitle = identity.title,
        aiArtists = identity.originalArtists,
        aiYear = identity.year,
    )

    private fun statusFor(outcome: QueryOutcome) = when {
        outcome.results.isNotEmpty() -> OriginalSearchStageStatus.OK
        outcome.failed -> OriginalSearchStageStatus.ERROR
        outcome.succeeded -> OriginalSearchStageStatus.NO_RESULTS
        else -> OriginalSearchStageStatus.NOT_RUN
    }

    private fun aiSource(source: String) = listOf("AI", source).filter { it.isNotBlank() }.distinct().joinToString(" + ")
    private fun isPlainVersionTitle(value: String) = !VERSION_MARKER_REGEX.containsMatchIn(value.lowercase()) && !DISALLOWED_REGEX.containsMatchIn(value.lowercase())
    private fun isLiveVersion(value: String) = LIVE_REGEX.containsMatchIn(value.lowercase())
    private fun isRemixVersion(value: String) = REMIX_REGEX.containsMatchIn(value.lowercase())

    private fun isWithOthersVersion(song: SongItem, originalArtists: Set<String>): Boolean {
        val hasAdditionalArtist = song.artists.any { artist ->
            val candidate = canonicalArtist(artist.name)
            candidate.isNotBlank() && originalArtists.none { original -> artistContains(candidate, original) }
        }
        return hasAdditionalArtist || WITH_OTHERS_REGEX.containsMatchIn(song.title.lowercase())
    }

    internal fun isOriginalTitleCompatible(
        value: String,
        targetTitle: String,
        originalArtists: Set<String> = emptySet(),
    ): Boolean =
        TitleMeaningResolver.matchesBaseTitle(
            targetTitle = targetTitle,
            value = value,
            artistAliases = originalArtists,
        )

    private fun matchesAiTitle(value: String, targetTitle: String, originalArtists: Set<String>): Boolean =
        isOriginalTitleCompatible(
            value = value,
            targetTitle = targetTitle,
            originalArtists = originalArtists,
        )

    private fun containsTokenPhrase(value: String, phrase: String): Boolean =
        value.isNotBlank() && phrase.isNotBlank() && (value == phrase || value.startsWith("$phrase ") || value.endsWith(" $phrase") || value.contains(" $phrase "))

    private fun mergeInto(target: MutableMap<String, CoverHubResult>, candidate: CoverHubResult) {
        val fingerprint = candidate.versionFingerprint
        val existingEntry = target.entries.firstOrNull { (_, existing) ->
            existing.song.id == candidate.song.id ||
                (!fingerprint.isNullOrBlank() && existing.versionFingerprint == fingerprint)
        }
        val key = existingEntry?.key ?: fingerprint?.takeIf(String::isNotBlank) ?: candidate.song.id
        val previous = existingEntry?.value
        if (previous == null) {
            target[key] = candidate
            return
        }
        val sources = listOf(previous.source, candidate.source)
            .flatMap { it.split(" + ") }.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(" + ")
        target[key] = previous.copy(
            year = previous.year ?: candidate.year,
            releaseDate = previous.releaseDate ?: candidate.releaseDate,
            releaseTitle = previous.releaseTitle ?: candidate.releaseTitle,
            discogsReleaseId = previous.discogsReleaseId ?: candidate.discogsReleaseId,
            discogsMasterId = previous.discogsMasterId ?: candidate.discogsMasterId,
            versionFingerprint = previous.versionFingerprint ?: candidate.versionFingerprint,
            source = sources,
            confirmed = previous.confirmed || candidate.confirmed,
            score = maxOf(previous.score, candidate.score),
            brainCandidate = previous.brainCandidate ?: candidate.brainCandidate,
        )
    }

    private fun hasAnyOriginalArtist(song: SongItem, originalArtists: Set<String>) = originalArtists.isNotEmpty() && song.artists.any { artist ->
        val candidate = canonicalArtist(artist.name)
        originalArtists.any { artistContains(candidate, it) }
    }

    private fun canonicalOriginalArtists(identity: GeminiOriginalIdentity) = identity.originalArtists.map(::canonicalArtist).filter { it.isNotBlank() }.toSet()

    private fun discogsArtistMatches(left: String, right: String): Boolean {
        val a = canonicalArtist(left)
        val b = canonicalArtist(right)
        return a.isNotBlank() && b.isNotBlank() && artistContains(a, b)
    }

    private fun exactBaseTitle(value: String): String {
        var clean = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()
        clean = BRACKETED_BLOCK_REGEX.replace(clean) { match ->
            val inner = match.value.drop(1).dropLast(1).trim()
            if (VERSION_MARKER_REGEX.containsMatchIn(inner) || YEAR_ONLY_REGEX.matches(inner)) " " else match.value
        }
        clean = clean.replace(Regex("\\b(feat|ft|featuring)\\.?\\s+.*$"), " ")
        clean = stripTrailingVersionSuffix(clean)
        clean = clean.replace(Regex("[^a-z0-9]+"), " ")
        return clean.trim().replace(Regex("\\s+"), " ")
    }

    private fun stripTrailingVersionSuffix(value: String): String {
        var clean = value
        while (true) {
            val removable = VERSION_SEPARATOR_REGEX.findAll(clean).toList().asReversed().firstOrNull { match ->
                val suffix = clean.substring(match.range.last + 1).trim()
                VERSION_MARKER_REGEX.containsMatchIn(suffix) || YEAR_ONLY_REGEX.matches(suffix)
            } ?: break
            clean = clean.substring(0, removable.range.first).trim()
        }
        return clean
    }

    private fun canonicalArtist(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ")
        .trim().replace(Regex("\\s+"), " ").removePrefix("the ")

    private fun artistContains(candidate: String, original: String): Boolean {
        if (candidate.isBlank() || original.isBlank()) return false
        if (candidate == original) return true
        return candidate.startsWith("$original ") || candidate.endsWith(" $original") || candidate.contains(" $original ") || original.startsWith("$candidate ") || original.endsWith(" $candidate")
    }

    private val versionOrder =
        compareBy<CoverHubResult> { if (it.year == null && it.releaseDate == null) 1 else 0 }
            .thenBy { it.releaseDate ?: it.year?.toString() ?: "9999-99-99" }
            .thenByDescending { it.confirmed }
            .thenByDescending { it.score }
    private val BRACKETED_BLOCK_REGEX = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val YEAR_ONLY_REGEX = Regex("^(?:18|19|20)\\d{2}$")
    private val YEAR_IN_TEXT_REGEX = Regex("\\b(?:18|19|20)\\d{2}\\b")
    private val VERSION_MARKER_REGEX = Regex("\\b(official|music\\s+video|video|audio|lyrics?|lyric|visualizer|live|remaster(?:ed)?|remix|mix|acoustic|unplugged|version|versione|original|originale|radio\\s+edit|edit|mono|stereo|deluxe|bonus\\s+track|session|performance|studio|feat|ft|featuring|cover|duet|duetto|collaboration|with|hd|hq|testo)\\b")
    private val LIVE_REGEX = Regex("\\b(live|dal vivo|concerto|concert|performance|session|festival|canzonissima|rai|tv|radio|unplugged)\\b")
    private val REMIX_REGEX = Regex("\\b(remix|club mix|extended mix|radio edit|dance mix|mix)\\b")
    private val WITH_OTHERS_REGEX = Regex("\\b(feat|ft|featuring|duet|duetto|with|con|insieme|collaboration|collaborazione)\\b")
    private val CONTEXT_MARKER_REGEX = Regex("\\b(canta|interpreta|esegue|con|insieme|canzonissima|concerto|concert|show|festival|rai|tv|radio|televisione|session|unplugged)\\b")
    private val DISALLOWED_REGEX = Regex("\\b(mashup|medley|reaction|tutorial|lesson|how to play|karaoke|instrumental backing track|backing track)\\b")
    private val VERSION_SEPARATOR_REGEX = Regex("\\s+[-–—]\\s+|\\s*[:|]\\s*")

    private const val INITIAL_RESULTS_LIMIT = 10
    private const val MIN_ORIGINAL_PLAYABLE_TARGET = 10
    private const val ORIGINAL_MEMORY_QUERY_LIMIT = 80
    private const val MAX_AI_SEARCH_ROUNDS = 3
    private const val MAX_PAGES_PER_QUERY = 5
    private const val MAX_RESULTS_PER_QUERY = 80
    private const val MAX_RESULTS_PER_SOURCE = 220
    private const val YEAR_LOOKUP_BATCH_SIZE = 10
    private const val BACKGROUND_BATCH_PAUSE_MS = 70L
}
