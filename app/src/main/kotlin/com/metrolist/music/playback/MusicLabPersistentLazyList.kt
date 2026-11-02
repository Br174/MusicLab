package com.metrolist.music.playback

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.navigation.NavController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.collectLatest

/**
 * R41 universal adapter for ordinary MusicLab lazy-list screens. Every route
 * gets a stable isolated key. Only reads/writes UI scroll metadata; never
 * changes media queue, current song, search backend or native player.
 */
internal object MusicLabScreenRouteKey {
    fun of(navController: NavController): String {
        val entry = navController.currentBackStackEntry
        val base = entry?.destination?.route.orEmpty()
        val args = entry?.arguments
        val suffix = args?.keySet()?.sorted()?.filter { name ->
            listOf("id", "playlist", "album", "artist", "browse", "query", "type").any {
                name.contains(it, ignoreCase = true)
            }
        }?.joinToString("|") { name ->
            name + "=" + (args.get(name)?.toString().orEmpty())
        }.orEmpty()
        return base + "|" + suffix
    }
}

@Composable
internal fun rememberMusicLabPersistentLazyListState(key: String): LazyListState {
    val context = LocalContext.current
    val initial = remember(key) {
        MusicLabListPositionStore.attach(context)
        MusicLabListPositionStore.get(key)
    }
    val state = remember(key) { LazyListState(initial.index, initial.offset) }
    LaunchedEffect(key, state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) ->
                MusicLabListPositionStore.scroll(key, index, offset)
            }
    }
    DisposableEffect(key, state) {
        onDispose {
            MusicLabListPositionStore.scroll(key,
                state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
            MusicLabListPositionStore.flush(key)
        }
    }
    return state
}

/** Same durable viewport semantics for album, artist and playlist artwork grids. */
@Composable
internal fun rememberMusicLabPersistentLazyGridState(key: String): LazyGridState {
    val context = LocalContext.current
    val initial = remember(key) {
        MusicLabListPositionStore.attach(context)
        MusicLabListPositionStore.get(key)
    }
    val state = remember(key) { LazyGridState(initial.index, initial.offset) }
    LaunchedEffect(key, state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) ->
                MusicLabListPositionStore.scroll(key, index, offset)
            }
    }
    DisposableEffect(key, state) {
        onDispose {
            MusicLabListPositionStore.scroll(key,
                state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
            MusicLabListPositionStore.flush(key)
        }
    }
    return state
}
