/** MusicLab: AI-first search for the original performer and all of that performer's versions. */
package com.metrolist.music.ui.component

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer

internal enum class OriginalSearchStageStatus {
    OK,
    NO_RESULTS,
    ERROR,
    NOT_RUN,
}

internal enum class OriginalAiStatus {
    OK,
    NOT_CONFIGURED,
    NO_ANSWER,
    ERROR,
}

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
)

internal data class OriginalVersionSearchResult(
    val original: CoverHubResult?,
    val versions: List<CoverHubResult>,
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
    suspend fun findVersions(
        title: String,
        currentArtist: String,
        durationSec: Int,
        currentYouTubeId: String,
        geminiConfig: GeminiCoverVerificationConfig?,
    ): OriginalVersionSearchResult = coroutineScope {
        if (geminiConfig == null) {
            return@coroutineScope OriginalVersionSearchResult(
                original = null,
                versions = emptyList(),
                diagnostics = OriginalVersionDiagnostics(
                    aiStatus = OriginalAiStatus.NOT_CONFIGURED,
                ),
            )
        }

        val aiAttempt = runCatching {
            GeminiOriginalDiscovery.identify(
                currentTitle = title,
                currentArtist = currentArtist,
                config = geminiConfig,
            )
        }
        if (aiAttempt.isFailure) {
            return@coroutineScope OriginalVersionSearchResult(
                original = null,
                versions = emptyList(),
                diagnostics = OriginalVersionDiagnostics(
                    aiStatus = OriginalAiStatus.ERROR,
                ),
            )
        }

        val identity = aiAttempt.getOrNull()
        if (identity == null) {
            return@coroutineScope OriginalVersionSearchResult(
                original = null,
                versions = emptyList(),
                diagnostics = OriginalVersionDiagnostics(
                    aiStatus = OriginalAiStatus.NO_ANSWER,
                ),
            )
        }

        val targetTitle = exactBaseTitle(identity.title)
        val originalArtists = identity.originalArtists
            .map(::canonicalArtist)
            .filter { it.isNotBlank() }
            .toSet()
        if (targetTitle.isBlank() || originalArtists.isEmpty()) {
            return@coroutineScope OriginalVersionSearchResult(
                original = null,
                versions = emptyList(),
                aiIdentity = identity,
                diagnostics = OriginalVersionDiagnostics(
                    aiStatus = OriginalAiStatus.NO_ANSWER,
                    aiMode = identity.mode,
                    aiWebSources = identity.webSourceCount,
                    aiTitle = identity.title,
                    aiArtists = identity.originalArtists,
                    aiYear = identity.year,
                ),
            )
        }

        val youtubeMusicDeferred = async {
            searchAiArtistVersions(
                identity = identity,
                targetTitle = targetTitle,
                originalArtists = originalArtists,
                filter = YouTube.SearchFilter.FILTER_SONG,
                source = "YouTube Music",
            )
        }
        val youtubeDeferred = async {
            searchAiArtistVersions(
                identity = identity,
                targetTitle = targetTitle,
                originalArtists = originalArtists,
                filter = YouTube.SearchFilter.FILTER_VIDEO,
                source = "YouTube",
            )
        }

        val youtubeMusic = youtubeMusicDeferred.await()
        val youtube = youtubeDeferred.await()

        val merged = linkedMapOf<String, CoverHubResult>()
        youtubeMusic.results.forEach { mergeInto(merged, it) }
        youtube.results.forEach { mergeInto(merged, it) }

        if (currentYouTubeId.isNotBlank()) {
            runCatching {
                YouTube.queue(listOf(currentYouTubeId)).getOrNull()?.firstOrNull()
            }.getOrNull()?.let { song ->
                if (
                    matchesAiTitle(song.title, targetTitle, originalArtists) &&
                    hasAnyOriginalArtist(song, originalArtists)
                ) {
                    mergeInto(
                        merged,
                        CoverHubResult(
                            song = song,
                            source = "Versione di partenza",
                            confirmed = true,
                        ),
                    )
                }
            }
        }

        val enriched = enrichYears(merged.values.toList())
            .sortedWith(versionOrder)
            .distinctBy { it.song.id }

        val chosen = chooseAiOriginal(enriched, identity)
        val original = chosen?.copy(
            year = identity.year ?: chosen.year,
            source = listOf("AI", chosen.source)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" + "),
            confirmed = true,
        )

        val versions = enriched
            .asSequence()
            .filter { it.song.id != original?.song?.id }
            .sortedWith(versionOrder)
            .toList()

        OriginalVersionSearchResult(
            original = original,
            versions = versions,
            aiIdentity = identity,
            diagnostics = OriginalVersionDiagnostics(
                aiStatus = OriginalAiStatus.OK,
                aiMode = identity.mode,
                aiWebSources = identity.webSourceCount,
                aiTitle = identity.title,
                aiArtists = identity.originalArtists,
                aiYear = identity.year,
                youtubeMusicStatus = youtubeMusic.status,
                youtubeMusicFound = youtubeMusic.results.size,
                youtubeMusicPages = youtubeMusic.pages,
                youtubeStatus = youtube.status,
                youtubeFound = youtube.results.size,
                youtubePages = youtube.pages,
                finalVersions = versions.size + if (original != null) 1 else 0,
            ),
        )
    }

    private suspend fun searchAiArtistVersions(
        identity: GeminiOriginalIdentity,
        targetTitle: String,
        originalArtists: Set<String>,
        filter: YouTube.SearchFilter,
        source: String,
    ): OriginalArtistSearchOutcome = coroutineScope {
        val leadArtist = identity.originalArtists.firstOrNull().orEmpty()
        if (identity.title.isBlank() || leadArtist.isBlank()) {
            return@coroutineScope OriginalArtistSearchOutcome(
                results = emptyList(),
                pages = 0,
                status = OriginalSearchStageStatus.NOT_RUN,
            )
        }

        val queries = linkedSetOf(
            "${identity.title} $leadArtist",
            "${identity.title} $leadArtist live",
            "${identity.title} $leadArtist duet",
            "${identity.title} $leadArtist remastered",
            "${identity.title} $leadArtist acoustic",
        )

        val queryOutcomes = queries.map { query ->
            async(Dispatchers.IO) {
                searchQueryPages(
                    query = query,
                    targetTitle = targetTitle,
                    originalArtists = originalArtists,
                    filter = filter,
                    source = source,
                )
            }
        }.awaitAll()

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

        OriginalArtistSearchOutcome(
            results = merged.values.take(MAX_RESULTS_PER_SOURCE),
            pages = pages,
            status = status,
        )
    }

    private suspend fun searchQueryPages(
        query: String,
        targetTitle: String,
        originalArtists: Set<String>,
        filter: YouTube.SearchFilter,
        source: String,
    ): QueryOutcome {
        val firstAttempt = runCatching {
            YouTube.search(query, filter).getOrThrow()
        }
        if (firstAttempt.isFailure) {
            return QueryOutcome(emptyList(), 0, failed = true, succeeded = false)
        }

        var page = firstAttempt.getOrNull()
            ?: return QueryOutcome(emptyList(), 0, failed = true, succeeded = false)
        var pages = 0
        var failed = false
        val found = linkedMapOf<String, CoverHubResult>()
        val seenContinuations = mutableSetOf<String>()

        while (true) {
            pages++
            page.items.filterIsInstance<SongItem>().forEach { song ->
                if (!matchesAiTitle(song.title, targetTitle, originalArtists)) return@forEach
                if (!hasAnyOriginalArtist(song, originalArtists)) return@forEach
                if (DISALLOWED_REGEX.containsMatchIn(song.title.lowercase())) return@forEach

                mergeInto(
                    found,
                    CoverHubResult(
                        song = song,
                        source = source,
                        confirmed = true,
                        score = if (isPlainVersionTitle(song.title)) 1.0 else 0.85,
                    ),
                )
            }

            val continuation = page.continuation
            if (
                continuation == null ||
                pages >= MAX_PAGES_PER_QUERY ||
                found.size >= MAX_RESULTS_PER_QUERY ||
                !seenContinuations.add(continuation)
            ) {
                break
            }

            val continuationAttempt = runCatching {
                YouTube.searchContinuation(continuation).getOrThrow()
            }
            if (continuationAttempt.isFailure) {
                failed = true
                break
            }
            page = continuationAttempt.getOrNull() ?: break
        }

        return QueryOutcome(
            results = found.values.toList(),
            pages = pages,
            failed = failed,
            succeeded = true,
        )
    }

    private suspend fun enrichYears(results: List<CoverHubResult>): List<CoverHubResult> = coroutineScope {
        val output = results.toMutableList()
        val indexes = output.indices.filter { output[it].year == null }.take(MAX_YEAR_LOOKUPS)

        for (batch in indexes.chunked(8)) {
            val resolved = batch.map { index ->
                async(Dispatchers.IO) {
                    index to runCatching {
                        CoverYearResolver.resolve(output[index].song)
                    }.getOrNull()
                }
            }.awaitAll()

            resolved.forEach { (index, year) ->
                if (year != null) output[index] = output[index].copy(year = year)
            }
        }
        output
    }

    private fun chooseAiOriginal(
        results: List<CoverHubResult>,
        identity: GeminiOriginalIdentity,
    ): CoverHubResult? {
        if (results.isEmpty()) return null

        val plain = results.filter { isPlainVersionTitle(it.song.title) }
        identity.year?.let { aiYear ->
            plain.firstOrNull { it.year == aiYear }?.let { return it }
            results.firstOrNull { it.year == aiYear }?.let { return it }
        }

        plain.minWithOrNull(versionOrder)?.let { return it }
        return results.minWithOrNull(versionOrder) ?: results.firstOrNull()
    }

    private fun isPlainVersionTitle(value: String): Boolean {
        val lower = value.lowercase()
        return !VERSION_MARKER_REGEX.containsMatchIn(lower) &&
            !DISALLOWED_REGEX.containsMatchIn(lower)
    }

    private fun matchesAiTitle(
        value: String,
        targetTitle: String,
        originalArtists: Set<String>,
    ): Boolean {
        val candidate = exactBaseTitle(value)
        if (candidate == targetTitle) return true
        if (
            originalArtists.any { artist ->
                candidate == "$artist $targetTitle" ||
                    candidate == "$targetTitle $artist"
            }
        ) {
            return true
        }

        // Video uploads often use descriptive titles instead of the bare song title,
        // e.g. "Jimmy Fontana canta Il mondo" or "Il mondo - Canzonissima 1965".
        // The caller separately requires the AI-selected original performer in the
        // SongItem artist metadata, so here we can safely accept contextual wording
        // while still rejecting unrelated longer song titles such as "Il mondo nuovo".
        if (!containsTokenPhrase(candidate, targetTitle)) return false
        if (originalArtists.any { artist -> containsTokenPhrase(candidate, artist) }) return true

        val residual = candidate
            .replace(targetTitle, " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (residual.isBlank()) return true

        return VERSION_MARKER_REGEX.containsMatchIn(residual) ||
            YEAR_IN_TEXT_REGEX.containsMatchIn(residual) ||
            CONTEXT_MARKER_REGEX.containsMatchIn(residual)
    }

    private fun containsTokenPhrase(value: String, phrase: String): Boolean {
        if (value.isBlank() || phrase.isBlank()) return false
        if (value == phrase) return true
        return value.startsWith("$phrase ") ||
            value.endsWith(" $phrase") ||
            value.contains(" $phrase ")
    }

    private fun mergeInto(
        target: MutableMap<String, CoverHubResult>,
        candidate: CoverHubResult,
    ) {
        val previous = target[candidate.song.id]
        if (previous == null) {
            target[candidate.song.id] = candidate
            return
        }

        val sources = listOf(previous.source, candidate.source)
            .flatMap { it.split(" + ") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" + ")

        target[candidate.song.id] = previous.copy(
            year = previous.year ?: candidate.year,
            source = sources,
            confirmed = previous.confirmed || candidate.confirmed,
            score = maxOf(previous.score, candidate.score),
        )
    }

    private fun hasAnyOriginalArtist(song: SongItem, originalArtists: Set<String>): Boolean {
        if (originalArtists.isEmpty()) return false
        return song.artists.any { artist ->
            val candidate = canonicalArtist(artist.name)
            originalArtists.any { originalArtist -> artistContains(candidate, originalArtist) }
        }
    }

    private fun exactBaseTitle(value: String): String {
        var clean = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .trim()

        clean = BRACKETED_BLOCK_REGEX.replace(clean) { match ->
            val inner = match.value.drop(1).dropLast(1).trim()
            if (
                VERSION_MARKER_REGEX.containsMatchIn(inner) ||
                YEAR_ONLY_REGEX.matches(inner)
            ) {
                " "
            } else {
                match.value
            }
        }

        clean = clean.replace(Regex("\\b(feat|ft|featuring)\\.?\\s+.*$"), " ")
        clean = stripTrailingVersionSuffix(clean)
        clean = clean.replace(Regex("[^a-z0-9]+"), " ")
        return clean.trim().replace(Regex("\\s+"), " ")
    }

    private fun stripTrailingVersionSuffix(value: String): String {
        var clean = value
        while (true) {
            val separators = VERSION_SEPARATOR_REGEX.findAll(clean).toList()
            val removable = separators.asReversed().firstOrNull { match ->
                val suffix = clean.substring(match.range.last + 1).trim()
                VERSION_MARKER_REGEX.containsMatchIn(suffix) || YEAR_ONLY_REGEX.matches(suffix)
            } ?: break
            clean = clean.substring(0, removable.range.first).trim()
        }
        return clean
    }

    private fun canonicalArtist(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
            .removePrefix("the ")

    private fun artistContains(candidate: String, original: String): Boolean {
        if (candidate.isBlank() || original.isBlank()) return false
        if (candidate == original) return true
        return candidate.startsWith("$original ") ||
            candidate.endsWith(" $original") ||
            candidate.contains(" $original ") ||
            original.startsWith("$candidate ") ||
            original.endsWith(" $candidate")
    }

    private val versionOrder =
        compareBy<CoverHubResult> { if (it.year == null) 1 else 0 }
            .thenBy { it.year ?: Int.MAX_VALUE }
            .thenByDescending { it.confirmed }
            .thenByDescending { it.score }

    private val BRACKETED_BLOCK_REGEX = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val YEAR_ONLY_REGEX = Regex("^(?:18|19|20)\\d{2}$")
    private val YEAR_IN_TEXT_REGEX = Regex("\\b(?:18|19|20)\\d{2}\\b")

    private val VERSION_MARKER_REGEX = Regex(
        "\\b(official|music\\s+video|video|audio|lyrics?|lyric|visualizer|live|remaster(?:ed)?|remix|mix|acoustic|unplugged|version|versione|original|originale|radio\\s+edit|edit|mono|stereo|deluxe|bonus\\s+track|session|performance|studio|feat|ft|featuring|cover|duet|duetto|collaboration|with|hd|hq|testo)\\b",
    )

    private val CONTEXT_MARKER_REGEX = Regex(
        "\\b(canta|interpreta|esegue|con|insieme|canzonissima|concerto|concert|show|festival|rai|tv|televisione)\\b",
    )

    private val DISALLOWED_REGEX = Regex(
        "\\b(mashup|medley|reaction|tutorial|lesson|how to play|karaoke|instrumental backing track|backing track)\\b",
    )

    private val VERSION_SEPARATOR_REGEX = Regex("\\s+[-–—]\\s+|\\s*[:|]\\s*")

    private const val MAX_PAGES_PER_QUERY = 3
    private const val MAX_RESULTS_PER_QUERY = 50
    private const val MAX_RESULTS_PER_SOURCE = 140
    private const val MAX_YEAR_LOOKUPS = 64
}
