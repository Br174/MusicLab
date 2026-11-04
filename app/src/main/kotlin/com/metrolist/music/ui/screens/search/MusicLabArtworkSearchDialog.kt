package com.metrolist.music.ui.screens.search

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Custom MusicLab sections only. Invoked by a long press on the entire result row.
 * The ordinary tap remains owned by the existing playback handler.
 */
@Composable
internal fun MusicLabArtworkSearchDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSearch: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copertina del brano") },
        text = { Text("Vuoi cercare di nuovo una copertina discografica? La riproduzione non sarà interrotta.") },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onSearch() }) {
                Text("Cerca copertina")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
