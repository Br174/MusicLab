package com.metrolist.music.playback

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * R67 AUDIO FIRST. Optional disc artwork MUST NOT compete with first audio.
 *
 * Media3 owns playback unchanged. This gate is only used around OPTIONAL
 * catalogue/video-preview lookups in custom MusicLab sections. Taps, skips,
 * stream extraction and native playlist play never acquire this semaphore.
 */
internal object MusicLabArtworkAudioGate {
    const val FIRST_AUDIO_RESERVE_MS = 6_000L
    const val ARTWORK_SAFE_WAIT_MS = 8_500L
    private val artworkNetworkSlots = Semaphore(2)
    private val activeArtwork = HashSet<Job>()

    @Volatile private var reservedUntilMs = 0L
    @Volatile private var wantsAudio = false
    @Volatile private var buffering = false
    @Volatile private var lastPlayerState = Player.STATE_IDLE

    /** Called synchronously at the earliest user playback priority signal. */
    fun onAudioRequested() {
        reservedUntilMs = maxOf(
            reservedUntilMs,
            SystemClock.elapsedRealtime() + FIRST_AUDIO_RESERVE_MS,
        )
        val pending = synchronized(activeArtwork) { activeArtwork.toList() }
        // Cancel only optional artwork coroutines. Never touch Media3 or a
        // song/video lookup requested by the listener.
        pending.forEach { it.cancel(CancellationException("audio-first artwork preemption")) }
    }

    fun onPlayerState(state: Int, playWhenReady: Boolean) {
        lastPlayerState = state
        wantsAudio = playWhenReady
        buffering = playWhenReady && state == Player.STATE_BUFFERING
    }

    fun onPlayIntent(playWhenReady: Boolean) {
        wantsAudio = playWhenReady
        buffering = playWhenReady && lastPlayerState == Player.STATE_BUFFERING
    }

    internal fun mayStart(nowMs: Long, reserveUntilMs: Long, buffering: Boolean): Boolean =
        !buffering && nowMs >= reserveUntilMs

    fun mayStartOptionalArtwork(): Boolean = mayStart(
        SystemClock.elapsedRealtime(), reservedUntilMs, wantsAudio && buffering,
    )

    /** No queue of network work can delay the user's next tap. */
    suspend fun <T> optionalLookup(block: suspend () -> T?): T? {
        // Suspend only this OPTIONAL metadata coroutine. The requested audio
        // never calls this function or waits for a permit.
        val start = SystemClock.elapsedRealtime()
        while (!mayStartOptionalArtwork()) {
            if (SystemClock.elapsedRealtime() - start >= ARTWORK_SAFE_WAIT_MS)
                return null
            delay(180L)
        }
        return artworkNetworkSlots.withPermit {
            if (!mayStartOptionalArtwork()) return@withPermit null
            val job = currentCoroutineContext().job
            synchronized(activeArtwork) { activeArtwork += job }
            try {
                if (!mayStartOptionalArtwork()) null else block()
            } finally {
                synchronized(activeArtwork) { activeArtwork.remove(job) }
            }
        }
    }
}
