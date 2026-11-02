package com.metrolist.music.ui.component

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Targeted R40 recovery. Never lower the identity predicate; a provider hit
 * without matching artist/title is not substituted for the requested work.
 * It is called only if the established MusicLab FastPath misses.
 */
internal object MusicLabTrackRecovery {
    suspend fun find(
        title: String,
        artist: String,
        compatible: (SongItem) -> Boolean,
    ): SongItem? {
        MusicLabFastPath.find(title, artist, compatible)?.let { return it }
        if (title.isBlank()) return null
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(5_000L) {
                val precise = listOf(artist.trim(), title.trim()).filter(String::isNotBlank)
                    .joinToString(" ")
                val videos = YouTube.search(precise, YouTube.SearchFilter.FILTER_VIDEO)
                    .getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                videos.firstOrNull(compatible)?.let { return@withTimeoutOrNull it }
                // Catalogues occasionally omit the performer in search indexing.
                // A title-only fallback is allowed only with real matching metadata.
                val titleOnly = YouTube.search(title.trim(), YouTube.SearchFilter.FILTER_SONG)
                    .getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                titleOnly.firstOrNull(compatible)
            }
        }
    }
}
