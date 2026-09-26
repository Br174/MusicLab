from pathlib import Path
import re

player_path = Path('app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt')
player = player_path.read_text(encoding='utf-8')

listener_block = '''    // Keep the full player from remaining persistently over a newly opened page.\n    // Opening the player itself does not trigger this: we collapse only when the\n    // NavController actually moves from one destination to another.\n    DisposableEffect(navController, state) {\n        var lastDestinationId = navController.currentDestination?.id\n        val destinationListener =\n            NavController.OnDestinationChangedListener { _, destination, _ ->\n                val previousDestinationId = lastDestinationId\n                lastDestinationId = destination.id\n\n                if (\n                    previousDestinationId != null &&\n                    destination.id != previousDestinationId &&\n                    state.isExpanded\n                ) {\n                    state.collapseSoft()\n                }\n            }\n\n        navController.addOnDestinationChangedListener(destinationListener)\n        onDispose {\n            navController.removeOnDestinationChangedListener(destinationListener)\n        }\n    }\n\n'''

if listener_block in player:
    player = player.replace(listener_block, '', 1)
elif 'Keep the full player from remaining persistently over a newly opened page.' in player:
    raise SystemExit('Persistence listener shape changed; refusing unsafe patch')

player_path.write_text(player, encoding='utf-8')

menu_path = Path('app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt')
menu = menu_path.read_text(encoding='utf-8')

anchor = '    val coroutineScope = rememberCoroutineScope()\n'
helper = '''    val coroutineScope = rememberCoroutineScope()\n\n    // Navigation from the expanded player must leave the player in its mini state.\n    // collapseSoft() updates the saved anchor; snapTo() makes the visual transition\n    // immediate so the destination is never hidden behind the full player.\n    fun collapsePlayerBeforeNavigation() {\n        playerBottomSheetState.collapseSoft()\n        playerBottomSheetState.snapTo(playerBottomSheetState.collapsedBound)\n    }\n'''

if 'fun collapsePlayerBeforeNavigation()' not in menu:
    if anchor not in menu:
        raise SystemExit('PlayerMenu coroutineScope anchor not found')
    menu = menu.replace(anchor, helper, 1)

pattern = re.compile(r'(?m)^([ \t]*)navController\.navigate\(')
if 'collapsePlayerBeforeNavigation()\n' not in menu:
    menu, count = pattern.subn(r'\1collapsePlayerBeforeNavigation()\n\1navController.navigate(', menu)
else:
    count = menu.count('collapsePlayerBeforeNavigation()') - 1

if count < 5:
    raise SystemExit(f'Expected at least 5 navigation actions, patched {count}')

menu_path.write_text(menu, encoding='utf-8')
