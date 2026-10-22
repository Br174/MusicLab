package com.metrolist.music.ui.component

import android.content.Context
import android.util.AtomicFile
import com.metrolist.music.discogs.DiscogsCredit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * LAB46: durable, unsynced editorial outbox. Records are LOCAL until a
 * separately authenticated, isolated Cloud route acknowledges delivery.
 * Never stores video/audio, account tokens, or fabricated Cloud receipts.
 */
internal object CoverCloudPendingOutbox {
    private const val MAX_PENDING_PER_WORK = 2000
    private fun file(context: Context, workKey: String): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(workKey.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(context.applicationContext.filesDir,
            "musiclab-cloud-outbox-$digest.json"))
    }

    private fun load(file: AtomicFile): JSONArray = runCatching {
        JSONArray(String(file.openRead().use { it.readBytes() }, Charsets.UTF_8))
    }.getOrElse { JSONArray() }

    private fun toJson(
        workKey: String, seed: DiscogsVersionSeed, approval: Boolean,
    ): JSONObject = JSONObject().apply {
        put("workKey", workKey)
        put("fingerprint", seed.fingerprint)
        put("status", if (approval) "APPROVED" else "REJECTED")
        put("title", seed.trackTitle)
        put("artist", seed.artist)
        put("releaseTitle", seed.releaseTitle)
        put("releaseDate", seed.releaseDate)
        put("country", seed.country)
        put("year", seed.year)
        put("language", seed.language)
        put("coverUrl", seed.coverUrl)
        put("playbackVideoId", seed.resolvedVideoId)
        put("playbackVideoTitle", seed.resolvedVideoTitle)
        put("playbackVideoSource", seed.resolvedVideoSource)
        put("legacyScore20", seed.confidenceScore.coerceIn(1,20))
        put("musiclabScore100", seed.musiclabScore100)
        put("coverRound", seed.coverRound)
        put("roundPosition", seed.roundPosition)
        put("sourceNames", JSONArray(seed.sourceNames))
        put("sourceUrl", seed.sourceUrl)
        put("workRelationConfirmed", seed.workRelationConfirmed)
        put("credits", JSONArray().also { rows ->
            seed.credits.take(40).forEach { credit: DiscogsCredit ->
                rows.put(JSONObject().put("name", credit.name).put("role", credit.role))
            }
        })
    }

    /** An offline local acknowledgment, NOT a Cloud synchronization receipt. */
    @Synchronized
    fun queue(context: Context, workKey: String, seed: DiscogsVersionSeed, approved: Boolean): Boolean {
        if (workKey.isBlank() || seed.fingerprint.isBlank()) return false
        val outbox = file(context, workKey)
        val original = load(outbox)
        val pending = JSONArray()
        for (i in 0 until original.length()) {
            val old = original.optJSONObject(i) ?: continue
            // A changed decision replaces the previous PENDING value; never
            // replay both APPROVED and REJECTED for the same recording.
            if (old.optString("fingerprint") != seed.fingerprint) pending.put(old)
        }
        if (pending.length() >= MAX_PENDING_PER_WORK) return false
        pending.put(toJson(workKey, seed, approved))
        val bytes = pending.toString().toByteArray(Charsets.UTF_8)
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = outbox.startWrite()
            stream.write(bytes)
            outbox.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let(outbox::failWrite)
            false
        }
    }

    @Synchronized
    fun pendingCount(context: Context, workKey: String): Int =
        load(file(context, workKey)).length()

    /** No clear-on-attempt. A future authenticated sync may only remove a
     * pending decision after a verified server receipt for that exact revision.
     */
}
