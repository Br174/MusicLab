package com.metrolist.music.ui.screens.library

/** UI-only provenance filters; source records, subscriptions and sync are unchanged. */
enum class PlaylistSourceFilter { ALL, SPOTIFY, YOUTUBE_MUSIC }

internal object PlaylistSourcePolicy {
    fun isYouTubeMusic(browseId: String?, isLocal: Boolean): Boolean =
        !browseId.isNullOrBlank() && !isLocal

    fun acceptsSavedPlaylist(filter: PlaylistSourceFilter, browseId: String?, isLocal: Boolean): Boolean =
        when (filter) {
            PlaylistSourceFilter.ALL -> true
            PlaylistSourceFilter.SPOTIFY -> false
            PlaylistSourceFilter.YOUTUBE_MUSIC -> isYouTubeMusic(browseId, isLocal)
        }

    fun showsSpotify(filter: PlaylistSourceFilter): Boolean =
        filter != PlaylistSourceFilter.YOUTUBE_MUSIC

    fun showsAutomatic(filter: PlaylistSourceFilter): Boolean =
        filter == PlaylistSourceFilter.ALL

    fun showsYouTubeLiked(filter: PlaylistSourceFilter): Boolean =
        filter != PlaylistSourceFilter.SPOTIFY
}
