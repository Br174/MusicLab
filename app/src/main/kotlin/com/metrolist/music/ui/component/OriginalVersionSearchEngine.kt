/** MusicLab: find the oldest verifiable recording and exact-title versions by the original artist. */
package com.metrolist.music.ui.component

import com.metrolist.innertube.YouTube
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer

internal data class OriginalVersionSearchResult(
    val original: CoverHubResult?,
    val versions: List<CoverHubResult>,
)

internal object OriginalVersionSearchEngine {
    suspend fun findVersions(
        title: String,
        currentArtist: String,
        durationSec: Int,
        currentYouTubeId: String,
    ): OriginalVersionSearchResult = coroutineScope {
        val targetTitle = exactBaseTitle(title)
        if (targetTitle.isBlank()) {
            return@coroutineScope OriginalVersionSearchResult(null, emptyList())
        }

        // Structured cover sources help when the currently playing track is itself a cover.
        // Same-name YouTube Music discovery supplies alternate releases, live versions,
        // remasters and collaborations. Everything is filtered again below by exact base title.
        val coversDeferred = async {
            runCatching {
                CoverHubSearchEngine.searchCovers(
                    title = title,
                    originalArtist = currentArtist,
                    durationSec = durationSec,
                    currentYouTubeId = currentYouTubeId,
                    geminiConfig = null,
                ).results
            }.getOrDefault(emptyList())
        }
        val sameDeferred = async {
            runCatching {
                CoverHubSearchEngine.searchSameName(
                    title = title,
                    currentYouTubeId = currentYouTubeId,
                    geminiConfig = null,
                )
            }.getOrNull()
        }
        val startingDeferred = async {
            runCatching {
                YouTube.queue(listOf(currentYouTubeId)).getOrNull()?.firstOrNull()
            }.getOrNull()
        }

        val discovered = linkedMapOf<String, CoverHubResult>()
        coversDeferred.await().forEach { discovered.putIfAbsent(it.song.id, it) }

        var page = sameDeferred.await()
        page?.results.orEmpty().forEach { discovered.putIfAbsent(it.song.id, it) }
        var continuation = page?.continuation
        var passes = 0
        while (continuation != null && discovered.size < 120 && passes < 6) {
            passes++
            page = runCatching {
                CoverHubSearchEngine.searchSameNameMore(
                    title = title,
                    continuation = continuation!!,
                    currentYouTubeId = currentYouTubeId,
                    existingResults = discovered.values.toList(),
                    geminiConfig = null,
                )
            }.getOrNull() ?: break
            page.results.forEach { discovered[it.song.id] = it }
            continuation = page.continuation
        }

        // The track used to open Originali must also participate in chronology.
        // Search engines intentionally exclude currentYouTubeId to avoid duplicates,
        // so add it back here after discovery whenever it is a real YouTube item.
        startingDeferred.await()?.let { song ->
            discovered[song.id] = CoverHubResult(
                song = song,
                source = "Versione di partenza",
                confirmed = true,
            )
        }

        // Never accept a partial-title hit. Only genuine version annotations such as
        // Live/Remastered/Remix/Official Audio are ignored. Meaningful parenthetical
        // text remains part of the title, so e.g. "Sweet Dreams (Are Made of This)"
        // cannot collapse to "Sweet Dreams".
        val exact = discovered.values
            .filter { exactBaseTitle(it.song.title) == targetTitle }
            .distinctBy { it.song.id }

        val enriched = exact.map { result ->
            if (result.year != null) {
                result
            } else {
                val year = runCatching { CoverYearResolver.resolve(result.song) }.getOrNull()
                result.copy(year = year)
            }
        }

        // "Originale" is the oldest verifiable release. If no result has a year,
        // do not claim that any candidate is certainly the oldest.
        val original = enriched
            .filter { it.year != null }
            .minWithOrNull(
                compareBy<CoverHubResult> { it.year ?: Int.MAX_VALUE }
                    .thenByDescending { it.confirmed }
                    .thenByDescending { it.score },
            )

        if (original == null) {
            return@coroutineScope OriginalVersionSearchResult(null, emptyList())
        }

        val originalArtists = original.song.artists
            .map { canonicalArtist(it.name) }
            .filter { it.isNotBlank() }
            .toSet()

        // Every secondary result must contain at least one original performer.
        // This includes duet/feat/group/quartet releases as long as the original
        // artist is still present among the credited performers.
        val versions = enriched
            .asSequence()
            .filter { it.song.id != original.song.id }
            .filter { result ->
                originalArtists.isNotEmpty() && result.song.artists.any { artist ->
                    val candidate = canonicalArtist(artist.name)
                    originalArtists.any { originalArtist -> artistContains(candidate, originalArtist) }
                }
            }
            .sortedWith(
                compareBy<CoverHubResult> { if (it.year == null) 1 else 0 }
                    .thenBy { it.year ?: Int.MAX_VALUE }
                    .thenByDescending { it.confirmed }
                    .thenByDescending { it.score },
            )
            .distinctBy { it.song.id }
            .toList()

        OriginalVersionSearchResult(
            original = original,
            versions = versions,
        )
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

        // Remove parenthetical/bracketed blocks only when they are clearly metadata
        // about the recording/version. Meaningful subtitle text is preserved.
        clean = BRACKETED_BLOCK_REGEX.replace(clean) { match ->
            val inner = match.value.drop(1).dropLast(1)
            if (VERSION_MARKER_REGEX.containsMatchIn(inner)) " " else match.value
        }

        // Featuring credits appended to a title are performer metadata, not title text.
        clean = clean.replace(Regex("\\b(feat|ft|featuring)\\.?\\s+.*$"), " ")

        // Remove a trailing separator suffix only when that suffix clearly identifies
        // a recording variant. Do not strip arbitrary words from the song title.
        clean = stripTrailingVersionSuffix(clean)

        clean = clean.replace(Regex("[^a-z0-9]+"), " ")
        return clean.trim().replace(Regex("\\s+"), " ")
    }

    private fun stripTrailingVersionSuffix(value: String): String {
        var clean = value
        while (true) {
            val match = TRAILING_SUFFIX_REGEX.find(clean) ?: break
            val suffix = match.groupValues[1]
            if (!VERSION_MARKER_REGEX.containsMatchIn(suffix)) break
            clean = clean.substring(0, match.range.first).trim()
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

    private val BRACKETED_BLOCK_REGEX = Regex("\\([^)]*\\)|\\[[^]]*]")

    private val VERSION_MARKER_REGEX = Regex(
        "\\b(official|music\\s+video|video|audio|lyrics?|lyric|visualizer|live|remaster(?:ed)?|remix|mix|acoustic|unplugged|version|versione|radio\\s+edit|edit|mono|stereo|deluxe|bonus\\s+track|session|performance|studio|feat|ft|featuring)\\b",
    )

    private val TRAILING_SUFFIX_REGEX = Regex("\\s*[-–—:|]\\s*(.+)$")
}
