package com.metrolist.music.ui.component

import androidx.navigation.NavHostController
import java.lang.ref.WeakReference

internal data class CompilationTrackRequest(val title: String, val artist: String)

internal object CompilationNavigationBridge {
    @Volatile private var pendingTrack: CompilationTrackRequest? = null

    fun takePendingTrack(): CompilationTrackRequest? = synchronized(this) {
        pendingTrack.also { pendingTrack = null }
    }

    fun openForTrack(title: String, artist: String): Boolean {
        if (title.isBlank() || navControllerRef?.get() == null) return false
        synchronized(this) { pendingTrack = CompilationTrackRequest(title.trim(), artist.trim()) }
        return open()
    }

    const val ROUTE = "compilation_hub"

    private var navControllerRef: WeakReference<NavHostController>? = null

    fun bind(navController: NavHostController) {
        navControllerRef = WeakReference(navController)
    }

    fun open(): Boolean {
        val navController = navControllerRef?.get() ?: return false
        PlayerBottomSheetBridge.collapseToMiniPlayerNow()
        navController.navigate(ROUTE) {
            launchSingleTop = true
        }
        return true
    }
}
