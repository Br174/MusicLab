/**
 * MusicLab persistent in-app cover search screen
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.ui.menu.YouTubeSongMenu
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private val CoverHubScreenGeminiApiKey = stringPreferencesKey("coverGeminiApiKey")

private enum class CoverSearchScreenMode {
    COVERS,
    SAME_NAME,
    FOREIGN,
}

/** In-memory session so closing/reopening Cover does not throw away completed searches. */
private class CoverSearchSession {
    var modeName: String = CoverSearchScreenMode.COVERS.name

    var coverLoaded: Boolean = false
    var coverOutcome: CoverHubOutcome = CoverHubOutcome(emptyList())

    var sameNameLoaded: Boolean = false
    var sameNameResults: List<CoverHubResult> = emptyList()
    var sameNameContinuation: String? = null
    var sameNameLastScanPages: Int = 0
    var sameNameLastAdded: Int = 0

    var foreignLoaded: Boolean = false
    var foreignOutcome: ForeignVersionsOutcome = ForeignVersionsOutcome(emptyList())

    var coverIndex: Int = 0
    var coverOffset: Int = 0
    var sameNameIndex: Int = 0
    var sameNameOffset: Int = 0
    var foreignIndex: Int = 0
    var foreignOffset: Int = 0

    fun invalidateAiDependentSearches() {
        coverLoaded = false
        coverOutcome = CoverHubOutcome(emptyList())
        foreignLoaded = false
        foreignOutcome = ForeignVersionsOutcome(emptyList())
    }
}

private object CoverSearchSessionStore {
    private val sessions = ConcurrentHashMap<String, CoverSearchSession>()

    fun get(key: String): CoverSearchSession {
        if (sessions.size > 12 && !sessions.containsKey(key)) {
            sessions.keys.firstOrNull()?.let(sessions::remove)
        }
        return sessions.getOrPut(key) { CoverSearchSession() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoverSearchScreen(
    request: CoverSearchRequest,
    navController: NavHostController,
) {
    val title = request.title
    val originalArtist = request.originalArtist
    val durationSec = request.durationSec
    val currentYouTubeId = request.currentYouTubeId
    val sessionKey = currentYouTubeId?.takeIf { it.isNotBlank() }
        ?: "${title.trim()}|${originalArtist.trim()}"
    val session = remember(sessionKey) { CoverSearchSessionStore.get(sessionKey) }

    val playerConnection = LocalPlayerConnection.current
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val closeThresholdPx = with(density) { 64.dp.toPx() }
    var handleDrag by remember { mutableFloatStateOf(0f) }

    var mode by remember(sessionKey) {
        mutableStateOf(
            runCatching { CoverSearchScreenMode.valueOf(session.modeName) }
                .getOrDefault(CoverSearchScreenMode.COVERS),
        )
    }

    var coverLoading by remember(sessionKey) { mutableStateOf(!session.coverLoaded) }
    var coverLoaded by remember(sessionKey) { mutableStateOf(session.coverLoaded) }
    var coverFailed by remember(sessionKey) { mutableStateOf(false) }
    var coverOutcome by remember(sessionKey) { mutableStateOf(session.coverOutcome) }

    var sameNameLoading by remember(sessionKey) { mutableStateOf(false) }
    var sameNameLoadingMore by remember(sessionKey) { mutableStateOf(false) }
    var sameNameLoaded by remember(sessionKey) { mutableStateOf(session.sameNameLoaded) }
    var sameNameFailed by remember(sessionKey) { mutableStateOf(false) }
    var sameNameMoreFailed by remember(sessionKey) { mutableStateOf(false) }
    var sameNameResults by remember(sessionKey) { mutableStateOf(session.sameNameResults) }
    var sameNameContinuation by remember(sessionKey) { mutableStateOf(session.sameNameContinuation) }
    var sameNameLastScanPages by remember(sessionKey) { mutableStateOf(session.sameNameLastScanPages) }
    var sameNameLastAdded by remember(sessionKey) { mutableStateOf(session.sameNameLastAdded) }
    var sameNameScannedMore by remember(sessionKey) { mutableStateOf(false) }

    var foreignLoading by remember(sessionKey) { mutableStateOf(false) }
    var foreignLoaded by remember(sessionKey) { mutableStateOf(session.foreignLoaded) }
    var foreignFailed by remember(sessionKey) { mutableStateOf(false) }
    var foreignOutcome by remember(sessionKey) { mutableStateOf(session.foreignOutcome) }

    var showKeyDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }

    val coverListState = rememberLazyListState(session.coverIndex, session.coverOffset)
    val sameNameListState = rememberLazyListState(session.sameNameIndex, session.sameNameOffset)
    val foreignListState = rememberLazyListState(session.foreignIndex, session.foreignOffset)

    DisposableEffect(sessionKey) {
        onDispose {
            session.modeName = mode.name
            session.coverIndex = coverListState.firstVisibleItemIndex
            session.coverOffset = coverListState.firstVisibleItemScrollOffset
            session.sameNameIndex = sameNameListState.firstVisibleItemIndex
            session.sameNameOffset = sameNameListState.firstVisibleItemScrollOffset
            session.foreignIndex = foreignListState.firstVisibleItemIndex
            session.foreignOffset = foreignListState.firstVisibleItemScrollOffset
        }
    }

    val aiProvider by rememberPreference(AiProviderKey, "OpenRouter")
    val sharedApiKey by rememberPreference(OpenRouterApiKey, "")
    val sharedModel by rememberPreference(OpenRouterModelKey, "")
    var dedicatedGeminiKey by rememberPreference(CoverHubScreenGeminiApiKey, "")

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
                    session.invalidateAiDependentSearches()
                    coverLoaded = false
                    coverLoading = true
                    coverOutcome = CoverHubOutcome(emptyList())
                    foreignLoaded = false
                    foreignOutcome = ForeignVersionsOutcome(emptyList())
                    showKeyDialog = false
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
        session.coverLoaded = true
        session.coverOutcome = coverOutcome
    }

    LaunchedEffect(
        mode,
        title,
        currentYouTubeId,
        effectiveKey,
        effectiveModel,
        sameNameLoaded,
    ) {
        if (mode != CoverSearchScreenMode.SAME_NAME || sameNameLoaded) return@LaunchedEffect
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
            session.sameNameResults = page.results
            session.sameNameContinuation = page.continuation
            session.sameNameLastScanPages = page.scannedPages
            session.sameNameLastAdded = page.addedCount
        } catch (_: Exception) {
            sameNameFailed = true
            sameNameResults = emptyList()
            sameNameContinuation = null
        }
        sameNameLoading = false
        sameNameLoaded = true
        session.sameNameLoaded = true
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
        if (mode != CoverSearchScreenMode.FOREIGN || foreignLoaded) return@LaunchedEffect
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
        session.foreignLoaded = true
        session.foreignOutcome = foreignOutcome
    }

    fun play(result: CoverHubResult) {
        val connection = playerConnection ?: return
        connection.playNext(result.song.toMediaItem())
        connection.seekToNext()
        PlayerBottomSheetBridge.expandSoft()
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

    fun creditsState(status: CreditsFmStatus): String = when (status) {
        CreditsFmStatus.OK -> "ok"
        CreditsFmStatus.NO_MATCH -> "nessuna relazione"
        CreditsFmStatus.AUTH_REQUIRED -> "autenticazione richiesta"
        CreditsFmStatus.RATE_LIMITED -> "limite temporaneo"
        CreditsFmStatus.NETWORK_ERROR -> "non disponibile"
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
                    Text("Cover", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (coverLoading) {
                        Text("Ricerca ancora in corso…", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text("WhoSampled: ${whoState(coverOutcome.whoSampledStatus)}", style = MaterialTheme.typography.bodyMedium)
                        Text(statsText(coverOutcome.whoSampledStats), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Text("Credits.fm: ${creditsState(coverOutcome.creditsFmStatus)}", style = MaterialTheme.typography.bodyMedium)
                        Text(statsText(coverOutcome.creditsFmStats), style = MaterialTheme.typography.bodySmall)
                        Text(
                            "percorso: ${coverOutcome.creditsFmSourceCandidates} ISRC sorgente · " +
                                "${coverOutcome.creditsFmWorksFound} ISWC · " +
                                "${coverOutcome.creditsFmLinkedRecordings} registrazioni collegate",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("SecondHandSongs: ${secondState(coverOutcome.secondHandSongsStatus)}", style = MaterialTheme.typography.bodyMedium)
                        Text(statsText(coverOutcome.secondHandSongsStats), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Text("MusicBrainz: ${mbState(coverOutcome.musicBrainzStatus)}", style = MaterialTheme.typography.bodyMedium)
                        Text(statsText(coverOutcome.musicBrainzStats), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Text(if (coverOutcome.geminiConfigured) "Gemini AI: attivo" else "Gemini AI: non configurato")
                        Text(statsText(coverOutcome.geminiStats), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Text("YouTube Music", style = MaterialTheme.typography.bodyMedium)
                        Text(statsText(coverOutcome.youtubeMusicStats), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Risultati finali cover: ${coverOutcome.results.size}",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (foreignLoaded || foreignLoading) {
                        Spacer(Modifier.height(12.dp))
                        Text("Versioni straniere", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        if (foreignLoading) {
                            Text("Ricerca ancora in corso…", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Spacer(Modifier.height(6.dp))
                            Text("WhoSampled: ${whoState(foreignOutcome.whoSampledStatus)}")
                            Text(statsText(foreignOutcome.whoSampledStats), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            Text("SecondHandSongs: ${secondState(foreignOutcome.secondHandSongsStatus)}")
                            Text(statsText(foreignOutcome.secondHandSongsStats), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            Text("MusicBrainz: ${mbState(foreignOutcome.musicBrainzStatus)}")
                            Text(statsText(foreignOutcome.musicBrainzStats), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            Text(if (foreignOutcome.geminiConfigured) "Gemini AI: attivo" else "Gemini AI: non configurato")
                            Text(statsText(foreignOutcome.geminiStats), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            Text("YouTube Music: ${foreignOutcome.results.size} versioni riproducibili", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Risultati finali stranieri: ${foreignOutcome.results.size}",
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .pointerInput(closeThresholdPx) {
                        detectVerticalDragGestures(
                            onDragStart = { handleDrag = 0f },
                            onVerticalDrag = { _, dragAmount ->
                                if (dragAmount > 0f) handleDrag += dragAmount
                            },
                            onDragCancel = { handleDrag = 0f },
                            onDragEnd = {
                                if (handleDrag >= closeThresholdPx) {
                                    navController.popBackStack()
                                }
                                handleDrag = 0f
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 42.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
                )
            }

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
                TextButton(onClick = { navController.popBackStack() }) { Text("Chiudi") }
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
                    selected = mode == CoverSearchScreenMode.COVERS,
                    onClick = {
                        mode = CoverSearchScreenMode.COVERS
                        session.modeName = mode.name
                    },
                    label = { Text("Cover", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(0.8f),
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = mode == CoverSearchScreenMode.SAME_NAME,
                    onClick = {
                        mode = CoverSearchScreenMode.SAME_NAME
                        session.modeName = mode.name
                    },
                    label = { Text("Stesso nome", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1.05f),
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = mode == CoverSearchScreenMode.FOREIGN,
                    onClick = {
                        mode = CoverSearchScreenMode.FOREIGN
                        session.modeName = mode.name
                    },
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
                CoverSearchScreenMode.COVERS -> {
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

                CoverSearchScreenMode.SAME_NAME -> {
                    Text(
                        text = "Tutte le canzoni trovate con lo stesso titolo, anche se non sono cover.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                    if (!sameNameLoading && sameNameLoaded) {
                        when {
                            sameNameScannedMore && sameNameLastAdded == 0 && sameNameContinuation != null -> Text(
                                "Nessun nuovo risultato in questo blocco; puoi premere Cerca ancora.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            sameNameScannedMore && sameNameLastAdded > 0 -> Text(
                                "+$sameNameLastAdded nuovi risultati.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            sameNameContinuation == null && sameNameResults.isNotEmpty() -> Text(
                                "Fine dei risultati disponibili.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                CoverSearchScreenMode.FOREIGN -> {
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
                CoverSearchScreenMode.COVERS -> coverLoading
                CoverSearchScreenMode.SAME_NAME -> sameNameLoading
                CoverSearchScreenMode.FOREIGN -> foreignLoading
            }
            val failed = when (mode) {
                CoverSearchScreenMode.COVERS -> coverFailed
                CoverSearchScreenMode.SAME_NAME -> sameNameFailed
                CoverSearchScreenMode.FOREIGN -> foreignFailed
            }
            val results = when (mode) {
                CoverSearchScreenMode.COVERS -> coverOutcome.results
                CoverSearchScreenMode.SAME_NAME -> sameNameResults
                CoverSearchScreenMode.FOREIGN -> foreignOutcome.results
            }
            val listState = when (mode) {
                CoverSearchScreenMode.COVERS -> coverListState
                CoverSearchScreenMode.SAME_NAME -> sameNameListState
                CoverSearchScreenMode.FOREIGN -> foreignListState
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
                            CoverSearchScreenMode.COVERS -> "Nessuna interpretazione affidabile trovata"
                            CoverSearchScreenMode.SAME_NAME -> "Nessun altro brano con lo stesso nome trovato"
                            CoverSearchScreenMode.FOREIGN -> "Nessuna versione straniera verificabile trovata"
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
                                            onClick = { play(result) },
                                            onLongClick = {
                                                menuState.show {
                                                    YouTubeSongMenu(
                                                        song = result.song,
                                                        navController = navController,
                                                        onDismiss = menuState::dismiss,
                                                    )
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
                        mode == CoverSearchScreenMode.SAME_NAME &&
                        sameNameContinuation != null &&
                        sameNameResults.size < 100
                    ) {
                        TextButton(
                            enabled = !sameNameLoadingMore,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            onClick = {
                                val continuation = sameNameContinuation ?: return@TextButton
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
                                        session.sameNameResults = page.results
                                        session.sameNameContinuation = page.continuation
                                        session.sameNameLastScanPages = page.scannedPages
                                        session.sameNameLastAdded = page.addedCount
                                    } catch (_: Exception) {
                                        sameNameMoreFailed = true
                                    } finally {
                                        sameNameLoadingMore = false
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

                    if (mode == CoverSearchScreenMode.SAME_NAME && sameNameMoreFailed) {
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
