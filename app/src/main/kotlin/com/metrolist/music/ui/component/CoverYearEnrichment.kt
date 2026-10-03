package com.metrolist.music.ui.component

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal data class CoverYearHit(
    val year: Int,
    val source: String,
)

/**
 * LAB26 background-only year completion.
 *
 * Ordering is deliberate:
 *  1. playback/streaming metadata (YouTube / YouTube Music);
 *  2. Spotify/Brain years already present on the candidate are preserved by caller;
 *  3. MusicLab Cloud Brain asks its server-side AI to complete missing years;
 *  4. direct Gemini batch enrichment is the final fallback when a direct key exists.
 *
 * Nothing here blocks first paint or playback.
 */
internal object CoverYearEnrichment {
    suspend fun enrichMissing(
        originalTitle: String,
        originalArtist: String,
        originalInfo: AiCoverOriginalInfo?,
        playables: List<AiCoverPlayable>,
        config: GeminiCoverVerificationConfig?,
    ): Map<String, CoverYearHit> = withContext(Dispatchers.IO) {
        val missing = playables
            .filter { it.candidate.year == null }
            .distinctBy { it.candidate.stableKey }

        if (missing.isEmpty()) return@withContext emptyMap()

        val hits = linkedMapOf<String, CoverYearHit>()

        // STEP 1: real provider metadata. Limit concurrency so enrichment never
        // competes aggressively with playback/search.
        for (chunk in missing.chunked(STREAMING_CONCURRENCY)) {
            coroutineScope {
                chunk.map { playable ->
                    async(Dispatchers.IO) {
                        val year = runCatching { CoverYearResolver.resolve(playable.song) }.getOrNull()
                        playable.candidate.stableKey to year
                    }
                }.awaitAll()
            }.forEach { (key, year) ->
                year?.takeIf { it in MIN_YEAR..MAX_YEAR }?.let {
                    hits.putIfAbsent(key, CoverYearHit(it, "youtube_music"))
                }
            }
        }

        var remaining = missing.filter { it.candidate.stableKey !in hits }

        // STEP 2: server-side MusicLab Brain / Gemini. This works even when the
        // phone has no direct Gemini key configured.
        if (remaining.isNotEmpty() && config != null && config.cloudEndpoint.isNotBlank()) {
            val cloud = runCatching {
                CloudMusicDiscovery.discoverCover(
                    title = originalTitle,
                    artist = originalArtist,
                    config = config,
                    phase = "expand",
                    existing = remaining.map { it.candidate },
                    focus = "Completa l'anno della specifica incisione per TUTTE le versioni esistenti senza anno. " +
                        "Ogni versione deve avere un anno a quattro cifre: usa l'anno di pubblicazione noto oppure, se ambiguo, " +
                        "la stima storicamente più plausibile. Non lasciare l'anno nullo e non cambiare artista o titolo.",
                )
            }.getOrNull()

            cloud?.versions.orEmpty().forEach { candidate ->
                val year = candidate.year?.takeIf { it in MIN_YEAR..MAX_YEAR } ?: return@forEach
                val exact = remaining.firstOrNull { it.candidate.stableKey == candidate.stableKey }
                    ?: remaining.firstOrNull {
                        canonicalYearKey(it.candidate.title, it.candidate.artist) ==
                            canonicalYearKey(candidate.title, candidate.artist)
                    }
                exact?.let {
                    hits.putIfAbsent(it.candidate.stableKey, CoverYearHit(year, "musiclab_brain_ai"))
                }
            }
        }

        remaining = missing.filter { it.candidate.stableKey !in hits }

        // STEP 3: direct Gemini, batched by the existing credits engine (8 items
        // per request). It is the last resort and therefore does not spend quota
        // for rows already dated by provider/Spotify/Brain.
        if (remaining.isNotEmpty() && config != null && config.apiKey.isNotBlank()) {
            val identity = GeminiOriginalIdentity(
                title = originalInfo?.title?.takeIf { it.isNotBlank() } ?: originalTitle,
                originalArtists = listOf(
                    originalInfo?.artist?.takeIf { it.isNotBlank() } ?: originalArtist,
                ).filter { it.isNotBlank() },
                year = originalInfo?.year,
                songwriters = originalInfo?.songwriters.orEmpty(),
                composers = originalInfo?.composers.orEmpty(),
                lyricists = originalInfo?.lyricists.orEmpty(),
                producers = originalInfo?.producers.orEmpty(),
                label = originalInfo?.label,
                album = originalInfo?.album,
                mode = GeminiOriginalMode.MODEL_KNOWLEDGE,
                webSourceCount = 0,
            )

            for (chunk in remaining.chunked(DIRECT_AI_BATCH)) {
                val credits = runCatching {
                    GeminiOriginalVersionCredits.enrich(
                        identity = identity,
                        songs = chunk.map { it.song },
                        config = config,
                        requireYear = true,
                    )
                }.getOrDefault(emptyMap())

                chunk.forEach { playable ->
                    val year = credits[playable.song.id]?.year
                        ?.takeIf { it in MIN_YEAR..MAX_YEAR }
                        ?: return@forEach
                    hits.putIfAbsent(
                        playable.candidate.stableKey,
                        CoverYearHit(year, "gemini_ai"),
                    )
                }
            }
        }

        hits
    }

    private fun canonicalYearKey(title: String, artist: String): String =
        (title + "|" + artist)
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private const val STREAMING_CONCURRENCY = 3
    private const val DIRECT_AI_BATCH = 8
    private const val MIN_YEAR = 1850
    private const val MAX_YEAR = 2100
}
