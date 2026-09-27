package com.metrolist.music.ui.component

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.YouTubeMatchOverride
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Reuse the same dedicated Gemini key already configured by Cerca cover.
private val OriginalGeminiApiKey = stringPreferencesKey("coverGeminiApiKey")

@Composable
internal fun OriginalVersionScreen(
    request: OriginalVersionRequest,
    navController: NavHostController,
) {
    val connection = LocalPlayerConnection.current
    val service = connection?.service

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
        "gemini-2.5-flash-lite"
    }
    val geminiConfig = effectiveKey.takeIf { it.isNotBlank() }?.let {
        GeminiCoverVerificationConfig(apiKey = it, model = effectiveModel)
    }

    var loading by remember(request.currentYouTubeId) { mutableStateOf(true) }
    var searchResult by remember(request.currentYouTubeId) {
        mutableStateOf(OriginalVersionSearchResult(null, emptyList()))
    }
    var failed by remember(request.currentYouTubeId) { mutableStateOf(false) }
    var showDiagnosticsDialog by remember(request.currentYouTubeId) { mutableStateOf(false) }

    var link by remember(request.currentYouTubeId) { mutableStateOf("") }
    var manualCandidate by remember(request.currentYouTubeId) { mutableStateOf<SongItem?>(null) }
    var manualLoading by remember(request.currentYouTubeId) { mutableStateOf(false) }
    var manualError by remember(request.currentYouTubeId) { mutableStateOf<String?>(null) }

    var currentOverride by remember(request.currentYouTubeId) { mutableStateOf<YouTubeMatchOverride?>(null) }
    var startingVersion by remember(request.currentYouTubeId) { mutableStateOf<CoverHubResult?>(null) }

    LaunchedEffect(
        request.currentYouTubeId,
        request.title,
        request.artist,
        effectiveKey,
        effectiveModel,
    ) {
        currentOverride = service?.getYouTubeMatchOverride(request.currentYouTubeId)
        val startingId = currentOverride?.videoId?.takeIf { it.isNotBlank() } ?: request.currentYouTubeId
        startingVersion = withContext(Dispatchers.IO) {
            val song = YouTube.queue(listOf(startingId)).getOrNull()?.firstOrNull()
            song?.let {
                CoverHubResult(
                    song = it,
                    year = runCatching { CoverYearResolver.resolve(it) }.getOrNull(),
                    source = "Versione di partenza",
                    confirmed = true,
                )
            }
        }

        loading = true
        failed = false
        searchResult = runCatching {
            OriginalVersionSearchEngine.findVersions(
                title = request.title,
                currentArtist = request.artist,
                durationSec = request.durationSec,
                currentYouTubeId = request.currentYouTubeId,
                geminiConfig = geminiConfig,
            )
        }.onFailure { failed = true }
            .getOrDefault(OriginalVersionSearchResult(null, emptyList()))
        loading = false
    }

    LaunchedEffect(link) {
        val id = extractYouTubeVideoId(link)
        if (id == null) {
            manualCandidate = null
            manualError = null
            manualLoading = false
            return@LaunchedEffect
        }
        manualLoading = true
        manualError = null
        manualCandidate = withContext(Dispatchers.IO) {
            YouTube.queue(listOf(id)).getOrNull()?.firstOrNull()
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
        navController.popBackStack()
    }

    fun preview(song: SongItem) {
        connection?.playNext(song.toMediaItem())
        connection?.seekToNext()
        PlayerBottomSheetBridge.expandSoft()
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
                                GeminiOriginalMode.MODEL_KNOWLEDGE -> "Modalità AI: conoscenza del modello"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (diagnostics.aiWebSources > 0) {
                        Text(
                            "Fonti web viste dall'AI: ${diagnostics.aiWebSources}",
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
                        "YouTube Music: ${originalStageState(diagnostics.youtubeMusicStatus)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${diagnostics.youtubeMusicFound} risultati validi · ${diagnostics.youtubeMusicPages} pagine",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "YouTube: ${originalStageState(diagnostics.youtubeStatus)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${diagnostics.youtubeFound} risultati validi · ${diagnostics.youtubePages} pagine",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Versioni finali mostrate: ${diagnostics.finalVersions}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnosticsDialog = false }) { Text("Chiudi") }
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
                            result = CoverHubResult(song = candidate, source = "Link manuale"),
                            onPreview = { preview(candidate) },
                            onReplace = { replaceWith(candidate) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
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
                            Text("L'AI identifica l'originale e cerco tutte le sue versioni…")
                        }
                    }
                } else {
                    searchResult.aiIdentity?.let { identity ->
                        item {
                            VersionSectionTitle("Identificato dall'AI")
                            Text(
                                text = "${identity.title} · ${identity.originalArtists.joinToString(", ")}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = identity.year?.let { "Prima pubblicazione: $it" } ?: "Prima pubblicazione: anno non indicato",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            AiCreditsBlock(identity)
                            Text(
                                text = "L'AI decide l'originale; YouTube e YouTube Music servono solo a trovare le versioni riproducibili in cui compare questo interprete.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 5.dp, bottom = 12.dp),
                            )
                        }
                    }

                    val original = searchResult.original
                    if (original != null) {
                        item {
                            VersionSectionTitle("Originale secondo AI")
                            OriginalVersionRow(
                                result = original,
                                onPreview = { preview(original.song) },
                                onReplace = { replaceWith(original.song) },
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                        }

                        if (searchResult.versions.isNotEmpty()) {
                            item {
                                VersionSectionTitle("Altre versioni dello stesso cantante")
                                Text(
                                    text = "Sono incluse live, duetti, collaborazioni e altre versioni della stessa canzone, purché sia presente il cantante originale indicato dall'AI.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                            items(
                                items = searchResult.versions,
                                key = { it.song.id },
                            ) { version ->
                                OriginalVersionRow(
                                    result = version,
                                    onPreview = { preview(version.song) },
                                    onReplace = { replaceWith(version.song) },
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        } else {
                            item {
                                Text(
                                    text = "L'AI ha identificato l'originale, ma non ho trovato altre versioni riproducibili con lo stesso cantante.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else if (searchResult.aiIdentity != null) {
                        item {
                            Text(
                                text = "L'AI ha identificato titolo e cantante originale, ma YouTube/YouTube Music non hanno restituito una versione riproducibile che riporti quel cantante tra gli interpreti.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (searchResult.diagnostics.aiStatus == OriginalAiStatus.NOT_CONFIGURED) {
                        item {
                            Text(
                                "Gemini AI non è configurata. Originali ora funziona esclusivamente con l'intelligenza artificiale e non usa motori alternativi.",
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
                            onPreview = { preview(starting.song) },
                            onReplace = { replaceWith(starting.song) },
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
private fun AiCreditsBlock(identity: GeminiOriginalIdentity) {
    val rows = buildList {
        if (identity.songwriters.isNotEmpty()) add("Autori" to identity.songwriters.joinToString(", "))
        if (identity.composers.isNotEmpty()) add("Compositori" to identity.composers.joinToString(", "))
        if (identity.lyricists.isNotEmpty()) add("Parolieri" to identity.lyricists.joinToString(", "))
        if (identity.producers.isNotEmpty()) add("Produttori" to identity.producers.joinToString(", "))
        identity.label?.let { add("Etichetta" to it) }
        identity.album?.let { add("Pubblicazione" to it) }
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
    onPreview: () -> Unit,
    onReplace: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPreview)
                .padding(vertical = 6.dp),
        ) {
            AsyncImage(
                model = result.song.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.song.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    result.song.artists.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = result.year?.let { "Anno: $it" } ?: "Anno non disponibile",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (result.source.isNotBlank()) {
                    Text(
                        text = "Fonte: ${result.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "Tocca la locandina per ascoltare",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onReplace,
        ) {
            Text("Sostituisci")
        }
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
