package com.metrolist.music.playback

/**
 * R62: deterministic boundary decisions for all MusicLab editorial lists.
 * Native album and radio queues continue to use Media3's normal timeline.
 */
internal object OrderedResultAutoplayPolicy {
    fun candidates(currentIndex: Int, total: Int, direction: Int, automatic: Boolean): List<Int> {
        if (total <= 0 || currentIndex !in 0 until total) return emptyList()
        val next = currentIndex + if (direction > 0) 1 else -1
        if (next !in 0 until total) return emptyList()
        return if (automatic && direction > 0) (next until total).toList()
            else listOf(next)
    }

    fun shouldAdvanceAtEnd(
        owned: Boolean,
        ended: Boolean,
        wantsPlayback: Boolean,
        timerAllows: Boolean,
        hasMoreResults: Boolean,
    ): Boolean = owned && ended && wantsPlayback && timerAllows && hasMoreResults
}
