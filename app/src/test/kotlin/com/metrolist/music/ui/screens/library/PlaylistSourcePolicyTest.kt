package com.metrolist.music.ui.screens.library

import org.junit.Assert.*
import org.junit.Test

/** No provider leaks, no deletion, and no title-based source guessing. */
class PlaylistSourcePolicyTest {
    @Test fun youtubeMusicOnlyShowsRemoteYouTubePlaylists() {
        val f=PlaylistSourceFilter.YOUTUBE_MUSIC
        assertTrue(PlaylistSourcePolicy.acceptsSavedPlaylist(f,"VLabc123",false))
        assertFalse(PlaylistSourcePolicy.acceptsSavedPlaylist(f,null,false))
        assertFalse(PlaylistSourcePolicy.acceptsSavedPlaylist(f,"VLabc123",true))
        assertFalse(PlaylistSourcePolicy.acceptsSavedPlaylist(f,"",false))
        assertFalse(PlaylistSourcePolicy.showsSpotify(f))
        assertFalse(PlaylistSourcePolicy.showsAutomatic(f))
        assertTrue(PlaylistSourcePolicy.showsYouTubeLiked(f))
    }
    @Test fun spotifyOnlyUsesSpotifyViewModelResults() {
        val f=PlaylistSourceFilter.SPOTIFY
        assertFalse(PlaylistSourcePolicy.acceptsSavedPlaylist(f,"VLabc123",false))
        assertFalse(PlaylistSourcePolicy.acceptsSavedPlaylist(f,null,true))
        assertTrue(PlaylistSourcePolicy.showsSpotify(f))
        assertFalse(PlaylistSourcePolicy.showsAutomatic(f))
        assertFalse(PlaylistSourcePolicy.showsYouTubeLiked(f))
    }
    @Test fun allPlaylistsKeepsLocalYoutubeAndSpotify() {
        val f=PlaylistSourceFilter.ALL
        assertTrue(PlaylistSourcePolicy.acceptsSavedPlaylist(f,"VLabc123",false))
        assertTrue(PlaylistSourcePolicy.acceptsSavedPlaylist(f,null,true))
        assertTrue(PlaylistSourcePolicy.showsSpotify(f))
        assertTrue(PlaylistSourcePolicy.showsAutomatic(f))
        assertTrue(PlaylistSourcePolicy.showsYouTubeLiked(f))
    }
}
