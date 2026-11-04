package com.metrolist.music.playback

/**
 * R63: deterministic latest-finger-intent, shared by Cover, Compilation,
 * Hit Parade, Classifiche, Generi and general search result sequences.
 */
internal object MusicLabSwipeInputPolicy {
    fun nextTarget(
        current: Int,
        total: Int,
        direction: Int,
        pendingTarget: Int? = null,
    ): Int? {
        if (total <= 0 || current !in 0 until total || direction == 0) return null
        val base = pendingTarget?.takeIf { it in 0 until total } ?: current
        val next = base + if (direction > 0) 1 else -1
        return next.takeIf { it in 0 until total }
    }
}
