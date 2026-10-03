package com.metrolist.music.ui.component

import androidx.navigation.NavHostController
import java.lang.ref.WeakReference

internal object CompilationNavigationBridge {
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
