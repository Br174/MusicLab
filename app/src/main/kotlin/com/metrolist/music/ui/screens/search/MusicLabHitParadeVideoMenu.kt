package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.YouTubeMatchDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Per-row ⋮ control. Isolated from the row's tap/playback handler. */
@Composable
internal fun MusicLabHitParadeVideoMenu(title: String, artist: String) {
    val context = LocalContext.current
    var expanded by remember(title, artist) { mutableStateOf(false) }
    var showSearch by remember(title, artist) { mutableStateOf(false) }
    var showPaste by remember(title, artist) { mutableStateOf(false) }
    var saved by remember(title, artist) {
        mutableStateOf(MusicLabHitParadeVideoMemory.get(context, title, artist))
    }

    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(44.dp)) {
            Text("⋮", fontSize = 26.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Cerca video corrispondente") },
                onClick = { expanded = false; showSearch = true },
            )
            DropdownMenuItem(
                text = { Text("Inserisci link manualmente") },
                onClick = { expanded = false; showPaste = true },
            )
            if (saved != null) {
                DropdownMenuItem(
                    text = { Text("Rimuovi collegamento salvato") },
                    onClick = {
                        expanded = false
                        MusicLabHitParadeVideoResolver.removeSelection(context, title, artist)
                        saved = null
                    },
                )
            }
        }
    }

    if (showSearch) {
        MusicLabHitParadeVideoSearchDialog(
            title = title, artist = artist,
            onDismiss = { showSearch = false },
            onSelected = { video ->
                MusicLabHitParadeVideoResolver.userSelected(
                    context, title, artist, video.id, video.title,
                    video.artists.joinToString(", ") { it.name }, video.thumbnail)
                saved = MusicLabHitParadeVideoMemory.get(context, title, artist)
                showSearch = false
            },
        )
    }
    if (showPaste) {
        // Reuse the app's verified link parser, YouTube metadata preview
        // and check that a pasted URL actually maps to a playable video.
        YouTubeMatchDialog(
            currentYouTubeId = saved?.videoId,
            onConfirm = { video ->
                MusicLabHitParadeVideoResolver.userSelected(
                    context, title, artist,
                    video.videoId, video.title, video.artist, video.thumbnail)
                saved = MusicLabHitParadeVideoMemory.get(context, title, artist)
            },
            onDismiss = { showPaste = false },
        )
    }
}

/** User-reviewed search, not an auto-admission rule: user explicitly selects a recording. */
@Composable
private fun MusicLabHitParadeVideoSearchDialog(
    title: String,
    artist: String,
    onDismiss: () -> Unit,
    onSelected: (SongItem) -> Unit,
) {
    var query by remember(title, artist) { mutableStateOf(artist + " " + title) }
    var searchNumber by remember(title, artist) { mutableIntStateOf(0) }
    var loading by remember(title, artist) { mutableStateOf(true) }
    var error by remember(title, artist) { mutableStateOf<String?>(null) }
    var results by remember(title, artist) { mutableStateOf<List<SongItem>>(emptyList()) }

    LaunchedEffect(searchNumber) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            results = emptyList()
            error = "Inserisci titolo o cantante"
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            results = withContext(Dispatchers.IO) {
                withTimeoutOrNull(12_000L) {
                    val songs = YouTube.search(needle, YouTube.SearchFilter.FILTER_SONG)
                        .getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                    val videos = YouTube.search(needle, YouTube.SearchFilter.FILTER_VIDEO)
                        .getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                    (songs + videos).distinctBy { it.id }.take(20)
                }.orEmpty()
            }
            if (results.isEmpty()) error = "Nessun video trovato. Puoi inserire un link manualmente."
        } catch (_: Exception) {
            results = emptyList()
            error = "Ricerca non disponibile. Riprova o usa il collegamento manuale."
        } finally {
            loading = false
        }
    }

    DefaultDialog(
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        title = { Text("Cerca video corrispondente") },
        buttons = {
            TextButton(onClick = onDismiss) { Text("Chiudi") }
        },
    ) {
        Text(title + " · " + artist, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Titolo e artista") },
            )
            TextButton(onClick = { searchNumber++ }) { Text("Cerca") }
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(22.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall) }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(results, key = { it.id }) { song ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onSelected(song) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AsyncImage(model = song.thumbnail, contentDescription = null,
                        modifier = Modifier.size(48.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(song.title, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(song.artists.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
