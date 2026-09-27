from pathlib import Path


def one(text, old, new, label):
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"{label}: atteso 1 match, trovati {n}")
    return text.replace(old, new, 1)

p = Path("app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt")
s = p.read_text()

s = one(
    s,
    "import com.metrolist.music.extensions.toMediaItem\n",
    "import com.metrolist.music.extensions.toMediaItem\n"
    "import com.metrolist.music.intelligence.CanonicalMusicMetadata\n"
    "import com.metrolist.music.intelligence.MusicIntelligenceClient\n"
    "import com.metrolist.music.intelligence.MusicIntelligenceSettings\n",
    "imports",
)

s = one(
    s,
    "    val qobuzEnabled by rememberPreference(com.metrolist.music.constants.EnableQobuzKey, defaultValue = false)\n\n    val librarySong by database.song(mediaMetadata.id).collectAsState(initial = null)\n",
    "    val qobuzEnabled by rememberPreference(com.metrolist.music.constants.EnableQobuzKey, defaultValue = false)\n\n"
    "    // Resolver canonico solo dopo che il player esiste: mai nel percorso di avvio del Play.\n"
    "    val musicIntelligenceSettings = MusicIntelligenceSettings.from(context)\n"
    "    val canonicalMetadata by produceState<CanonicalMusicMetadata?>(\n"
    "        initialValue = MusicIntelligenceClient.cached(mediaMetadata.id),\n"
    "        key1 = mediaMetadata.id,\n"
    "        key2 = mediaMetadata.title,\n"
    "        key3 = mediaMetadata.artists.firstOrNull()?.name,\n"
    "    ) {\n"
    "        if (musicIntelligenceSettings.enabled && musicIntelligenceSettings.backgroundMetadata) {\n"
    "            value = kotlinx.coroutines.withContext(Dispatchers.IO) {\n"
    "                MusicIntelligenceClient.resolve(\n"
    "                    context = context,\n"
    "                    playbackId = mediaMetadata.id,\n"
    "                    title = mediaMetadata.title,\n"
    "                    artist = mediaMetadata.artists.firstOrNull()?.name.orEmpty(),\n"
    "                    album = mediaMetadata.album?.title,\n"
    "                )\n"
    "            }\n"
    "        }\n"
    "    }\n\n"
    "    val librarySong by database.song(mediaMetadata.id).collectAsState(initial = null)\n",
    "canonical state",
)

old = '''                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.go_to_artist)) },
                                description = {
                                    Text(
                                        text = mediaMetadata.artists.joinToString { it.name },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.artist),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                onClick = {
                                    when {
                                        navigationArtists.size == 1 -> {
                                            playerBottomSheetState.collapse(tween(durationMillis = 120))
                                            navController.navigate("artist/${navigationArtists[0].id}")
                                            onDismiss()
                                        }
                                        navigationArtists.size > 1 -> {
                                            showSelectArtistDialog = true
                                        }
                                        else -> {
                                            Toast.makeText(context, R.string.artist_unavailable, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                            ),
                        )
'''
new = '''                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.go_to_artist)) },
                                description = {
                                    Text(
                                        text = canonicalMetadata?.artist
                                            ?: mediaMetadata.artists.joinToString { it.name },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.artist),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                onClick = {
                                    val useCanonical = musicIntelligenceSettings.enabled && musicIntelligenceSettings.artistResolver
                                    val canonicalId = canonicalMetadata?.artistBrowseId
                                    if (useCanonical) {
                                        if (!canonicalId.isNullOrBlank()) {
                                            playerBottomSheetState.collapse(tween(durationMillis = 120))
                                            navController.navigate("artist/$canonicalId")
                                            onDismiss()
                                        } else {
                                            Toast.makeText(context, "Sto identificando la pagina corretta dell'artista…", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        when {
                                            navigationArtists.size == 1 -> {
                                                playerBottomSheetState.collapse(tween(durationMillis = 120))
                                                navController.navigate("artist/${navigationArtists[0].id}")
                                                onDismiss()
                                            }
                                            navigationArtists.size > 1 -> showSelectArtistDialog = true
                                            else -> Toast.makeText(context, R.string.artist_unavailable, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                            ),
                        )
'''
s = one(s, old, new, "artist item")

old = '''                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.go_to_album)) },
                                description = {
                                    navigationAlbumTitle?.let { title ->
                                        Text(
                                            text = title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(if (navigationAlbumIsPodcast) R.drawable.mic else R.drawable.album),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                onClick = {
                                    val albumId = navigationAlbumId
                                    if (albumId.isNullOrBlank()) {
                                        Toast.makeText(context, R.string.album_unavailable, Toast.LENGTH_SHORT).show()
                                    } else {
                                        playerBottomSheetState.collapse(tween(durationMillis = 120))
                                        if (navigationAlbumIsPodcast) {
                                            navController.navigate("online_podcast/$albumId")
                                        } else {
                                            navController.navigate("album/$albumId")
                                        }
                                        onDismiss()
                                    }
                                },
                            ),
                        )
'''
new = '''                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.go_to_album)) },
                                description = {
                                    (canonicalMetadata?.album ?: navigationAlbumTitle)?.let { title ->
                                        Text(
                                            text = title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(if (navigationAlbumIsPodcast) R.drawable.mic else R.drawable.album),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                onClick = {
                                    val useCanonical =
                                        !navigationAlbumIsPodcast &&
                                            musicIntelligenceSettings.enabled &&
                                            musicIntelligenceSettings.albumResolver
                                    if (useCanonical) {
                                        val canonicalId = canonicalMetadata?.albumBrowseId
                                        if (!canonicalId.isNullOrBlank()) {
                                            playerBottomSheetState.collapse(tween(durationMillis = 120))
                                            navController.navigate("album/$canonicalId")
                                            onDismiss()
                                        } else {
                                            Toast.makeText(context, "Sto identificando l'album corretto…", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        val albumId = navigationAlbumId
                                        if (albumId.isNullOrBlank()) {
                                            Toast.makeText(context, R.string.album_unavailable, Toast.LENGTH_SHORT).show()
                                        } else {
                                            playerBottomSheetState.collapse(tween(durationMillis = 120))
                                            if (navigationAlbumIsPodcast) {
                                                navController.navigate("online_podcast/$albumId")
                                            } else {
                                                navController.navigate("album/$albumId")
                                            }
                                            onDismiss()
                                        }
                                    }
                                },
                            ),
                        )
'''
s = one(s, old, new, "album item")
p.write_text(s)
print("PlayerMenu LAB11 patch applicata")
