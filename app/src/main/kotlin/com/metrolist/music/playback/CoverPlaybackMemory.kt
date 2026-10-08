package com.metrolist.music.playback

import android.content.Context
import com.metrolist.music.models.MediaMetadata
import java.security.MessageDigest

/**
 * LAB63 Pollicino: stable Cover artwork and per-recording video search history.
 * This is a tiny metadata-only store: never downloads audio, videos or images.
 * All writes use apply(); UI reads are memoized in memory.
 */
internal object CoverPlaybackMemory {
    private const val PREFS = "musiclab_lab63_cover_playback"
    private const val MAX_REJECTED = 24
    private val cached = HashMap<String, String?>()
    private val lock = Any()

    private fun key(raw: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun get(context: Context, k: String): String? = synchronized(lock) {
        if (cached.containsKey(k)) return@synchronized cached[k]
        val value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(k, null)
        cached[k] = value
        value
    }

    private fun put(context: Context, k: String, value: String) = synchronized(lock) {
        cached[k] = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(k, value).apply()
    }

    /** First valid preview wins. Never swap artwork because a new video ID arrives. */
    fun stableArtwork(context: Context, fingerprint: String, candidate: String?): String? {
        val k = "art:" + key(fingerprint)
        get(context, k)?.takeIf(String::isNotBlank)?.let { return it }
        val proposed = candidate?.trim()?.takeIf(String::isNotBlank) ?: return null
        put(context, k, proposed)
        return proposed
    }

    /** These IDs are verified by playback, not merely by YouTube search. */
    fun verifiedVideo(context: Context, fingerprint: String): String? =
        get(context, "verified:" + key(fingerprint))?.takeIf(String::isNotBlank)

    fun saveVerifiedVideo(context: Context, fingerprint: String, videoId: String, image: String?) {
        val cleanId = videoId.trim().takeIf(String::isNotBlank) ?: return
        put(context, "verified:" + key(fingerprint), cleanId)
        if (!image.isNullOrBlank()) pinVideoArtwork(context, cleanId, image)
    }

    fun pinnedVideoArtwork(context: Context, videoId: String): String? =
        get(context, "videoart:" + key(videoId))?.takeIf(String::isNotBlank)

    fun pinVideoArtwork(context: Context, videoId: String, artwork: String) {
        val id = videoId.trim().takeIf(String::isNotBlank) ?: return
        if (pinnedVideoArtwork(context, id).isNullOrBlank() && artwork.isNotBlank()) {
            put(context, "videoart:" + key(id), artwork)
        }
    }

    /** Failed ID exclusions are specific to a single recording, not global to YouTube. */
    fun rejectedVideoIds(context: Context, fingerprint: String): Set<String> =
        get(context, "rejected:" + key(fingerprint))
            ?.split(',')?.filter(String::isNotBlank)?.toSet().orEmpty()

    fun rejectVideo(context: Context, fingerprint: String, videoId: String) {
        val id = videoId.trim().takeIf(String::isNotBlank) ?: return
        val next = (rejectedVideoIds(context, fingerprint).toList() + id).takeLast(MAX_REJECTED)
        put(context, "rejected:" + key(fingerprint), next.joinToString(","))
    }

    /** Round-robin through alternative search phrases on repeated attempts. */
    fun nextSearchRound(context: Context, fingerprint: String): Int {
        val k = "round:" + key(fingerprint)
        val next = ((get(context, k)?.toIntOrNull() ?: -1) + 1) % 16
        put(context, k, next.toString())
        return next
    }

    fun stablePlayerMetadata(context: Context, metadata: MediaMetadata): MediaMetadata {
        val pinned = pinnedVideoArtwork(context, metadata.id) ?: return metadata
        return if (metadata.thumbnailUrl == pinned) metadata else metadata.copy(thumbnailUrl = pinned)
    }
}
