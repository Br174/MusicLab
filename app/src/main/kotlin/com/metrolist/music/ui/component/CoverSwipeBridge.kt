package com.metrolist.music.ui.component

/**
 * Cover-only swipe ownership. The normal playlist carousel is unrelated to
 * Cover's verified-videos order and must not show its previous/next posters.
 *
 * R61: retain just the outgoing and accepted incoming Cover IDs throughout
 * the player transition. No unrelated queue card can become Cover artwork.
 */
internal object CoverSwipeBridge {
    private var owner: String? = null
    private var handler: ((Int, String) -> Unit)? = null
    private var currentCoverVideoId: String? = null
    private var incomingCoverVideoId: String? = null
    private var selectionPending = false

    fun bind(screen: String, swipe: (direction: Int, currentlyPlayingVideoId: String) -> Unit) {
        if (owner != screen) {
            currentCoverVideoId = null
            incomingCoverVideoId = null
            selectionPending = false
        }
        owner = screen
        handler = swipe
    }

    /** The selection is pending, but the outgoing poster is still the right one. */
    fun beginSelection(screen: String) {
        if (owner != screen) return
        selectionPending = true
        incomingCoverVideoId = null
    }

    /**
     * Called before setMediaItem/playNow so the first Compose frame for the
     * new Media3 ID never falls back to the ordinary queue's third poster.
     */
    fun expectIncoming(screen: String, videoId: String) {
        if (owner == screen && videoId.isNotBlank()) {
            incomingCoverVideoId = videoId
        }
    }

    /** Cancel a failed selection without losing swipe ownership of the song. */
    fun cancelSelection(screen: String) {
        if (owner == screen) {
            selectionPending = false
            incomingCoverVideoId = null
        }
    }

    fun activate(screen: String, videoId: String) {
        if (owner == screen) {
            currentCoverVideoId = videoId
            incomingCoverVideoId = null
            selectionPending = false
        }
    }

    fun stop(screen: String) {
        if (owner == screen) {
            currentCoverVideoId = null
            incomingCoverVideoId = null
            selectionPending = false
        }
    }

    fun clear(screen: String) {
        if (owner == screen) {
            owner = null
            handler = null
            currentCoverVideoId = null
            incomingCoverVideoId = null
            selectionPending = false
        }
    }

    /** Read-only: true for ONLY the outgoing or incoming Cover recording. */
    fun isCoverArtwork(actualVideoId: String): Boolean =
        owner != null && actualVideoId.isNotBlank() &&
            (actualVideoId == currentCoverVideoId ||
                actualVideoId == incomingCoverVideoId)

    /** Consume a second gesture while the first swap is still in flight. */
    fun trySwipe(actualVideoId: String, direction: Int): Boolean {
        if (owner == null || actualVideoId.isBlank() ||
            currentCoverVideoId != actualVideoId) return false
        if (selectionPending) return true
        val callback = handler ?: return false
        callback(if (direction >= 0) 1 else -1, actualVideoId)
        return true
    }
}
