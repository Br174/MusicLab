/** MusicLab: find the oldest verifiable recording and exact-title versions by the original artist. */
package com.metrolist.music.ui.component

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

        // Never accept a partial-title hit. Technical annotations such as Live,
        // Remastered, Official Audio and years are ignored, but the complete song
        // title itself must remain identical.
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
     * the general Cover search: annotations describing a version are removed,
     * but no partial-word/title similarity is allowed afterwards.
     */
    private fun exactBaseTitle(value: String): String {
        var clean = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()

        clean = clean.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        clean = clean.replace(Regex("\\b(feat|ft|featuring)\\.?\\s+.*$"), " ")
        clean = clean.replace(
            Regex(
                "\\b(official|music video|video|audio|lyrics?|lyric|visualizer|live|remaster(?:ed)?|remix|mix|acoustic|unplugged|version|versione|radio edit|edit|mono|stereo|deluxe|bonus track|session|performance|studio)\\b",
            ),
            " ",
        )
        clean = clean.replace(Regex("\\b(?:19|20)\\d{2}\\b"), " ")
        clean = clean.replace(Regex("[^a-z0-9]+"), " ")
        return clean.trim().replace(Regex("\\s+"), " ")
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
}
