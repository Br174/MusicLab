package com.metrolist.music.ui.component

import android.content.Context
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.discogs.DiscogsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * R65: best-effort DOCUMENTED year, only for already visible Cover cards.
 * No guessed dates, no YouTube upload-year substitutions, no work on play().
 * The UI shows "Data non disponibile" for unverifiable amateur recordings.
 */
internal object CoverVisibleYearResolver {
    private const val PREFS = "musiclab_cover_verified_recording_year_r65"

    internal fun known(context: Context, fingerprint: String): Pair<Int, String>? {
        val value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(fingerprint, null) ?: return null
        val parts = value.split("|", limit = 2)
        val year = parts.firstOrNull()?.toIntOrNull()?.takeIf { it in 1850..2100 } ?: return null
        return year to (parts.getOrNull(1) ?: "Discogs")
    }

    internal fun save(context: Context, fingerprint: String, year: Int, source: String) {
        if (year !in 1850..2100) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(fingerprint, "$year|$source").apply()
    }

    suspend fun discover(
        seed: DiscogsVersionSeed,
        song: SongItem?,
        discogsToken: String,
    ): Pair<Int, String>? = withContext(Dispatchers.IO) {
        // Only an album-ID can establish a musical issue year from YT Music.
        // An amateur YouTube video with no album is NOT a dated release.
        song?.album?.id?.takeIf(String::isNotBlank)?.let {
            withTimeoutOrNull(2_400L) { CoverYearResolver.resolve(song) }
                ?.takeIf { it in 1850..2100 }
                ?.let { year -> return@withContext year to "YouTube Music" }
        }
        if (seed.releaseId > 0 && discogsToken.isNotBlank()) {
            withTimeoutOrNull(2_400L) {
                DiscogsClient.getRelease(
                    token = discogsToken,
                    releaseId = seed.releaseId,
                    includeMasterVideos = false,
                ).getOrNull()
            }?.let { release ->
                release.year?.takeIf { it in 1850..2100 }
                    ?.let { year -> return@withContext year to "Discogs" }
            }
        }
        null
    }

    internal fun applyDate(
        row: DiscogsVersionSeed,
        year: Int,
        source: String,
    ): DiscogsVersionSeed {
        if (CoverCardMetadata.publicationYear(row) != null) return row
        return row.copy(
            year = year,
            releaseDate = year.toString(),
            sourceNames = (row.sourceNames + source).distinct(),
        )
    }
}
