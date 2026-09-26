from pathlib import Path

# ------------------------------------------------------------
# 1) Replay the approved LAB 02 swipe/player synchronization fix
#    on a fresh branch created from the current MADRE.
# ------------------------------------------------------------
thumb = Path('app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt')
text = thumb.read_text(encoding='utf-8')

imports = [
    ('import androidx.compose.runtime.setValue\n', 'import androidx.compose.runtime.snapshotFlow\n'),
    ('import androidx.compose.ui.input.pointer.pointerInput\n', 'import androidx.compose.ui.input.pointer.PointerEventPass\n'),
    ('import kotlinx.coroutines.delay\n', 'import kotlin.math.abs\n'),
]
for anchor, addition in imports:
    if addition not in text:
        if anchor not in text:
            raise SystemExit(f'Thumbnail import anchor not found: {anchor!r}')
        text = text.replace(anchor, anchor + addition, 1)

start_marker = '    // Current item tracking - derived state for efficiency\n'
end_marker = '    // Update position when song changes\n'
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('Thumbnail swipe handler markers not found; refusing patch')

old_region = text[start:end]
if 'LaunchedEffect(itemScrollOffset)' not in old_region:
    raise SystemExit('Expected MADRE swipe handler not found; refusing patch')

new_region = '''    // Current item tracking - derived state for efficiency
    val currentItem by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemIndex } }

    // Only a genuine horizontal finger drag can arm a song change. Programmatic
    // movements (player opening, metadata updates, automatic centering) never arm it.
    var userPointerDown by remember { mutableStateOf(false) }
    var userSwipeArmed by remember { mutableStateOf(false) }

    val userSwipePointerModifier =
        Modifier.pointerInput(swipeThumbnail) {
            if (!swipeThumbnail) return@pointerInput
            val touchSlop = viewConfiguration.touchSlop

            awaitPointerEventScope {
                var gestureActive = false
                var startX = 0f
                var startY = 0f

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)

                    if (!gestureActive) {
                        val down = event.changes.firstOrNull { it.pressed && !it.previousPressed }
                        if (down != null) {
                            gestureActive = true
                            startX = down.position.x
                            startY = down.position.y
                            userPointerDown = true
                            userSwipeArmed = false
                        }
                        continue
                    }

                    val pressedChange = event.changes.firstOrNull { it.pressed }
                    if (pressedChange != null) {
                        val dx = abs(pressedChange.position.x - startX)
                        val dy = abs(pressedChange.position.y - startY)
                        if (dx > touchSlop && dx > dy) {
                            userSwipeArmed = true
                        }
                    }

                    if (event.changes.none { it.pressed }) {
                        gestureActive = false
                        userPointerDown = false
                    }
                }
            }
        }

    // Commit at most one skip after the real finger gesture has ended and the
    // carousel has settled. Automatic scroll/centering can never reach this path
    // because it cannot set userSwipeArmed.
    LaunchedEffect(
        thumbnailLazyGridState,
        swipeThumbnail,
        currentMediaIndex,
        canSkipNext,
        canSkipPrevious,
    ) {
        snapshotFlow {
            !thumbnailLazyGridState.isScrollInProgress &&
                !userPointerDown &&
                userSwipeArmed
        }.collect { shouldCommit ->
            if (!shouldCommit) return@collect

            userSwipeArmed = false
            if (!swipeThumbnail || currentMediaIndex < 0) return@collect

            val targetIndex = thumbnailLazyGridState.firstVisibleItemIndex
            val player = playerConnection.player
            val keepPlaying = player.playWhenReady
            val changed =
                when {
                    targetIndex > currentMediaIndex && canSkipNext -> {
                        player.seekToNextMediaItem()
                        true
                    }
                    targetIndex < currentMediaIndex && canSkipPrevious -> {
                        player.seekToPreviousMediaItem()
                        true
                    }
                    else -> false
                }

            if (changed) {
                if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                    player.prepare()
                }
                player.playWhenReady = keepPlaying
            }
        }
    }

'''
text = text[:start] + new_region + text[end:]

old_modifier = '''                        modifier = if (isLandscape) {
                            Modifier.size(dimensions.thumbnailSize + (PlayerHorizontalPadding * 2))
                        } else {
                            Modifier.fillMaxSize()
                        }
'''
new_modifier = '''                        modifier = (if (isLandscape) {
                            Modifier.size(dimensions.thumbnailSize + (PlayerHorizontalPadding * 2))
                        } else {
                            Modifier.fillMaxSize()
                        }).then(userSwipePointerModifier)
'''
if old_modifier not in text:
    raise SystemExit('Thumbnail LazyHorizontalGrid modifier anchor not found; refusing patch')
text = text.replace(old_modifier, new_modifier, 1)
thumb.write_text(text, encoding='utf-8')

# ------------------------------------------------------------
# 2) Collapse the expanded player into the mini-player whenever
#    an action from the player changes the app navigation target.
# ------------------------------------------------------------
player_path = Path('app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt')
player = player_path.read_text(encoding='utf-8')

back_handler = '''    BackHandler(enabled = state.isExpanded) {
        state.collapseSoft()
    }
'''
if back_handler not in player:
    raise SystemExit('Player BackHandler anchor not found; refusing patch')

nav_guard = '''
    // Keep the full player from remaining persistently over a newly opened page.
    // Opening the player itself does not trigger this: we collapse only when the
    // NavController actually moves from one destination to another.
    DisposableEffect(navController, state) {
        var lastDestinationId = navController.currentDestination?.id
        val destinationListener =
            NavController.OnDestinationChangedListener { _, destination, _ ->
                val previousDestinationId = lastDestinationId
                lastDestinationId = destination.id

                if (
                    previousDestinationId != null &&
                    destination.id != previousDestinationId &&
                    state.isExpanded
                ) {
                    state.collapseSoft()
                }
            }

        navController.addOnDestinationChangedListener(destinationListener)
        onDispose {
            navController.removeOnDestinationChangedListener(destinationListener)
        }
    }
'''
if 'destinationListener' not in player:
    player = player.replace(back_handler, back_handler + nav_guard, 1)

player_path.write_text(player, encoding='utf-8')
