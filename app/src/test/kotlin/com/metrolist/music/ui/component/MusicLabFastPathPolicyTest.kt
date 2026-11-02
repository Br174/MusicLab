package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class MusicLabFastPathPolicyTest {
    @Test fun boundedVisibleSearchDoesNotSaturateFullAlbums() {
        assertEquals(4, MusicLabFastPathPolicy.SEARCH_PARALLELISM)
        assertEquals(2, MusicLabFastPathPolicy.COMPILATION_WARM_TRACKS)
        assertEquals(2, MusicLabFastPathPolicy.COMPILATION_VISIBLE_WARM_PARALLELISM)
        assertTrue(MusicLabFastPathPolicy.COMPILATION_VISIBLE_WARM_PARALLELISM < MusicLabFastPathPolicy.SEARCH_PARALLELISM)
    }

    @Test fun oldAlbumOrExplicitSongTapCancelsProgressiveAlbum() {
        assertTrue(MusicLabFastPathPolicy.mayContinueCompilation(123,123,null))
        assertFalse(MusicLabFastPathPolicy.mayContinueCompilation(122,123,null))
        assertFalse(MusicLabFastPathPolicy.mayContinueCompilation(null,123,null))
        assertFalse(MusicLabFastPathPolicy.mayContinueCompilation(123,123,2))
    }

    @Test fun appendedSongsMustBeNewAndFromSameMusicLabQueue() {
        val queueIds = setOf("song-a", "song-b")
        assertTrue(MusicLabFastPathPolicy.mayAppendResolvedSong("song-a",queueIds,"song-c"))
        assertTrue(MusicLabFastPathPolicy.mayAppendResolvedSong("song-b",queueIds,"song-c"))
        assertFalse(MusicLabFastPathPolicy.mayAppendResolvedSong("song-z",queueIds,"song-c"))
        assertFalse(MusicLabFastPathPolicy.mayAppendResolvedSong("song-a",queueIds,"song-a"))
        assertFalse(MusicLabFastPathPolicy.mayAppendResolvedSong("song-a",queueIds,""))
    }

    @Test fun immediateFirstTrackCanJoinStillInitializingQueue() {
        assertTrue(MusicLabFastPathPolicy.mayAppendResolvedSong(null,setOf("song-a"),"song-b"))
    }
}
