from pathlib import Path

path = Path("app/src/main/kotlin/com/metrolist/music/ui/menu/MusicLabIntelligenceMenu.kt")
text = path.read_text(encoding="utf-8")

# MusicLabIntelligenceActions is used both by PlayerMenu (which owns the real
# BottomSheetState) and SongMenu (which does not). Keep the direct state path
# where available and use the explicitly MainActivity-bound bridge elsewhere.
old_import = "import com.metrolist.music.ui.component.BottomSheetState\n"
new_import = old_import + "import com.metrolist.music.ui.component.PlayerBottomSheetBridge\n"
if old_import not in text:
    raise SystemExit("LAB19 compat: BottomSheetState import not found")
text = text.replace(old_import, new_import, 1)

old_param = "    playerBottomSheetState: BottomSheetState,\n"
new_param = "    playerBottomSheetState: BottomSheetState? = null,\n"
if old_param not in text:
    raise SystemExit("LAB19 compat: playerBottomSheetState parameter not found")
text = text.replace(old_param, new_param, 1)

anchor = "    val settings = MusicIntelligenceSettings.from(context)\n"
helper = anchor + "\n    fun collapsePlayerToMiniNow() {\n        playerBottomSheetState?.collapseToMiniPlayerNow()\n            ?: PlayerBottomSheetBridge.collapseToMiniPlayerNow()\n    }\n"
if anchor not in text:
    raise SystemExit("LAB19 compat: settings anchor not found")
text = text.replace(anchor, helper, 1)

count = text.count("playerBottomSheetState.collapseToMiniPlayerNow()")
if count < 4:
    raise SystemExit(f"LAB19 compat: expected direct collapse calls, found {count}")
text = text.replace("playerBottomSheetState.collapseToMiniPlayerNow()", "collapsePlayerToMiniNow()")

path.write_text(text, encoding="utf-8")
print(f"LAB19 API compatibility applied; collapse call replacements={count}")
