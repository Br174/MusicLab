/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata

class ListQueue(
    val title: String? = null,
    val items: List<MediaItem>,
    val startIndex: Int = 0,
    val position: Long = 0L,
) : Queue {
    /**
     * Give MusicService the selected item immediately so playback can prepare before the
     * remainder of the queue is attached in background. Resume queues with a non-zero
     * position keep the previous path so their exact position is preserved.
     */
    override val preloadItem: MediaMetadata? =
        if (position == 0L) items.getOrNull(startIndex)?.metadata else null

    override suspend fun getInitialStatus() = Queue.Status(title, items, startIndex, position)

    override fun hasNextPage(): Boolean = false

    override suspend fun nextPage() = throw UnsupportedOperationException()
}
