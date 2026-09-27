from pathlib import Path

p = Path("app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt")
t = p.read_text()

start_marker = "                actions =\n"
end_marker = "                columns = if (isListenTogetherGuest) 2 else 3,\n"
start = t.find(start_marker, t.find("NewActionGrid("))
end = t.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("NewActionGrid action block not found")

replacement = '''                actions =
                    listOfNotNull(
                        if (!isListenTogetherGuest) {
                            NewAction(
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.radio),
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                text = stringResource(R.string.start_radio),
                                onClick = {
                                    Toast.makeText(context, startingRadioText, Toast.LENGTH_SHORT).show()
                                    playerConnection.startRadioSeamlessly()
                                    onDismiss()
                                },
                            )
                        } else {
                            null
                        },
                        NewAction(
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.playlist_add),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            text = stringResource(R.string.add_to_playlist),
                            onClick = { showChoosePlaylistDialog = true },
                        ),
                        NewAction(
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.link),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            text = stringResource(R.string.copy_link),
                            onClick = {
                                val clipboard =
                                    context.getSystemService(
                                        android.content.Context.CLIPBOARD_SERVICE,
                                    ) as android.content.ClipboardManager
                                val clip =
                                    android.content.ClipData.newPlainText(
                                        "Song Link",
                                        "https://music.youtube.com/watch?v=${mediaMetadata.id}",
                                    )
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast
                                    .makeText(context, R.string.link_copied, android.widget.Toast.LENGTH_SHORT)
                                    .show()
                                onDismiss()
                            },
                        ),
                    ) +
                        (if (com.metrolist.spotify.Spotify.isAuthenticated()) {
                            listOf(
                                NewAction(
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.spotify),
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    text = stringResource(R.string.spotify_add_to_playlist),
                                    onClick = { showAddToSpotifyPlaylist = true },
                                ),
                            )
                        } else {
                            emptyList()
                        }) +
                        listOf(
                            NewAction(
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.album),
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                text = "Originali",
                                onClick = {
                                    val opened = com.metrolist.music.ui.component.OriginalVersionNavigationBridge.open(
                                        com.metrolist.music.ui.component.OriginalVersionRequest(
                                            title = mediaMetadata.title,
                                            artist = mediaMetadata.artists.firstOrNull()?.name.orEmpty(),
                                            durationSec = mediaMetadata.duration,
                                            currentYouTubeId = mediaMetadata.id,
                                        ),
                                    )
                                    if (opened) {
                                        playerBottomSheetState.collapseSoft()
                                        onDismiss()
                                    }
                                },
                            ),
                            NewAction(
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.link),
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                text = "Cover",
                                onClick = {
                                    playerBottomSheetState.collapseSoft()
                                    showCoverSearchDialog = true
                                },
                            ),
                        ),
'''

new_text = t[:start] + replacement + t[end:]
if new_text == t:
    raise SystemExit("No change produced")

# Guard the exact visual order requested when Spotify is authenticated.
order_tokens = [
    "R.string.start_radio",
    "R.string.add_to_playlist",
    "R.string.copy_link",
    "R.drawable.spotify",
    'text = "Originali"',
    'text = "Cover"',
]
pos = -1
for token in order_tokens:
    nxt = new_text.find(token, pos + 1)
    if nxt < 0:
        raise SystemExit(f"Missing order token: {token}")
    pos = nxt

p.write_text(new_text)
print("Quick-action order updated: Spotify -> Originali -> Cover")
