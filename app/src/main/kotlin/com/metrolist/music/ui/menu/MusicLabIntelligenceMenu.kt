package com.metrolist.music.ui.menu

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalNavController
import com.metrolist.music.R
import com.metrolist.music.intelligence.CanonicalMusicMetadata
import com.metrolist.music.intelligence.MusicIntelligenceClient
import com.metrolist.music.intelligence.MusicIntelligenceSettings
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.CoverNavigationBridge
import com.metrolist.music.ui.component.CoverSearchRequest
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.NewAction
import com.metrolist.music.ui.component.NewActionGrid
import com.metrolist.music.ui.component.OriginalVersionNavigationBridge
import com.metrolist.music.ui.component.OriginalVersionRequest
import com.metrolist.music.ui.component.PlayerBottomSheetBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Overlay MusicLab indipendente dai menu upstream Meld.
 *
 * Il playback non passa mai da qui: l'AI risolve identità/crediti/destinazioni solo
 * in background o dopo un'azione esplicita dell'utente. YouTube/YTM restano soltanto
 * provider tecnici dei browse-id dopo che l'AI ha deciso l'identità canonica.
 */
@Composable
internal fun MusicLabIntelligenceActions(
    mediaMetadata: MediaMetadata,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val settings = MusicIntelligenceSettings.from(context)

    if (!settings.enabled) return

    var canonical by remember(mediaMetadata.id) {
        mutableStateOf(MusicIntelligenceClient.cached(mediaMetadata.id))
    }
    var creditsOpen by remember(mediaMetadata.id) { mutableStateOf(false) }
    var creditsLoading by remember(mediaMetadata.id) { mutableStateOf(false) }

    suspend fun resolveCanonical(): CanonicalMusicMetadata? {
        canonical?.let { return it }
        MusicIntelligenceClient.cached(mediaMetadata.id)?.let {
            canonical = it
            return it
        }
        val resolved = withContext(Dispatchers.IO) {
            MusicIntelligenceClient.resolve(
                context = context,
                playbackId = mediaMetadata.id,
                title = mediaMetadata.title,
                artist = mediaMetadata.artists.firstOrNull()?.name.orEmpty(),
                album = mediaMetadata.album?.title,
            )
        }
        if (resolved != null) canonical = resolved
        return resolved
    }

    LaunchedEffect(
        mediaMetadata.id,
        settings.backgroundMetadata,
        settings.credits,
        settings.artistResolver,
        settings.albumResolver,
    ) {
        if (settings.backgroundMetadata && canonical == null) {
            canonical = withContext(Dispatchers.IO) {
                MusicIntelligenceClient.resolve(
                    context = context,
                    playbackId = mediaMetadata.id,
                    title = mediaMetadata.title,
                    artist = mediaMetadata.artists.firstOrNull()?.name.orEmpty(),
                    album = mediaMetadata.album?.title,
                )
            }
        }
    }

    if (creditsOpen) {
        AlertDialog(
            onDismissRequest = { if (!creditsLoading) creditsOpen = false },
            title = { Text("Crediti") },
            text = {
                val data = canonical
                if (creditsLoading) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text("Sto recuperando i crediti canonici…")
                    }
                } else if (data == null) {
                    Text("Crediti non disponibili per questo brano.")
                } else {
                    val rows = buildList {
                        add("Titolo" to data.title)
                        add("Artista" to data.artist)
                        data.album?.takeIf { it.isNotBlank() }?.let { add("Album" to it) }
                        data.year?.let { add("Anno" to it.toString()) }
                        data.category?.takeIf { it.isNotBlank() }?.let { add("Versione" to it) }
                        data.credits.songwriters.takeIf { it.isNotEmpty() }?.let { add("Autori" to it.joinToString()) }
                        data.credits.composers.takeIf { it.isNotEmpty() }?.let { add("Compositori" to it.joinToString()) }
                        data.credits.lyricists.takeIf { it.isNotEmpty() }?.let { add("Parolieri" to it.joinToString()) }
                        data.credits.producers.takeIf { it.isNotEmpty() }?.let { add("Produttori" to it.joinToString()) }
                        data.credits.label?.takeIf { it.isNotBlank() }?.let { add("Etichetta" to it) }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rows.forEach { (label, value) -> Text("$label: $value") }
                        if (rows.size <= 2) {
                            Text(
                                "Gli altri crediti non sono ancora disponibili.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !creditsLoading,
                    onClick = { creditsOpen = false },
                ) { Text("Chiudi") }
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val quickActions = buildList {
            if (settings.originals) {
                add(
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.album),
                                contentDescription = null,
                                modifier = Modifier.size(30.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        text = "Originali",
                        onClick = {
                            // Originali deve aprirsi subito: l'identificazione AI avviene
                            // all'interno della schermata e non deve bloccare la navigazione.
                            val opened = OriginalVersionNavigationBridge.open(
                                OriginalVersionRequest(
                                    title = mediaMetadata.title,
                                    artist = mediaMetadata.artists.firstOrNull()?.name.orEmpty(),
                                    durationSec = mediaMetadata.duration,
                                    currentYouTubeId = mediaMetadata.id,
                                ),
                            )
                            if (opened) {
                                PlayerBottomSheetBridge.collapseSoft()
                                onDismiss()
                            } else {
                                Toast.makeText(context, "Originali non disponibile in questa schermata", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ),
                )
            }
            if (settings.cover) {
                add(
                    NewAction(
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.link),
                                contentDescription = null,
                                modifier = Modifier.size(30.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        text = "Cover",
                        onClick = {
                            // Come Originali, la schermata Cover possiede già il proprio
                            // motore AI: apriamo immediatamente e lasciamo il lavoro al suo pipeline.
                            val opened = CoverNavigationBridge.open(
                                CoverSearchRequest(
                                    title = mediaMetadata.title,
                                    originalArtist = mediaMetadata.artists.firstOrNull()?.name.orEmpty(),
                                    durationSec = mediaMetadata.duration,
                                    currentYouTubeId = mediaMetadata.id,
                                ),
                            )
                            if (opened) {
                                PlayerBottomSheetBridge.collapseSoft()
                                onDismiss()
                            } else {
                                Toast.makeText(context, "Cover non disponibile in questa schermata", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ),
                )
            }
        }

        if (quickActions.isNotEmpty()) {
            NewActionGrid(
                actions = quickActions,
                columns = quickActions.size.coerceAtMost(2),
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        val canonicalItems = buildList {
            if (settings.artistResolver) {
                add(
                    Material3MenuItemData(
                        title = { Text("Vai all'artista") },
                        description = {
                            Text(canonical?.artist ?: "Identificazione AI in background")
                        },
                        icon = { Icon(painterResource(R.drawable.person), contentDescription = null) },
                        onClick = {
                            Toast.makeText(context, "Sto identificando l'artista corretto…", Toast.LENGTH_SHORT).show()
                            scope.launch {
                                val resolved = resolveCanonical()
                                val browseId = resolved?.artistBrowseId
                                if (!browseId.isNullOrBlank()) {
                                    PlayerBottomSheetBridge.collapseSoft()
                                    navController.navigate("artist/$browseId")
                                    onDismiss()
                                } else {
                                    Toast.makeText(context, "Pagina artista canonica non disponibile", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    ),
                )
            }
            if (settings.albumResolver) {
                add(
                    Material3MenuItemData(
                        title = { Text("Vai all'album") },
                        description = {
                            Text(canonical?.album ?: "Album canonico in identificazione")
                        },
                        icon = { Icon(painterResource(R.drawable.album), contentDescription = null) },
                        onClick = {
                            Toast.makeText(context, "Sto identificando l'album corretto…", Toast.LENGTH_SHORT).show()
                            scope.launch {
                                val resolved = resolveCanonical()
                                val browseId = resolved?.albumBrowseId
                                if (!browseId.isNullOrBlank()) {
                                    PlayerBottomSheetBridge.collapseSoft()
                                    navController.navigate("album/$browseId")
                                    onDismiss()
                                } else {
                                    Toast.makeText(context, "Album canonico non disponibile", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    ),
                )
            }
            if (settings.credits) {
                add(
                    Material3MenuItemData(
                        title = { Text("Crediti") },
                        description = { Text("Autori, compositori, parolieri, produttori ed etichetta") },
                        icon = { Icon(painterResource(R.drawable.info), contentDescription = null) },
                        onClick = {
                            creditsOpen = true
                            if (canonical == null) {
                                creditsLoading = true
                                scope.launch {
                                    resolveCanonical()
                                    creditsLoading = false
                                }
                            }
                        },
                    ),
                )
            }
        }

        if (canonicalItems.isNotEmpty()) {
            Material3MenuGroup(items = canonicalItems)
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}
