package com.metrolist.music.ui.component

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.DEFAULT_MUSIC_AI_CLOUD_ENDPOINT
import com.metrolist.music.constants.MusicAiEngineEnabledKey
import com.metrolist.music.constants.MusicAiCloudEndpointKey
import com.metrolist.music.constants.MusicAiCloudMemoryEnabledKey
import com.metrolist.music.constants.MusicAiOriginalsEnabledKey
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.YouTubeMatchOverride
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Reuse the same dedicated Gemini key already configured by Cerca cover.
private val OriginalGeminiApiKey = stringPreferencesKey("coverGeminiApiKey")
private const val ORIGINAL_PAGE_SIZE = 10

private enum class OriginalResultsTab {
    STUDIO,
    LIVE,
    WITH_OTHERS,
    REMIX,
}

@Composable
internal fun OriginalVersionScreen(
    request: OriginalVersionRequest,
    navController: NavHostController,
) {
    val connection = LocalPlayerConnection.current
    val service = connection?.service

    val aiMasterEnabled by rememberPreference(MusicAiEngineEnabledKey, true)
    val originalsAiEnabled by rememberPreference(MusicAiOriginalsEnabledKey, true)
    val cloudMemoryEnabled by rememberPreference(MusicAiCloudMemoryEnabledKey, true)
    val cloudEndpoint by rememberPreference(MusicAiCloudEndpointKey, DEFAULT_MUSIC_AI_CLOUD_ENDPOINT)
    val effectiveCloudEndpoint = cloudEndpoint.trim().ifBlank { DEFAULT_MUSIC_AI_CLOUD_ENDPOINT }

    val aiProvider by rememberPreference(AiProviderKey, "OpenRouter")
    val sharedApiKey by rememberPreference(OpenRouterApiKey, "")
    val sharedModel by rememberPreference(OpenRouterModelKey, "")
    val dedicatedGeminiKey by rememberPreference(OriginalGeminiApiKey, "")

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
        "gemini-3.5-flash-lite"
    }
    val geminiConfig = if (effectiveKey.isNotBlank() || effectiveCloudEndpoint.isNotBlank()) {
        GeminiCoverVerificationConfig(
            apiKey = effectiveKey,
            model = effectiveModel,
            cloudEndpoint = effectiveCloudEndpoint,
            useCloudMemory = cloudMemoryEnabled,
        )
    } else {
        null
    }

    var loading by remember(request.currentYouTubeId) { mutableStateOf(true) }
    var backgroundLoading by remember(request.currentYouTubeId) { mutableStateOf(false) }
    var creditsLoading by remember(request.currentYouTubeId) { mutableStateOf(false) }
    var versionCredits by remember(request.currentYouTubeId) {
        mutableStateOf<Map<String, GeminiVersionCredits>>(emptyMap())
    }
    var searchResult by remember(request.currentYouTubeId) {
        mutableStateOf(OriginalVersionSearchResult(null, emptyList()))
    }
    var failed by remember(request.currentYouTubeId) { mutableStateOf(false) }
    var showDiagnosticsDialog by remember(request.currentYouTubeId) { mutableStateOf(false) }
    var selectedTab by remember(request.currentYouTubeId) { mutableStateOf(OriginalResultsTab.STUDIO) }
    var selectedOriginalCoverageMode by remember(request.currentYouTubeId) { mutableStateOf(AiCoverageMode.PRECISE) }
    var visibleVersionCount by remember(request.currentYouTubeId, selectedTab, selectedOriginalCoverageMode) {
        mutableIntStateOf(ORIGINAL_PAGE_SIZE)
    }
    val listState = rememberLazyListState()

    var link by remember(request.currentYouTubeId) { mutableStateOf("") }
    var manualCandidate by remember(request.currentYouTubeId) { mutableStateOf<SongItem?>(null) }
    var manualYear by remember(request.currentYouTubeId) { mutableStateOf<Int?>(null) }
    var manualLoading by remember(request.currentYouTubeId) { mutableStateOf(false) }
    var manualError by remember(request.currentYouTubeId) { mutableStateOf<String?>(null) }

    var currentOverride by remember(request.currentYouTubeId) { mutableStateOf<YouTubeMatchOverride?>(null) }
    var startingVersion by remember(request.currentYouTubeId) { mutableStateOf<CoverHubResult?>(null) }
    var detailResult by remember(request.currentYouTubeId) { mutableStateOf<CoverHubResult?>(null) }
    var detailLoading by remember(request.currentYouTubeId) { mutableStateOf(false) }
    val brainReviewScope = rememberCoroutineScope()

    // Load the starting version independently: it must never delay the AI or the first results.
    LaunchedEffect(request.currentYouTubeId) {
        currentOverride = service?.getYouTubeMatchOverride(request.currentYouTubeId)
        val startingId = currentOverride?.videoId?.takeIf { it.isNotBlank() } ?: request.currentYouTubeId
        startingVersion = withContext(Dispatchers.IO) {
            val song = YouTube.queue(listOf(startingId)).getOrNull()?.firstOrNull()
            song?.let {
                CoverHubResult(
                    song = it,
                    year = CoverYearResolver.resolve(it),
                    source = "Versione di partenza",
                    confirmed = true,
                )
            }
        }
    }

    // Progressive pipeline: AI -> first useful results -> extended search + AI credits in background.
    LaunchedEffect(
        request.currentYouTubeId,
        request.title,
        request.artist,
        effectiveKey,
        effectiveModel,
        cloudMemoryEnabled,
        cloudEndpoint,
        aiMasterEnabled,
        originalsAiEnabled,
    ) {
        if (!aiMasterEnabled || !originalsAiEnabled) {
            loading = false
            backgroundLoading = false
            creditsLoading = false
            searchResult = OriginalVersionSearchResult(null, emptyList())
            return@LaunchedEffect
        }

        loading = true
        backgroundLoading = false
        creditsLoading = false
        versionCredits = emptyMap()
        failed = false
        selectedTab = OriginalResultsTab.STUDIO
        visibleVersionCount = ORIGINAL_PAGE_SIZE

        val identified = runCatching {
            OriginalVersionSearchEngine.identifyOriginal(
                title = request.title,
                currentArtist = request.artist,
                geminiConfig = geminiConfig,
            )
        }.onFailure { failed = true }
            .getOrDefault(OriginalVersionSearchResult(null, emptyList()))

        searchResult = identified
        val identity = identified.aiIdentity
        if (identity == null) {
            loading = false
            return@LaunchedEffect
        }

        val initial = runCatching {
            OriginalVersionSearchEngine.findInitialVersions(
                identity = identity,
                currentYouTubeId = request.currentYouTubeId,
            )
        }.onFailure { failed = true }
            .getOrElse { identified }

        searchResult = initial
        loading = false
        backgroundLoading = true
        creditsLoading = true

        val expanded = runCatching {
            OriginalVersionSearchEngine.findExpandedVersions(
                identity = identity,
                currentYouTubeId = request.currentYouTubeId,
                seed = initial,
                geminiConfig = geminiConfig,
            )
        }.onFailure { failed = true }
            .getOrNull()

        if (expanded != null) searchResult = expanded
        backgroundLoading = false
        creditsLoading = false
        creditsLoading = false
    }

    LaunchedEffect(detailResult?.song?.id, searchResult.aiIdentity?.title, geminiConfig) {
        val selected = detailResult ?: return@LaunchedEffect
        val identity = searchResult.aiIdentity ?: return@LaunchedEffect
        if (versionCredits[selected.song.id] != null) return@LaunchedEffect
        detailLoading = true
        val loaded = runCatching {
            GeminiOriginalVersionCredits.enrich(identity, listOf(selected.song), geminiConfig)
        }.getOrDefault(emptyMap())
        if (loaded.isNotEmpty()) versionCredits = versionCredits + loaded
        detailLoading = false
    }

    LaunchedEffect(link) {
        val id = extractYouTubeVideoId(link)
        if (id == null) {
            manualCandidate = null
            manualYear = null
            manualError = null
            manualLoading = false
            return@LaunchedEffect
        }
        manualLoading = true
        manualError = null
        manualCandidate = withContext(Dispatchers.IO) {
            YouTube.queue(listOf(id)).getOrNull()?.firstOrNull()
        }
        manualYear = manualCandidate?.let { candidate ->
            withContext(Dispatchers.IO) { CoverYearResolver.resolve(candidate) }
        }
        if (manualCandidate == null) manualError = "Video non trovato"
        manualLoading = false
    }

    fun replaceWith(song: SongItem) {
        service?.setYouTubeMatchOverride(
            request.currentYouTubeId,
            YouTubeMatchOverride(
                videoId = song.id,
                title = song.title,
                artist = song.artists.joinToString(", ") { it.name },
                thumbnail = song.thumbnail,
            ),
        )
        PlayerBottomSheetBridge.collapseToMiniPlayerNow()
        navController.popBackStack()
    }

    fun preview(song: SongItem) {
        connection?.playNext(song.toMediaItem())
        connection?.seekToNext()
        PlayerBottomSheetBridge.collapseToMiniPlayerNow()
    }

    fun updateBrainCandidate(updated: AiCoverCandidate) {
        fun update(result: CoverHubResult): CoverHubResult =
            if (result.brainCandidate?.stableKey == updated.stableKey) result.copy(brainCandidate = updated) else result

        searchResult = searchResult.copy(
            original = searchResult.original?.let(::update),
            versions = searchResult.versions.map(::update),
            liveVersions = searchResult.liveVersions.map(::update),
            withOthersVersions = searchResult.withOthersVersions.map(::update),
            remixVersions = searchResult.remixVersions.map(::update),
        )
    }

    fun saveBrainDecision(result: CoverHubResult, status: AiBrainDecisionStatus) {
        val candidate = result.brainCandidate ?: return
        val identity = searchResult.aiIdentity ?: return
        val sourceArtist = identity.originalArtists.firstOrNull().orEmpty()
        val config = geminiConfig ?: return
        if (sourceArtist.isBlank()) return
        brainReviewScope.launch {
            val saved = CloudMusicDiscovery.saveBrainDecision(
                originalTitle = identity.title,
                originalArtist = sourceArtist,
                candidate = candidate,
                status = status,
                config = config,
            )
            if (saved) updateBrainCandidate(candidate.copy(brainStatus = status))
        }
    }

    fun verifyCandidateBetter(result: CoverHubResult) {
        val candidate = result.brainCandidate ?: return
        val identity = searchResult.aiIdentity ?: return
        val sourceArtist = identity.originalArtists.firstOrNull().orEmpty()
        val config = geminiConfig ?: return
        if (sourceArtist.isBlank()) return
        brainReviewScope.launch {
            CloudMusicDiscovery.verifyCandidate(
                originalTitle = identity.title,
                originalArtist = sourceArtist,
                candidate = candidate,
                mode = "originals",
                config = config,
            )?.let(::updateBrainCandidate)
        }
    }

    val nonStudioIds = (searchResult.liveVersions + searchResult.remixVersions).map { it.song.id }.toSet()
    val studioVersions = searchResult.versions.filter { it.song.id !in nonStudioIds }
    val selectedVersions = when (selectedTab) {
        OriginalResultsTab.STUDIO -> studioVersions
        OriginalResultsTab.LIVE -> searchResult.liveVersions
        OriginalResultsTab.WITH_OTHERS -> searchResult.withOthersVersions
        OriginalResultsTab.REMIX -> searchResult.remixVersions
    }
    val reviewVersions = selectedVersions.filter { it.brainCandidate?.brainStatus == AiBrainDecisionStatus.UNCERTAIN }
    val filteredSelectedVersions = AiCoverageFilter.visibleItems(
        selectedVersions.filterNot { it.brainCandidate?.brainStatus == AiBrainDecisionStatus.UNCERTAIN },
        selectedOriginalCoverageMode,
    ) { it.brainCandidate }
    val shouldLoadNextPage by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 1
        }
    }

    LaunchedEffect(shouldLoadNextPage, selectedTab, selectedOriginalCoverageMode, filteredSelectedVersions.size) {
        if (shouldLoadNextPage && visibleVersionCount < filteredSelectedVersions.size) {
            visibleVersionCount = minOf(visibleVersionCount + ORIGINAL_PAGE_SIZE, filteredSelectedVersions.size)
        }
    }

    if (showDiagnosticsDialog) {
        val diagnostics = searchResult.diagnostics
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("Verifica ricerca Originali") },
            text = {
                Column {
                    Text(
                        "Gemini AI: ${originalAiState(diagnostics.aiStatus)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    diagnostics.aiMode?.let { mode ->
                        Text(
                            text = when (mode) {
                                GeminiOriginalMode.GOOGLE_SEARCH -> "Modalità AI: ricerca Google"
                                GeminiOriginalMode.MODEL_KNOWLEDGE -> "Modalità AI: conoscenza diretta del modello"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (diagnostics.aiTitle.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Titolo deciso dall'AI: ${diagnostics.aiTitle}")
                        Text(
                            "Cantante originale: ${diagnostics.aiArtists.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            diagnostics.aiYear?.let { "Anno originale AI: $it" } ?: "Anno originale AI: non indicato",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (diagnostics.backgroundComplete) {
                            "Ricerca estesa: completata"
                        } else if (backgroundLoading) {
                            "Ricerca estesa: in background"
                        } else if (diagnostics.initialVisible > 0) {
                            "Ricerca estesa: in background"
                        } else {
                            "Ricerca estesa: pronta"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (diagnostics.initialVisible > 0) {
                        Text(
                            "Primi risultati mostrati subito: ${diagnostics.initialVisible}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "YouTube Music: ${originalStageState(diagnostics.youtubeMusicStatus)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${diagnostics.youtubeMusicFound} riproduzioni trovate · ${diagnostics.youtubeMusicPages} pagine",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "YouTube: ${originalStageState(diagnostics.youtubeStatus)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${diagnostics.youtubeFound} riproduzioni trovate · ${diagnostics.youtubePages} pagine",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Versioni: ${diagnostics.finalVersions} · Live: ${diagnostics.liveFound} · Con altri: ${diagnostics.withOthersFound} · Remix: ${diagnostics.remixFound}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Spotify assist: ${diagnostics.spotifyHintsFound} suggerimenti",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Dettagli AI caricati su richiesta: ${versionCredits.size}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnosticsDialog = false }) { Text("Chiudi") }
            },
        )
    }

    detailResult?.let { selected ->
        OriginalDetailDialog(
            result = selected,
            credits = versionCredits[selected.song.id],
            loading = detailLoading,
            onDismiss = { detailResult = null },
            onReplace = {
                replaceWith(selected.song)
                detailResult = null
            },
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Originali",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showDiagnosticsDialog = true }) { Text("ⓘ") }
                TextButton(onClick = { navController.popBackStack() }) { Text("Chiudi") }
            }

            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Incolla link YouTube") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
            ) {
                if (manualLoading) {
                    item {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Verifico il link…")
                        }
                    }
                }

                manualError?.let { error ->
                    item {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }

                manualCandidate?.let { candidate ->
                    item {
                        VersionSectionTitle("Versione dal link")
                        OriginalVersionRow(
                            result = CoverHubResult(song = candidate, year = manualYear, source = "Link manuale"),
                            credits = versionCredits[candidate.id],
                            creditsLoading = creditsLoading,
                            onPreview = { preview(candidate) },
                            onReplace = { replaceWith(candidate) },
                            onDetails = { detailResult = CoverHubResult(song = candidate, year = manualYear, source = "Link manuale") },
                            onTitleSearch = { navController.navigate(SearchRoutes.resultRoute(candidate.title)) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
                    }
                }

                searchResult.aiIdentity?.let { identity ->
                    item {
                        VersionSectionTitle("Identificato dall'AI")
                        Text(
                            text = "${identity.title} · ${identity.originalArtists.joinToString(", ")}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = identity.year?.let { "Prima pubblicazione: $it" } ?: "Prima pubblicazione: data non disponibile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        identity.album?.let { Text("Album / pubblicazione: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(
                            text = "L'AI identifica originale, anno e pubblicazione. I crediti completi vengono caricati solo aprendo Dettagli; YouTube Music e YouTube servono a localizzare la riproduzione.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp, bottom = 12.dp),
                        )
                    }
                }

                if (loading) {
                    item {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (searchResult.aiIdentity == null) {
                                    "L'AI identifica l'originale…"
                                } else {
                                    "Cerco i primi risultati…"
                                },
                            )
                        }
                    }
                } else {
                    val original = searchResult.original
                    if (original != null) {
                        item {
                            VersionSectionTitle("Originale secondo AI")
                            OriginalVersionRow(
                                result = original,
                                credits = versionCredits[original.song.id]
                                    ?: searchResult.aiIdentity?.toVersionCredits(),
                                creditsLoading = creditsLoading,
                                onPreview = { preview(original.song) },
                                onReplace = { replaceWith(original.song) },
                                onDetails = { detailResult = original },
                                onTitleSearch = { navController.navigate(SearchRoutes.resultRoute(original.song.title)) },
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                        }
                    }

                    if (searchResult.versions.isNotEmpty()) {
                        item {
                            VersionSectionTitle("Altre versioni dello stesso cantante")
                            OriginalResultsTabs(
                                selected = selectedTab,
                                versionsCount = studioVersions.size,
                                liveCount = searchResult.liveVersions.size,
                                withOthersCount = searchResult.withOthersVersions.size,
                                remixCount = searchResult.remixVersions.size,
                                onSelected = { selectedTab = it },
                            )
                            Spacer(Modifier.height(8.dp))
                            AiCoverageSelector(
                                selected = selectedOriginalCoverageMode,
                                onSelected = { selectedOriginalCoverageMode = it },
                            )
                            if (backgroundLoading) {
                                Row(
                                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Altri risultati stanno arrivando in background…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                Text(
                                    text = "Sempre e solo versioni in cui compare il cantante originale scelto dall'AI.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 7.dp, bottom = 6.dp),
                                )
                            }
                        }

                        val page = filteredSelectedVersions.take(visibleVersionCount)
                        items(
                            items = page,
                            key = { "${selectedTab.name}-${it.song.id}" },
                        ) { result ->
                            OriginalVersionRow(
                                result = result,
                                credits = versionCredits[result.song.id],
                                creditsLoading = creditsLoading,
                                onPreview = { preview(result.song) },
                                onReplace = { replaceWith(result.song) },
                                onDetails = { detailResult = result },
                                onTitleSearch = { navController.navigate(SearchRoutes.resultRoute(result.song.title)) },
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        if (reviewVersions.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(14.dp))
                                VersionSectionTitle("Da verificare")
                                Text(
                                    "Versioni che il Brain conserva per una decisione esplicita.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                            items(
                                items = reviewVersions,
                                key = { "review-${selectedTab.name}-${it.song.id}" },
                            ) { result ->
                                OriginalVersionRow(
                                    result = result,
                                    credits = versionCredits[result.song.id],
                                    creditsLoading = creditsLoading,
                                    onPreview = { preview(result.song) },
                                    onReplace = { replaceWith(result.song) },
                                    onDetails = { detailResult = result },
                                    onTitleSearch = { navController.navigate(SearchRoutes.resultRoute(result.song.title)) },
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    TextButton(onClick = { preview(result.song) }) { Text("Ascolta") }
                                    TextButton(onClick = { saveBrainDecision(result, AiBrainDecisionStatus.APPROVED) }) { Text("Conferma") }
                                    TextButton(onClick = { saveBrainDecision(result, AiBrainDecisionStatus.REJECTED) }) { Text("Rifiuta") }
                                    TextButton(onClick = { verifyCandidateBetter(result) }) { Text("Verifica meglio") }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    } else if (backgroundLoading) {
                        item {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Cerco altre versioni in background…")
                            }
                        }
                    } else if (searchResult.aiIdentity != null && searchResult.original == null) {
                        item {
                            Text(
                                text = "L'AI ha identificato titolo e cantante originale, ma YouTube/YouTube Music non hanno restituito una versione riproducibile che riporti quel cantante tra gli interpreti.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (searchResult.aiIdentity != null) {
                        item {
                            Text(
                                text = "L'AI ha identificato l'originale, ma non ho trovato altre versioni riproducibili con lo stesso cantante.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (searchResult.diagnostics.aiStatus == OriginalAiStatus.NOT_CONFIGURED) {
                        item {
                            Text(
                                "Gemini AI non è configurata. Originali funziona esclusivamente con l'intelligenza artificiale e non usa motori alternativi.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (failed || searchResult.diagnostics.aiStatus == OriginalAiStatus.ERROR) {
                        item {
                            Text("La ricerca AI non è riuscita. Apri ⓘ per il feedback oppure incolla un link YouTube.")
                        }
                    } else {
                        item {
                            Text(
                                "L'AI non ha restituito un'identificazione utilizzabile. Apri ⓘ per il feedback oppure incolla un link YouTube.",
                            )
                        }
                    }
                }

                val shownIds = buildSet {
                    searchResult.original?.song?.id?.let(::add)
                    searchResult.versions.forEach { add(it.song.id) }
                    manualCandidate?.id?.let(::add)
                }
                startingVersion?.takeIf { it.song.id !in shownIds }?.let { starting ->
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                        VersionSectionTitle("Versione di partenza")
                        Text(
                            text = "La versione con cui hai aperto questa ricerca.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        OriginalVersionRow(
                            result = starting,
                            credits = versionCredits[starting.song.id],
                            creditsLoading = creditsLoading,
                            onPreview = { preview(starting.song) },
                            onReplace = { replaceWith(starting.song) },
                            onDetails = { detailResult = starting },
                            onTitleSearch = { navController.navigate(SearchRoutes.resultRoute(starting.song.title)) },
                        )
                    }
                }

                if (currentOverride != null) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                service?.setYouTubeMatchOverride(request.currentYouTubeId, null)
                                navController.popBackStack()
                            },
                        ) {
                            Text("Ripristina versione automatica")
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun OriginalResultsTabs(
    selected: OriginalResultsTab,
    versionsCount: Int,
    liveCount: Int,
    withOthersCount: Int,
    remixCount: Int,
    onSelected: (OriginalResultsTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OriginalResultChip(
            label = "Studio",
            count = versionsCount,
            selected = selected == OriginalResultsTab.STUDIO,
            onClick = { onSelected(OriginalResultsTab.STUDIO) },
        )
        if (liveCount > 0) {
            OriginalResultChip(
                label = "Live",
                count = liveCount,
                selected = selected == OriginalResultsTab.LIVE,
                onClick = { onSelected(OriginalResultsTab.LIVE) },
            )
        }
        if (withOthersCount > 0) {
            OriginalResultChip(
                label = "Con altri",
                count = withOthersCount,
                selected = selected == OriginalResultsTab.WITH_OTHERS,
                onClick = { onSelected(OriginalResultsTab.WITH_OTHERS) },
            )
        }
        if (remixCount > 0) {
            OriginalResultChip(
                label = "Remix",
                count = remixCount,
                selected = selected == OriginalResultsTab.REMIX,
                onClick = { onSelected(OriginalResultsTab.REMIX) },
            )
        }
    }
}

@Composable
private fun OriginalResultChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = "$label · $count",
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun AiCreditsBlock(identity: GeminiOriginalIdentity) {
    val rows = buildList {
        if (identity.songwriters.isNotEmpty()) add("Autori" to identity.songwriters.joinToString(", "))
        if (identity.composers.isNotEmpty()) add("Compositori" to identity.composers.joinToString(", "))
        if (identity.lyricists.isNotEmpty()) add("Parolieri" to identity.lyricists.joinToString(", "))
        if (identity.producers.isNotEmpty()) add("Produttori" to identity.producers.joinToString(", "))
        identity.label?.let { add("Etichetta" to it) }
        identity.album?.let { add("Album / pubblicazione" to it) }
    }

    if (rows.isEmpty()) {
        Text(
            "Crediti AI: non disponibili",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Spacer(Modifier.height(5.dp))
    rows.forEach { (label, value) ->
        Text(
            "$label: $value",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun GeminiOriginalIdentity.toVersionCredits() = GeminiVersionCredits(
    year = year,
    album = album,
    songwriters = songwriters,
    composers = composers,
    lyricists = lyricists,
    producers = producers,
    label = label,
)

@Composable
private fun VersionSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun OriginalVersionRow(
    result: CoverHubResult,
    credits: GeminiVersionCredits?,
    creditsLoading: Boolean,
    onPreview: () -> Unit,
    onReplace: () -> Unit,
    onDetails: () -> Unit,
    onTitleSearch: () -> Unit,
) {
    val displayYear = credits?.year ?: result.year
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDetails).padding(vertical = 6.dp),
    ) {
        AsyncImage(
            model = result.song.thumbnail,
            contentDescription = "Riproduci",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPreview),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                result.song.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onTitleSearch),
            )
            Text(
                result.song.artists.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = displayYear?.let { "Data: $it" } ?: "Data non disponibile",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            credits?.album?.let { album -> Text("Album: $album", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (result.source.isNotBlank()) Text("Riproduzione: ${result.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(
            onClick = onDetails,
            modifier = Modifier.padding(start = 8.dp).height(36.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        ) { Text("Dettagli", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun OriginalDetailDialog(
    result: CoverHubResult,
    credits: GeminiVersionCredits?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onReplace: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(result.song.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(result.song.artists.joinToString(", ") { it.name }, style = MaterialTheme.typography.titleSmall)
                Text((credits?.year ?: result.year)?.let { "Data: $it" } ?: "Data: non disponibile")
                credits?.album?.let { Text("Album: $it") }
                if (result.source.isNotBlank()) Text("Riproduzione: ${result.source}", style = MaterialTheme.typography.bodySmall)
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Recupero informazioni complete…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                credits?.let { VersionCreditsBlock(it) }
            }
        },
        confirmButton = { TextButton(onClick = onReplace) { Text("Sostituisci") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
}

@Composable
private fun VersionCreditsBlock(credits: GeminiVersionCredits?) {
    if (credits == null) return
    val rows = buildList {
        if (credits.songwriters.isNotEmpty()) add("Autori" to credits.songwriters.joinToString(", "))
        if (credits.composers.isNotEmpty()) add("Compositori" to credits.composers.joinToString(", "))
        if (credits.lyricists.isNotEmpty()) add("Parolieri" to credits.lyricists.joinToString(", "))
        if (credits.producers.isNotEmpty()) add("Produttori" to credits.producers.joinToString(", "))
        credits.label?.let { add("Etichetta" to it) }
    }
    rows.forEach { (label, value) ->
        Text(
            text = "$label: $value",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun originalAiState(status: OriginalAiStatus): String = when (status) {
    OriginalAiStatus.OK -> "ok · decisione ricevuta"
    OriginalAiStatus.NOT_CONFIGURED -> "non configurata"
    OriginalAiStatus.NO_ANSWER -> "nessuna identificazione utile"
    OriginalAiStatus.ERROR -> "errore"
}

private fun originalStageState(status: OriginalSearchStageStatus): String = when (status) {
    OriginalSearchStageStatus.OK -> "ok"
    OriginalSearchStageStatus.NO_RESULTS -> "nessun risultato"
    OriginalSearchStageStatus.ERROR -> "errore"
    OriginalSearchStageStatus.NOT_RUN -> "non eseguita"
}

private fun extractYouTubeVideoId(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return trimmed
    return runCatching {
        val uri = Uri.parse(trimmed)
        when {
            uri.host == "youtu.be" -> uri.pathSegments.firstOrNull()?.takeIf { it.length == 11 }
            uri.host?.contains("youtube.com") == true && uri.path == "/watch" -> uri.getQueryParameter("v")?.takeIf { it.length == 11 }
            else -> null
        }
    }.getOrNull()
}
