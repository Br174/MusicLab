/** MusicLab: find the oldest verifiable recording and exact-title versions by the original artist. */
package com.metrolist.music.ui.component

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer

internal enum class OriginalSearchStageStatus {
    OK,
    NO_RESULTS,
    ERROR,
    NOT_RUN,
}

internal data class OriginalVersionDiagnostics(
    val searchedTitle: String = "",
    val coverOutcome: CoverHubOutcome = CoverHubOutcome(emptyList()),
    val sameNameStatus: OriginalSearchStageStatus = OriginalSearchStageStatus.NOT_RUN,
    val sameNameFound: Int = 0,
    val sameNamePages: Int = 0,
    val targetedArtistStatus: OriginalSearchStageStatus = OriginalSearchStageStatus.NOT_RUN,
    val targetedArtistFound: Int = 0,
    val targetedArtistPages: Int = 0,
    val exactTitleFound: Int = 0,
    val datedFound: Int = 0,
    val finalVersions: Int = 0,
)

internal data class OriginalVersionSearchResult(
    val original: CoverHubResult?,
    val versions: List<CoverHubResult>,
    val diagnostics: OriginalVersionDiagnostics = OriginalVersionDiagnostics(),
)

private data class OriginalArtistSearchOutcome(
    val results: List<CoverHubResult>,
    val pages: Int,
    val status: OriginalSearchStageStatus,
)

internal object OriginalVersionSearchEngine {
    suspend fun findVersions(
        title: String,
        currentArtist: String,
        durationSec: Int,
        currentYouTubeId: String,
    ): OriginalVersionSearchResult = coroutineScope {
        // A YouTube display title may describe a TV performance or a cover rather than
        // the composition itself (for example: “Il mondo” di Jimmy Fontana secondo Jacopo | X Factor 2022).
        // Originali must search the composition title, not the entire display string.
        val lookupTitle = compositionLookupTitle(title, currentArtist)
        val targetTitle = exactBaseTitle(lookupTitle)
        if (targetTitle.isBlank()) {
            return@coroutineScope OriginalVersionSearchResult(
                original = null,
                versions = emptyList(),
                diagnostics = OriginalVersionDiagnostics(searchedTitle = lookupTitle),
            )
        }

        // Start from the same discovery engines already used by Cover. This is useful
        // when the currently playing item is itself a cover, because structured cover
        // sources may lead back to the original performer/composition.
        val coversDeferred = async {
            runCatching {
                CoverHubSearchEngine.searchCovers(
                    title = lookupTitle,
                    originalArtist = currentArtist,
                    durationSec = durationSec,
                    currentYouTubeId = currentYouTubeId,
                    geminiConfig = null,
                )
            }.getOrDefault(CoverHubOutcome(emptyList()))
        }
        val sameDeferred = async {
            runCatching {
                CoverHubSearchEngine.searchSameName(
                    title = lookupTitle,
                    currentYouTubeId = currentYouTubeId,
                    geminiConfig = null,
                )
            }
        }
        val startingDeferred = async {
            if (currentYouTubeId.isBlank()) {
                null
            } else {
                runCatching {
                    YouTube.queue(listOf(currentYouTubeId)).getOrNull()?.firstOrNull()
                }.getOrNull()
            }
        }

        val coverOutcome = coversDeferred.await()

        val sameName = linkedMapOf<String, CoverHubResult>()
        val firstSameAttempt = sameDeferred.await()
        var sameNameFailed = firstSameAttempt.isFailure
        var page = firstSameAttempt.getOrNull()
        page?.results.orEmpty().forEach { result -> mergeInto(sameName, result) }
        var sameNamePages = page?.scannedPages ?: 0
        var continuation = page?.continuation
        var passes = 0
        while (continuation != null && sameName.size < 120 && passes < 6) {
            passes++
            val moreAttempt = runCatching {
                CoverHubSearchEngine.searchSameNameMore(
                    title = lookupTitle,
                    continuation = continuation!!,
                    currentYouTubeId = currentYouTubeId,
                    existingResults = sameName.values.toList(),
                    geminiConfig = null,
                )
            }
            if (moreAttempt.isFailure) {
                sameNameFailed = true
                break
            }
            page = moreAttempt.getOrNull() ?: break
            page.results.forEach { result -> mergeInto(sameName, result) }
            sameNamePages += page.scannedPages
            continuation = page.continuation
        }
        val sameNameStatus = when {
            sameName.isNotEmpty() -> OriginalSearchStageStatus.OK
            sameNameFailed -> OriginalSearchStageStatus.ERROR
            else -> OriginalSearchStageStatus.NO_RESULTS
        }

        val discovered = linkedMapOf<String, CoverHubResult>()
        coverOutcome.results.forEach { result -> mergeInto(discovered, result) }
        sameName.values.forEach { result -> mergeInto(discovered, result) }

        // Keep the starting item available for chronology only when its real title is
        // the exact composition title. The screen still shows it separately regardless.
        startingDeferred.await()?.let { song ->
            mergeInto(
                discovered,
                CoverHubResult(
                    song = song,
                    source = "Versione di partenza",
                    confirmed = true,
                ),
            )
        }

        // Never accept a partial-title hit. Technical version annotations and pure year
        // tags are ignored, while meaningful parenthetical title text is preserved.
        val exact = discovered.values
            .filter { exactBaseTitle(it.song.title) == targetTitle }
            .distinctBy { it.song.id }

        var enriched = enrichYears(exact)

        // "Partire dalle Cover" means that a dated candidate discovered by the Cover
        // relationship engines has priority over a generic same-title hit. Within that
        // group, confirmed structured relations are preferred. Generic same-title search
        // remains the fallback when the Cover engines cannot identify a dated candidate.
        val coverExactIds = coverOutcome.results
            .asSequence()
            .filter { exactBaseTitle(it.song.title) == targetTitle }
            .map { it.song.id }
            .toSet()
        val confirmedCoverDated = enriched.filter {
            it.song.id in coverExactIds && it.year != null && it.confirmed
        }
        val coverDated = enriched.filter {
            it.song.id in coverExactIds && it.year != null
        }
        var original = confirmedCoverDated.minWithOrNull(originalOrder)
            ?: coverDated.minWithOrNull(originalOrder)
            ?: enriched.filter { it.year != null }.minWithOrNull(originalOrder)

        var targetedOutcome = OriginalArtistSearchOutcome(
            results = emptyList(),
            pages = 0,
            status = OriginalSearchStageStatus.NOT_RUN,
        )
        if (original != null) {
            targetedOutcome = searchOriginalArtistVersions(
                title = lookupTitle,
                original = original,
                currentYouTubeId = currentYouTubeId,
            )

            // Targeted YouTube Music searches usually expose live, remastered, duet,
            // reissue and alternate releases that a generic title search can miss.
            val merged = linkedMapOf<String, CoverHubResult>()
            enriched.forEach { result -> mergeInto(merged, result) }
            targetedOutcome.results.forEach { result -> mergeInto(merged, result) }
            enriched = enrichYears(merged.values.toList())

            val originalArtists = original.song.artists
                .map { canonicalArtist(it.name) }
                .filter { it.isNotBlank() }
                .toSet()

            // Re-evaluate the oldest release only among the performer(s) already identified
            // as the original artist, so unrelated songs with a generic identical title
            // cannot steal the "Originale" label.
            original = enriched
                .filter { it.year != null }
                .filter { result -> hasAnyOriginalArtist(result.song, originalArtists) }
                .minWithOrNull(originalOrder)
        }

        val orderedAll = enriched
            .sortedWith(versionOrder)
            .distinctBy { it.song.id }

        val versions = if (original == null) {
            // Important: lack of a verified date is not the same as lack of results.
            // Show what was found and let diagnostics explain why no original is certified yet.
            orderedAll
        } else {
            val originalArtists = original.song.artists
                .map { canonicalArtist(it.name) }
                .filter { it.isNotBlank() }
                .toSet()

            orderedAll
                .asSequence()
                .filter { it.song.id != original.song.id }
                .filter { result -> hasAnyOriginalArtist(result.song, originalArtists) }
                .toList()
        }

        OriginalVersionSearchResult(
            original = original,
            versions = versions,
            diagnostics = OriginalVersionDiagnostics(
                searchedTitle = lookupTitle,
                coverOutcome = coverOutcome,
                sameNameStatus = sameNameStatus,
                sameNameFound = sameName.size,
                sameNamePages = sameNamePages,
                targetedArtistStatus = targetedOutcome.status,
                targetedArtistFound = targetedOutcome.results.size,
                targetedArtistPages = targetedOutcome.pages,
                exactTitleFound = orderedAll.size,
                datedFound = orderedAll.count { it.year != null },
                finalVersions = versions.size + if (original != null) 1 else 0,
            ),
        )
    }

    private suspend fun searchOriginalArtistVersions(
        title: String,
        original: CoverHubResult,
        currentYouTubeId: String,
    ): OriginalArtistSearchOutcome {
        val targetTitle = exactBaseTitle(title)
        val artistNames = original.song.artists
            .map { it.name.trim() }
            .filter { it.isNotBlank() }
            .distinctBy(::canonicalArtist)
            .take(3)
        val originalArtists = artistNames.map(::canonicalArtist).filter { it.isNotBlank() }.toSet()
        if (targetTitle.isBlank() || artistNames.isEmpty() || originalArtists.isEmpty()) {
            return OriginalArtistSearchOutcome(
                results = emptyList(),
                pages = 0,
                status = OriginalSearchStageStatus.NOT_RUN,
            )
        }

        val found = linkedMapOf<String, CoverHubResult>()
        var pages = 0
        var hadSuccessfulRequest = false
        var hadFailedRequest = false

        artistNames.forEach artistLoop@ { artistName ->
            val firstAttempt = runCatching {
                YouTube.search("$title $artistName", YouTube.SearchFilter.FILTER_SONG).getOrThrow()
            }
            if (firstAttempt.isFailure) {
                hadFailedRequest = true
                return@artistLoop
            }
            var page = firstAttempt.getOrNull() ?: return@artistLoop
            hadSuccessfulRequest = true

            var continuation = page.continuation
            var artistPages = 0
            val seenContinuations = mutableSetOf<String>()

            while (true) {
                pages++
                artistPages++
                page.items.filterIsInstance<SongItem>().forEach songLoop@ { song ->
                    if (song.id == currentYouTubeId) return@songLoop
                    if (exactBaseTitle(song.title) != targetTitle) return@songLoop
                    if (!hasAnyOriginalArtist(song, originalArtists)) return@songLoop
                    mergeInto(
                        found,
                        CoverHubResult(
                            song = song,
                            source = "YouTube Music · ricerca artista originale",
                            confirmed = true,
                        ),
                    )
                }

                val nextContinuation = continuation
                if (
                    nextContinuation == null ||
                    artistPages >= MAX_TARGETED_PAGES_PER_ARTIST ||
                    found.size >= MAX_TARGETED_RESULTS ||
                    !seenContinuations.add(nextContinuation)
                ) {
                    break
                }

                val continuationAttempt = runCatching {
                    YouTube.searchContinuation(nextContinuation).getOrThrow()
                }
                if (continuationAttempt.isFailure) {
                    hadFailedRequest = true
                    break
                }
                page = continuationAttempt.getOrNull() ?: break
                hadSuccessfulRequest = true
                continuation = page.continuation
            }
        }

        val status = when {
            found.isNotEmpty() -> OriginalSearchStageStatus.OK
            hadFailedRequest -> OriginalSearchStageStatus.ERROR
            hadSuccessfulRequest -> OriginalSearchStageStatus.NO_RESULTS
            else -> OriginalSearchStageStatus.NOT_RUN
        }

        return OriginalArtistSearchOutcome(
            results = found.values.toList(),
            pages = pages,
            status = status,
        )
    }

    private suspend fun enrichYears(results: List<CoverHubResult>): List<CoverHubResult> =
        results.map { result ->
            if (result.year != null) {
                result
            } else {
                val year = runCatching { CoverYearResolver.resolve(result.song) }.getOrNull()
                result.copy(year = year)
            }
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

    /**
     * Convert a YouTube display title into the composition title used by Originali.
     * Quoted titles are especially useful for television/performance uploads.
     */
    private fun compositionLookupTitle(value: String, currentArtist: String): String {
        val original = value.trim()
        if (original.isBlank()) return original

        QUOTED_TITLE_REGEX.find(original)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.length >= 2 }
            ?.let { return stripDisplayMetadata(it) }

        var clean = original

        val artist = currentArtist.trim()
        if (artist.isNotBlank()) {
            clean = clean.replace(
                Regex("^\\s*${Regex.escape(artist)}\\s*[-–—:]\\s*", RegexOption.IGNORE_CASE),
                "",
            )
        }

        clean = clean.replace(
            Regex("^(.+?)\\s+di\\s+.+?\\s+secondo\\s+.+$", RegexOption.IGNORE_CASE),
            "$1",
        )
        clean = clean.replace(
            Regex("\\s+cover\\s+(?:di|by)\\s+.+$", RegexOption.IGNORE_CASE),
            "",
        )
        clean = clean.substringBefore('|').trim()
        return stripDisplayMetadata(clean)
    }

    private fun stripDisplayMetadata(value: String): String {
        var clean = value.trim().trim('"', '“', '”', '«', '»')
        clean = BRACKETED_BLOCK_REGEX.replace(clean) { match ->
            val inner = match.value.drop(1).dropLast(1).trim()
            if (
                VERSION_MARKER_REGEX.containsMatchIn(inner.lowercase()) ||
                YEAR_ONLY_REGEX.matches(inner)
            ) {
                " "
            } else {
                match.value
            }
        }
        clean = stripTrailingVersionSuffixPreservingCase(clean)
        return clean.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Canonical title used only by Originali. It is deliberately stricter than
     * the general Cover search: only annotations that clearly describe a version
     * are removed. The remaining complete title must then match exactly.
     */
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

    private fun stripTrailingVersionSuffixPreservingCase(value: String): String {
        var clean = value
        while (true) {
            val separators = VERSION_SEPARATOR_REGEX.findAll(clean).toList()
            val removable = separators.asReversed().firstOrNull { match ->
                val suffix = clean.substring(match.range.last + 1).trim()
                VERSION_MARKER_REGEX.containsMatchIn(suffix.lowercase()) || YEAR_ONLY_REGEX.matches(suffix)
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
            candidate.contains(" $original ")
    }

    private val originalOrder =
        compareBy<CoverHubResult> { it.year ?: Int.MAX_VALUE }
            .thenByDescending { it.confirmed }
            .thenByDescending { it.score }

    private val versionOrder =
        compareBy<CoverHubResult> { if (it.year == null) 1 else 0 }
            .thenBy { it.year ?: Int.MAX_VALUE }
            .thenByDescending { it.confirmed }
            .thenByDescending { it.score }

    private val BRACKETED_BLOCK_REGEX = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val QUOTED_TITLE_REGEX = Regex("[“\\\"«„]([^”\\\"»‟]{2,160})[”\\\"»‟]")
    private val YEAR_ONLY_REGEX = Regex("^(?:18|19|20)\\d{2}$")

    private val VERSION_MARKER_REGEX = Regex(
        "\\b(official|music\\s+video|video|audio|lyrics?|lyric|visualizer|live|remaster(?:ed)?|remix|mix|acoustic|unplugged|version|versione|original|originale|radio\\s+edit|edit|mono|stereo|deluxe|bonus\\s+track|session|performance|studio|feat|ft|featuring|cover|karaoke|hd|hq|testo)\\b",
    )

    private val VERSION_SEPARATOR_REGEX = Regex("\\s+[-–—]\\s+|\\s*[:|]\\s*")

    private const val MAX_TARGETED_PAGES_PER_ARTIST = 5
    private const val MAX_TARGETED_RESULTS = 100
}
