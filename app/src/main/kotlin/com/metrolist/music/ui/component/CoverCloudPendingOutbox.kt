package com.metrolist.music.ui.component

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * LAB46: durable editorial outbox, never proof of remote synchronization.
 *
 * Every record includes the exact original work and a server-shaped payload,
 * and is kept until an authenticated server acknowledgement names the SAME
 * pending revision. Local approval remains independent of network state.
 * Only tiny metadata is saved; no audio, videos, artwork bytes or secrets.
 */
internal object CoverCloudPendingOutbox {
    private const val MAX_PENDING_PER_WORK = 2000
    private const val MAX_BYTES = 2_000_000

    private fun file(context: Context, workKey: String): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(workKey.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(context.applicationContext.filesDir, "musiclab-cloud-outbox-$digest.json"))
    }

    private fun load(file: AtomicFile): JSONArray = runCatching {
        val bytes = file.openRead().use { it.readBytes() }
        if (bytes.size > MAX_BYTES) JSONArray() else JSONArray(String(bytes, Charsets.UTF_8))
    }.getOrElse { JSONArray() }

    private fun write(file: AtomicFile, content: JSONArray): Boolean {
        val bytes = content.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return false
        var stream: FileOutputStream? = null
        return try {
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let(file::failWrite)
            false
        }
    }

    private fun nonBlank(value: String?): String? = value?.trim()?.takeIf(String::isNotBlank)

    private fun credits(seed: DiscogsVersionSeed): JSONObject {
        fun matching(vararg names: String): JSONArray = JSONArray().apply {
            seed.credits.filter { credit ->
                val role = credit.role.lowercase()
                names.any { name -> role.contains(name) }
            }.map { it.name.trim() }.filter(String::isNotBlank).distinct()
                .take(40).forEach { put(it) }
        }
        return JSONObject().apply {
            put("songwriters", matching("songwriter", "written", "writer", "words by"))
            put("composers", matching("composer", "composed", "music by"))
            put("lyricists", matching("lyrics", "lyricist"))
            put("producers", matching("producer", "produced"))
            nonBlank(seed.labels.firstOrNull())?.let { put("label", it) }
        }
    }

    private fun payload(
        originalTitle: String,
        originalArtist: String,
        seed: DiscogsVersionSeed,
        approved: Boolean,
    ): JSONObject = JSONObject().apply {
        put("originalTitle", originalTitle)
        put("originalArtist", originalArtist)
        put("status", if (approved) "APPROVED" else "REJECTED")
        put("reason", "turbo_lab46_pending")
        put("candidate", JSONObject().apply {
            put("title", seed.trackTitle)
            put("artist", seed.artist)
            put("category", when (seed.kind) {
                DiscogsVersionKind.LIVE -> "live"
                DiscogsVersionKind.REMIX -> "remix"
                else -> "cover"
            })
            put("language", seed.language)
            put("year", seed.year)
            put("album", nonBlank(seed.releaseTitle))
            put("releaseDate", seed.releaseDate)
            put("country", seed.country)
            put("coverUrl", seed.coverUrl)
            put("playbackVideoId", seed.resolvedVideoId)
            put("playbackVideoTitle", seed.resolvedVideoTitle)
            put("playbackVideoSource", seed.resolvedVideoSource)
            put("versionFingerprint", seed.fingerprint)
            put("legacyScore20", seed.confidenceScore.coerceIn(1,20))
            put("musiclabScore100", seed.musiclabScore100)
            put("coverRound", seed.coverRound)
            put("roundPosition", seed.roundPosition)
            put("sourceNames", JSONArray(seed.sourceNames))
            put("sourceUrl", seed.sourceUrl)
            put("credits", credits(seed))
            // Must not fabricate a verified musical-work assertion from APPROVA.
        })
    }

    /**
     * The pending transport revision changes on EVERY local editorial action.
     * Old unresolved decisions for this recording are superseded atomically.
     */
    @Synchronized
    fun queue(
        context: Context,
        workKey: String,
        originalTitle: String,
        originalArtist: String,
        seed: DiscogsVersionSeed,
        approved: Boolean,
    ): Boolean {
        if (workKey.isBlank() || originalTitle.isBlank() || originalArtist.isBlank() ||
            seed.fingerprint.isBlank()) return false
        val file = file(context, workKey)
        val old = load(file)
        val fresh = JSONArray()
        for (index in 0 until old.length()) {
            val item = old.optJSONObject(index) ?: continue
            if (item.optString("fingerprint") != seed.fingerprint) fresh.put(item)
        }
        if (fresh.length() >= MAX_PENDING_PER_WORK) return false
        fresh.put(JSONObject().apply {
            put("fingerprint", seed.fingerprint)
            put("revision", UUID.randomUUID().toString())
            put("payload", payload(originalTitle, originalArtist, seed, approved))
        })
        return write(file, fresh)
    }

    /**
     * Read-only snapshot for future credentialed staging transport.
     * Old, incompatible pre-LAB46 envelopes are preserved but not transmitted.
     */
    @Synchronized
    fun pending(context: Context, workKey: String): List<JSONObject> {
        val array = load(file(context, workKey))
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.takeIf {
                it.optString("revision").isNotBlank() &&
                    it.optJSONObject("payload") != null
            }?.let { JSONObject(it.toString()) }
        }
    }

    @Synchronized
    fun pendingCount(context: Context, workKey: String): Int =
        load(file(context, workKey)).length()

    /**
     * Called only AFTER the authenticated server responds with a verified,
     * idempotent receipt that echoes the exact pending revision.
     * A stale receipt cannot erase a newer user's change.
     */
    @Synchronized
    fun acknowledge(
        context: Context, workKey: String, fingerprint: String,
        revision: String, confirmedRevision: String,
    ): Boolean {
        if (workKey.isBlank() || fingerprint.isBlank() || revision.isBlank() ||
            confirmedRevision != revision) return false
        val file = file(context, workKey)
        val current = load(file)
        val next = JSONArray()
        var removed = false
        for (i in 0 until current.length()) {
            val row = current.optJSONObject(i) ?: continue
            if (row.optString("fingerprint") == fingerprint &&
                row.optString("revision") == revision) {
                removed = true
            } else next.put(row)
        }
        return removed && write(file, next)
    }
}
