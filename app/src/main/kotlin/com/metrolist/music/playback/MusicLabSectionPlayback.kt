package com.metrolist.music.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.metrolist.music.playback.queues.ListQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
        val selectionKey: String? = null,
        val selectedKey: String? = null,
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
    private var selectionKey: String? = null
    private val resolved = mutableMapOf<Int, MediaItem>()
    private var requestJob: Job? = null
    private var warmJob: Job? = null
    private var internalPlayback = false
    private var versionContext: android.content.Context? = null

    /** Called by the existing native player on any external/playlist selection. */
    fun externalSelection() {
        if (internalPlayback) return
        revision++
        requestJob?.cancel()
        warmJob?.cancel()
        entries = emptyList()
        resolver = null
        versionContext = null
        selectionKey = null
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
        selectionKey: String? = null,
    ) {
        if (selectedIndex !in orderedEntries.indices || selected.mediaId.isBlank()) return
        revision++
        val rev = revision
        requestJob?.cancel()
        warmJob?.cancel()
        versionContext = connection.service
        entries = orderedEntries.toList()
        resolver = findPlayable
        this.selectionKey = selectionKey
        resolved.clear()
        val effective = MusicLabManualVersionStore.apply(connection.service, selected)
        resolved[selectedIndex] = effective
        playAt(connection, source, selectedIndex, effective, rev)
    }

    private fun playAt(
        connection: PlayerConnection,
        source: String,
        index: Int,
        item: MediaItem,
        rev: Long,
    ) {
        if (rev != revision) return
        publishSelection(Snapshot(source, index, entries.size, item.mediaId, rev,
            selectionKey = selectionKey, selectedKey = entries.getOrNull(index)?.key))
        internalPlayback = true
        try {
            connection.beginPlaybackPriorityBurst("section-selection")
            // One synchronous selection. No deferred ListQueue hydration may rewind
            // the track after sound starts or after a manual seek.
            connection.playSectionNow(item, source)
        } finally {
            internalPlayback = false
        }
        scheduleNeighbour(connection, source, index, item, rev)
    }

    private fun publishSelection(snapshot: Snapshot) {
        snapshotFlow.value = snapshot
        val key = snapshot.selectionKey
        val selected = snapshot.selectedKey
        if (key != null && selected != null) {
            MusicLabListPositionStore.select(key, selected)
        }
    }

    /**
     * R62: published result sets can grow while the listener keeps playing.
     * Append only, never move the currently playing version or erase the
     * already shown cards. A new search/explicit selection still replaces it.
     */
    fun appendResults(
        connection: PlayerConnection,
        source: String,
        freshEntries: List<Entry>,
    ) {
        val current = snapshotFlow.value ?: return
        if (source != current.source ||
            connection.mediaMetadata.value?.id != current.mediaId) return
        val known = entries.mapTo(mutableSetOf()) { it.key }
        val additions = freshEntries.filter { known.add(it.key) }
        if (additions.isEmpty()) return
        entries = entries + additions
        snapshotFlow.value = current.copy(size = entries.size)
        scheduleNeighbour(connection, source, current.index,
            resolved[current.index] ?: connection.player.currentMediaItem ?: return,
            revision)
    }

    /** Read-only result window; normal playlists keep native Media3 behavior. */
    fun isEditorialSelection(mediaId: String?): Boolean = isCurrent(mediaId)

    private fun scheduleNeighbour(
        connection: PlayerConnection,
        source: String,
        index: Int,
        item: MediaItem,
        rev: Long,
    ) {
        // Deferred neighbour only. Never block selected audio for a full list lookup.
        warmJob?.cancel()
        warmJob = worker.launch {
            delay(750)
            if (rev != revision) return@launch
            val next = index + 1
            if (next !in entries.indices) return@launch
            while (connection.isPlaybackPriorityBurstActive() && rev == revision) delay(180)
            val nextItem = withTimeoutOrNull(9_000L) {
                lookup(next, rev)
            } ?: return@launch
            if (rev != revision || snapshotFlow.value?.index != index ||
                connection.mediaMetadata.value?.id != item.mediaId) return@launch
            // Repeated READY/transition events must never stack duplicates in
            // the native queue. The correct immediate neighbor is enough.
            val nativeNext = connection.player.nextMediaItemIndex
            if (nativeNext != C.INDEX_UNSET &&
                connection.player.getMediaItemAt(nativeNext).mediaId == nextItem.mediaId) {
                return@launch
            }
            connection.playNext(nextItem)
        }
    }

    private suspend fun lookup(index: Int, rev: Long): MediaItem? {
        resolved[index]?.let { return it }
        if (index !in entries.indices || rev != revision) return null
        val item = entries[index].ready ?: resolver?.invoke(entries[index]) ?: return null
        if (rev != revision || item.mediaId.isBlank()) return null
        val effective = versionContext?.let { MusicLabManualVersionStore.apply(it, item) } ?: item
        resolved[index] = effective
        return effective
    }

    /**
     * R62: both a manual swipe and natural end use the SAME ordered-context
     * advance. A temporarily unavailable recording cannot block the rest.
     * Only the latest explicit selection/revision is allowed to play.
     */
    private fun advance(
        connection: PlayerConnection,
        direction: Int,
        automatic: Boolean,
    ): Boolean {
        val current = snapshotFlow.value ?: return false
        if (connection.mediaMetadata.value?.id != current.mediaId) return false
        val step = if (direction > 0) 1 else -1
        val firstTarget = current.index + step
        if (firstTarget !in entries.indices) return true
        val rev = revision
        requestJob?.cancel()
        requestJob = worker.launch {
            try {
                val nextIndices = if (automatic) {
                    (firstTarget until entries.size).toList()
                } else listOf(firstTarget)
                for (target in nextIndices) {
                    if (rev != revision || snapshotFlow.value?.index != current.index ||
                        connection.mediaMetadata.value?.id != current.mediaId ||
                        (automatic && !connection.player.playWhenReady)) return@launch
                    val item = withTimeoutOrNull(if (automatic) 9_000L else 12_000L) {
                        lookup(target, rev)
                    } ?: continue
                    if (rev != revision || snapshotFlow.value?.index != current.index ||
                        connection.mediaMetadata.value?.id != current.mediaId) return@launch
                    val nativeNext = if (step > 0) connection.player.nextMediaItemIndex
                        else C.INDEX_UNSET
                    val prepared = nativeNext != C.INDEX_UNSET &&
                        connection.player.getMediaItemAt(nativeNext).mediaId == item.mediaId
                    if (prepared) {
                        publishSelection(current.copy(index = target, mediaId = item.mediaId,
                            selectedKey = entries[target].key))
                        connection.beginPlaybackPriorityBurst("section-prepared-next")
                        connection.player.seekTo(nativeNext, 0L)
                        if (connection.player.playbackState == Player.STATE_ENDED ||
                            connection.player.playbackState == Player.STATE_IDLE) {
                            connection.player.prepare()
                        }
                        connection.player.playWhenReady = true
                        scheduleNeighbour(connection, current.source, target, item, rev)
                    } else {
                        playAt(connection, current.source, target, item, rev)
                    }
                    return@launch
                }
                // No playable matches remain. Stop at the end of the actual
                // result list, not on YouTube radio or an obsolete queue item.
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Keep the current selection; later user taps may try again.
            }
        }
        return true
    }

    /** true = section-owned gesture, false = normal native playlist. */
    fun skip(connection: PlayerConnection, direction: Int): Boolean =
        advance(connection, direction, automatic = false)

    /**
     * Media3 reports ENDED when no preloaded next item existed. Start the next
     * real result, including when the previous source lookup was slow.
     */
    fun onPlaybackEnded(connection: PlayerConnection) {
        val current = snapshotFlow.value ?: return
        if (current.index + 1 >= entries.size ||
            connection.player.playbackState != Player.STATE_ENDED ||
            !connection.player.playWhenReady ||
            !connection.service.canAdvanceOrderedResults() ||
            connection.player.currentMediaItem?.mediaId != current.mediaId) return
        advance(connection, 1, automatic = true)
    }

    /** Preserve the R40 contextual index when just the recording version changes. */
    fun onManualVersionReplaced(oldId: String, replacement: MediaItem) {
        val current = snapshotFlow.value ?: return
        if (current.mediaId != oldId) return
        resolved[current.index] = replacement
        snapshotFlow.value = current.copy(mediaId = replacement.mediaId)
    }

    fun isCurrent(mediaId: String?): Boolean =
        !mediaId.isNullOrBlank() && snapshotFlow.value?.mediaId == mediaId

    /** Automatically prewarm each next result after every Media3 transition. */
    fun onMediaTransition(connection: PlayerConnection, mediaId: String?) {
        val current = snapshotFlow.value ?: return
        if (mediaId == null || mediaId == current.mediaId) return
        val next = current.index + 1
        if (resolved[next]?.mediaId == mediaId && next in entries.indices) {
            publishSelection(current.copy(index = next, mediaId = mediaId,
                selectedKey = entries[next].key))
            scheduleNeighbour(connection, current.source, next, resolved[next]!!, revision)
        } else {
            externalSelection()
        }
    }
}
