package com.metrolist.music.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.metrolist.music.constants.DEFAULT_MUSIC_AI_CLOUD_ENDPOINT
import com.metrolist.music.constants.MusicAiCloudEndpointKey
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.delay

internal object MusicLabArchiveNavigationBridge {
    const val ROUTE = "musiclab_archive"
}

@Composable
internal fun MusicLabArchiveScreen(
    navController: NavHostController,
) {
    val cloudEndpoint by rememberPreference(MusicAiCloudEndpointKey, DEFAULT_MUSIC_AI_CLOUD_ENDPOINT)
    val endpoint = cloudEndpoint.trim().ifBlank { DEFAULT_MUSIC_AI_CLOUD_ENDPOINT }
    val config =
        remember(endpoint) {
            GeminiCoverVerificationConfig(
                apiKey = "",
                model = "gemini-3.5-flash-lite",
                cloudEndpoint = endpoint,
                useCloudMemory = true,
            )
        }

    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<CloudArchiveItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, endpoint) {
        delay(if (query.isBlank()) 0 else 280)
        loading = true
        error = null
        items =
            runCatching {
                CloudMusicDiscovery.searchArchive(
                    query = query,
                    config = config,
                    limit = 100,
                )
            }.onFailure {
                error = "Archivio cloud non raggiungibile."
            }.getOrDefault(emptyList())
        loading = false
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Archivio MusicLab",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { navController.popBackStack() }) {
                    Text("Chiudi")
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Cerca titolo, artista, originale…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                }
            }

            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (!loading && items.isEmpty() && error == null) {
                Text(
                    text = if (query.isBlank()) {
                        "L'archivio approvato è ancora vuoto."
                    } else {
                        "Nessun risultato approvato per questa ricerca."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = items,
                    key = { item ->
                        listOf(item.title, item.artist, item.category, item.year ?: 0).joinToString("|")
                    },
                ) { item ->
                    Surface(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    navController.navigate(SearchRoutes.resultRoute(item.title))
                                },
                        tonalElevation = 1.dp,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = item.artist,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text =
                                    listOfNotNull(
                                        item.category.takeIf(String::isNotBlank),
                                        item.language,
                                        item.year?.toString(),
                                        item.album,
                                    ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (item.workTitle.isNotBlank()) {
                                Text(
                                    text = "Opera: ${item.workTitle}" +
                                        item.originalArtist.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
