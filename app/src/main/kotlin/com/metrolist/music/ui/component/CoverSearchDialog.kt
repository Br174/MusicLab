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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

private data class CoverCandidate(
    val song: SongItem,
    val score: Double,
    val differentArtist: Boolean,
    val confirmedByWhoSampled: Boolean = false,
)

private data class CoverSearchOutcome(
    val candidates: List<CoverCandidate>,
    val whoSampledStatus: WhoSampledStatus,
    val confirmedCount: Int,
)

/**
 * Finds alternate performances of the same musical work.
 *
 * WhoSampled is used as an optional relationship source. Its links can identify
 * translated/adapted cover titles that a title-only YouTube search cannot.
 * Confirmed WhoSampled relations are then resolved to playable YouTube Music
 * results. If WhoSampled cannot be read, the existing MusicLab search remains
 * available as a fallback.
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
    var whoSampledStatus by remember { mutableStateOf(WhoSampledStatus.NETWORK_ERROR) }
    var confirmedCount by remember { mutableStateOf(0) }

    LaunchedEffect(title, originalArtist, durationSec, currentYouTubeId) {
        loading = true
        failed = false
        val outcome = try {
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
            CoverSearchOutcome(
                candidates = emptyList(),
                whoSampledStatus = WhoSampledStatus.NETWORK_ERROR,
                confirmedCount = 0,
            )
        }
        candidates = outcome.candidates
        whoSampledStatus = outcome.whoSampledStatus
        confirmedCount = outcome.confirmedCount
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

            if (!loading) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (whoSampledStatus) {
                        WhoSampledStatus.OK ->
                            if (confirmedCount > 0) {
                                stringResource(R.string.cover_source_whosampled_count, confirmedCount)
                            } else {
                                stringResource(R.string.cover_source_whosampled_empty)
                            }
                        WhoSampledStatus.NO_MATCH ->
                            stringResource(R.string.cover_source_whosampled_no_match)
                        WhoSampledStatus.BLOCKED ->
                            stringResource(R.string.cover_source_whosampled_blocked)
                        WhoSampledStatus.STRUCTURE_CHANGED ->
                            stringResource(R.string.cover_source_whosampled_changed)
                        WhoSampledStatus.NETWORK_ERROR ->
                            stringResource(R.string.cover_source_whosampled_unavailable)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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

                failed && candidates.isEmpty() -> {
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
            if (candidate.confirmedByWhoSampled) {
                Text(
                    text = stringResource(R.string.cover_confirmed_whosampled),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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
): CoverSearchOutcome = coroutineScope {
    val cleanTitle = title.trim()
    if (cleanTitle.isBlank()) {
        return@coroutineScope CoverSearchOutcome(
            emptyList(),
            WhoSampledStatus.NO_MATCH,
            0,
        )
    }

    val internalDeferred = async(Dispatchers.IO) {
        findInternalCoverCandidates(
            title = cleanTitle,
            originalArtist = originalArtist,
            durationSec = durationSec,
            currentYouTubeId = currentYouTubeId,
        )
    }

    val whoSampledDeferred = async(Dispatchers.IO) {
        runCatching {
            WhoSampledCoverSource.lookup(cleanTitle, originalArtist)
        }.getOrElse {
            WhoSampledLookup(emptyList(), WhoSampledStatus.NETWORK_ERROR)
        }
    }

    val whoSampledLookup = whoSampledDeferred.await()
    val confirmed = if (
        whoSampledLookup.status == WhoSampledStatus.OK &&
        whoSampledLookup.covers.isNotEmpty()
    ) {
        resolveWhoSampledCovers(
            references = whoSampledLookup.covers,
            originalArtist = originalArtist,
            originalDurationSec = durationSec,
            currentYouTubeId = currentYouTubeId,
        )
    } else {
        emptyList()
    }

    val internal = internalDeferred.await()
    val merged = linkedMapOf<String, CoverCandidate>()

    confirmed.forEach { candidate ->
        merged[candidate.song.id] = candidate
    }
    internal.forEach { candidate ->
        val existing = merged[candidate.song.id]
        if (existing == null || (!existing.confirmedByWhoSampled && candidate.score > existing.score)) {
            merged[candidate.song.id] = candidate
        }
    }

    val ordered = merged.values
        .sortedWith(
            compareByDescending<CoverCandidate> { it.confirmedByWhoSampled }
                .thenByDescending { it.differentArtist }
                .thenByDescending { it.score }
        )
        .take(MAX_RESULTS)

    CoverSearchOutcome(
        candidates = ordered,
        whoSampledStatus = whoSampledLookup.status,
        confirmedCount = confirmed.size,
    )
}

private suspend fun resolveWhoSampledCovers(
    references: List<WhoSampledCover>,
    originalArtist: String,
    originalDurationSec: Int,
    currentYouTubeId: String?,
): List<CoverCandidate> = coroutineScope {
    val deduped = references
        .distinctBy { "${canonicalWorkTitle(it.title)}|${canonicalArtist(it.artist)}" }
        .take(MAX_WHOSAMPLED_TO_RESOLVE)

    val result = mutableListOf<CoverCandidate>()
    for (batch in deduped.chunked(4)) {
        result += batch.map { reference ->
            async(Dispatchers.IO) {
                resolveWhoSampledCover(
                    reference = reference,
                    originalArtist = originalArtist,
                    originalDurationSec = originalDurationSec,
                    currentYouTubeId = currentYouTubeId,
                )
            }
        }.awaitAll().filterNotNull()
    }
    result
}

private suspend fun resolveWhoSampledCover(
    reference: WhoSampledCover,
    originalArtist: String,
    originalDurationSec: Int,
    currentYouTubeId: String?,
): CoverCandidate? {
    val query = "${reference.title} ${reference.artist}".trim()
    val search = YouTube.searchSummary(query, incognito = true).getOrNull() ?: return null
    val songs = search.summaries
        .flatMap { it.items }
        .filterIsInstance<SongItem>()
        .distinctBy { it.id }

    val refTitle = canonicalWorkTitle(reference.title)
    val refArtist = canonicalArtist(reference.artist)
    val originalArtistKey = canonicalArtist(originalArtist)

    return songs.mapNotNull { song ->
        if (DISALLOWED_VARIANT_REGEX.containsMatchIn(song.title.lowercase())) return@mapNotNull null

        val titleScore = sameWorkScore(refTitle, canonicalWorkTitle(song.title))
        if (titleScore < 0.60) return@mapNotNull null

        val artistScore = song.artists
            .map { artistSimilarity(refArtist, canonicalArtist(it.name)) }
            .maxOrNull()
            ?: 0.0
        if (artistScore < 0.36 && titleScore < 0.90) return@mapNotNull null

        val candidateArtists = song.artists.map { canonicalArtist(it.name) }.filter { it.isNotBlank() }
        val differentArtist = originalArtistKey.isNotBlank() &&
            candidateArtists.none { it == originalArtistKey }

        val durationScore = looseDurationCompatibility(originalDurationSec, song.duration ?: -1)
        val currentPenalty = if (song.id == currentYouTubeId) 0.18 else 0.0
        val score = 1.0 + titleScore * 0.55 + artistScore * 0.30 + durationScore * 0.10 - currentPenalty

        CoverCandidate(
            song = song,
            score = score,
            differentArtist = differentArtist,
            confirmedByWhoSampled = true,
        )
    }.maxByOrNull { it.score }
}

private suspend fun findInternalCoverCandidates(
    title: String,
    originalArtist: String,
    durationSec: Int,
    currentYouTubeId: String?,
): List<CoverCandidate> = coroutineScope {
    val queries = linkedSetOf<String>().apply {
        if (originalArtist.isNotBlank()) {
            add("$title $originalArtist cover")
            add("$title $originalArtist version")
            add("$title $originalArtist live")
            add("$title $originalArtist")
        }
        add("$title cover")
        add("$title version")
        add("$title live")
        add("$title acoustic")
        add("$title unplugged")
        add("$title tribute")
        add("$title rendition")
        add("$title interpretation")
        add("$title versione")
        add("$title versión")
        add("$title versão")
        add(title)
    }

    val uniqueSongs = linkedMapOf<String, SongItem>()
    for (batch in queries.chunked(4)) {
        val pages = batch.map { query ->
            async(Dispatchers.IO) {
                YouTube.searchSummary(query, incognito = true).getOrNull()
            }
        }.awaitAll()

        pages.filterNotNull().forEach { page ->
            page.summaries
                .flatMap { it.items }
                .filterIsInstance<SongItem>()
                .forEach { uniqueSongs.putIfAbsent(it.id, it) }
        }
    }

    val originalWork = canonicalWorkTitle(title)
    val originalArtistKey = canonicalArtist(originalArtist)

    uniqueSongs.values.mapNotNull { song ->
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
            score = titleScore * 0.70 + durationScore * 0.20 + variantBoost + artistBoost - currentPenalty,
            differentArtist = differentArtist,
        )
    }
        .sortedWith(
            compareByDescending<CoverCandidate> { it.differentArtist }
                .thenByDescending { it.score }
        )
        .take(MAX_INTERNAL_RESULTS)
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

private fun artistSimilarity(reference: String, candidate: String): Double {
    if (reference.isBlank() || candidate.isBlank()) return 0.0
    if (reference == candidate) return 1.0
    if (reference.contains(candidate) || candidate.contains(reference)) return 0.90

    val a = reference.split(' ').filter { it.length > 1 }.toSet()
    val b = candidate.split(' ').filter { it.length > 1 }.toSet()
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val overlap = a.intersect(b).size.toDouble()
    return overlap / max(1, minOf(a.size, b.size)).toDouble()
}

/** Returns null only when a normal same-title cover has an implausible duration. */
private fun durationCompatibility(originalSec: Int, candidateSec: Int): Double? {
    if (originalSec <= 0 || candidateSec <= 0) return 0.55
    val difference = abs(originalSec - candidateSec)
    val allowed = max(120, (originalSec * 0.50).toInt())
    if (difference > allowed) return null
    return 1.0 - (difference.toDouble() / allowed.toDouble())
}

/** WhoSampled already confirms the work, so duration is only a ranking hint. */
private fun looseDurationCompatibility(originalSec: Int, candidateSec: Int): Double {
    if (originalSec <= 0 || candidateSec <= 0) return 0.55
    val difference = abs(originalSec - candidateSec)
    val allowed = max(180, (originalSec * 0.75).toInt())
    if (difference >= allowed) return 0.0
    return 1.0 - (difference.toDouble() / allowed.toDouble())
}

private const val MIN_TITLE_SCORE = 0.70
private const val MAX_INTERNAL_RESULTS = 56
private const val MAX_WHOSAMPLED_TO_RESOLVE = 24
private const val MAX_RESULTS = 60

private val WORK_NOISE_REGEX = Regex(
    "\\b(official|video|audio|lyrics?|lyric|cover|acoustic|unplugged|live|" +
        "version|versione|versión|versao|versão|rendition|interpretation|" +
        "tribute|performance|session|remaster(?:ed)?|studio)\\b"
)

private val COVER_VARIANT_REGEX = Regex(
    "\\b(cover|acoustic|unplugged|live|version|versione|versión|versao|versão|" +
        "rendition|interpretation|tribute|performance|session)\\b"
)

private val DISALLOWED_VARIANT_REGEX = Regex(
    "\\b(mashup|medley|reaction|tutorial|lesson|how to play|karaoke|" +
        "instrumental backing track|backing track)\\b"
)
