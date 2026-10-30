package com.metrolist.music.playback

import org.junit.Assert.*
import org.junit.Test

/** No network, no Android context. R20 sprint decision tests. */
class PlaybackSprintPolicyTest {
    @Test fun coverContextRequiresHigherReserveThanOrdinaryNextTrack() {
        assertEquals(30_000L, PlaybackSprintPolicy.requiredPreloadReserve(false, 30_000L))
        assertEquals(40_000L, PlaybackSprintPolicy.requiredPreloadReserve(true, 30_000L))
        assertEquals(40_000L, PlaybackSprintPolicy.requiredPreloadReserve(true, 26_000L))
    }

    @Test fun playbackAlwaysWinsDuringBufferingAndStartupBursts() {
        assertFalse(PlaybackSprintPolicy.canPreload(true,false,false,false,40_000L,40_000L))
        assertFalse(PlaybackSprintPolicy.canPreload(true,true,true,false,40_000L,40_000L))
        assertFalse(PlaybackSprintPolicy.canPreload(true,true,false,true,50_000L,40_000L))
        assertFalse(PlaybackSprintPolicy.canPreload(true,true,false,false,39_999L,40_000L))
        assertFalse(PlaybackSprintPolicy.canPreload(false,true,false,false,50_000L,40_000L))
    }

    @Test fun safeSingleTrackPreloadCanStartWhenReserveIsHealthy() {
        assertTrue(PlaybackSprintPolicy.canPreload(true,true,false,false,40_000L,40_000L))
        assertTrue(PlaybackSprintPolicy.canPreload(true,true,false,false,30_000L,30_000L))
    }
}
