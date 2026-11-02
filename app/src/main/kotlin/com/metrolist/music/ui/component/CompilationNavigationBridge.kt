package com.metrolist.music.ui.component

import androidx.navigation.NavHostController
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class CompilationTrackRequest(
    val title: String,
    val artist: String,
    val requestId: Long,
)

internal object CompilationNavigationBridge {
    @Volatile private var pendingTrack: CompilationTrackRequest? = null
    private val serial = AtomicLong()
    private val pendingFlow = MutableStateFlow<CompilationTrackRequest?>(null)
    val requests: StateFlow<CompilationTrackRequest?> = pendingFlow

    fun takePendingTrack(): CompilationTrackRequest? = synchronized(this) {
        pendingTrack.also {
            pendingTrack = null
            pendingFlow.value = null
        }
    }

    fun openForTrack(title: String, artist: String): Boolean {
        if (title.isBlank() || navControllerRef?.get() == null) return false
        synchronized(this) {
            val request = CompilationTrackRequest(title.trim(), artist.trim(), serial.incrementAndGet())
            pendingTrack = request
            pendingFlow.value = request
        }
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
