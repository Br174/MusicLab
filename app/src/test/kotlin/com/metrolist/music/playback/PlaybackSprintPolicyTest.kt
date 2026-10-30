package com.metrolist.music.playback

import androidx.media3.common.Player
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

    @Test fun sameSongIdleMustPrepareButReadyMustNotReprepare() {
        assertTrue(PlaybackSprintPolicy.shouldPrepareExistingTrack(Player.STATE_IDLE))
        assertTrue(PlaybackSprintPolicy.shouldPrepareExistingTrack(Player.STATE_ENDED))
        assertFalse(PlaybackSprintPolicy.shouldPrepareExistingTrack(Player.STATE_BUFFERING))
        assertFalse(PlaybackSprintPolicy.shouldPrepareExistingTrack(Player.STATE_READY))
    }

    @Test fun oneWorkerPreloadPrefersDocumentedCoverButRejectsOldOrInvalidId() {
        val current = "current0001"
        val validNext = "nextcover01"
        assertEquals(
            validNext,
            PlaybackSprintPolicy.nextPreloadMediaId(true, current, validNext, "queue-track"),
        )
        assertEquals(
            "queue-track",
            PlaybackSprintPolicy.nextPreloadMediaId(false, current, validNext, "queue-track"),
        )
        assertEquals(
            "queue-track",
            PlaybackSprintPolicy.nextPreloadMediaId(true, current, current, "queue-track"),
        )
        assertEquals(
            "queue-track",
            PlaybackSprintPolicy.nextPreloadMediaId(true, current, "not valid!", "queue-track"),
        )
        assertNull(PlaybackSprintPolicy.nextPreloadMediaId(true, current, null, current))
    }

    @Test fun midSongBufferStallIsRecoverableWithBoundedAttempts() {
        assertEquals(9_000L, PlaybackSprintPolicy.BUFFER_STALL_RECOVERY_DELAY_MS)
        assertTrue(PlaybackSprintPolicy.shouldRecoverBufferStall(
            isBuffering = true,
            playRequested = true,
            sameMediaId = true,
            online = true,
            recoveryAttempts = 0,
        ))
        assertTrue(PlaybackSprintPolicy.shouldRecoverBufferStall(true, true, true, true, 1))
        assertFalse(PlaybackSprintPolicy.shouldRecoverBufferStall(true, true, true, true, 2))
        assertFalse(PlaybackSprintPolicy.shouldRecoverBufferStall(false, true, true, true, 0))
        assertFalse(PlaybackSprintPolicy.shouldRecoverBufferStall(true, false, true, true, 0))
        assertFalse(PlaybackSprintPolicy.shouldRecoverBufferStall(true, true, false, true, 0))
        assertFalse(PlaybackSprintPolicy.shouldRecoverBufferStall(true, true, true, false, 0))
    }

    @Test fun safeSingleTrackPreloadCanStartWhenReserveIsHealthy() {
        assertTrue(PlaybackSprintPolicy.canPreload(true,true,false,false,40_000L,40_000L))
        assertTrue(PlaybackSprintPolicy.canPreload(true,true,false,false,30_000L,30_000L))
    }
}
