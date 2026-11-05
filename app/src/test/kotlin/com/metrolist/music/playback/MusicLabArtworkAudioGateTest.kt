package com.metrolist.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure time/priority policy: no network, device or player initialization. */
class MusicLabArtworkAudioGateTest {
    @Test fun artworkCannotStartInsideUserTapPriorityWindow() {
        assertFalse(MusicLabArtworkAudioGate.mayStart(
            nowMs = 1_000L, reserveUntilMs = 7_000L, buffering = false,
        ))
        assertFalse(MusicLabArtworkAudioGate.mayStart(
            nowMs = 6_999L, reserveUntilMs = 7_000L, buffering = false,
        ))
    }

    @Test fun metadataMayResumeAfterAudioHeadStart() {
        assertTrue(MusicLabArtworkAudioGate.mayStart(
            nowMs = 7_000L, reserveUntilMs = 7_000L, buffering = false,
        ))
    }

    @Test fun rebufferingPreemptsArtworkEvenAfterDeadline() {
        assertFalse(MusicLabArtworkAudioGate.mayStart(
            nowMs = 90_000L, reserveUntilMs = 7_000L, buffering = true,
        ))
    }

    @Test fun idleAndPausedAudioNeverNeedProviderThrottleAfterReservation() {
        assertTrue(MusicLabArtworkAudioGate.mayStart(
            nowMs = 30_000L, reserveUntilMs = 0L, buffering = false,
        ))
    }

    @Test fun boundedWaitAndSmallProviderFanOutStayConstant() {
        assertTrue(MusicLabArtworkAudioGate.FIRST_AUDIO_RESERVE_MS in 4_000L..8_000L)
        assertTrue(MusicLabArtworkAudioGate.ARTWORK_SAFE_WAIT_MS in 7_000L..12_000L)
    }
}
