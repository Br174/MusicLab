package com.metrolist.music.ui.component

import androidx.navigation.NavHostController
import java.lang.ref.WeakReference

internal data class CoverSearchRequest(
    val title: String,
    val originalArtist: String,
    val durationSec: Int,
    val currentYouTubeId: String?,
)

/**
 * Small navigation bridge used by the legacy PlayerMenu entry point.
 *
 * PlayerMenu historically opened CoverSearchDialog as an Android Dialog window.
 * The search now lives in the normal NavHost so the existing player bottom sheet
 * can expand above it. Keeping this bridge avoids coupling the cover feature to
 * MainActivity or duplicating the player UI.
 */
internal object CoverNavigationBridge {
    const val ROUTE = "cover_search_hub"

    private var navControllerRef: WeakReference<NavHostController>? = null

    @Volatile
    var currentRequest: CoverSearchRequest? = null
        private set

    fun bind(navController: NavHostController) {
        navControllerRef = WeakReference(navController)
    }

    fun open(request: CoverSearchRequest): Boolean {
        currentRequest = request
        val navController = navControllerRef?.get() ?: return false
        navController.navigate(ROUTE) {
            launchSingleTop = true
        }
        return true
    }
}
