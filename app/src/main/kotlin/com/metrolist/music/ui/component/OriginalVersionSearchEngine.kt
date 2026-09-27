/** MusicLab: find the oldest verifiable recording of a song. */
package com.metrolist.music.ui.component

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal object OriginalVersionSearchEngine {
    suspend fun findOldest(
        title: String,
        currentArtist: String,
        durationSec: Int,
        currentYouTubeId: String,
    ): CoverHubResult? = coroutineScope {
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

        val versions = linkedMapOf<String, CoverHubResult>()
        coversDeferred.await().forEach { versions.putIfAbsent(it.song.id, it) }

        var page = sameDeferred.await()
        page?.results.orEmpty().forEach { versions.putIfAbsent(it.song.id, it) }
        var continuation = page?.continuation
        var passes = 0
        while (continuation != null && versions.size < 80 && passes < 4) {
            passes++
            page = runCatching {
                CoverHubSearchEngine.searchSameNameMore(
                    title = title,
                    continuation = continuation!!,
                    currentYouTubeId = currentYouTubeId,
                    existingResults = versions.values.toList(),
                    geminiConfig = null,
                )
            }.getOrNull() ?: break
            page.results.forEach { versions[it.song.id] = it }
            continuation = page.continuation
        }

        val dated = versions.values.mapNotNull { result ->
            val year = result.year ?: runCatching { CoverYearResolver.resolve(result.song) }.getOrNull()
            year?.let { result.copy(year = it) }
        }

        dated.minWithOrNull(
            compareBy<CoverHubResult> { it.year ?: Int.MAX_VALUE }
                .thenByDescending { it.confirmed }
                .thenByDescending { it.score },
        )
    }
}
