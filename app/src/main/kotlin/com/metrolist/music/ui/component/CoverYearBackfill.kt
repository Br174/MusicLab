package com.metrolist.music.ui.component

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Year
import java.util.concurrent.ConcurrentHashMap

/**
 * LAB26 year completion chain for Cover.
 *
 * Priority:
 *  1) metadata already attached by Brain/Spotify;
 *  2) YouTube Music album metadata;
 *  3) MusicLab cloud AI batch enrichment;
 *  4) cloud candidate verification for remaining stragglers;
 *  5) direct Gemini batch credits when a direct key is configured.
 *
 * It is background-only and never blocks the first Cover rows.
 */
internal object CoverYearBackfill {
    private val cache = ConcurrentHashMap<String, Int>()

    suspend fun resolve(
        originalTitle: String,
        originalArtist: String,
        playables: List<AiCoverPlayable>,
        config: GeminiCoverVerificationConfig?,
    ): Map<String, Int> = withContext(Dispatchers.IO) {
        if (playables.isEmpty()) return@withContext emptyMap()

        val result = linkedMapOf<String, Int>()
        val pending = playables
            .distinctBy { it.song.id }
            .filter { playable ->
                val known = playable.candidate.year
                if (known != null && validYear(known)) {
                    result[playable.song.id] = known
                    false
                } else {
                    cache[cacheKey(playable)]?.let { result[playable.song.id] = it }
                    playable.song.id !in result
                }
            }

        if (pending.isEmpty()) return@withContext result

        // Real streaming metadata first. Bound concurrency so this cannot flood mobile data.
        val streaming = coroutineScope {
            pending.chunked(STREAMING_BATCH).flatMap { chunk ->
                chunk.map { playable ->
                    async(Dispatchers.IO) {
                        playable.song.id to CoverYearResolver.resolve(playable.song)
                    }
                }.awaitAll()
            }
        }
        streaming.forEach { (id, year) ->
            if (year != null && validYear(year)) {
                result[id] = year
                pending.firstOrNull { it.song.id == id }?.let { cache[cacheKey(it)] = year }
            }
        }

        var unresolved = pending.filter { it.song.id !in result }
        val cfg = config
        if (unresolved.isEmpty() || cfg == null) return@withContext result

        // One cloud AI batch for the missing years. This uses the same server-side Gemini
        // lane already powering MusicLab Brain and does not require a per-row UI request.
        if (cfg.cloudEndpoint.isNotBlank()) {
            val cloud = runCatching {
                CloudMusicDiscovery.discoverCover(
                    title = originalTitle,
                    artist = originalArtist,
                    config = cfg.copy(useCloudMemory = false),
                    phase = "expand",
                    existing = unresolved.map { it.candidate },
                    focus = YEAR_ONLY_FOCUS,
                )
            }.getOrNull()

            val cloudCandidates = cloud?.versions.orEmpty()
            unresolved.forEach { playable ->
                val match = cloudCandidates.firstOrNull { it.stableKey == playable.candidate.stableKey }
                    ?: cloudCandidates.firstOrNull {
                        it.title.equals(playable.candidate.title, ignoreCase = true) &&
                            it.artist.equals(playable.candidate.artist, ignoreCase = true)
                    }
                val year = match?.year
                if (year != null && validYear(year)) {
                    result[playable.song.id] = year
                    cache[cacheKey(playable)] = year
                }
            }
        }

        unresolved = unresolved.filter { it.song.id !in result }

        // Re-verify a small number of stubborn candidates with the cloud Brain.
        if (cfg.cloudEndpoint.isNotBlank() && unresolved.isNotEmpty()) {
            unresolved.take(MAX_STRAGGLER_VERIFY).forEach { playable ->
                val verified = runCatching {
                    CloudMusicDiscovery.verifyCandidate(
                        originalTitle = originalTitle,
                        originalArtist = originalArtist,
                        candidate = playable.candidate,
                        mode = "cover",
                        config = cfg,
                    )
                }.getOrNull()
                verified?.year?.takeIf(::validYear)?.let { year ->
                    result[playable.song.id] = year
                    cache[cacheKey(playable)] = year
                }
            }
        }

        unresolved = unresolved.filter { it.song.id !in result }

        // Direct Gemini is the last fallback when the user configured a direct key.
        if (cfg.apiKey.isNotBlank() && unresolved.isNotEmpty()) {
            val identity = GeminiOriginalIdentity(
                title = originalTitle,
                originalArtists = listOf(originalArtist).filter { it.isNotBlank() },
                mode = GeminiOriginalMode.MODEL_KNOWLEDGE,
                webSourceCount = 0,
            )
            unresolved.chunked(DIRECT_AI_BATCH).forEach { chunk ->
                val credits = runCatching {
                    GeminiOriginalVersionCredits.enrich(
                        identity = identity,
                        songs = chunk.map { it.song },
                        config = cfg,
                    )
                }.getOrDefault(emptyMap())
                chunk.forEach { playable ->
                    credits[playable.song.id]?.year?.takeIf(::validYear)?.let { year ->
                        result[playable.song.id] = year
                        cache[cacheKey(playable)] = year
                    }
                }
            }
        }

        result
    }

    private fun cacheKey(playable: AiCoverPlayable): String =
        "${playable.candidate.stableKey}|${playable.song.id}"

    private fun validYear(value: Int): Boolean =
        value in 1900..(Year.now().value + 1)

    private const val STREAMING_BATCH = 5
    private const val DIRECT_AI_BATCH = 16
    private const val MAX_STRAGGLER_VERIFY = 12

    private const val YEAR_ONLY_FOCUS =
        "Completa ESCLUSIVAMENTE l'anno di pubblicazione/incisione delle versioni già elencate. " +
            "Non sostituire gli interpreti e non inventare nuove versioni. Per ogni candidato esistente " +
            "restituisci il miglior anno documentato; se la data esatta non è disponibile, usa la migliore " +
            "stima musicale ragionata e NON lasciare year nullo."
}
