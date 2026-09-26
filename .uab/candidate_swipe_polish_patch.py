from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Anchor not found for {label}")
    return text.replace(old, new, 1)

# 1) Global player collapse on real navigation changes
main_path = Path("app/src/main/kotlin/com/metrolist/music/MainActivity.kt")
main = main_path.read_text(encoding="utf-8")
old = """                // Navigation tracking\n                LaunchedEffect(navBackStackEntry) {\n                    if (inSearchScreen) {\n"""
new = """                // Navigation tracking\n                LaunchedEffect(navBackStackEntry) {\n                    // If the user opens another screen while the full player is visible,\n                    // immediately return the player to its mini state so the destination\n                    // is never hidden behind the expanded player.\n                    if (!playerBottomSheetState.isCollapsed && !playerBottomSheetState.isDismissed) {\n                        playerBottomSheetState.collapseSoft()\n                    }\n\n                    if (inSearchScreen) {\n"""
main = replace_once(main, old, new, "MainActivity navigation collapse")
main_path.write_text(main, encoding="utf-8")

# 2) Remove the earlier menu-local workaround and collapse explicitly for Cover Search
menu_path = Path("app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt")
menu = menu_path.read_text(encoding="utf-8")
helper = """    // Navigation from the expanded player must leave the player in its mini state.\n    // collapseSoft() updates the saved anchor; snapTo() makes the visual transition\n    // immediate so the destination is never hidden behind the full player.\n    fun collapsePlayerBeforeNavigation() {\n        playerBottomSheetState.collapseSoft()\n        playerBottomSheetState.snapTo(playerBottomSheetState.collapsedBound)\n    }\n\n"""
if helper in menu:
    menu = menu.replace(helper, "", 1)
menu = re.sub(r"^\s*collapsePlayerBeforeNavigation\(\)\n", "", menu, flags=re.MULTILINE)
old_cover = """                                onClick = { showCoverSearchDialog = true },\n"""
new_cover = """                                onClick = {\n                                    playerBottomSheetState.collapseSoft()\n                                    showCoverSearchDialog = true\n                                },\n"""
menu = replace_once(menu, old_cover, new_cover, "Cover Search collapse")
if "collapsePlayerBeforeNavigation" in menu:
    raise SystemExit("Old collapsePlayerBeforeNavigation workaround still present")
menu_path.write_text(menu, encoding="utf-8")

# 3) Make the artwork swipe easier to commit without touching anti-loop logic
thumb_path = Path("app/src/main/kotlin/com/metrolist/music/ui/player/Thumbnail.kt")
thumb = thumb_path.read_text(encoding="utf-8")
thumb = replace_once(
    thumb,
    "            velocityThreshold = 500f\n",
    "            velocityThreshold = 120f\n",
    "thumbnail snap sensitivity",
)
thumb_path.write_text(thumb, encoding="utf-8")
