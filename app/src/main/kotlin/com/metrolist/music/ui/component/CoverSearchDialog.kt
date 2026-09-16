/**
 * MusicLab cover search
 * Licensed under GPL-3.0 | See repository history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

private data class CoverCandidate(
    val song: SongItem,
    val score: Double,
    val differentArtist: Boolean,
)

/**
 * Finds alternate performances of the same musical work.
 *
 * The search deliberately does not require the original performer: cover, live,
 * acoustic and alternate-version queries are merged. Candidates are then filtered
 * by a strong title-work match and a broad duration sanity check. Different
 * performers are ranked ahead of the original artist, without hiding legitimate
 * alternate versions by the original performer.
 */
@Composable
fun CoverSearchDialog(
    title: String,
    originalArtist: String,
    durationSec: Int,
    currentYouTubeId: String?,
    onSelect: (SongItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var candidates by remember { mutableStateOf<List<CoverCandidate>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(title, originalArtist, durationSec, currentYouTubeId) {
        loading = true
        failed = false
        candidates = try {
            withContext(Dispatchers.IO) {
                findCoverCandidates(
                    title = title,
                    originalArtist = originalArtist,
                    durationSec = durationSec,
                    currentYouTubeId = currentYouTubeId,
                )
            }
        } catch (_: Exception) {
            failed = true
            emptyList()
        }
        loading = false
    }

    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(text = stringResource(R.string.find_covers)) },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.find_covers_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            when {
                loading -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    ) {
                        CircularProgressIndicator()
                    }
                }

                failed -> {
                    Text(
                        text = stringResource(R.string.cover_search_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                candidates.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.no_covers_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp),
                    ) {
                        items(candidates, key = { it.song.id }) { candidate ->
                            CoverCandidateRow(
                                candidate = candidate,
                                onClick = {
                                    onSelect(candidate.song)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverCandidateRow(
    candidate: CoverCandidate,
    onClick: () -> Unit,
) {
    val song = candidate.song
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        AsyncImage(
            model = song.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artists.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = if (candidate.differentArtist) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            song.duration?.takeIf { it > 0 }?.let { seconds ->
                Text(
                    text = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private suspend fun findCoverCandidates(
    title: String,
    originalArtist: String,
    durationSec: Int,
    currentYouTubeId: String?,
): List<CoverCandidate> {
    val cleanTitle = title.trim()
    if (cleanTitle.isBlank()) return emptyList()

    val queries = linkedSetOf(
        "$cleanTitle cover",
        "$cleanTitle live",
        "$cleanTitle acoustic",
        "$cleanTitle version",
        "$cleanTitle tribute",
        cleanTitle,
    )

    val uniqueSongs = linkedMapOf<String, SongItem>()
    for (query in queries) {
        val result = YouTube.searchSummary(query, incognito = true).getOrNull() ?: continue
        result.summaries
            .flatMap { it.items }
            .filterIsInstance<SongItem>()
            .forEach { uniqueSongs.putIfAbsent(it.id, it) }
    }

    val originalWork = canonicalWorkTitle(cleanTitle)
    val originalArtistKey = canonicalArtist(originalArtist)

    return uniqueSongs.values.mapNotNull { song ->
        if (DISALLOWED_VARIANT_REGEX.containsMatchIn(song.title.lowercase())) return@mapNotNull null

        val titleScore = sameWorkScore(originalWork, canonicalWorkTitle(song.title))
        if (titleScore < MIN_TITLE_SCORE) return@mapNotNull null

        val durationScore = durationCompatibility(durationSec, song.duration ?: -1)
            ?: return@mapNotNull null

        val candidateArtists = song.artists.map { canonicalArtist(it.name) }.filter { it.isNotBlank() }
        val differentArtist = originalArtistKey.isNotBlank() &&
            candidateArtists.none { it == originalArtistKey }

        val variantBoost = if (COVER_VARIANT_REGEX.containsMatchIn(song.title.lowercase())) 0.10 else 0.0
        val artistBoost = if (differentArtist) 0.24 else 0.0
        val currentPenalty = if (song.id == currentYouTubeId) 0.20 else 0.0

        CoverCandidate(
            song = song,
            score = titleScore * 0.72 + durationScore * 0.18 + variantBoost + artistBoost - currentPenalty,
            differentArtist = differentArtist,
        )
    }
        .sortedWith(
            compareByDescending<CoverCandidate> { it.differentArtist }
                .thenByDescending { it.score }
        )
        .take(MAX_RESULTS)
}

private fun canonicalWorkTitle(value: String): String {
    val noDiacritics = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()

    return noDiacritics
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(WORK_NOISE_REGEX, " ")
        .replace(Regex("\\b(feat|ft|featuring)\\.?\\s+.*$"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

private fun canonicalArtist(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

private fun sameWorkScore(original: String, candidate: String): Double {
    if (original.isBlank() || candidate.isBlank()) return 0.0
    if (original == candidate) return 1.0
    if (candidate.startsWith(original) || original.startsWith(candidate)) return 0.94

    val a = original.split(' ').filter { it.length > 1 }.toSet()
    val b = candidate.split(' ').filter { it.length > 1 }.toSet()
    if (a.isEmpty() || b.isEmpty()) return 0.0

    val overlap = a.intersect(b).size.toDouble()
    val containment = overlap / max(1, minOf(a.size, b.size)).toDouble()
    val jaccard = overlap / a.union(b).size.toDouble()
    return containment * 0.65 + jaccard * 0.35
}

/** Returns null only when the duration is implausible for the same song. */
private fun durationCompatibility(originalSec: Int, candidateSec: Int): Double? {
    if (originalSec <= 0 || candidateSec <= 0) return 0.55
    val difference = abs(originalSec - candidateSec)
    val allowed = max(90, (originalSec * 0.40).toInt())
    if (difference > allowed) return null
    return 1.0 - (difference.toDouble() / allowed.toDouble())
}

private const val MIN_TITLE_SCORE = 0.72
private const val MAX_RESULTS = 24

private val WORK_NOISE_REGEX = Regex(
    "\\b(official|video|audio|lyrics?|lyric|cover|acoustic|unplugged|live|" +
        "version|tribute|performance|session|remaster(?:ed)?|studio)\\b"
)

private val COVER_VARIANT_REGEX = Regex(
    "\\b(cover|acoustic|unplugged|live|version|tribute|performance|session)\\b"
)

private val DISALLOWED_VARIANT_REGEX = Regex(
    "\\b(mashup|medley|reaction|tutorial|lesson|how to play|karaoke)\\b"
)
