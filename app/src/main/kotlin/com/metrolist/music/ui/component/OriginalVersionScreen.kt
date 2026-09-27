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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.YouTubeMatchOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun OriginalVersionScreen(
    request: OriginalVersionRequest,
    navController: NavHostController,
) {
    val connection = LocalPlayerConnection.current
    val service = connection?.service
    var loading by remember(request.currentYouTubeId) { mutableStateOf(true) }
    var autoCandidate by remember(request.currentYouTubeId) { mutableStateOf<CoverHubResult?>(null) }
    var failed by remember(request.currentYouTubeId) { mutableStateOf(false) }
    var link by remember(request.currentYouTubeId) { mutableStateOf("") }
    var manualCandidate by remember(request.currentYouTubeId) { mutableStateOf<SongItem?>(null) }
    var manualLoading by remember(request.currentYouTubeId) { mutableStateOf(false) }
    var manualError by remember(request.currentYouTubeId) { mutableStateOf<String?>(null) }
    var currentOverride by remember(request.currentYouTubeId) { mutableStateOf<YouTubeMatchOverride?>(null) }

    LaunchedEffect(request.currentYouTubeId) {
        currentOverride = service?.getYouTubeMatchOverride(request.currentYouTubeId)
        loading = true
        failed = false
        autoCandidate = runCatching {
            OriginalVersionSearchEngine.findOldest(
                title = request.title,
                currentArtist = request.artist,
                durationSec = request.durationSec,
                currentYouTubeId = request.currentYouTubeId,
            )
        }.onFailure { failed = true }.getOrNull()
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

    val selectedSong = manualCandidate ?: autoCandidate?.song
    val selectedYear = if (manualCandidate == null) autoCandidate?.year else null

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
                Text("Originali", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { navController.popBackStack() }) { Text("Chiudi") }
            }

            Text(
                text = "Cerco la versione con la data di pubblicazione più antica disponibile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Incolla link YouTube") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))

            when {
                manualLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                manualError != null -> Text(manualError!!, color = MaterialTheme.colorScheme.error)
                loading && manualCandidate == null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Cerco la prima incisione…")
                    }
                }
                selectedSong != null -> {
                    Text(
                        text = if (manualCandidate != null) "Versione scelta dal link" else "Versione più antica trovata",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                connection?.playNext(selectedSong.toMediaItem())
                                connection?.seekToNext()
                                PlayerBottomSheetBridge.expandSoft()
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        AsyncImage(
                            model = selectedSong.thumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(selectedSong.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                selectedSong.artists.joinToString(", ") { it.name },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            selectedYear?.let {
                                Text("Anno: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Text("Tocca la locandina per ascoltare", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        enabled = selectedSong.id != request.currentYouTubeId,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            service?.setYouTubeMatchOverride(
                                request.currentYouTubeId,
                                YouTubeMatchOverride(
                                    videoId = selectedSong.id,
                                    title = selectedSong.title,
                                    artist = selectedSong.artists.joinToString(", ") { it.name },
                                    thumbnail = selectedSong.thumbnail,
                                ),
                            )
                            navController.popBackStack()
                        },
                    ) { Text("Sostituisci") }
                }
                failed -> Text("Ricerca non riuscita. Puoi comunque incollare un link YouTube.")
                else -> Text("Non ho trovato una versione con una data verificabile. Puoi incollare un link YouTube.")
            }

            if (currentOverride != null) {
                Spacer(Modifier.height(12.dp))
                TextButton(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    onClick = {
                        service?.setYouTubeMatchOverride(request.currentYouTubeId, null)
                        navController.popBackStack()
                    },
                ) { Text("Ripristina versione automatica") }
            }
        }
    }
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
