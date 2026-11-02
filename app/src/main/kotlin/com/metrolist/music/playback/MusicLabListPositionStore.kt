package com.metrolist.music.playback

import java.util.concurrent.ConcurrentHashMap

/** R40 lightweight UI position ledger; never changes search results or audio. */
internal object MusicLabListPositionStore {
    data class Position(val index: Int = 0, val offset: Int = 0, val selectedKey: String? = null)
    private val values = ConcurrentHashMap<String, Position>()
    fun get(key: String): Position = values[key] ?: Position()
    fun scroll(key: String, index: Int, offset: Int) {
        val previous = get(key)
        values[key] = previous.copy(index = index.coerceAtLeast(0), offset = offset.coerceAtLeast(0))
    }
    fun select(key: String, selectedKey: String) {
        values[key] = get(key).copy(selectedKey = selectedKey)
    }
    fun clear(key: String) { values.remove(key) }
}
