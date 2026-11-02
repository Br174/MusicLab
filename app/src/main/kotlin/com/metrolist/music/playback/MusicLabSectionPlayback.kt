package com.metrolist.music.playback

import androidx.media3.common.MediaItem
import com.metrolist.music.playback.queues.ListQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * R40: the last EXPLICIT song selection owns the ordered list. Opening a different
 * screen or fetching results is never a new selection. A repeated tap on the same
 * media ID must still start at 0:00 and may rebind to another list.
 *
 * Native playlists keep their existing Queue implementation. This bridge handles
 * editorial lists whose source rows are not all resolved to playable IDs yet.
 */
internal object MusicLabSectionPlayback {
    data class Entry(
        val key: String,
        val title: String,
        val artist: String,
        val ready: MediaItem? = null,
    )

    data class Snapshot(
        val source: String,
        val index: Int,
        val size: Int,
        val mediaId: String,
        val revision: Long,
    ) {
        val canPrevious get() = index > 0
        val canNext get() = index + 1 < size
    }

    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val snapshotFlow = MutableStateFlow<Snapshot?>(null)
    val active: StateFlow<Snapshot?> = snapshotFlow
    private var revision = 0L
    private var entries: List<Entry> = emptyList()
    private var resolver: (suspend (Entry) -> MediaItem?)? = null
    private val resolved = mutableMapOf<Int, MediaItem>()
    private var requestJob: Job? = null
    private var warmJob: Job? = null
    private var internalPlayback = false

    /** Called by the existing native player on any external/playlist selection. */
    fun externalSelection() {
        if (internalPlayback) return
        revision++
        requestJob?.cancel()
        warmJob?.cancel()
        entries = emptyList()
        resolver = null
        resolved.clear()
        snapshotFlow.value = null
    }

    fun select(
        connection: PlayerConnection,
        source: String,
        orderedEntries: List<Entry>,
        selectedIndex: Int,
        selected: MediaItem,
        findPlayable: suspend (Entry) -> MediaItem?,
    ) {
        if (selectedIndex !in orderedEntries.indices || selected.mediaId.isBlank()) return
        revision++
        val rev = revision
        requestJob?.cancel()
        warmJob?.cancel()
        entries = orderedEntries.toList()
        resolver = findPlayable
        resolved.clear()
        resolved[selectedIndex] = selected
        playAt(connection, source, selectedIndex, selected, rev)
    }

    private fun playAt(
        connection: PlayerConnection,
        source: String,
        index: Int,
        item: MediaItem,
        rev: Long,
    ) {
        if (rev != revision) return
        snapshotFlow.value = Snapshot(source, index, entries.size, item.mediaId, rev)
        internalPlayback = true
        try {
            connection.beginPlaybackPriorityBurst("section-selection")
            // Always reset to the first millisecond even if the video ID is unchanged.
            connection.playQueue(ListQueue(title = source, items = listOf(item), startIndex = 0, position = 0L))
        } finally {
            internalPlayback = false
        }
        // Deferred neighbour only. Never block selected audio for a full list lookup.
        warmJob?.cancel()
        warmJob = worker.launch {
            delay(750)
            if (rev != revision) return@launch
            val next = index + 1
            if (next !in entries.indices) return@launch
            while (connection.isPlaybackPriorityBurstActive() && rev == revision) delay(180)
            val nextItem = lookup(next, rev) ?: return@launch
            if (rev != revision || snapshotFlow.value?.index != index ||
                connection.mediaMetadata.value?.id != item.mediaId) return@launch
            connection.playNext(nextItem)
        }
    }

    private suspend fun lookup(index: Int, rev: Long): MediaItem? {
        resolved[index]?.let { return it }
        if (index !in entries.indices || rev != revision) return null
        val item = entries[index].ready ?: resolver?.invoke(entries[index])
        if (rev != revision || item?.mediaId.isNullOrBlank()) return null
        resolved[index] = item
        return item
    }

    /** true = contextual skip accepted; false = use the native player's queue. */
    fun skip(connection: PlayerConnection, direction: Int): Boolean {
        val current = snapshotFlow.value ?: return false
        if (connection.mediaMetadata.value?.id != current.mediaId) return false
        val target = current.index + if (direction > 0) 1 else -1
        if (target !in entries.indices) return true // Keep section boundaries.
        val rev = revision
        requestJob?.cancel()
        requestJob = worker.launch {
            try {
                val item = lookup(target, rev) ?: return@launch
                if (rev != revision || snapshotFlow.value?.index != current.index ||
                    connection.mediaMetadata.value?.id != current.mediaId) return@launch
                playAt(connection, current.source, target, item, rev)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // A missing or temporarily unavailable version must not be
                // replaced by a different track or a generic YouTube radio.
            }
        }
        return true
    }

    fun isCurrent(mediaId: String?): Boolean =
        !mediaId.isNullOrBlank() && snapshotFlow.value?.mediaId == mediaId

    /** Automatic end-of-track transition must advance the same contextual list. */
    fun onMediaTransition(mediaId: String?) {
        val current = snapshotFlow.value ?: return
        if (mediaId == null || mediaId == current.mediaId) return
        val next = current.index + 1
        if (resolved[next]?.mediaId == mediaId && next in entries.indices) {
            snapshotFlow.value = current.copy(index = next, mediaId = mediaId)
        } else {
            externalSelection()
        }
    }
}
