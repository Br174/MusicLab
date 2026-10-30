package com.metrolist.music.playback

import androidx.media3.common.Player

/** MusicLab Turbo R20: bounded speculative network work must never outrank playing music. */
internal object PlaybackSprintPolicy {
    const val COVER_PRELOAD_RESERVE_MS = 40_000L

    fun requiredPreloadReserve(coverActive: Boolean, normalReserveMs: Long): Long =
        if (coverActive) maxOf(COVER_PRELOAD_RESERVE_MS, normalReserveMs) else normalReserveMs

    fun canPreload(
        isPlaying: Boolean,
        isReady: Boolean,
        priorityBurst: Boolean,
        coverHeavy: Boolean,
        bufferedMs: Long,
        requiredReserveMs: Long,
    ): Boolean =
        isPlaying && isReady && !priorityBurst && !coverHeavy && bufferedMs >= requiredReserveMs

    // R22: recovery applies throughout the song, never solely to position <5s.
    // Two guarded attempts maximum per track prevent endless recovery loops.
    const val BUFFER_STALL_RECOVERY_DELAY_MS = 5_000L
    const val MAX_BUFFER_STALL_RECOVERIES = 2

    fun shouldRecoverBufferStall(
        isBuffering: Boolean,
        playRequested: Boolean,
        sameMediaId: Boolean,
        online: Boolean,
        recoveryAttempts: Int,
    ): Boolean =
        isBuffering && playRequested && sameMediaId && online &&
            recoveryAttempts < MAX_BUFFER_STALL_RECOVERIES

    // R21: an already selected song cannot leave IDLE by playWhenReady alone.
    fun shouldPrepareExistingTrack(playbackState: Int): Boolean =
        playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED

    // One existing smart-preload worker owns the next item; prefer an already
    // documented Cover video without adding another download/resolver coordinator.
    fun nextPreloadMediaId(
        coverActive: Boolean,
        currentMediaId: String,
        nextCoverVideoId: String?,
        queuedMediaId: String?,
    ): String? {
        val coverId = nextCoverVideoId?.takeIf {
            coverActive && it != currentMediaId && it.matches(Regex("[A-Za-z0-9_-]{11}"))
        }
        return coverId ?: queuedMediaId?.takeIf { it.isNotBlank() && it != currentMediaId }
    }
}
