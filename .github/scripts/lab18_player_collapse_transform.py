from pathlib import Path

path = Path("app/src/main/kotlin/com/metrolist/music/MainActivity.kt")
text = path.read_text(encoding="utf-8")

old_global = '''if (navBackStackEntry?.destination?.route != null && playerBottomSheetState.isExpanded) {
                        playerBottomSheetState.collapseSoft()
                    }'''
new_global = '''if (
                        navBackStackEntry?.destination?.route != null &&
                        !playerBottomSheetState.isCollapsed &&
                        !playerBottomSheetState.isDismissed
                    ) {
                        playerBottomSheetState.collapseSoft()
                    }'''

if old_global not in text:
    raise SystemExit("LAB18: global navigation collapse pattern not found")
text = text.replace(old_global, new_global, 1)

old_local = '''if (playerBottomSheetState.isExpanded) {
                                            playerBottomSheetState.collapseSoft()
                                        }'''
new_local = '''if (!playerBottomSheetState.isCollapsed && !playerBottomSheetState.isDismissed) {
                                            playerBottomSheetState.collapseSoft()
                                        }'''
count = text.count(old_local)
if count < 1:
    raise SystemExit("LAB18: nav-item collapse pattern not found")
text = text.replace(old_local, new_local)

old_rail = '''if (playerBottomSheetState.isExpanded) {
                                            playerBottomSheetState.collapseSoft()
                                        }

                                        if (isSelected) {'''
new_rail = '''if (!playerBottomSheetState.isCollapsed && !playerBottomSheetState.isDismissed) {
                                            playerBottomSheetState.collapseSoft()
                                        }

                                        if (isSelected) {'''
if old_rail in text:
    text = text.replace(old_rail, new_rail)

path.write_text(text, encoding="utf-8")
print(f"LAB18 transform completed; pre-navigation replacements={count}")
