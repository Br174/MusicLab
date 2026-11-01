package com.metrolist.music.ui.component

/**
 * R36 native MusicLab search reuse. Pure deterministic decisions for
 * first-audio priority, bounded visible prewarm and stale-result isolation.
 */
internal object MusicLabFastPathPolicy {
    const val SEARCH_PARALLELISM = 4
    const val COMPILATION_WARM_TRACKS = 2

    fun mayContinueCompilation(
        currentAlbumId: Int?,
        requestedAlbumId: Int,
        explicitTrackSelection: Int?,
    ): Boolean = currentAlbumId == requestedAlbumId && explicitTrackSelection == null

    fun mayAppendResolvedSong(
        currentSongId: String?,
        musicLabQueueIds: Set<String>,
        newSongId: String,
    ): Boolean =
        newSongId.isNotBlank() &&
            newSongId !in musicLabQueueIds &&
            (currentSongId == null || currentSongId in musicLabQueueIds)
}
