package com.metrolist.music.playback

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** R41: per-list selection and scroll persist even after process death. */
internal object MusicLabListPositionStore {
    data class Position(val index: Int = 0, val offset: Int = 0, val selectedKey: String? = null)
    private val values = ConcurrentHashMap<String, Position>()
    // Reactive row selection: a player swipe changes the highlighted card
    // even when no screen-local onClick handler runs.
    private val selectedKeysFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val selectedKeys: StateFlow<Map<String, String>> = selectedKeysFlow
    @Volatile private var application: Context? = null
    private val lastDiskScroll = ConcurrentHashMap<String, Position>()

    fun attach(context: Context) {
        application = context.applicationContext
    }

    fun get(key: String): Position =
        values[key] ?: synchronized(this) {
            values[key] ?: load(key).also { values[key] = it }
        }

    private fun load(key: String): Position {
        val app = application ?: return Position()
        val saved = MusicLabSectionMemory.readState(app, "position:$key")
        return Position(
            index = saved.optInt("index").coerceAtLeast(0),
            offset = saved.optInt("offset").coerceAtLeast(0),
            selectedKey = saved.optString("selected").takeIf { it.isNotBlank() },
        )
    }

    private fun save(key: String, position: Position) {
        val app = application ?: return
        MusicLabSectionMemory.writeState(app, "position:$key", org.json.JSONObject().apply {
            put("index", position.index)
            put("offset", position.offset)
            put("selected", position.selectedKey.orEmpty())
        })
        lastDiskScroll[key] = position
    }

    fun scroll(key: String, index: Int, offset: Int) {
        val previous = get(key)
        val next = previous.copy(index = index.coerceAtLeast(0), offset = offset.coerceAtLeast(0))
        values[key] = next
        // Coalesce sub-pixel/rapid scroll updates; flush always runs on leave.
        val persisted = lastDiskScroll[key]
        if (persisted == null || persisted.index != next.index ||
            kotlin.math.abs(persisted.offset - next.offset) >= 80) save(key, next)
    }

    fun flush(key: String) = save(key, get(key))

    fun select(key: String, selectedKey: String) {
        val next = get(key).copy(selectedKey = selectedKey)
        values[key] = next
        selectedKeysFlow.value = selectedKeysFlow.value + (key to selectedKey)
        save(key, next)
    }

    fun clear(key: String) {
        values.remove(key)
        lastDiskScroll.remove(key)
        selectedKeysFlow.value = selectedKeysFlow.value - key
        application?.let { MusicLabSectionMemory.eraseSection(it, "position:$key") }
    }
}
