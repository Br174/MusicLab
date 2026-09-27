package com.metrolist.music.ui.component

import androidx.navigation.NavHostController
import java.lang.ref.WeakReference

internal data class OriginalVersionRequest(
    val title: String,
    val artist: String,
    val durationSec: Int,
    val currentYouTubeId: String,
)

internal object OriginalVersionNavigationBridge {
    const val ROUTE = "original_version"
    private var navControllerRef: WeakReference<NavHostController>? = null

    @Volatile
    var currentRequest: OriginalVersionRequest? = null
        private set

    fun bind(navController: NavHostController) {
        navControllerRef = WeakReference(navController)
    }

    fun open(request: OriginalVersionRequest): Boolean {
        currentRequest = request
        val navController = navControllerRef?.get() ?: return false
        navController.navigate(ROUTE) { launchSingleTop = true }
        return true
    }
}
