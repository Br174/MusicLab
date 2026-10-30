package com.metrolist.music.playback

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
}
