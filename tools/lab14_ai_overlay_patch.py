from pathlib import Path

player = Path('app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt')
p = player.read_text()
if 'MusicLabIntelligenceActions(' not in p:
    anchor = "        item {\n            // Check if this is a podcast episode (album ID doesn't start with MPREb_)\n"
    if anchor not in p:
        raise SystemExit('PlayerMenu anchor non trovato')
    block = (
        "        item {\n"
        "            MusicLabIntelligenceActions(\n"
        "                mediaMetadata = mediaMetadata,\n"
        "                onDismiss = onDismiss,\n"
        "            )\n"
        "        }\n\n"
        "        item { Spacer(modifier = Modifier.height(12.dp)) }\n\n"
    )
    player.write_text(p.replace(anchor, block + anchor, 1))

song = Path('app/src/main/kotlin/com/metrolist/music/ui/menu/SongMenu.kt')
s = song.read_text()
if 'MusicLabIntelligenceActions(' not in s:
    anchor = "        item {\n            Material3MenuGroup(\n"
    pos = s.find(anchor)
    if pos < 0:
        raise SystemExit('SongMenu anchor non trovato')
    block = (
        "        item {\n"
        "            MusicLabIntelligenceActions(\n"
        "                mediaMetadata = song.toMediaMetadata(),\n"
        "                onDismiss = onDismiss,\n"
        "            )\n"
        "        }\n\n"
        "        item { Spacer(modifier = Modifier.height(12.dp)) }\n\n"
    )
    song.write_text(s[:pos] + block + s[pos:])
