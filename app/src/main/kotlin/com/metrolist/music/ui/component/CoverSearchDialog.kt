/**
 * MusicLab cover search legacy entry point
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import com.metrolist.innertube.models.SongItem

/**
 * Compatibility shim for the old PlayerMenu call site.
 *
 * Cover search used to be rendered here as a full-screen Android Dialog. A
 * Dialog owns a separate window, so the app player could never be drawn above
 * it. We now navigate to a normal in-app CoverSearchScreen instead. The old
 * function remains so existing menu code does not need to be rewritten.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun CoverSearchDialog(
    title: String,
    originalArtist: String,
    durationSec: Int,
    currentYouTubeId: String?,
    onSelect: (SongItem) -> Unit,
    onDismiss: () -> Unit,
    navController: NavController? = null,
) {
    val menuState = LocalMenuState.current

    LaunchedEffect(title, originalArtist, durationSec, currentYouTubeId) {
        val opened = CoverNavigationBridge.open(
            CoverSearchRequest(
                title = title,
                originalArtist = originalArtist,
                durationSec = durationSec,
                currentYouTubeId = currentYouTubeId,
            ),
        )
        if (opened) {
            // Reveal the new search route underneath, then remove the old menu
            // layer. The same player sheet can later expand above the route.
            PlayerBottomSheetBridge.collapseSoft()
            menuState.dismiss()
            onDismiss()
        }
    }
}
