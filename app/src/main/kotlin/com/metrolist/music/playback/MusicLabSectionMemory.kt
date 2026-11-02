package com.metrolist.music.playback

import android.content.Context
import org.json.JSONObject

/**
 * R41: durable, locally owned UI memory. Never contains user tokens or playback
 * queue data; writing/restoring it must never invoke a player operation.
 * Keep snapshots small and bounded for a fast Android first frame.
 */
internal object MusicLabSectionMemory {
    private const val PREFS = "musiclab_section_memory_r41"
    private const val MAX_SNAPSHOT_BYTES = 850_000

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun text(context: Context, section: String, property: String, default: String = ""): String =
        prefs(context).getString("$section|$property", default) ?: default

    fun putText(context: Context, section: String, property: String, value: String) {
        if (value.length <= MAX_SNAPSHOT_BYTES) {
            prefs(context).edit().putString("$section|$property", value).apply()
        }
    }

    fun number(context: Context, section: String, property: String, default: Long = 0): Long =
        prefs(context).getLong("$section|$property", default)

    fun putNumber(context: Context, section: String, property: String, value: Long) {
        prefs(context).edit().putLong("$section|$property", value).apply()
    }

    fun readState(context: Context, section: String): JSONObject =
        runCatching { JSONObject(text(context, section, "state").ifEmpty { "{}" }) }
            .getOrDefault(JSONObject())

    fun writeState(context: Context, section: String, state: JSONObject) =
        putText(context, section, "state", state.toString())

    fun eraseSection(context: Context, section: String) {
        prefs(context).edit().remove("$section|state").apply()
    }
}
