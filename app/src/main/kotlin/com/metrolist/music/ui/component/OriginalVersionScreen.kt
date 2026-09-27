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
    var searchResult by remember(request.currentYouTubeId) {
        mutableStateOf(OriginalVersionSearchResult(null, emptyList()))
    }
    var failed by remember(request.currentYouTubeId) { mutableStateOf(false) }

    var link by remember(request.currentYouTubeId) { mutableStateOf("") }
    var manualCandidate by remember(request.currentYouTubeId) { mutableStateOf<SongItem?>(null) }
    var manualLoading by remember(request.currentYouTubeId) { mutableStateOf(false) }
    var manualError by remember(request.currentYouTubeId) { mutableStateOf<String?>(null) }

    var currentOverride by remember(request.currentYouTubeId) { mutableStateOf<YouTubeMatchOverride?>(null) }
    var startingVersion by remember(request.currentYouTubeId) { mutableStateOf<CoverHubResult?>(null) }

    LaunchedEffect(request.currentYouTubeId) {
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

            // Manual YouTube address stays above every search result.
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
                            Text("Cerco il titolo esatto e la prima incisione…")
                        }
                    }
                } else {
                    val original = searchResult.original
                    if (original != null) {
                        item {
                            VersionSectionTitle("Originale")
                            OriginalVersionRow(
                                result = original,
                                onPreview = { preview(original.song) },
                                onReplace = { replaceWith(original.song) },
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                        }

                        if (searchResult.versions.isNotEmpty()) {
                            item {
                                VersionSectionTitle("Altre versioni")
                                Text(
                                    text = "Titolo esatto · dalla più vecchia alla più recente",
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
                                    text = "Non ho trovato altre versioni con lo stesso titolo e con l'artista originale presente.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else if (failed) {
                        item {
                            Text("Ricerca non riuscita. Puoi comunque incollare un link YouTube.")
                        }
                    } else {
                        item {
                            Text(
                                "Non ho trovato una versione con titolo esatto e una data verificabile. Puoi comunque incollare un link YouTube.",
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
