/**
 * MusicLab cover search entry point
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.ui.menu.YouTubeSongMenu
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch

private val CoverHubGeminiApiKey = stringPreferencesKey("coverGeminiApiKey")

private enum class CoverSearchMode {
    COVERS,
    SAME_NAME,
    FOREIGN,
}

/**
 * Full-screen persistent search hub. Normal taps play a result without closing
 * the search; long press opens the standard MusicLab song menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoverSearchDialog(
    title: String,
    originalArtist: String,
    durationSec: Int,
    currentYouTubeId: String?,
    onSelect: (SongItem) -> Unit,
    onDismiss: () -> Unit,
    navController: NavController? = null,
) {
    val playerConnection = LocalPlayerConnection.current
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(CoverSearchMode.COVERS) }

    var coverLoading by remember { mutableStateOf(true) }
    var coverLoaded by remember { mutableStateOf(false) }
    var coverFailed by remember { mutableStateOf(false) }
    var coverOutcome by remember { mutableStateOf(CoverHubOutcome(emptyList())) }

    var sameNameLoading by remember { mutableStateOf(false) }
    var sameNameLoadingMore by remember { mutableStateOf(false) }
    var sameNameLoaded by remember { mutableStateOf(false) }
    var sameNameFailed by remember { mutableStateOf(false) }
    var sameNameMoreFailed by remember { mutableStateOf(false) }
    var sameNameResults by remember { mutableStateOf<List<CoverHubResult>>(emptyList()) }
    var sameNameContinuation by remember { mutableStateOf<String?>(null) }
    var sameNameLastScanPages by remember { mutableStateOf(0) }
    var sameNameLastAdded by remember { mutableStateOf(0) }
    var sameNameScannedMore by remember { mutableStateOf(false) }

    var foreignLoading by remember { mutableStateOf(false) }
    var foreignLoaded by remember { mutableStateOf(false) }
    var foreignFailed by remember { mutableStateOf(false) }
    var foreignOutcome by remember { mutableStateOf(ForeignVersionsOutcome(emptyList())) }

    var showKeyDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }

    val coverListState = rememberLazyListState()
    val sameNameListState = rememberLazyListState()
    val foreignListState = rememberLazyListState()

    val aiProvider by rememberPreference(AiProviderKey, "OpenRouter")
    val sharedApiKey by rememberPreference(OpenRouterApiKey, "")
    val sharedModel by rememberPreference(OpenRouterModelKey, "")
    var dedicatedGeminiKey by rememberPreference(CoverHubGeminiApiKey, "")

    val sharedGoogleKey = sharedApiKey.takeIf {
        it.isNotBlank() && (aiProvider == "Gemini" || it.trim().startsWith("AIza"))
    }.orEmpty()
    val effectiveKey = dedicatedGeminiKey.ifBlank { sharedGoogleKey }
    val effectiveModel = if (
        aiProvider == "Gemini" &&
        sharedModel.isNotBlank() &&
        !sharedModel.contains('/')
    ) {
        sharedModel
    } else {
        "gemini-2.5-flash-lite"
    }
    val geminiConfig = effectiveKey.takeIf { it.isNotBlank() }?.let {
        GeminiCoverVerificationConfig(apiKey = it, model = effectiveModel)
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("Gemini per Cerca cover") },
            text = {
                Column {
                    Text(
                        "Chiave Google Gemini dedicata alla ricerca. La configurazione della traduzione AI resta indipendente.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
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
                TextButton(onClick = {
                    dedicatedGeminiKey = keyDraft.trim()
                    showKeyDialog = false
                    coverLoaded = false
                    sameNameLoaded = false
                    foreignLoaded = false
                }) { Text("Salva") }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("Annulla") }
            },
        )
    }

    LaunchedEffect(
        title,
        originalArtist,
        durationSec,
        currentYouTubeId,
        effectiveKey,
        effectiveModel,
        coverLoaded,
    ) {
        if (coverLoaded) return@LaunchedEffect
        coverLoading = true
        coverFailed = false
        coverOutcome = try {
            CoverHubSearchEngine.searchCovers(
                title = title,
                originalArtist = originalArtist,
                durationSec = durationSec,
                currentYouTubeId = currentYouTubeId,
                geminiConfig = geminiConfig,
            )
        } catch (_: Exception) {
            coverFailed = true
            CoverHubOutcome(emptyList())
        }
        coverLoading = false
        coverLoaded = true
    }

    LaunchedEffect(
        mode,
        title,
        currentYouTubeId,
        effectiveKey,
        effectiveModel,
        sameNameLoaded,
    ) {
        if (mode != CoverSearchMode.SAME_NAME || sameNameLoaded) return@LaunchedEffect
        sameNameLoading = true
        sameNameFailed = false
        sameNameMoreFailed = false
        sameNameScannedMore = false
        try {
            val page = CoverHubSearchEngine.searchSameName(
                title = title,
                currentYouTubeId = currentYouTubeId,
                geminiConfig = geminiConfig,
            )
            sameNameResults = page.results
            sameNameContinuation = page.continuation
            sameNameLastScanPages = page.scannedPages
            sameNameLastAdded = page.addedCount
        } catch (_: Exception) {
            sameNameFailed = true
            sameNameResults = emptyList()
            sameNameContinuation = null
            sameNameLastScanPages = 0
            sameNameLastAdded = 0
        }
        sameNameLoading = false
        sameNameLoaded = true
    }

    LaunchedEffect(
        mode,
        title,
        originalArtist,
        currentYouTubeId,
        effectiveKey,
        effectiveModel,
        foreignLoaded,
    ) {
        if (mode != CoverSearchMode.FOREIGN || foreignLoaded) return@LaunchedEffect
        foreignLoading = true
        foreignFailed = false
        foreignOutcome = try {
            ForeignVersionsSearchEngine.search(
                title = title,
                originalArtist = originalArtist,
                currentYouTubeId = currentYouTubeId,
                geminiConfig = geminiConfig,
            )
        } catch (_: Exception) {
            foreignFailed = true
            ForeignVersionsOutcome(emptyList())
        }
        foreignLoading = false
        foreignLoaded = true
    }

    fun play(song: SongItem) {
        if (playerConnection != null) {
            playerConnection.playNext(song.toMediaItem())
            playerConnection.seekToNext()
        } else {
            onSelect(song)
        }
    }

    fun statsText(stats: CoverSourceStats): String {
        val notResolved = (stats.found - stats.resolved).coerceAtLeast(0)
        return "${stats.found} trovati · $notResolved non risolti · ${stats.resolved} risolti · ${stats.used} usati"
    }

    fun whoState(status: WhoSampledStatus): String = when (status) {
        WhoSampledStatus.OK -> "ok"
        WhoSampledStatus.NO_MATCH -> "nessuna relazione"
        WhoSampledStatus.BLOCKED -> "bloccato da verifica"
        WhoSampledStatus.STRUCTURE_CHANGED -> "struttura non leggibile"
        WhoSampledStatus.NETWORK_ERROR -> "non disponibile"
    }

    fun secondState(status: SecondHandSongsStatus): String = when (status) {
        SecondHandSongsStatus.OK -> "ok"
        SecondHandSongsStatus.NO_MATCH -> "nessuna relazione"
        SecondHandSongsStatus.BLOCKED -> "bloccato"
        SecondHandSongsStatus.AUTH_REQUIRED -> "autenticazione richiesta"
        SecondHandSongsStatus.RATE_LIMITED -> "limite temporaneo"
        SecondHandSongsStatus.NETWORK_ERROR -> "non disponibile"
    }

    fun mbState(status: MusicBrainzStatus): String = when (status) {
        MusicBrainzStatus.OK -> "ok"
        MusicBrainzStatus.NO_MATCH -> "nessuna relazione"
        MusicBrainzStatus.NETWORK_ERROR -> "non disponibile"
    }

    if (showDiagnosticsDialog) {
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("Verifica motori") },
            text = {
                Column {
                    if (coverLoading) {
                        Text("Ricerca cover ancora in corso…", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }

                    Text("Cover", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text("WhoSampled: ${whoState(coverOutcome.whoSampledStatus)}", style = MaterialTheme.typography.bodyMedium)
                    Text(statsText(coverOutcome.whoSampledStats), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text("SecondHandSongs: ${secondState(coverOutcome.secondHandSongsStatus)}", style = MaterialTheme.typography.bodyMedium)
                    Text(statsText(coverOutcome.secondHandSongsStats), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text("MusicBrainz: ${mbState(coverOutcome.musicBrainzStatus)}", style = MaterialTheme.typography.bodyMedium)
                    Text(statsText(coverOutcome.musicBrainzStats), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (coverOutcome.geminiConfigured) "Gemini AI: attivo" else "Gemini AI: non configurato",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(statsText(coverOutcome.geminiStats), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text("YouTube Music", style = MaterialTheme.typography.bodyMedium)
                    Text(statsText(coverOutcome.youtubeMusicStats), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Risultati finali cover: ${coverOutcome.results.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    if (foreignLoaded || foreignLoading) {
                        Spacer(Modifier.height(12.dp))
                        Text("Versioni straniere", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        if (foreignLoading) {
                            Text("Ricerca ancora in corso…", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Spacer(Modifier.height(6.dp))
                            Text("WhoSampled: ${whoState(foreignOutcome.whoSampledStatus)}", style = MaterialTheme.typography.bodyMedium)
                            Text(statsText(foreignOutcome.whoSampledStats), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            Text("SecondHandSongs: ${secondState(foreignOutcome.secondHandSongsStatus)}", style = MaterialTheme.typography.bodyMedium)
                            Text(statsText(foreignOutcome.secondHandSongsStats), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            Text("MusicBrainz: ${mbState(foreignOutcome.musicBrainzStatus)}", style = MaterialTheme.typography.bodyMedium)
                            Text(statsText(foreignOutcome.musicBrainzStats), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (foreignOutcome.geminiConfigured) "Gemini AI: attivo" else "Gemini AI: non configurato",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(statsText(foreignOutcome.geminiStats), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "YouTube Music: ${foreignOutcome.results.size} versioni riproducibili",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Risultati finali stranieri: ${foreignOutcome.results.size}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    if (sameNameLoaded) {
                        Spacer(Modifier.height(12.dp))
                        Text("Stesso nome · YouTube Music", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "${sameNameResults.size} risultati · $sameNameLastScanPages pagine nell'ultimo blocco · $sameNameLastAdded aggiunti",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnosticsDialog = false }) { Text("Chiudi") }
            },
        )
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Cerca",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showDiagnosticsDialog = true }) { Text("ⓘ") }
                    TextButton(onClick = onDismiss) { Text("Chiudi") }
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = mode == CoverSearchMode.COVERS,
                        onClick = { mode = CoverSearchMode.COVERS },
                        label = { Text("Cover", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(0.8f),
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = mode == CoverSearchMode.SAME_NAME,
                        onClick = { mode = CoverSearchMode.SAME_NAME },
                        label = { Text("Stesso nome", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1.05f),
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = mode == CoverSearchMode.FOREIGN,
                        onClick = { mode = CoverSearchMode.FOREIGN },
                        label = {
                            Text(
                                "Versioni straniere",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                            )
                        },
                        modifier = Modifier.weight(1.35f),
                    )
                }

                when (mode) {
                    CoverSearchMode.COVERS -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (geminiConfig != null) "Gemini: attivo" else "Gemini: non configurato",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (geminiConfig != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                keyDraft = dedicatedGeminiKey.ifBlank { sharedGoogleKey }
                                showKeyDialog = true
                            }) { Text("Chiave AI") }
                        }
                    }

                    CoverSearchMode.SAME_NAME -> {
                        Text(
                            text = "Tutte le canzoni trovate con lo stesso titolo, anche se non sono cover.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                        if (!sameNameLoading && sameNameLoaded) {
                            if (sameNameScannedMore && sameNameLastAdded == 0 && sameNameContinuation != null) {
                                Text(
                                    text = "Nessun nuovo risultato in questo blocco; puoi premere Cerca ancora.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            } else if (sameNameScannedMore && sameNameLastAdded > 0) {
                                Text(
                                    text = "+$sameNameLastAdded nuovi risultati.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            } else if (sameNameContinuation == null && sameNameResults.isNotEmpty()) {
                                Text(
                                    text = "Fine dei risultati disponibili.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }
                    }

                    CoverSearchMode.FOREIGN -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Adattamenti e traduzioni in altre lingue della stessa composizione.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                keyDraft = dedicatedGeminiKey.ifBlank { sharedGoogleKey }
                                showKeyDialog = true
                            }) { Text("Chiave AI") }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                val loading = when (mode) {
                    CoverSearchMode.COVERS -> coverLoading
                    CoverSearchMode.SAME_NAME -> sameNameLoading
                    CoverSearchMode.FOREIGN -> foreignLoading
                }
                val failed = when (mode) {
                    CoverSearchMode.COVERS -> coverFailed
                    CoverSearchMode.SAME_NAME -> sameNameFailed
                    CoverSearchMode.FOREIGN -> foreignFailed
                }
                val results = when (mode) {
                    CoverSearchMode.COVERS -> coverOutcome.results
                    CoverSearchMode.SAME_NAME -> sameNameResults
                    CoverSearchMode.FOREIGN -> foreignOutcome.results
                }
                val listState = when (mode) {
                    CoverSearchMode.COVERS -> coverListState
                    CoverSearchMode.SAME_NAME -> sameNameListState
                    CoverSearchMode.FOREIGN -> foreignListState
                }

                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    failed && results.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { Text("Errore durante la ricerca") }

                    results.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (mode) {
                                CoverSearchMode.COVERS -> "Nessuna interpretazione affidabile trovata"
                                CoverSearchMode.SAME_NAME -> "Nessun altro brano con lo stesso nome trovato"
                                CoverSearchMode.FOREIGN -> "Nessuna versione straniera verificabile trovata"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        Text(
                            text = "${results.size} risultati • dalla più vecchia alla più nuova",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val groups = remember(results) {
                            results.groupBy { it.year }.toList().sortedWith(
                                compareBy<Pair<Int?, List<CoverHubResult>>> { if (it.first == null) 1 else 0 }
                                    .thenBy { it.first ?: Int.MAX_VALUE },
                            )
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            groups.forEach { group ->
                                item(key = "hub-year-${mode.name}-${group.first ?: "unknown"}") {
                                    Text(
                                        text = group.first?.toString() ?: "Data sconosciuta",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                    )
                                }
                                items(group.second, key = { "${mode.name}-${it.song.id}" }) { result ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { play(result.song) },
                                                onLongClick = {
                                                    navController?.let { nav ->
                                                        menuState.show {
                                                            YouTubeSongMenu(
                                                                song = result.song,
                                                                navController = nav,
                                                                onDismiss = menuState::dismiss,
                                                            )
                                                        }
                                                    }
                                                },
                                            )
                                            .padding(vertical = 7.dp),
                                    ) {
                                        AsyncImage(
                                            model = result.song.thumbnail,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(ListThumbnailSize)
                                                .clip(RoundedCornerShape(6.dp)),
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                result.song.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                result.song.artists.joinToString(", ") { it.name },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                result.year?.let {
                                                    Text(
                                                        "Anno: $it",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                }
                                                Text(
                                                    result.source,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (result.confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (
                            mode == CoverSearchMode.SAME_NAME &&
                            sameNameContinuation != null &&
                            sameNameResults.size < 100
                        ) {
                            TextButton(
                                enabled = !sameNameLoadingMore,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                onClick = {
                                    val continuation = sameNameContinuation
                                    if (continuation != null) {
                                        sameNameLoadingMore = true
                                        sameNameMoreFailed = false
                                        coroutineScope.launch {
                                            try {
                                                val page = CoverHubSearchEngine.searchSameNameMore(
                                                    title = title,
                                                    continuation = continuation,
                                                    currentYouTubeId = currentYouTubeId,
                                                    existingResults = sameNameResults,
                                                    geminiConfig = geminiConfig,
                                                )
                                                sameNameResults = page.results
                                                sameNameContinuation = page.continuation
                                                sameNameLastScanPages = page.scannedPages
                                                sameNameLastAdded = page.addedCount
                                                sameNameScannedMore = true
                                            } catch (_: Exception) {
                                                sameNameMoreFailed = true
                                            } finally {
                                                sameNameLoadingMore = false
                                            }
                                        }
                                    }
                                },
                            ) {
                                if (sameNameLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Cerco altri risultati…")
                                } else {
                                    Text("Cerca ancora")
                                }
                            }
                        }

                        if (mode == CoverSearchMode.SAME_NAME && sameNameMoreFailed) {
                            Text(
                                text = "Impossibile caricare altri risultati. Puoi riprovare.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                        }
                    }
                }
            }
        }
    }
}
