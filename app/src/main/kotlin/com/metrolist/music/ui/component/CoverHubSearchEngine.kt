/**
 * MusicLab cover/name search hub engine
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

internal enum class CoverHubMode {
    COVERS,
    SAME_NAME,
}

internal data class CoverHubResult(
    val song: SongItem,
    val year: Int? = null,
    val source: String = "MusicLab",
    val confirmed: Boolean = false,
    val score: Double = 0.0,
)

internal data class CoverSourceStats(
    val found: Int = 0,
    val resolved: Int = 0,
    val used: Int = 0,
)

internal data class CoverHubOutcome(
    val results: List<CoverHubResult>,
    val whoSampledStatus: WhoSampledStatus = WhoSampledStatus.NETWORK_ERROR,
    val secondHandSongsStatus: SecondHandSongsStatus = SecondHandSongsStatus.NETWORK_ERROR,
    val musicBrainzStatus: MusicBrainzStatus = MusicBrainzStatus.NETWORK_ERROR,
    val geminiConfigured: Boolean = false,
    val whoSampledStats: CoverSourceStats = CoverSourceStats(),
    val secondHandSongsStats: CoverSourceStats = CoverSourceStats(),
    val musicBrainzStats: CoverSourceStats = CoverSourceStats(),
    val geminiStats: CoverSourceStats = CoverSourceStats(),
    val youtubeMusicStats: CoverSourceStats = CoverSourceStats(),
    // Kept for compatibility with older callers/UI while diagnostics migrate.
    val whoSampledCount: Int = 0,
    val geminiCount: Int = 0,
)

internal data class SameNameSearchPage(
    val results: List<CoverHubResult>,
    val continuation: String? = null,
    val scannedPages: Int = 0,
    val addedCount: Int = 0,
)

internal object CoverHubSearchEngine {
    suspend fun searchCovers(
        title: String,
        originalArtist: String,
        durationSec: Int,
        currentYouTubeId: String?,
        geminiConfig: GeminiCoverVerificationConfig?,
    ): CoverHubOutcome = coroutineScope {
        val cleanTitle = coverLookupTitle(title, originalArtist)
        if (cleanTitle.isBlank()) return@coroutineScope CoverHubOutcome(emptyList())

        val whoDeferred = async(Dispatchers.IO) {
            runCatching { WhoSampledCoverSource.lookup(cleanTitle, originalArtist) }
                .getOrElse { WhoSampledLookup(emptyList(), WhoSampledStatus.NETWORK_ERROR) }
        }
        val secondHandSongsDeferred = async(Dispatchers.IO) {
            runCatching { SecondHandSongsCoverSource.lookup(cleanTitle, originalArtist) }
                .getOrElse {
                    SecondHandSongsLookup(
                        emptyList(),
                        SecondHandSongsStatus.NETWORK_ERROR,
                    )
                }
        }
        val mbDeferred = async(Dispatchers.IO) {
            runCatching { MusicBrainzCoverSource.lookup(cleanTitle, originalArtist) }
                .getOrElse { MusicBrainzLookup(emptyList(), MusicBrainzStatus.NETWORK_ERROR) }
        }
        val geminiDeferred = geminiConfig?.let { config ->
            async(Dispatchers.IO) {
                runCatching {
                    GeminiCoverDiscovery.discover(
                        originalTitle = cleanTitle,
                        originalArtist = originalArtist,
                        config = config,
                    )
                }.getOrDefault(emptyList())
            }
        }
        val broadDeferred = async(Dispatchers.IO) {
            broadYouTubeCovers(
                title = cleanTitle,
                originalArtist = originalArtist,
                durationSec = durationSec,
                currentYouTubeId = currentYouTubeId,
            )
        }

        val directWho = whoDeferred.await()
        val indexedWho =
            if (
                directWho.covers.isEmpty() &&
                directWho.status != WhoSampledStatus.OK &&
                geminiConfig != null
            ) {
                runCatching {
                    WhoSampledIndexedDiscovery.discover(
                        originalTitle = cleanTitle,
                        originalArtist = originalArtist,
                        config = geminiConfig,
                    )
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        val whoFromIndex = indexedWho.isNotEmpty()
        val who =
            if (whoFromIndex) {
                WhoSampledLookup(
                    covers = indexedWho,
                    status = WhoSampledStatus.OK,
                    sourceUrl = indexedWho.firstOrNull()?.url,
                )
            } else {
                directWho
            }
        val whoSource = if (whoFromIndex) "WhoSampled · indice web" else "WhoSampled"

        val secondHandSongs = secondHandSongsDeferred.await()
        val mb = mbDeferred.await()
        val gemini = geminiDeferred?.await().orEmpty()

        val whoResolvedDeferred = async {
            resolveReferences(
                references = who.covers.map {
                    Ref(
                        title = it.title,
                        artist = it.artist,
                        year = null,
                        source = whoSource,
                        confirmed = !whoFromIndex,
                    )
                },
                originalArtist = originalArtist,
                durationSec = durationSec,
                currentYouTubeId = currentYouTubeId,
                maxRefs = 60,
            )
        }
        val secondHandSongsResolvedDeferred = async {
            resolveReferences(
                references = secondHandSongs.covers.map {
                    Ref(
                        title = it.title,
                        artist = it.artist,
                        year = it.year,
                        source = "SecondHandSongs",
                        confirmed = true,
                    )
                },
                originalArtist = originalArtist,
                durationSec = durationSec,
                currentYouTubeId = currentYouTubeId,
                maxRefs = 80,
            )
        }
        val mbResolvedDeferred = async {
            resolveReferences(
                references = mb.covers.map { Ref(it.title, it.artist, it.year, "MusicBrainz", true) },
                originalArtist = originalArtist,
                durationSec = durationSec,
                currentYouTubeId = currentYouTubeId,
                maxRefs = 80,
            )
        }
        val geminiResolvedDeferred = async {
            resolveReferences(
                references = gemini.map {
                    Ref(
                        title = it.title,
                        artist = it.artist,
                        year = it.year,
                        source = "Gemini AI",
                        confirmed = false,
                    )
                },
                originalArtist = originalArtist,
                durationSec = durationSec,
                currentYouTubeId = currentYouTubeId,
                maxRefs = 48,
            )
        }

        val whoResolved = whoResolvedDeferred.await()
        val secondHandSongsResolved = secondHandSongsResolvedDeferred.await()
        val mbResolved = mbResolvedDeferred.await()
        val geminiResolved = geminiResolvedDeferred.await()
        val broad = broadDeferred.await()

        val all = buildList {
            addAll(whoResolved)
            addAll(secondHandSongsResolved)
            addAll(mbResolved)
            addAll(geminiResolved)
            addAll(broad)
        }

        val merged = mergeResults(all)
        val enriched = enrichYears(merged, geminiConfig)
        val ordered = orderOldestFirst(enriched).take(MAX_RESULTS)

        fun used(source: String): Int = ordered.count { hasSource(it, source) }

        val whoUsed = used("WhoSampled")
        val geminiUsed = used("Gemini AI")

        CoverHubOutcome(
            results = ordered,
            whoSampledStatus = who.status,
            secondHandSongsStatus = secondHandSongs.status,
            musicBrainzStatus = mb.status,
            geminiConfigured = geminiConfig != null,
            whoSampledStats = CoverSourceStats(
                found = who.covers.size,
                resolved = whoResolved.size,
                used = whoUsed,
            ),
            secondHandSongsStats = CoverSourceStats(
                found = secondHandSongs.covers.size,
                resolved = secondHandSongsResolved.size,
                used = used("SecondHandSongs"),
            ),
            musicBrainzStats = CoverSourceStats(
                found = mb.covers.size,
                resolved = mbResolved.size,
                used = used("MusicBrainz"),
            ),
            geminiStats = CoverSourceStats(
                found = gemini.size,
                resolved = geminiResolved.size,
                used = geminiUsed,
            ),
            youtubeMusicStats = CoverSourceStats(
                found = broad.size,
                resolved = broad.size,
                used = used("YouTube Music"),
            ),
            whoSampledCount = whoUsed,
            geminiCount = gemini.size,
        )
    }

    suspend fun searchSameName(
        title: String,
        currentYouTubeId: String?,
        geminiConfig: GeminiCoverVerificationConfig?,
    ): SameNameSearchPage = coroutineScope {
        val clean = title.trim()
        if (clean.isBlank()) return@coroutineScope SameNameSearchPage(emptyList())

        val firstPage = YouTube.search(clean, YouTube.SearchFilter.FILTER_SONG).getOrThrow()
        val collected = linkedMapOf<String, CoverHubResult>()
        val seenIds = mutableSetOf<String>()
        var continuation = firstPage.continuation
        var scannedPages = 1

        sameNameResults(
            songs = firstPage.items.filterIsInstance<SongItem>(),
            title = clean,
            currentYouTubeId = currentYouTubeId,
            seenIds = seenIds,
        ).forEach { result ->
            collected.putIfAbsent(result.song.id, result)
            seenIds += result.song.id
        }

        val seenContinuations = mutableSetOf<String>()
        while (
            continuation != null &&
            collected.size < INITIAL_SAME_NAME_TARGET &&
            scannedPages < MAX_SAME_NAME_PAGES_PER_BATCH &&
            seenContinuations.add(continuation)
        ) {
            val page = YouTube.searchContinuation(continuation).getOrThrow()
            scannedPages++
            sameNameResults(
                songs = page.items.filterIsInstance<SongItem>(),
                title = clean,
                currentYouTubeId = currentYouTubeId,
                seenIds = seenIds,
            ).forEach { result ->
                collected.putIfAbsent(result.song.id, result)
                seenIds += result.song.id
            }
            continuation = page.continuation
        }

        // Same-name discovery is intentionally kept fast: album metadata may
        // still provide a year, but we avoid one Gemini/web lookup per result.
        val enriched = orderOldestFirst(enrichYears(collected.values.toList(), null))
            .distinctBy { it.song.id }
            .take(MAX_SAME_NAME_RESULTS)

        SameNameSearchPage(
            results = enriched,
            continuation = continuation.takeIf { enriched.size < MAX_SAME_NAME_RESULTS },
            scannedPages = scannedPages,
            addedCount = enriched.size,
        )
    }

    suspend fun searchSameNameMore(
        title: String,
        continuation: String,
        currentYouTubeId: String?,
        existingResults: List<CoverHubResult>,
        geminiConfig: GeminiCoverVerificationConfig?,
    ): SameNameSearchPage = coroutineScope {
        if (existingResults.size >= MAX_SAME_NAME_RESULTS) {
            return@coroutineScope SameNameSearchPage(
                results = existingResults.take(MAX_SAME_NAME_RESULTS),
                continuation = null,
            )
        }

        val existingIds = existingResults.mapTo(mutableSetOf()) { it.song.id }
        val fresh = linkedMapOf<String, CoverHubResult>()
        val seenIds = existingIds.toMutableSet()
        val seenContinuations = mutableSetOf<String>()
        var nextContinuation: String? = continuation
        var scannedPages = 0

        while (
            nextContinuation != null &&
            fresh.size < MORE_SAME_NAME_TARGET &&
            scannedPages < MAX_SAME_NAME_PAGES_PER_BATCH &&
            seenContinuations.add(nextContinuation)
        ) {
            val page = YouTube.searchContinuation(nextContinuation).getOrThrow()
            scannedPages++
            sameNameResults(
                songs = page.items.filterIsInstance<SongItem>(),
                title = title,
                currentYouTubeId = currentYouTubeId,
                seenIds = seenIds,
            ).forEach { result ->
                fresh.putIfAbsent(result.song.id, result)
                seenIds += result.song.id
            }
            nextContinuation = page.continuation
        }

        // Keep "Cerca ancora" responsive as well: no per-item AI year lookup.
        val enrichedFresh = enrichYears(fresh.values.toList(), null)
        val merged = orderOldestFirst(existingResults + enrichedFresh)
            .distinctBy { it.song.id }
            .take(MAX_SAME_NAME_RESULTS)
        val addedCount = merged.count { it.song.id !in existingIds }

        SameNameSearchPage(
            results = merged,
            continuation = nextContinuation.takeIf { merged.size < MAX_SAME_NAME_RESULTS },
            scannedPages = scannedPages,
            addedCount = addedCount,
        )
    }

    private fun sameNameResults(
        songs: List<SongItem>,
        title: String,
        currentYouTubeId: String?,
        seenIds: Set<String>,
    ): List<CoverHubResult> {
        val target = canonicalTitle(title)
        val unique = linkedMapOf<String, CoverHubResult>()
        songs.forEach { song ->
            if (song.id == currentYouTubeId || song.id in seenIds) return@forEach
            val candidateTitle = canonicalTitle(song.title)
            val similarity = titleSimilarity(target, candidateTitle)
            if (similarity < SAME_NAME_MIN_SIMILARITY) return@forEach
            unique.putIfAbsent(
                song.id,
                CoverHubResult(
                    song = song,
                    source = "Stesso nome · YouTube Music",
                    score = similarity,
                ),
            )
        }
        return unique.values.toList()
    }

    private data class Ref(
        val title: String,
        val artist: String,
        val year: Int?,
        val source: String,
        val confirmed: Boolean,
    )

    private suspend fun resolveReferences(
        references: List<Ref>,
        originalArtist: String,
        durationSec: Int,
        currentYouTubeId: String?,
        maxRefs: Int,
    ): List<CoverHubResult> = coroutineScope {
        val deduped = references
            .distinctBy { "${canonicalTitle(it.title)}|${canonicalArtist(it.artist)}" }
            .take(maxRefs)
        val results = mutableListOf<CoverHubResult>()

        for (batch in deduped.chunked(6)) {
            results += batch.map { ref ->
                async(Dispatchers.IO) {
                    resolveReference(
                        ref = ref,
                        originalArtist = originalArtist,
                        durationSec = durationSec,
                        currentYouTubeId = currentYouTubeId,
                    )
                }
            }.awaitAll().filterNotNull()
        }
        results
    }

    private suspend fun resolveReference(
        ref: Ref,
        originalArtist: String,
        durationSec: Int,
        currentYouTubeId: String?,
    ): CoverHubResult? {
        val page = YouTube.searchSummary("${ref.title} ${ref.artist}".trim(), incognito = true).getOrNull()
            ?: return null
        val songs = page.summaries.flatMap { it.items }.filterIsInstance<SongItem>().distinctBy { it.id }
        val refTitle = canonicalTitle(ref.title)
        val refArtist = canonicalArtist(ref.artist)
        val originalArtistKey = canonicalArtist(originalArtist)

        return songs.mapNotNull { song ->
            if (song.id == currentYouTubeId) return@mapNotNull null
            if (DISALLOWED_REGEX.containsMatchIn(song.title.lowercase())) return@mapNotNull null

            val titleScore = titleSimilarity(refTitle, canonicalTitle(song.title))
            if (titleScore < 0.52) return@mapNotNull null
            val artistScore = song.artists.maxOfOrNull { artistSimilarity(refArtist, canonicalArtist(it.name)) } ?: 0.0
            if (refArtist.isNotBlank() && artistScore < 0.24 && titleScore < 0.92) return@mapNotNull null

            val differentArtist = originalArtistKey.isBlank() || song.artists.none {
                canonicalArtist(it.name) == originalArtistKey
            }
            if (!differentArtist && !ref.source.startsWith("WhoSampled")) return@mapNotNull null

            val durationScore = durationCompatibility(durationSec, song.duration ?: -1)
            val score = titleScore * 0.62 + artistScore * 0.25 + durationScore * 0.13 + if (ref.confirmed) 0.25 else 0.0
            CoverHubResult(
                song = song,
                year = ref.year,
                source = ref.source,
                confirmed = ref.confirmed,
                score = score,
            )
        }.maxByOrNull { it.score }
    }

    private suspend fun broadYouTubeCovers(
        title: String,
        originalArtist: String,
        durationSec: Int,
        currentYouTubeId: String?,
    ): List<CoverHubResult> = coroutineScope {
        val queries = linkedSetOf<String>().apply {
            if (originalArtist.isNotBlank()) {
                add("$title $originalArtist cover")
                add("$title $originalArtist version")
                add("$title $originalArtist interpretation")
            }
            add("$title cover")
            add("$title covers")
            add("$title version")
            add("$title rendition")
            add("$title interpretation")
            add("$title reinterpretation")
            add("$title performed by")
            add("$title tribute")
            add("$title acoustic cover")
            add("$title unplugged cover")
            add("$title live cover")
            add("$title remake")
            add("$title versione")
            add("$title versión")
            add("$title versão")
            add("$title reprise")
            add(title)
        }

        val songs = linkedMapOf<String, SongItem>()
        for (batch in queries.chunked(5)) {
            batch.map { query ->
                async(Dispatchers.IO) { YouTube.searchSummary(query, incognito = true).getOrNull() }
            }.awaitAll().filterNotNull().forEach { page ->
                page.summaries.flatMap { it.items }.filterIsInstance<SongItem>().forEach {
                    songs.putIfAbsent(it.id, it)
                }
            }
        }

        val target = canonicalTitle(title)
        val originalArtistKey = canonicalArtist(originalArtist)
        songs.values.mapNotNull { song ->
            if (song.id == currentYouTubeId) return@mapNotNull null
            if (DISALLOWED_REGEX.containsMatchIn(song.title.lowercase())) return@mapNotNull null
            if (originalArtistKey.isNotBlank() && song.artists.any { canonicalArtist(it.name) == originalArtistKey }) {
                return@mapNotNull null
            }
            val titleScore = titleSimilarity(target, canonicalTitle(song.title))
            if (titleScore < 0.66) return@mapNotNull null
            val durationScore = durationCompatibility(durationSec, song.duration ?: -1)
            if (durationScore < 0.15 && titleScore < 0.92) return@mapNotNull null
            CoverHubResult(
                song = song,
                source = "YouTube Music",
                confirmed = false,
                score = titleScore * 0.78 + durationScore * 0.22,
            )
        }.sortedByDescending { it.score }.take(120)
    }

    /**
     * Merge duplicate playable songs without losing provenance. If two or more
     * engines discovered the same result, the strongest candidate still owns
     * the score/year while the source label keeps every contributing engine.
     */
    private fun mergeResults(values: List<CoverHubResult>): List<CoverHubResult> {
        val byId = linkedMapOf<String, CoverHubResult>()
        values.forEach { candidate ->
            byId[candidate.song.id] = mergeCandidate(byId[candidate.song.id], candidate)
        }

        return byId.values
            .groupBy {
                "${canonicalTitle(it.song.title)}|${it.song.artists.joinToString("|") { a -> canonicalArtist(a.name) }}"
            }
            .map { (_, versions) ->
                versions.drop(1).fold(versions.first()) { accumulated, candidate ->
                    mergeCandidate(accumulated, candidate)
                }
            }
    }

    private fun mergeCandidate(
        existing: CoverHubResult?,
        candidate: CoverHubResult,
    ): CoverHubResult {
        if (existing == null) return candidate

        val candidateWins =
            priority(candidate) > priority(existing) ||
                (priority(candidate) == priority(existing) && candidate.score > existing.score)
        val winner = if (candidateWins) candidate else existing
        val other = if (candidateWins) existing else candidate

        return winner.copy(
            year = winner.year ?: other.year,
            source = combineSources(winner.source, other.source),
            confirmed = winner.confirmed || other.confirmed,
            score = maxOf(winner.score, other.score),
        )
    }

    private fun sourceNames(source: String): List<String> =
        source.split(SOURCE_DELIMITER)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun combineSources(primary: String, secondary: String): String =
        linkedSetOf<String>().apply {
            addAll(sourceNames(primary))
            addAll(sourceNames(secondary))
        }.joinToString(SOURCE_DELIMITER)

    private fun hasSource(result: CoverHubResult, source: String): Boolean =
        sourceNames(result.source).any { candidate ->
            if (source.equals("WhoSampled", ignoreCase = true)) {
                candidate.startsWith("WhoSampled", ignoreCase = true)
            } else {
                candidate.equals(source, ignoreCase = true)
            }
        }

    private fun priority(result: CoverHubResult): Int {
        val sourcePriority = sourceNames(result.source).maxOfOrNull { source ->
            when {
                source == "WhoSampled" -> 5
                source == "SecondHandSongs" -> 5
                source == "MusicBrainz" -> 4
                source.startsWith("WhoSampled") -> 4
                else -> 1
            }
        } ?: 1
        return maxOf(sourcePriority, if (result.confirmed) 3 else 1)
    }

    private suspend fun enrichYears(
        values: List<CoverHubResult>,
        geminiConfig: GeminiCoverVerificationConfig?,
    ): List<CoverHubResult> = coroutineScope {
        val missing = values.filter { it.year == null }
        val albumYears = mutableMapOf<String, Int?>()
        val representatives = missing.mapNotNull { item ->
            item.song.album?.id?.let { id -> id to item.song }
        }.distinctBy { it.first }

        for (batch in representatives.chunked(6)) {
            batch.map { (albumId, song) ->
                async(Dispatchers.IO) { albumId to runCatching { CoverYearResolver.resolve(song) }.getOrNull() }
            }.awaitAll().forEach { (id, year) -> albumYears[id] = year }
        }

        var enriched = values.map { value ->
            val albumYear = value.song.album?.id?.let { albumYears[it] }
            value.copy(year = value.year ?: albumYear)
        }

        if (geminiConfig != null) {
            val unresolved = enriched.filter { it.year == null }.take(36)
            val webYears = mutableMapOf<String, Int?>()
            for (batch in unresolved.chunked(4)) {
                batch.map { item ->
                    async(Dispatchers.IO) {
                        item.song.id to runCatching {
                            GeminiCoverDiscovery.resolveYear(
                                title = item.song.title,
                                artist = item.song.artists.joinToString(", ") { it.name },
                                config = geminiConfig,
                            )
                        }.getOrNull()
                    }
                }.awaitAll().forEach { (id, year) -> webYears[id] = year }
            }
            enriched = enriched.map { it.copy(year = it.year ?: webYears[it.song.id]) }
        }
        enriched
    }

    private fun orderOldestFirst(values: List<CoverHubResult>): List<CoverHubResult> =
        values.sortedWith(
            compareBy<CoverHubResult> { if (it.year == null) 1 else 0 }
                .thenBy { it.year ?: Int.MAX_VALUE }
                .thenByDescending { priority(it) }
                .thenByDescending { it.score },
        )

    /**
     * Structured sources work best with the composition title, not a YouTube
     * display title such as "Artist - Song (Remastered 2023) (Official Audio)".
     * Keep the original text whenever an annotation is not clearly technical.
     */
    private fun coverLookupTitle(value: String, originalArtist: String): String {
        val original = value.trim()
        if (original.isBlank()) return original
        var clean = original

        val artist = originalArtist.trim()
        if (artist.isNotBlank()) {
            val artistPrefix = Regex(
                "^\\s*${Regex.escape(artist)}\\s*[-–—:|]\\s*",
                RegexOption.IGNORE_CASE,
            )
            clean = clean.replaceFirst(artistPrefix, "").trim()
        }

        clean = TECHNICAL_ANNOTATION_REGEX.replace(clean) { match ->
            val inner = match.value.drop(1).dropLast(1)
            if (TECHNICAL_ANNOTATION_MARKERS.containsMatchIn(inner)) " " else match.value
        }
        clean = clean.replace(TRAILING_TECHNICAL_SUFFIX_REGEX, " ")
        clean = clean.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', ':', '|')

        return clean.ifBlank { original }
    }

    private fun canonicalTitle(value: String): String {
        val clean = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(TITLE_NOISE_REGEX, " ")
            .replace(Regex("\\b(feat|ft|featuring)\\.?\\s+.*$"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return clean.replace(Regex("\\s+"), " ")
    }

    private fun canonicalArtist(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun titleSimilarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (a.startsWith(b) || b.startsWith(a)) return 0.94
        val aa = a.split(' ').filter { it.length > 1 }.toSet()
        val bb = b.split(' ').filter { it.length > 1 }.toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        val overlap = aa.intersect(bb).size.toDouble()
        val containment = overlap / max(1, minOf(aa.size, bb.size)).toDouble()
        val jaccard = overlap / aa.union(bb).size.toDouble()
        return containment * 0.65 + jaccard * 0.35
    }

    private fun artistSimilarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return 0.90
        val aa = a.split(' ').filter { it.length > 1 }.toSet()
        val bb = b.split(' ').filter { it.length > 1 }.toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        return aa.intersect(bb).size.toDouble() / max(1, minOf(aa.size, bb.size)).toDouble()
    }

    private fun durationCompatibility(original: Int, candidate: Int): Double {
        if (original <= 0 || candidate <= 0) return 0.55
        val difference = abs(original - candidate)
        val allowed = max(210, (original * 0.85).toInt())
        if (difference >= allowed) return 0.0
        return 1.0 - difference.toDouble() / allowed.toDouble()
    }

    private const val MAX_RESULTS = 120
    private const val MAX_SAME_NAME_RESULTS = 100
    private const val INITIAL_SAME_NAME_TARGET = 40
    private const val MORE_SAME_NAME_TARGET = 30
    private const val MAX_SAME_NAME_PAGES_PER_BATCH = 12
    private const val SAME_NAME_MIN_SIMILARITY = 0.94
    private const val SOURCE_DELIMITER = " + "

    private val TECHNICAL_ANNOTATION_REGEX = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val TECHNICAL_ANNOTATION_MARKERS = Regex(
        "\\b(remaster(?:ed)?|official|video|audio|lyrics?|lyric|live|version|visualizer|hd|4k)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_TECHNICAL_SUFFIX_REGEX = Regex(
        "\\s*[-–—:|]\\s*(?:official\\s*)?(?:music\\s*)?(?:video|audio|lyrics?|lyric|visualizer).*$",
        RegexOption.IGNORE_CASE,
    )

    private val TITLE_NOISE_REGEX = Regex(
        "\\b(official|video|audio|lyrics?|lyric|cover|acoustic|unplugged|live|version|versione|versión|versao|versão|rendition|interpretation|reinterpretation|tribute|performance|session|remaster(?:ed)?|studio)\\b",
    )

    private val DISALLOWED_REGEX = Regex(
        "\\b(mashup|medley|reaction|tutorial|lesson|how to play|karaoke|instrumental backing track|backing track)\\b",
    )
}
