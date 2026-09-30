from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"LAB19: pattern not found: {label} in {path}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_all(path: str, old: str, new: str, min_count: int, label: str) -> int:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count < min_count:
        raise SystemExit(f"LAB19: expected at least {min_count} replacements for {label} in {path}, found {count}")
    p.write_text(text.replace(old, new), encoding="utf-8")
    return count


bottom_sheet = "app/src/main/kotlin/com/metrolist/music/ui/component/BottomSheet.kt"
main_activity = "app/src/main/kotlin/com/metrolist/music/MainActivity.kt"
player_menu = "app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt"
intelligence_menu = "app/src/main/kotlin/com/metrolist/music/ui/menu/MusicLabIntelligenceMenu.kt"
cover_bridge = "app/src/main/kotlin/com/metrolist/music/ui/component/CoverNavigationBridge.kt"
original_bridge = "app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionNavigationBridge.kt"
cover_screen = "app/src/main/kotlin/com/metrolist/music/ui/component/CoverSearchScreen.kt"
original_screen = "app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionScreen.kt"

# 1) BottomSheet: the bridge is explicitly owned by MainActivity, not discovered heuristically.
replace_once(
    bottom_sheet,
    '''    internal fun attach(candidate: BottomSheetState) {\n        state = candidate\n    }\n\n    fun expandSoft() {\n        state?.expandSoft()\n    }\n\n    fun collapseSoft() {\n        state?.collapseSoft()\n    }\n''',
    '''    internal fun attach(candidate: BottomSheetState) {\n        state = candidate\n    }\n\n    internal fun detach(candidate: BottomSheetState) {\n        if (state === candidate) state = null\n    }\n\n    fun expandSoft() {\n        state?.expandSoft()\n    }\n\n    fun collapseSoft() {\n        state?.collapseSoft()\n    }\n\n    fun collapseToMiniPlayerNow() {\n        state?.collapseToMiniPlayerNow()\n    }\n''',
    "explicit bridge attach/detach",
)
replace_once(
    bottom_sheet,
    '''    fun collapseSoft() {\n        collapse(spring(stiffness = Spring.StiffnessMediumLow))\n    }\n\n    fun expandSoft() {\n''',
    '''    fun collapseSoft() {\n        collapse(spring(stiffness = Spring.StiffnessMediumLow))\n    }\n\n    fun collapseToMiniPlayerNow() {\n        onAnchorChanged(collapsedAnchor)\n        coroutineScope.launch {\n            // snapTo cancels any competing expand animation before placing the real\n            // player exactly on the mini-player anchor. External navigation must win.\n            animatable.snapTo(collapsedBound)\n        }\n    }\n\n    fun expandSoft() {\n''',
    "hard mini-player collapse API",
)
replace_once(
    bottom_sheet,
    '''        BottomSheetState(\n            draggableState = DraggableState { delta ->\n                coroutineScope.launch {\n                    animatable.snapTo(animatable.value - with(density) { delta.toDp() })\n                }\n            },\n            onAnchorChanged = { previousAnchor = it },\n            coroutineScope = coroutineScope,\n            animatable = animatable,\n            collapsedBound = collapsedBound\n        ).also { state ->\n            // The app's primary player is the only custom sheet with a real\n            // collapsed mini-player height above a 0.dp dismissed bound.\n            if (dismissedBound == 0.dp && collapsedBound > dismissedBound) {\n                PlayerBottomSheetBridge.attach(state)\n            }\n        }\n''',
    '''        BottomSheetState(\n            draggableState = DraggableState { delta ->\n                coroutineScope.launch {\n                    animatable.snapTo(animatable.value - with(density) { delta.toDp() })\n                }\n            },\n            onAnchorChanged = { previousAnchor = it },\n            coroutineScope = coroutineScope,\n            animatable = animatable,\n            collapsedBound = collapsedBound\n        )\n''',
    "remove heuristic bridge attachment",
)

# 2) MainActivity: bind the bridge to the one true player state and make route fallback immediate.
replace_once(
    main_activity,
    '''import com.metrolist.music.ui.component.LocalMenuState\nimport com.metrolist.music.ui.component.rememberBottomSheetState\n''',
    '''import com.metrolist.music.ui.component.LocalMenuState\nimport com.metrolist.music.ui.component.PlayerBottomSheetBridge\nimport com.metrolist.music.ui.component.rememberBottomSheetState\n''',
    "MainActivity bridge import",
)
replace_once(
    main_activity,
    '''                val playerBottomSheetState =\n                    rememberBottomSheetState(\n                        dismissedBound = 0.dp,\n                        collapsedBound =\n                            bottomInset +\n                                (if (!showRail && shouldShowNavigationBar) navPadding else 0.dp) +\n                                (if (useNewMiniPlayerDesign) MiniPlayerBottomSpacing else 0.dp) +\n                                MiniPlayerHeight,\n                        expandedBound = maxHeight,\n                    )\n\n                val playerReadyState =\n''',
    '''                val playerBottomSheetState =\n                    rememberBottomSheetState(\n                        dismissedBound = 0.dp,\n                        collapsedBound =\n                            bottomInset +\n                                (if (!showRail && shouldShowNavigationBar) navPadding else 0.dp) +\n                                (if (useNewMiniPlayerDesign) MiniPlayerBottomSpacing else 0.dp) +\n                                MiniPlayerHeight,\n                        expandedBound = maxHeight,\n                    )\n\n                // LAB19: bind the global MusicLab bridge explicitly to the real player.\n                // Generic BottomSheet instances are never allowed to steal this reference.\n                DisposableEffect(playerBottomSheetState) {\n                    PlayerBottomSheetBridge.attach(playerBottomSheetState)\n                    onDispose { PlayerBottomSheetBridge.detach(playerBottomSheetState) }\n                }\n\n                val playerReadyState =\n''',
    "explicit MainActivity bridge binding",
)
replace_once(
    main_activity,
    '''                    if (\n                        navBackStackEntry?.destination?.route != null &&\n                        !playerBottomSheetState.isCollapsed &&\n                        !playerBottomSheetState.isDismissed\n                    ) {\n                        playerBottomSheetState.collapseSoft()\n                    }\n''',
    '''                    if (\n                        navBackStackEntry?.destination?.route != null &&\n                        !playerBottomSheetState.isCollapsed &&\n                        !playerBottomSheetState.isDismissed\n                    ) {\n                        playerBottomSheetState.collapseToMiniPlayerNow()\n                    }\n''',
    "global route hard collapse",
)

# 3) PlayerMenu: pass the real state into MusicLab and collapse before navigating.
replace_once(
    player_menu,
    '''            MusicLabIntelligenceActions(\n                mediaMetadata = mediaMetadata,\n                onDismiss = onDismiss,\n            )\n''',
    '''            MusicLabIntelligenceActions(\n                mediaMetadata = mediaMetadata,\n                playerBottomSheetState = playerBottomSheetState,\n                onDismiss = onDismiss,\n            )\n''',
    "pass player state to MusicLab menu",
)
replace_once(
    player_menu,
    '''                            .clickable {\n                                navController.navigate("artist/${artist.id}")\n                                showSelectArtistDialog = false\n                                playerBottomSheetState.collapseSoft()\n                                onDismiss()\n                            }.padding(horizontal = 24.dp),\n''',
    '''                            .clickable {\n                                playerBottomSheetState.collapseToMiniPlayerNow()\n                                navController.navigate("artist/${artist.id}")\n                                showSelectArtistDialog = false\n                                onDismiss()\n                            }.padding(horizontal = 24.dp),\n''',
    "multi-artist navigation collapse first",
)
replace_once(
    player_menu,
    '''                                        if (mediaMetadata.artists.size == 1) {\n                                            navController.navigate("artist/${mediaMetadata.artists[0].id}")\n                                            playerBottomSheetState.collapseSoft()\n                                            onDismiss()\n                                        } else {\n''',
    '''                                        if (mediaMetadata.artists.size == 1) {\n                                            playerBottomSheetState.collapseToMiniPlayerNow()\n                                            navController.navigate("artist/${mediaMetadata.artists[0].id}")\n                                            onDismiss()\n                                        } else {\n''',
    "single artist navigation collapse first",
)
replace_once(
    player_menu,
    '''                                    onClick = {\n                                        if (isPodcast) {\n                                            navController.navigate("online_podcast/${mediaMetadata.album.id}")\n                                        } else {\n                                            navController.navigate("album/${mediaMetadata.album.id}")\n                                        }\n                                        playerBottomSheetState.collapseSoft()\n                                        onDismiss()\n                                    },\n''',
    '''                                    onClick = {\n                                        playerBottomSheetState.collapseToMiniPlayerNow()\n                                        if (isPodcast) {\n                                            navController.navigate("online_podcast/${mediaMetadata.album.id}")\n                                        } else {\n                                            navController.navigate("album/${mediaMetadata.album.id}")\n                                        }\n                                        onDismiss()\n                                    },\n''',
    "album navigation collapse first",
)

# 4) MusicLab intelligence menu: use the real player state directly and collapse immediately.
replace_once(
    intelligence_menu,
    '''import com.metrolist.music.ui.component.CoverSearchRequest\n''',
    '''import com.metrolist.music.ui.component.CoverSearchRequest\nimport com.metrolist.music.ui.component.BottomSheetState\n''',
    "MusicLab menu BottomSheetState import",
)
replace_once(
    intelligence_menu,
    '''import com.metrolist.music.ui.component.PlayerBottomSheetBridge\n''',
    '''''',
    "remove MusicLab menu bridge import",
)
replace_once(
    intelligence_menu,
    '''internal fun MusicLabIntelligenceActions(\n    mediaMetadata: MediaMetadata,\n    onDismiss: () -> Unit,\n) {\n''',
    '''internal fun MusicLabIntelligenceActions(\n    mediaMetadata: MediaMetadata,\n    playerBottomSheetState: BottomSheetState,\n    onDismiss: () -> Unit,\n) {\n''',
    "MusicLab menu real state parameter",
)
replace_all(
    intelligence_menu,
    "PlayerBottomSheetBridge.collapseSoft()",
    "playerBottomSheetState.collapseToMiniPlayerNow()",
    4,
    "MusicLab menu bridge collapse replacement",
)
replace_once(
    intelligence_menu,
    '''                            val opened = OriginalVersionNavigationBridge.open(\n''',
    '''                            playerBottomSheetState.collapseToMiniPlayerNow()\n                            val opened = OriginalVersionNavigationBridge.open(\n''',
    "Originali collapse before open",
)
replace_once(
    intelligence_menu,
    '''                            val opened = CoverNavigationBridge.open(\n''',
    '''                            playerBottomSheetState.collapseToMiniPlayerNow()\n                            val opened = CoverNavigationBridge.open(\n''',
    "Cover collapse before open",
)
replace_once(
    intelligence_menu,
    '''                        onClick = {\n                            Toast.makeText(context, "Sto identificando l'artista corretto…", Toast.LENGTH_SHORT).show()\n''',
    '''                        onClick = {\n                            playerBottomSheetState.collapseToMiniPlayerNow()\n                            Toast.makeText(context, "Sto identificando l'artista corretto…", Toast.LENGTH_SHORT).show()\n''',
    "canonical artist collapse immediately",
)
replace_once(
    intelligence_menu,
    '''                        onClick = {\n                            Toast.makeText(context, "Sto identificando l'album corretto…", Toast.LENGTH_SHORT).show()\n''',
    '''                        onClick = {\n                            playerBottomSheetState.collapseToMiniPlayerNow()\n                            Toast.makeText(context, "Sto identificando l'album corretto…", Toast.LENGTH_SHORT).show()\n''',
    "canonical album collapse immediately",
)

# 5) Centralize Cover/Originali route entry: even future callers collapse the real player first.
replace_once(
    cover_bridge,
    '''        val navController = navControllerRef?.get() ?: return false\n        navController.navigate(ROUTE) {\n''',
    '''        val navController = navControllerRef?.get() ?: return false\n        PlayerBottomSheetBridge.collapseToMiniPlayerNow()\n        navController.navigate(ROUTE) {\n''',
    "Cover bridge collapse",
)
replace_once(
    original_bridge,
    '''        val navController = navControllerRef?.get() ?: return false\n        navController.navigate(ROUTE) { launchSingleTop = true }\n''',
    '''        val navController = navControllerRef?.get() ?: return false\n        PlayerBottomSheetBridge.collapseToMiniPlayerNow()\n        navController.navigate(ROUTE) { launchSingleTop = true }\n''',
    "Originali bridge collapse",
)

# 6) Result playback must never re-expand the full player. It starts audio and keeps mini-player.
replace_once(
    cover_screen,
    '''        PlayerBottomSheetBridge.expandSoft()\n    }\n\n    fun replaceWith(result: AiCoverPlayable) {\n''',
    '''        PlayerBottomSheetBridge.collapseToMiniPlayerNow()\n    }\n\n    fun replaceWith(result: AiCoverPlayable) {\n''',
    "Cover result playback stays mini",
)
replace_once(
    cover_screen,
    '''        navController.popBackStack()\n    }\n\n    val coverResults = playables.filter { it.candidate.category == AiCoverCategory.COVER }\n''',
    '''        PlayerBottomSheetBridge.collapseToMiniPlayerNow()\n        navController.popBackStack()\n    }\n\n    val coverResults = playables.filter { it.candidate.category == AiCoverCategory.COVER }\n''',
    "Cover replace stays mini",
)
replace_once(
    original_screen,
    '''        navController.popBackStack()\n    }\n\n    fun preview(song: SongItem) {\n''',
    '''        PlayerBottomSheetBridge.collapseToMiniPlayerNow()\n        navController.popBackStack()\n    }\n\n    fun preview(song: SongItem) {\n''',
    "Originali replace stays mini",
)
replace_once(
    original_screen,
    '''        PlayerBottomSheetBridge.expandSoft()\n    }\n\n    val nonStudioIds =\n''',
    '''        PlayerBottomSheetBridge.collapseToMiniPlayerNow()\n    }\n\n    val nonStudioIds =\n''',
    "Originali preview stays mini",
)

print("LAB19 transform completed: explicit player ownership + direct navigation/result collapse")
