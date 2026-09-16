/**
 * MusicLab cover year resolver
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a song's release year from its YouTube Music album metadata.
 *
 * SongItem itself does not expose a year, so we look up the album only when one
 * is available. Missing years are cached as zero to avoid repeating failed
 * network requests while the cover screen is open/reopened.
 */
internal object CoverYearResolver {
    private const val UNKNOWN_YEAR = 0
    private val cache = ConcurrentHashMap<String, Int>()

    suspend fun resolve(song: SongItem): Int? {
        val albumId = song.album?.id?.takeIf { it.isNotBlank() } ?: return null

        cache[albumId]?.let { cached ->
            return cached.takeIf { it != UNKNOWN_YEAR }
        }

        val year = runCatching {
            YouTube.album(albumId, withSongs = false)
                .getOrNull()
                ?.album
                ?.year
        }.getOrNull()

        cache[albumId] = year ?: UNKNOWN_YEAR
        return year
    }
}
