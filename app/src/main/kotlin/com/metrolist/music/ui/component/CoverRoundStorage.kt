package com.metrolist.music.ui.component

import android.content.Context
import android.util.AtomicFile
import com.metrolist.music.BuildConfig
import com.metrolist.music.discogs.DiscogsTrack
import com.metrolist.music.discogs.DiscogsCredit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * LAB45: an app-process-safe metadata snapshot of confirmed Cover search rounds.
 * Never stores media, downloaded images, tokens or network credentials.
 * One file per screen/work. All entries were admitted by the existing same-work
 * gate before publication; loading does NOT certify a new search candidate.
 */
internal object CoverRoundStorage {
    internal data class Snapshot(
        val round: Int = 1,
        val rows: List<DiscogsVersionSeed> = emptyList(),
        val originalArtist: String? = null,
        val workTitle: String? = null,
        val deferredRows: List<DiscogsVersionSeed> = emptyList(),
    )


    /** Published cards and pending discoveries have different restoration boundaries. */
    internal fun partitionForRestoration(saved: Snapshot, referenceTitle: String, referenceArtist: String):
        Pair<List<DiscogsVersionSeed>, List<DiscogsVersionSeed>> {
        val (visible, held) = saved.rows.partition { row ->
            val verdict = CoverLabTurboRoundAdmission.evaluate(
                CoverLabTurboRoundAdmission.candidate(row), referenceTitle, referenceArtist, row.coverRound ?: 1)
            val savedFacts = row.identityEvidence.toSet()
            val allocated = !verdict.rejected && (verdict.admitted ||
                (savedFacts.contains("title") && (row.coverRound ?: 1) >=
                    CoverLabTurboRoundAdmission.minimumRound(savedFacts)))
            allocated && row.videoResolutionChecked && !row.resolvedVideoId.isNullOrBlank() &&
                (row.coverRound ?: 1) <= saved.round
        }
        return visible to (held + saved.deferredRows)
    }

    private fun path(context: Context, workKey: String): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(workKey.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(context.applicationContext.filesDir, "musiclab-cover-rounds-$digest.json"))
    }

    private fun text(json: JSONObject, key: String): String? =
        if (!json.has(key) || json.isNull(key)) null
        else json.optString(key).trim().takeIf(String::isNotBlank)

    private fun int(json: JSONObject, key: String): Int? =
        if (!json.has(key) || json.isNull(key)) null else json.optInt(key)

    private fun strings(values: JSONArray?): List<String> = if (values == null) emptyList()
    else (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }

    private fun encode(seed: DiscogsVersionSeed): JSONObject = JSONObject().apply {
        put("title", seed.trackTitle)
        put("artist", seed.artist)
        put("releaseTitle", seed.releaseTitle)
        put("releaseId", seed.releaseId)
        put("masterId", seed.masterId)
        put("year", seed.year)
        put("releaseDate", seed.releaseDate)
        put("kind", seed.kind.name)
        put("country", seed.country)
        put("formats", JSONArray(seed.formats))
        put("formatDescriptions", JSONArray(seed.formatDescriptions))
        put("labels", JSONArray(seed.labels))
        // LAB46: credits are light textual metadata and must survive Android restart.
        put("credits", JSONArray().also { rows ->
            seed.credits.take(40).forEach { credit ->
                rows.put(JSONObject().put("name", credit.name).put("role", credit.role))
            }
        })
        put("coverUrl", seed.coverUrl)
        put("durationSeconds", seed.durationSeconds)
        put("fingerprint", seed.fingerprint)
        put("language", seed.language)
        put("confidenceScore", seed.confidenceScore)
        put("musiclabScore100", seed.musiclabScore100)
        put("coverRound", seed.coverRound)
        put("roundPosition", seed.roundPosition)
        put("identityEvidence", JSONArray(seed.identityEvidence))
        put("confidenceReasons", JSONArray(seed.confidenceReasons))
        put("resolvedVideoId", seed.resolvedVideoId)
        put("resolvedVideoTitle", seed.resolvedVideoTitle)
        put("resolvedVideoSource", seed.resolvedVideoSource)
        put("videoResolutionChecked", seed.videoResolutionChecked)
        put("sourceNames", JSONArray(seed.sourceNames))
        put("sourceUrl", seed.sourceUrl)
        put("discogsVerificationChecked", seed.discogsVerificationChecked)
        put("manuallyApproved", seed.manuallyApproved)
        put("originalWorkReference", seed.originalWorkReference)
        put("workRelationConfirmed", seed.workRelationConfirmed)
        put("collectorHave", seed.collectorHave)
        put("trackPosition", seed.track?.position)
    }

    private fun decode(json: JSONObject): DiscogsVersionSeed? = runCatching {
        DiscogsVersionSeed(
            trackTitle = json.getString("title"),
            artist = json.getString("artist"),
            releaseTitle = json.optString("releaseTitle"),
            releaseId = json.optInt("releaseId"),
            masterId = int(json, "masterId"),
            year = int(json, "year"),
            releaseDate = text(json, "releaseDate"),
            kind = DiscogsVersionKind.valueOf(json.getString("kind")),
            country = text(json, "country"),
            formats = strings(json.optJSONArray("formats")),
            formatDescriptions = strings(json.optJSONArray("formatDescriptions")),
            labels = strings(json.optJSONArray("labels")),
            credits = json.optJSONArray("credits")?.let { rows ->
                (0 until rows.length()).mapNotNull { index ->
                    val credit = rows.optJSONObject(index) ?: return@mapNotNull null
                    val name = credit.optString("name").trim()
                    val role = credit.optString("role").trim()
                    if (name.isBlank() || role.isBlank()) null
                    else DiscogsCredit(name = name, role = role)
                }
            }.orEmpty(),
            coverUrl = text(json, "coverUrl"),
            durationSeconds = int(json, "durationSeconds"),
            fingerprint = json.getString("fingerprint"),
            language = text(json, "language"),
            confidenceScore = json.optInt("confidenceScore", 1).coerceIn(1, 20),
            musiclabScore100 = int(json, "musiclabScore100")?.takeIf { it in 0..100 },
            coverRound = int(json, "coverRound")?.takeIf { it >= 1 },
            roundPosition = int(json, "roundPosition")?.takeIf { it >= 1 },
            identityEvidence = strings(json.optJSONArray("identityEvidence")),
            confidenceReasons = strings(json.optJSONArray("confidenceReasons")),
            resolvedVideoId = text(json, "resolvedVideoId"),
            resolvedVideoTitle = text(json, "resolvedVideoTitle"),
            resolvedVideoSource = text(json, "resolvedVideoSource"),
            videoResolutionChecked = json.optBoolean("videoResolutionChecked"),
            sourceNames = strings(json.optJSONArray("sourceNames")),
            sourceUrl = text(json, "sourceUrl"),
            discogsVerificationChecked = json.optBoolean("discogsVerificationChecked"),
            manuallyApproved = json.optBoolean("manuallyApproved"),
            originalWorkReference = json.optBoolean("originalWorkReference"),
            workRelationConfirmed = json.optBoolean("workRelationConfirmed"),
            collectorHave = int(json, "collectorHave"),
            track = text(json, "trackPosition")?.let { position ->
                DiscogsTrack(position, json.getString("title"), listOf(json.getString("artist")), null, int(json, "durationSeconds"))
            },
        )
    }.getOrNull()

    private fun retained(rows: List<DiscogsVersionSeed>, originalArtist: String? = null): List<DiscogsVersionSeed> {
        if (BuildConfig.APPLICATION_ID == "it.verlezza.musiclab.turbo01") {
            // Legacy originalWorkReference flags can mean the selected cover singer.
            // Only the explicitly persisted R2 documentary reference can impose this cap.
            val original = originalArtist.orEmpty()
            return CoverPriorityOrder.retainedRows(rows, original)
        }
        val seen = HashSet<String>()
        val videos = HashSet<String>()
        return DiscogsVersionSource.dedupeVersions(rows, preserveInputOrder = true).filter { seed ->
            seen.add(DiscogsVersionSource.recordingIdentityKey(seed)) &&
                (seed.resolvedVideoId.isNullOrBlank() || videos.add(seed.resolvedVideoId))
        }
    }

    @Synchronized
    internal fun load(context: Context, workKey: String): Snapshot = runCatching {
        val json = JSONObject(String(path(context, workKey).readFully(), Charsets.UTF_8))
        if (json.optInt("schema") != 1) return@runCatching Snapshot()
        val results = json.optJSONArray("rows") ?: JSONArray()
        val restored = (0 until results.length()).mapNotNull { index ->
            results.optJSONObject(index)?.let(::decode)
        }
        val original = text(json, "originalArtist")
        val pending = json.optJSONArray("deferredRows") ?: JSONArray()
        val deferred = (0 until pending.length()).mapNotNull { index ->
            pending.optJSONObject(index)?.let(::decode)
        }
        val cards = retained(restored, original)
        Snapshot(round = json.optInt("round", 1).coerceAtLeast(1), rows = cards,
            originalArtist = original, workTitle = text(json, "workTitle"),
            deferredRows = retained(deferred, original))
    }.getOrDefault(Snapshot())

    @Synchronized
    internal fun save(context: Context, workKey: String, round: Int, rows: List<DiscogsVersionSeed>,
        originalArtist: String? = null, workTitle: String? = null,
        deferredRows: List<DiscogsVersionSeed>? = null) {
        runCatching {
            // Out-of-order IO jobs may finish after a newer Cover round.
            // Never overwrite a larger or newer confirmed snapshot.
            val saved = load(context, workKey)
            if (saved.round > round || (deferredRows == null && saved.round == round &&
                    retained(saved.rows, originalArtist).size > retained(rows, originalArtist).size)) {
                return@runCatching
            }
            val records = retained(rows, originalArtist ?: saved.originalArtist)
            // Only the native preparation owner replaces the complete pending queue.
            // Older/UI-only save callers preserve it instead of erasing hidden discoveries.
            val shownIds = records.mapNotNull { it.resolvedVideoId }.toSet()
            val waiting = retained(deferredRows ?: saved.deferredRows, originalArtist ?: saved.originalArtist)
                .filterNot { it.resolvedVideoId in shownIds }
            val json = JSONObject()
                .put("schema", 1)
                .put("originalArtist", originalArtist ?: saved.originalArtist)
                .put("workTitle", workTitle ?: saved.workTitle)
                .put("round", round.coerceAtLeast(1))
                .put("rows", JSONArray().also { array ->
                    records.forEach { array.put(encode(it)) }
                })
                .put("deferredRows", JSONArray().also { array ->
                    waiting.forEach { array.put(encode(it)) }
                })
            val file = path(context, workKey)
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (failure: Exception) {
                stream?.let(file::failWrite)
                throw failure
            }
        }
    }

    @Synchronized
    internal fun clear(context: Context, workKey: String) {
        path(context, workKey).delete()
    }
}
