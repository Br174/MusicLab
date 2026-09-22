/**
 * MusicLab cover search entry point
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import androidx.compose.runtime.Composable
import com.metrolist.innertube.models.SongItem

/**
 * Stable public entry point used by the player menu. The implementation lives
 * in EnhancedCoverSearchDialog so cover discovery can evolve without touching
 * the player/menu integration.
 */
@Composable
fun CoverSearchDialog(
    title: String,
    originalArtist: String,
    durationSec: Int,
    currentYouTubeId: String?,
    onSelect: (SongItem) -> Unit,
    onDismiss: () -> Unit,
) {
    EnhancedCoverSearchDialog(
        title = title,
        originalArtist = originalArtist,
        durationSec = durationSec,
        currentYouTubeId = currentYouTubeId,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}
