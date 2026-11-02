package com.metrolist.music.playback

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/**
 * R44: one durable, non-audio checkpoint contract for section work.
 * The owners of discovery (Cover, Compilation, Charts, Search) remain native.
 * It never runs a worker, changes playback, saves tokens, or auto-starts media.
 */
internal object MusicLabSectionWorkCheckpoint {
    enum class Phase { RUNNING, STANDBY, COMPLETE }

    internal data class Progress(
        val phase: Phase = Phase.STANDBY,
        val cursor: Int = 0,
        val total: Int = 0,
    )

    private const val PREFS = "musiclab_section_work_r44"

    private fun key(section: String, request: String): String {
        val raw = "$section|$request"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun read(context: Context, section: String, request: String): Progress {
        val raw = prefs(context).getString(key(section, request), null) ?: return Progress()
        return runCatching {
            val json = JSONObject(raw)
            Progress(
                phase = Phase.values().firstOrNull {
                    it.name == json.optString("phase")
                } ?: Phase.STANDBY,
                cursor = json.optInt("cursor").coerceAtLeast(0),
                total = json.optInt("total").coerceAtLeast(0),
            )
        }.getOrDefault(Progress())
    }

    private fun write(context: Context, section: String, request: String, progress: Progress) {
        val data = JSONObject()
            .put("phase", progress.phase.name)
            .put("cursor", progress.cursor)
            .put("total", progress.total)
        // Small synchronous commit only at checkpoint boundaries, so a screen
        // switch or process death cannot discard the last acknowledged cursor.
        prefs(context).edit().putString(key(section, request), data.toString()).commit()
    }

    @Synchronized
    fun begin(context: Context, section: String, request: String): Boolean {
        val old = read(context, section, request)
        if (old.phase == Phase.COMPLETE) return false
        write(context, section, request, old.copy(phase = Phase.RUNNING))
        return true
    }

    @Synchronized
    fun advance(context: Context, section: String, request: String, cursor: Int, total: Int = 0) {
        val old = read(context, section, request)
        if (old.phase == Phase.COMPLETE) return
        write(context, section, request, old.copy(
            phase = Phase.RUNNING,
            cursor = maxOf(old.cursor, cursor.coerceAtLeast(0)),
            total = maxOf(old.total, total.coerceAtLeast(0)),
        ))
    }

    @Synchronized
    fun standby(context: Context, section: String, request: String) {
        val old = read(context, section, request)
        if (old.phase != Phase.COMPLETE) write(context, section, request,
            old.copy(phase = Phase.STANDBY))
    }

    @Synchronized
    fun complete(context: Context, section: String, request: String, cursor: Int = 0) {
        val old = read(context, section, request)
        write(context, section, request, old.copy(
            phase = Phase.COMPLETE,
            cursor = maxOf(old.cursor, cursor.coerceAtLeast(0)),
        ))
    }

    @Synchronized
    fun reset(context: Context, section: String, request: String) {
        prefs(context).edit().remove(key(section, request)).commit()
    }
}
