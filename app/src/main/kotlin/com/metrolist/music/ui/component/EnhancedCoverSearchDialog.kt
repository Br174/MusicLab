/**
 * MusicLab enhanced cover search
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.preferences.core.stringPreferencesKey
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.R
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

private val CoverGeminiApiKeyKey = stringPreferencesKey("coverGeminiApiKey")

private data class EnhancedCoverCandidate(
    val song: SongItem,
    val score: Double,
    val differentArtist: Boolean,
    val confirmedByWhoSampled: Boolean = false,
    val confidence: CoverConfidence = CoverConfidence.PROBABLE,
    val year: Int? = null,
)

private data class EnhancedCoverSearchOutcome(
    val candidates: List<EnhancedCoverCandidate>,
    val whoSampledStatus: WhoSampledStatus,
    val confirmedCount: Int,
    val geminiDiscoveredCount: Int,
)

private enum class StructuredCoverSource {
    WHOSAMPLED,
    MUSICBRAINZ,
    GEMINI,
}

@Composable
internal fun EnhancedCoverSearchDialog(
    title: String,
    originalArtist: String,
    durationSec: Int,
    currentYouTubeId: String?,
    onSelect: (SongItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var candidates by remember { mutableStateOf<List<EnhancedCoverCandidate>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var whoSampledStatus by remember { mutableStateOf(WhoSampledStatus.NETWORK_ERROR) }
    var confirmedCount by remember { mutableStateOf(0) }
    var geminiDiscoveredCount by remember { mutableStateOf(0) }
    var showGeminiKeyDialog by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }

    val aiProvider by rememberPreference(AiProviderKey, "OpenRouter")
    val sharedAiApiKey by rememberPreference(OpenRouterApiKey, "")
    val sharedAiModel by rememberPreference(OpenRouterModelKey, "")
    var coverGeminiApiKey by rememberPreference(CoverGeminiApiKeyKey, "")

    val sharedGoogleKey =
        sharedAiApiKey.takeIf {
            it.isNotBlank() &&
                (aiProvider == "Gemini" || it.trim().startsWith("AIza"))
        }.orEmpty()
    val effectiveGeminiKey = coverGeminiApiKey.ifBlank { sharedGoogleKey }
    val effectiveGeminiModel =
        if (aiProvider == "Gemini" && sharedAiModel.isNotBlank() && !sharedAiModel.contains('/')) {
            sharedAiModel
        } else {
            DEFAULT_COVER_GEMINI_MODEL
        }
    val geminiConfig =
        effectiveGeminiKey
            .takeIf { it.isNotBlank() }
            ?.let {
                GeminiCoverVerificationConfig(
                    apiKey = it,
                    model = effectiveGeminiModel,
                )
            }

    if (showGeminiKeyDialog) {
        AlertDialog(
            onDismissRequest = { showGeminiKeyDialog = false },
            title = { Text("Gemini per Cerca cover") },
            text = {
                Column {
                    Text(
                        "Chiave Google Gemini dedicata alla ricerca cover. " +
                            "OpenRouter e la traduzione AI restano indipendenti.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        label = { Text("Chiave API Google AI") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coverGeminiApiKey = keyDraft.trim()
                        showGeminiKeyDialog = false
                    },
                ) {
                    Text("Salva")
                }
            },
            dismissButton = {
                TextButton(onClick = { showGeminiKeyDialog = false }) {
                    Text("Annulla")
                }
            },
        )
    }

    LaunchedEffect(
        title,
        originalArtist,
        durationSec,
        currentYouTubeId,
        effectiveGeminiKey,
        effectiveGeminiModel,
    ) {
        loading = true
        failed = false
        val outcome =
            try {
                withContext(Dispatchers.IO) {
                    findEnhancedCoverCandidates(
                        title = title,
                        originalArtist = originalArtist,
                        durationSec = durationSec,
                        currentYouTubeId = currentYouTubeId,
                        geminiConfig = geminiConfig,
                    )
                }
            } catch (_: Exception) {
                failed = true
                EnhancedCoverSearchOutcome(
                    candidates = emptyList(),
                    whoSampledStatus = WhoSampledStatus.NETWORK_ERROR,
                    confirmedCount = 0,
                    geminiDiscoveredCount = 0,
                )
            }
        candidates = outcome.candidates
        whoSampledStatus = outcome.whoSampledStatus
        confirmedCount = outcome.confirmedCount
        geminiDiscoveredCount = outcome.geminiDiscoveredCount
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.find_covers),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(R.string.cover_close))
                    }
                }

                Text(
                    text = stringResource(R.string.find_covers_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text =
                            if (geminiConfig != null) {
                                if (!loading && geminiDiscoveredCount > 0) {
                                    "Gemini: attivo • $geminiDiscoveredCount risultati web"
                                } else {
                                    "Gemini: attivo"
                                }
                            } else {
                                "Gemini: non configurato"
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (geminiConfig != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            keyDraft = coverGeminiApiKey.ifBlank { sharedGoogleKey }
                            showGeminiKeyDialog = true
                        },
                    ) {
                        Text(if (geminiConfig == null) "Configura AI" else "Chiave AI")
                    }
                }

                if (!loading) {
                    Text(
                        text =
                            when (whoSampledStatus) {
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
                    if (candidates.isNotEmpty()) {
                        Text(
                            text = "${candidates.size} interpretazioni trovate • dalla più vecchia alla più nuova",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                when {
                    loading -> {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    failed && candidates.isEmpty() -> {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                        ) {
                            Text(
                                text = stringResource(R.string.cover_search_error),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    candidates.isEmpty() -> {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                        ) {
                            Text(
                                text = stringResource(R.string.no_covers_found),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    else -> {
                        val yearGroups =
                            remember(candidates) {
                                candidates
                                    .groupBy { it.year }
                                    .toList()
                                    .sortedWith(
                                        compareBy<Pair<Int?, List<EnhancedCoverCandidate>>> {
                                            if (it.first == null) 1 else 0
                                        }.thenBy { it.first ?: Int.MAX_VALUE },
                                    )
                            }

                        LazyColumn(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                        ) {
                            yearGroups.forEach { group ->
                                item(key = "cover-year-${group.first ?: "unknown"}") {
                                    Text(
                                        text =
                                            group.first?.toString()
                                                ?: stringResource(R.string.cover_year_unknown),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                                    )
                                }

                                items(group.second, key = { it.song.id }) { candidate ->
                                    EnhancedCoverCandidateRow(
                                        candidate = candidate,
                                        originalTitle = title,
                                        originalArtist = originalArtist,
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
    }
}

@Composable
private fun EnhancedCoverCandidateRow(
    candidate: EnhancedCoverCandidate,
    originalTitle: String,
    originalArtist: String,
    onClick: () -> Unit,
) {
    val song = candidate.song
    val uriHandler = LocalUriHandler.current
    val candidateArtist = song.artists.joinToString(", ") { it.name }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
    ) {
        AsyncImage(
            model = song.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(58.dp)
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
                text = candidateArtist,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (candidate.differentArtist) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            candidate.year?.let { year ->
                Text(
                    text = "Anno: $year",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text =
                    when (candidate.confidence) {
                        CoverConfidence.CONFIRMED -> stringResource(R.string.cover_confidence_confirmed)
                        CoverConfidence.VERIFIED -> stringResource(R.string.cover_confidence_verified)
                        CoverConfidence.PROBABLE -> stringResource(R.string.cover_confidence_probable)
                        CoverConfidence.REJECTED -> stringResource(R.string.cover_confidence_rejected)
                    },
                style = MaterialTheme.typography.labelSmall,
                color =
                    when (candidate.confidence) {
                        CoverConfidence.CONFIRMED,
                        CoverConfidence.VERIFIED,
                        -> MaterialTheme.colorScheme.primary

                        CoverConfidence.PROBABLE,
                        CoverConfidence.REJECTED,
                        -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
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

            if (candidate.confidence == CoverConfidence.PROBABLE) {
                TextButton(
                    onClick = {
                        val url =
                            CoverWebVerification.googleSearchUrl(
                                originalTitle = originalTitle,
                                originalArtist = originalArtist,
                                candidateTitle = song.title,
                                candidateArtist = candidateArtist,
                            )
                        runCatching { uriHandler.openUri(url) }
                    },
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(text = stringResource(R.string.cover_verify_web))
                }
            }
        }
    }
}

private suspend fun findEnhancedCoverCandidates(
    title: String,
    originalArtist: String,
    durationSec: Int,
    currentYouTubeId: String?,
    geminiConfig: GeminiCoverVerificationConfig?,
): EnhancedCoverSearchOutcome = coroutineScope {
    val cleanTitle = title.trim()
    if (cleanTitle.isBlank()) {
        return@coroutineScope EnhancedCoverSearchOutcome(
            candidates = emptyList(),
            whoSampledStatus = WhoSampledStatus.NO_MATCH,
            confirmedCount = 0,
            geminiDiscoveredCount = 0,
        )
    }

    val whoLookupDeferred =
        async(Dispatchers.IO) {
            runCatching { WhoSampledCoverSource.lookup(cleanTitle, originalArtist) }
                .getOrElse { WhoSampledLookup(emptyList(), WhoSampledStatus.NETWORK_ERROR) }
        }
    val musicBrainzLookupDeferred =
        async(Dispatchers.IO) {
            runCatching { MusicBrainzCoverSource.lookup(cleanTitle, originalArtist) }
                .getOrElse { MusicBrainzLookup(emptyList(), MusicBrainzStatus.NETWORK_ERROR) }
        }
    val geminiDiscoveryDeferred =
        geminiConfig?.let { config ->
            async(Dispatchers.IO) {
                runCatching {
                    GeminiCoverDiscovery.discover(
                        originalTitle = cleanTitle,
                        originalArtist = originalArtist,
                        config = config,
                    )
                }.getOrDefault(emptyList())
            }
        }
    val internalDeferred =
        async(Dispatchers.IO) {
            findBroadInternalCoverCandidates(
                title = cleanTitle,
                originalArtist = originalArtist,
                durationSec = durationSec,
                currentYouTubeId = currentYouTubeId,
            )
        }

    val whoLookup = whoLookupDeferred.await()
    val musicBrainzLookup = musicBrainzLookupDeferred.await()
    val geminiReferences = geminiDiscoveryDeferred?.await().orEmpty()

    val whoResolvedDeferred =
        async {
            resolveWhoSampledReferences(
                references = whoLookup.covers,
                originalArtist = originalArtist,
                originalDurationSec = durationSec,
                currentYouTubeId = currentYouTubeId,
            )
        }
    val musicBrainzResolvedDeferred =
        async {
            resolveMusicBrainzReferences(
                references = musicBrainzLookup.covers,
                originalArtist = originalArtist,
                originalDurationSec = durationSec,
                currentYouTubeId = currentYouTubeId,
            )
        }
    val geminiResolvedDeferred =
        async {
            resolveGeminiReferences(
                references = geminiReferences,
                originalArtist = originalArtist,
                originalDurationSec = durationSec,
                currentYouTubeId = currentYouTubeId,
            )
        }

    val whoResolved = whoResolvedDeferred.await()
    val musicBrainzResolved = musicBrainzResolvedDeferred.await()
    val geminiResolved = geminiResolvedDeferred.await()
    val internal = internalDeferred.await()

    val merged = linkedMapOf<String, EnhancedCoverCandidate>()

    fun merge(candidate: EnhancedCoverCandidate) {
        if (candidate.confidence == CoverConfidence.REJECTED) return
        if (candidate.song.id == currentYouTubeId) return
        val existing = merged[candidate.song.id]
        if (
            existing == null ||
            enhancedConfidenceRank(candidate.confidence) > enhancedConfidenceRank(existing.confidence) ||
            (
                enhancedConfidenceRank(candidate.confidence) == enhancedConfidenceRank(existing.confidence) &&
                    candidate.score > existing.score
            )
        ) {
            merged[candidate.song.id] = candidate
        }
    }

    whoResolved.forEach(::merge)
    musicBrainzResolved.forEach(::merge)
    geminiResolved.forEach(::merge)
    internal.forEach(::merge)

    val performanceDeduped =
        merged.values
            .groupBy { candidate ->
                val artistKey = candidate.song.artists.joinToString("|") { enhancedCanonicalArtist(it.name) }
                "${enhancedCanonicalTitle(candidate.song.title)}|$artistKey"
            }.map { (_, versions) ->
                versions.maxWithOrNull(
                    compareBy<EnhancedCoverCandidate> { enhancedConfidenceRank(it.confidence) }
                        .thenBy { it.score },
                ) ?: versions.first()
            }

    val ranked =
        performanceDeduped
            .sortedWith(
                compareByDescending<EnhancedCoverCandidate> { enhancedConfidenceRank(it.confidence) }
                    .thenByDescending { it.differentArtist }
                    .thenByDescending { it.score },
            ).take(MAX_ENHANCED_RESULTS)

    val verified =
        if (geminiConfig != null) {
            verifyEnhancedProbableCandidates(
                candidates = ranked,
                originalTitle = cleanTitle,
                originalArtist = originalArtist,
                originalDurationSec = durationSec,
                config = geminiConfig,
            )
        } else {
            ranked
        }

    val withYears = enrichEnhancedYears(verified, geminiConfig)
    val ordered =
        withYears.sortedWith(
            compareBy<EnhancedCoverCandidate> { if (it.year == null) 1 else 0 }
                .thenBy { it.year ?: Int.MAX_VALUE }
                .thenByDescending { enhancedConfidenceRank(it.confidence) }
                .thenByDescending { it.score },
        )

    EnhancedCoverSearchOutcome(
        candidates = ordered,
        whoSampledStatus = whoLookup.status,
        confirmedCount = whoResolved.count { it.confidence == CoverConfidence.CONFIRMED },
        geminiDiscoveredCount = geminiReferences.size,
    )
}

private suspend fun resolveWhoSampledReferences(
    references: List<WhoSampledCover>,
    originalArtist: String,
    originalDurationSec: Int,
    currentYouTubeId: String?,
): List<EnhancedCoverCandidate> = coroutineScope {
    val deduped =
        references
            .distinctBy { "${enhancedCanonicalTitle(it.title)}|${enhancedCanonicalArtist(it.artist)}" }
            .take(MAX_WHOSAMPLED_TO_RESOLVE)

    val result = mutableListOf<EnhancedCoverCandidate>()
    for (batch in deduped.chunked(5)) {
        result +=
            batch.map { reference ->
                async(Dispatchers.IO) {
                    resolveStructuredReference(
                        title = reference.title,
                        artist = reference.artist,
                        year = null,
                        source = StructuredCoverSource.WHOSAMPLED,
                        translatedOrAdaptedTitle = false,
                        originalArtist = originalArtist,
                        originalDurationSec = originalDurationSec,
                        currentYouTubeId = currentYouTubeId,
                    )
                }
            }.awaitAll().filterNotNull()
    }
    result
}

private suspend fun resolveMusicBrainzReferences(
    references: List<MusicBrainzCover>,
    originalArtist: String,
    originalDurationSec: Int,
    currentYouTubeId: String?,
): List<EnhancedCoverCandidate> = coroutineScope {
    val deduped = references.distinctBy { it.recordingId }.take(MAX_MUSICBRAINZ_TO_RESOLVE)
    val result = mutableListOf<EnhancedCoverCandidate>()
    for (batch in deduped.chunked(5)) {
        result +=
            batch.map { reference ->
                async(Dispatchers.IO) {
                    resolveStructuredReference(
                        title = reference.title,
                        artist = reference.artist,
                        year = reference.year,
                        source = StructuredCoverSource.MUSICBRAINZ,
                        translatedOrAdaptedTitle = false,
                        originalArtist = originalArtist,
                        originalDurationSec = originalDurationSec,
                        currentYouTubeId = currentYouTubeId,
                    )
                }
            }.awaitAll().filterNotNull()
    }
    result
}

private suspend fun resolveGeminiReferences(
    references: List<GeminiDiscoveredCover>,
    originalArtist: String,
    originalDurationSec: Int,
    currentYouTubeId: String?,
): List<EnhancedCoverCandidate> = coroutineScope {
    val deduped =
        references
            .distinctBy { "${enhancedCanonicalTitle(it.title)}|${enhancedCanonicalArtist(it.artist)}" }
            .take(MAX_GEMINI_TO_RESOLVE)
    val result = mutableListOf<EnhancedCoverCandidate>()
    for (batch in deduped.chunked(5)) {
        result +=
            batch.map { reference ->
                async(Dispatchers.IO) {
                    resolveStructuredReference(
                        title = reference.title,
                        artist = reference.artist,
                        year = reference.year,
                        source = StructuredCoverSource.GEMINI,
                        translatedOrAdaptedTitle = reference.translatedOrAdaptedTitle,
                        originalArtist = originalArtist,
                        originalDurationSec = originalDurationSec,
                        currentYouTubeId = currentYouTubeId,
                    )
                }
            }.awaitAll().filterNotNull()
    }
    result
}

private suspend fun resolveStructuredReference(
    title: String,
    artist: String,
    year: Int?,
    source: StructuredCoverSource,
    translatedOrAdaptedTitle: Boolean,
    originalArtist: String,
    originalDurationSec: Int,
    currentYouTubeId: String?,
): EnhancedCoverCandidate? {
    val query = "$title $artist".trim()
    val search = YouTube.searchSummary(query, incognito = true).getOrNull() ?: return null
    val songs =
        search.summaries
            .flatMap { it.items }
            .filterIsInstance<SongItem>()
            .distinctBy { it.id }

    val refTitle = enhancedCanonicalTitle(title)
    val refArtist = enhancedCanonicalArtist(artist)
    val originalArtistKey = enhancedCanonicalArtist(originalArtist)

    return songs
        .mapNotNull { song ->
            if (song.id == currentYouTubeId) return@mapNotNull null
            if (ENHANCED_DISALLOWED_VARIANT_REGEX.containsMatchIn(song.title.lowercase())) return@mapNotNull null

            val titleScore = enhancedSameWorkScore(refTitle, enhancedCanonicalTitle(song.title))
            if (titleScore < 0.50) return@mapNotNull null

            val artistScore =
                song.artists
                    .map { enhancedArtistSimilarity(refArtist, enhancedCanonicalArtist(it.name)) }
                    .maxOrNull()
                    ?: 0.0
            if (artistScore < 0.27 && titleScore < 0.91) return@mapNotNull null

            val candidateArtists = song.artists.map { enhancedCanonicalArtist(it.name) }.filter { it.isNotBlank() }
            val differentArtist =
                originalArtistKey.isNotBlank() &&
                    candidateArtists.none { it == originalArtistKey }
            if (!differentArtist && source != StructuredCoverSource.WHOSAMPLED) return@mapNotNull null

            val durationScore = enhancedLooseDurationCompatibility(originalDurationSec, song.duration ?: -1)
            val confidence =
                when (source) {
                    StructuredCoverSource.WHOSAMPLED ->
                        CoverConfidenceEngine.evaluate(
                            CoverEvidence(
                                whoSampledRelationship = true,
                                titleSimilarity = titleScore,
                                durationSimilarity = durationScore,
                                differentArtist = differentArtist,
                            ),
                        )

                    StructuredCoverSource.MUSICBRAINZ ->
                        CoverConfidenceEngine.evaluate(
                            CoverEvidence(
                                workIdentifierMatch = true,
                                titleSimilarity = titleScore,
                                durationSimilarity = durationScore,
                                differentArtist = differentArtist,
                            ),
                        )

                    StructuredCoverSource.GEMINI -> CoverConfidence.PROBABLE
                }

            val sourceBoost =
                when (source) {
                    StructuredCoverSource.WHOSAMPLED -> 1.05
                    StructuredCoverSource.MUSICBRAINZ -> 1.0
                    StructuredCoverSource.GEMINI -> 0.78
                }
            val score =
                sourceBoost +
                    titleScore * 0.52 +
                    artistScore * 0.28 +
                    durationScore * 0.10 +
                    if (translatedOrAdaptedTitle) 0.08 else 0.0

            EnhancedCoverCandidate(
                song = song,
                score = score,
                differentArtist = differentArtist,
                confirmedByWhoSampled = source == StructuredCoverSource.WHOSAMPLED,
                confidence = confidence,
                year = year,
            )
        }.maxByOrNull { it.score }
}

private suspend fun findBroadInternalCoverCandidates(
    title: String,
    originalArtist: String,
    durationSec: Int,
    currentYouTubeId: String?,
): List<EnhancedCoverCandidate> = coroutineScope {
    val queries =
        linkedSetOf<String>().apply {
            if (originalArtist.isNotBlank()) {
                add("$title $originalArtist cover")
                add("$title $originalArtist version")
                add("$title $originalArtist interpretation")
                add("$title $originalArtist")
            }
            add("$title cover")
            add("$title covers")
            add("$title version")
            add("$title rendition")
            add("$title interpretation")
            add("$title reinterpretation")
            add("$title performed by")
            add("$title tribute")
            add("$title acoustic cover")
            add("$title unplugged cover")
            add("$title live cover")
            add("$title remake")
            add("$title versione")
            add("$title versión")
            add("$title versão")
            add("$title reprise")
            add(title)
        }

    val uniqueSongs = linkedMapOf<String, SongItem>()
    for (batch in queries.chunked(5)) {
        val pages =
            batch.map { query ->
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

    val originalWork = enhancedCanonicalTitle(title)
    val originalArtistKey = enhancedCanonicalArtist(originalArtist)

    uniqueSongs.values
        .mapNotNull { song ->
            if (song.id == currentYouTubeId) return@mapNotNull null
            if (ENHANCED_DISALLOWED_VARIANT_REGEX.containsMatchIn(song.title.lowercase())) return@mapNotNull null

            val candidateArtists = song.artists.map { enhancedCanonicalArtist(it.name) }.filter { it.isNotBlank() }
            val differentArtist =
                originalArtistKey.isNotBlank() &&
                    candidateArtists.none { it == originalArtistKey }
            if (!differentArtist) return@mapNotNull null

            val titleScore = enhancedSameWorkScore(originalWork, enhancedCanonicalTitle(song.title))
            if (titleScore < MIN_ENHANCED_TITLE_SCORE) return@mapNotNull null

            val durationScore =
                enhancedDurationCompatibility(durationSec, song.duration ?: -1)
                    ?: return@mapNotNull null
            val explicitVariantLabel = ENHANCED_COVER_VARIANT_REGEX.containsMatchIn(song.title.lowercase())

            val confidence =
                CoverConfidenceEngine.evaluate(
                    CoverEvidence(
                        titleSimilarity = titleScore,
                        durationSimilarity = durationScore,
                        differentArtist = true,
                        explicitVariantLabel = explicitVariantLabel,
                    ),
                )
            if (confidence == CoverConfidence.REJECTED) return@mapNotNull null

            val variantBoost = if (explicitVariantLabel) 0.12 else 0.0
            EnhancedCoverCandidate(
                song = song,
                score = titleScore * 0.72 + durationScore * 0.20 + variantBoost + 0.24,
                differentArtist = true,
                confidence = confidence,
            )
        }.sortedWith(
            compareByDescending<EnhancedCoverCandidate> { enhancedConfidenceRank(it.confidence) }
                .thenByDescending { it.score },
        ).take(MAX_INTERNAL_RESULTS)
}

private suspend fun verifyEnhancedProbableCandidates(
    candidates: List<EnhancedCoverCandidate>,
    originalTitle: String,
    originalArtist: String,
    originalDurationSec: Int,
    config: GeminiCoverVerificationConfig,
): List<EnhancedCoverCandidate> = coroutineScope {
    val probableIds =
        candidates
            .asSequence()
            .filter { it.confidence == CoverConfidence.PROBABLE }
            .sortedByDescending { it.score }
            .take(MAX_GEMINI_VERIFICATIONS)
            .map { it.song.id }
            .toSet()

    if (probableIds.isEmpty()) return@coroutineScope candidates

    val verdicts = mutableMapOf<String, GeminiCoverVerdict?>()
    candidates
        .filter { it.song.id in probableIds }
        .chunked(GEMINI_VERIFICATION_CONCURRENCY)
        .forEach { batch ->
            batch.map { candidate ->
                async(Dispatchers.IO) {
                    val candidateArtist = candidate.song.artists.joinToString(", ") { it.name }
                    candidate.song.id to
                        runCatching {
                            GeminiCoverVerification.verify(
                                originalTitle = originalTitle,
                                originalArtist = originalArtist,
                                originalDurationSec = originalDurationSec,
                                candidateTitle = candidate.song.title,
                                candidateArtist = candidateArtist,
                                candidateDurationSec = candidate.song.duration ?: -1,
                                config = config,
                            )
                        }.getOrNull()
                }
            }.awaitAll().forEach { (id, verdict) -> verdicts[id] = verdict }
        }

    candidates.mapNotNull { candidate ->
        val verdict = verdicts[candidate.song.id] ?: return@mapNotNull candidate
        if (
            verdict.decision == GeminiCoverDecision.DIFFERENT_WORK &&
            verdict.webSourceConfirmations > 0
        ) {
            return@mapNotNull null
        }
        if (verdict.decision != GeminiCoverDecision.SAME_WORK) return@mapNotNull candidate

        val titleScore =
            enhancedSameWorkScore(
                enhancedCanonicalTitle(originalTitle),
                enhancedCanonicalTitle(candidate.song.title),
            )
        val durationScore =
            enhancedDurationCompatibility(originalDurationSec, candidate.song.duration ?: -1) ?: 0.0
        val confidence =
            CoverConfidenceEngine.evaluate(
                CoverEvidence(
                    webSourceConfirmations = verdict.webSourceConfirmations,
                    aiSupportsSameWork = true,
                    titleSimilarity = titleScore,
                    durationSimilarity = durationScore,
                    translatedOrAdaptedTitle = verdict.translatedOrAdaptedTitle,
                    differentArtist = candidate.differentArtist,
                    explicitVariantLabel = ENHANCED_COVER_VARIANT_REGEX.containsMatchIn(candidate.song.title.lowercase()),
                ),
            )

        if (confidence == CoverConfidence.VERIFIED) {
            candidate.copy(
                confidence = CoverConfidence.VERIFIED,
                score = candidate.score + 0.25,
            )
        } else {
            candidate
        }
    }
}

private suspend fun enrichEnhancedYears(
    candidates: List<EnhancedCoverCandidate>,
    geminiConfig: GeminiCoverVerificationConfig?,
): List<EnhancedCoverCandidate> = coroutineScope {
    val albumRepresentatives =
        candidates
            .filter { it.year == null }
            .mapNotNull { candidate ->
                candidate.song.album?.id
                    ?.takeIf { it.isNotBlank() }
                    ?.let { albumId -> albumId to candidate.song }
            }.distinctBy { it.first }

    val albumYears = mutableMapOf<String, Int?>()
    for (batch in albumRepresentatives.chunked(6)) {
        batch.map { (albumId, song) ->
            async(Dispatchers.IO) {
                albumId to CoverYearResolver.resolve(song)
            }
        }.awaitAll().forEach { (albumId, year) -> albumYears[albumId] = year }
    }

    val albumEnriched =
        candidates.map { candidate ->
            val albumId = candidate.song.album?.id
            candidate.copy(year = candidate.year ?: albumId?.let { albumYears[it] })
        }

    if (geminiConfig == null) return@coroutineScope albumEnriched

    val unresolved =
        albumEnriched
            .filter { it.year == null }
            .take(MAX_GEMINI_YEAR_LOOKUPS)
    if (unresolved.isEmpty()) return@coroutineScope albumEnriched

    val resolvedYears = mutableMapOf<String, Int?>()
    unresolved
        .chunked(GEMINI_YEAR_CONCURRENCY)
        .forEach { batch ->
            batch.map { candidate ->
                async(Dispatchers.IO) {
                    val artist = candidate.song.artists.joinToString(", ") { it.name }
                    candidate.song.id to
                        runCatching {
                            GeminiCoverDiscovery.resolveYear(
                                title = candidate.song.title,
                                artist = artist,
                                config = geminiConfig,
                            )
                        }.getOrNull()
                }
            }.awaitAll().forEach { (id, year) -> resolvedYears[id] = year }
        }

    albumEnriched.map { candidate ->
        candidate.copy(year = candidate.year ?: resolvedYears[candidate.song.id])
    }
}

private fun enhancedConfidenceRank(confidence: CoverConfidence): Int =
    when (confidence) {
        CoverConfidence.CONFIRMED -> 3
        CoverConfidence.VERIFIED -> 2
        CoverConfidence.PROBABLE -> 1
        CoverConfidence.REJECTED -> 0
    }

private fun enhancedCanonicalTitle(value: String): String {
    val noDiacritics =
        Normalizer
            .normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()

    return noDiacritics
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(ENHANCED_WORK_NOISE_REGEX, " ")
        .replace(Regex("\\b(feat|ft|featuring)\\.?\\s+.*$"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

private fun enhancedCanonicalArtist(value: String): String =
    Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

private fun enhancedSameWorkScore(
    original: String,
    candidate: String,
): Double {
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

private fun enhancedArtistSimilarity(
    reference: String,
    candidate: String,
): Double {
    if (reference.isBlank() || candidate.isBlank()) return 0.0
    if (reference == candidate) return 1.0
    if (reference.contains(candidate) || candidate.contains(reference)) return 0.90

    val a = reference.split(' ').filter { it.length > 1 }.toSet()
    val b = candidate.split(' ').filter { it.length > 1 }.toSet()
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val overlap = a.intersect(b).size.toDouble()
    return overlap / max(1, minOf(a.size, b.size)).toDouble()
}

private fun enhancedDurationCompatibility(
    originalSec: Int,
    candidateSec: Int,
): Double? {
    if (originalSec <= 0 || candidateSec <= 0) return 0.55
    val difference = abs(originalSec - candidateSec)
    val allowed = max(150, (originalSec * 0.60).toInt())
    if (difference > allowed) return null
    return 1.0 - (difference.toDouble() / allowed.toDouble())
}

private fun enhancedLooseDurationCompatibility(
    originalSec: Int,
    candidateSec: Int,
): Double {
    if (originalSec <= 0 || candidateSec <= 0) return 0.55
    val difference = abs(originalSec - candidateSec)
    val allowed = max(210, (originalSec * 0.85).toInt())
    if (difference >= allowed) return 0.0
    return 1.0 - (difference.toDouble() / allowed.toDouble())
}

private const val DEFAULT_COVER_GEMINI_MODEL = "gemini-2.5-flash-lite"
private const val MIN_ENHANCED_TITLE_SCORE = 0.64
private const val MAX_INTERNAL_RESULTS = 100
private const val MAX_WHOSAMPLED_TO_RESOLVE = 48
private const val MAX_MUSICBRAINZ_TO_RESOLVE = 64
private const val MAX_GEMINI_TO_RESOLVE = 36
private const val MAX_GEMINI_VERIFICATIONS = 16
private const val GEMINI_VERIFICATION_CONCURRENCY = 4
private const val MAX_GEMINI_YEAR_LOOKUPS = 24
private const val GEMINI_YEAR_CONCURRENCY = 4
private const val MAX_ENHANCED_RESULTS = 100

private val ENHANCED_WORK_NOISE_REGEX =
    Regex(
        "\\b(official|video|audio|lyrics?|lyric|cover|acoustic|unplugged|live|" +
            "version|versione|versión|versao|versão|rendition|interpretation|" +
            "reinterpretation|tribute|performance|session|remaster(?:ed)?|studio)\\b",
    )

private val ENHANCED_COVER_VARIANT_REGEX =
    Regex(
        "\\b(cover|acoustic|unplugged|live|version|versione|versión|versao|versão|" +
            "rendition|interpretation|reinterpretation|tribute|performance|session)\\b",
    )

private val ENHANCED_DISALLOWED_VARIANT_REGEX =
    Regex(
        "\\b(mashup|medley|reaction|tutorial|lesson|how to play|karaoke|" +
            "instrumental backing track|backing track)\\b",
    )
