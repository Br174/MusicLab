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

    /**
     * Corrupt/oversized storage must fail closed. Silently replacing it with an
     * empty array could destroy previously unsynchronized approvals.
     */
    private fun load(file: AtomicFile): JSONArray? {
        if (!file.baseFile.exists()) return JSONArray()
        return runCatching {
            val bytes = file.openRead().use { it.readBytes() }
            if (bytes.size > MAX_BYTES) null
            else JSONArray(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }

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
            put("creditDetails", JSONArray().apply {
                seed.credits.distinctBy { it.name to it.role }.take(80).forEach {
                    put(JSONObject().put("name", it.name).put("role", it.role))
                }
            })
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
        val old = load(file) ?: return false
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
        val array = load(file(context, workKey)) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.takeIf {
                it.optString("revision").isNotBlank() &&
                    it.optJSONObject("payload") != null
            }?.let { JSONObject(it.toString()) }
        }
    }

    @Synchronized
    fun pendingCount(context: Context, workKey: String): Int =
        load(file(context, workKey))?.length() ?: -1

    /**
     * Isolated LAB46 owner diagnostics: enumerate ONLY existing private
     * on-device outbox envelopes, never queue a synthetic test decision.
     * The original workKey is intentionally hashed in filenames, so a
     * user-selected existing filename is used to locate and acknowledge it.
     */
    internal data class PendingChoice(
        val filename: String,
        val revision: String,
        val fingerprint: String,
        val title: String,
        val artist: String,
        val status: String,
    )

    private val outboxFilename = Regex("^musiclab-cloud-outbox-[0-9a-f]{32}\\.json$")

    private fun choiceFile(context: Context, name: String): AtomicFile? {
        if (!outboxFilename.matches(name)) return null
        return AtomicFile(File(context.applicationContext.filesDir, name))
    }

    @Synchronized
    internal fun pendingChoices(context: Context): List<PendingChoice> {
        val files = context.applicationContext.filesDir.listFiles()
            ?.asSequence()?.filter { it.isFile && outboxFilename.matches(it.name) }
            ?.sortedByDescending { it.lastModified() }?.take(200) ?: return emptyList()
        val choices = mutableListOf<PendingChoice>()
        for (source in files) {
            val rows = load(AtomicFile(source)) ?: continue
            for (i in rows.length() - 1 downTo 0) {
                val entry = rows.optJSONObject(i) ?: continue
                val payload = entry.optJSONObject("payload") ?: continue
                val fingerprint = entry.optString("fingerprint")
                val revision = entry.optString("revision")
                val candidate = payload.optJSONObject("candidate") ?: continue
                if (!revision.matches(Regex(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-" +
                        "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
                    )) || fingerprint.isBlank() ||
                    candidate.optString("versionFingerprint") != fingerprint
                ) continue
                choices.add(PendingChoice(
                    source.name, revision, fingerprint,
                    candidate.optString("title").take(120),
                    candidate.optString("artist").take(120),
                    payload.optString("status"),
                ))
                if (choices.size >= 25) return choices
            }
        }
        return choices
    }

    /** Detect a stale selection, never send another decision in its place. */
    @Synchronized
    internal fun selectedPending(context: Context, choice: PendingChoice): JSONObject? {
        val file = choiceFile(context, choice.filename) ?: return null
        val rows = load(file) ?: return null
        for (i in 0 until rows.length()) {
            val entry = rows.optJSONObject(i) ?: continue
            if (entry.optString("revision") != choice.revision ||
                entry.optString("fingerprint") != choice.fingerprint) continue
            val payload = entry.optJSONObject("payload") ?: return null
            val candidate = payload.optJSONObject("candidate") ?: return null
            if (candidate.optString("versionFingerprint") != choice.fingerprint ||
                candidate.optString("title").take(120) != choice.title ||
                candidate.optString("artist").take(120) != choice.artist ||
                payload.optString("status") != choice.status) return null
            return JSONObject(entry.toString())
        }
        return null
    }

    /** Consume precisely the owner-selected committed revision, not later edits. */
    @Synchronized
    internal fun acknowledgeSelected(
        context: Context, choice: PendingChoice, confirmedRevision: String,
    ): Boolean {
        if (choice.revision != confirmedRevision) return false
        val file = choiceFile(context, choice.filename) ?: return false
        val current = load(file) ?: return false
        val next = JSONArray()
        var removed = false
        for (i in 0 until current.length()) {
            val entry = current.optJSONObject(i) ?: continue
            if (entry.optString("revision") == choice.revision &&
                entry.optString("fingerprint") == choice.fingerprint) {
                val candidate = entry.optJSONObject("payload")?.optJSONObject("candidate")
                if (candidate?.optString("title")?.take(120) != choice.title ||
                    candidate.optString("artist").take(120) != choice.artist ||
                    entry.optJSONObject("payload")?.optString("status") != choice.status)
                    return false
                removed = true
            } else next.put(entry)
        }
        return removed && write(file, next)
    }

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
        val current = load(file) ?: return false
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
