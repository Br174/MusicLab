package com.metrolist.music.ui.component

/**
 * LAB64 Pollicino: opt-in swipe mode for covers only.
 * The default player carousel never changes outside the Cover search screen.
 * All callbacks are installed by the active Cover screen and released on exit.
 */
internal object CoverSwipeBridge {
    private var owner: String? = null
    private var handler: ((Int, String) -> Unit)? = null
    private var currentCoverVideoId: String? = null

    fun bind(screen: String, swipe: (direction: Int, currentlyPlayingVideoId: String) -> Unit) {
        owner = screen
        handler = swipe
    }

    fun activate(screen: String, videoId: String) {
        if (owner == screen) currentCoverVideoId = videoId
    }

    fun stop(screen: String) {
        if (owner == screen) currentCoverVideoId = null
    }

    fun clear(screen: String) {
        if (owner == screen) {
            owner = null
            handler = null
            currentCoverVideoId = null
        }
    }

    /** True only if the actual player is still playing the Cover-owned ID. */
    fun trySwipe(actualVideoId: String, direction: Int): Boolean {
        if (owner == null || actualVideoId.isBlank() || currentCoverVideoId != actualVideoId) return false
        val callback = handler ?: return false
        callback(if (direction >= 0) 1 else -1, actualVideoId)
        return true
    }
}
