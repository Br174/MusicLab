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

    // R35: asynchronous queue results are accepted only for the latest tap.
    fun isLatestQueueRequest(requestSerial: Long, activeSerial: Long): Boolean =
        requestSerial == activeSerial

    // R35: a visual carousel must not pretend that a short or vertical drag
    // is a successful audio transition.
    fun committedSwipeDirection(
        horizontalPx: Float,
        verticalPx: Float,
        minimumPx: Float,
    ): Int = when {
        kotlin.math.abs(horizontalPx) <= minimumPx -> 0
        kotlin.math.abs(horizontalPx) <= kotlin.math.abs(verticalPx) -> 0
        horizontalPx < 0f -> 1
        else -> -1
    }

    // R34: network timeouts, decoder resets and expired URLs must not destroy
    // the healthy cached audio prefix. Purge only confirmed damaged cache/ranges.
    fun shouldPurgeCachedAudio(fileMissing: Boolean, invalidRange: Boolean): Boolean =
        fileMissing || invalidRange

    // Optional network work must never compete with the current song's short reserve.
    fun canRunOptionalBackgroundAudioWork(
        isReady: Boolean,
        wantsPlayback: Boolean,
        bufferedMs: Long,
        minimumReserveMs: Long,
    ): Boolean = isReady && wantsPlayback && bufferedMs >= minimumReserveMs

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
