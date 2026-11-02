package com.metrolist.music.playback

import androidx.compose.foundation.lazy.LazyListState
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
