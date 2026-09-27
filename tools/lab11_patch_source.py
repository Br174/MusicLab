from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: atteso 1 match, trovati {count}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# SongMenu: resolver canonico globale, mai sul percorso critico del Play.
# -----------------------------------------------------------------------------
p = Path("app/src/main/kotlin/com/metrolist/music/ui/menu/SongMenu.kt")
s = p.read_text()

s = replace_once(
    s,
    "import com.metrolist.music.extensions.toMediaItem\n",
    "import com.metrolist.music.extensions.toMediaItem\n"
    "import com.metrolist.music.intelligence.CanonicalMusicMetadata\n"
    "import com.metrolist.music.intelligence.MusicIntelligenceClient\n"
    "import com.metrolist.music.intelligence.MusicIntelligenceSettings\n",
    "SongMenu imports",
)

s = replace_once(
    s,
    "    val qobuzEnabled by rememberPreference(EnableQobuzKey, defaultValue = false)\n\n",
    "    val qobuzEnabled by rememberPreference(EnableQobuzKey, defaultValue = false)\n\n"
    "    // LAB11: identità musicale canonica caricata solo quando il menu è aperto.\n"
    "    // Non partecipa mai alla preparazione o all'avvio dello stream audio/video.\n"
    "    val musicIntelligenceSettings = MusicIntelligenceSettings.from(context)\n"
    "    val canonicalMetadata by produceState<CanonicalMusicMetadata?>(\n"
    "        initialValue = MusicIntelligenceClient.cached(song.id),\n"
    "        key1 = song.id,\n"
    "        key2 = song.song.title,\n"
    "        key3 = song.artists.firstOrNull()?.name,\n"
    "    ) {\n"
    "        if (\n"
    "            musicIntelligenceSettings.enabled &&\n"
    "            musicIntelligenceSettings.backgroundMetadata &&\n"
    "            !song.song.isEpisode\n"
    "        ) {\n"
    "            value = withContext(Dispatchers.IO) {\n"
    "                MusicIntelligenceClient.resolve(\n"
    "                    context = context,\n"
    "                    playbackId = song.id,\n"
    "                    title = song.song.title,\n"
    "                    artist = song.artists.firstOrNull()?.name.orEmpty(),\n"
    "                    album = song.song.albumName,\n"
    "                )\n"
    "            }\n"
    "        }\n"
    "    }\n\n",
    "SongMenu canonical state",
)

old_artist = '''                        // Don't show "View Artist" for podcast episodes
                        if (!song.song.isEpisode) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.view_artist)) },
                                    description = { Text(text = song.artists.joinToString { it.name }) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.artist),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        if (song.artists.size == 1) {
                                            navController.navigate("artist/${song.artists[0].id}")
                                            onDismiss()
                                        } else {
                                            showSelectArtistDialog = true
                                        }
                                    },
                                ),
                            )
                        }
'''
new_artist = '''                        // LAB11: se il resolver AI è attivo, "Vai all'artista" usa esclusivamente
                        // l'identità canonica AI + il browse ID tecnico risolto successivamente.
                        if (!song.song.isEpisode) {
                            val useCanonicalArtist = musicIntelligenceSettings.enabled && musicIntelligenceSettings.artistResolver
                            val canonicalArtistName = canonicalMetadata?.artist
                            val canonicalArtistId = canonicalMetadata?.artistBrowseId
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.view_artist)) },
                                    description = {
                                        Text(
                                            text = canonicalArtistName
                                                ?: song.artists.joinToString { it.name },
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.artist),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        if (useCanonicalArtist) {
                                            if (!canonicalArtistId.isNullOrBlank()) {
                                                navController.navigate("artist/$canonicalArtistId")
                                                onDismiss()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "Sto identificando la pagina corretta dell'artista…",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        } else if (song.artists.size == 1) {
                                            navController.navigate("artist/${song.artists[0].id}")
                                            onDismiss()
                                        } else {
                                            showSelectArtistDialog = true
                                        }
                                    },
                                ),
                            )
                        }
'''
s = replace_once(s, old_artist, new_artist, "SongMenu view artist")

old_album = '''                        if (song.song.albumId != null) {
                            // Show "View Podcast" for episodes, "View Album" for songs
                            val isPodcast = song.song.isEpisode
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(if (isPodcast) R.string.view_podcast else R.string.view_album)) },
                                    description = {
                                        song.song.albumName?.let {
                                            Text(text = it)
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            painter = painterResource(if (isPodcast) R.drawable.mic else R.drawable.album),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        onDismiss()
                                        if (isPodcast) {
                                            navController.navigate("online_podcast/${song.song.albumId}")
                                        } else {
                                            navController.navigate("album/${song.song.albumId}")
                                        }
                                    },
                                ),
                            )
                        }
'''
new_album = '''                        val useCanonicalAlbum =
                            !song.song.isEpisode &&
                                musicIntelligenceSettings.enabled &&
                                musicIntelligenceSettings.albumResolver
                        if (
                            song.song.albumId != null ||
                            (useCanonicalAlbum && !canonicalMetadata?.album.isNullOrBlank())
                        ) {
                            // I podcast conservano la navigazione classica. Per i brani il motore AI,
                            // quando attivo, impedisce di aprire per errore playlist/canali dell'uploader.
                            val isPodcast = song.song.isEpisode
                            val canonicalAlbumName = canonicalMetadata?.album
                            val canonicalAlbumId = canonicalMetadata?.albumBrowseId
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(if (isPodcast) R.string.view_podcast else R.string.view_album)) },
                                    description = {
                                        (canonicalAlbumName ?: song.song.albumName)?.let {
                                            Text(text = it)
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            painter = painterResource(if (isPodcast) R.drawable.mic else R.drawable.album),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        if (isPodcast) {
                                            onDismiss()
                                            navController.navigate("online_podcast/${song.song.albumId}")
                                        } else if (useCanonicalAlbum) {
                                            if (!canonicalAlbumId.isNullOrBlank()) {
                                                onDismiss()
                                                navController.navigate("album/$canonicalAlbumId")
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "Sto identificando l'album corretto…",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        } else {
                                            onDismiss()
                                            navController.navigate("album/${song.song.albumId}")
                                        }
                                    },
                                ),
                            )
                        }
'''
s = replace_once(s, old_album, new_album, "SongMenu view album")
p.write_text(s)


# -----------------------------------------------------------------------------
# Originali: il master switch e lo switch dedicato devono spegnere le chiamate AI.
# -----------------------------------------------------------------------------
p = Path("app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionScreen.kt")
s = p.read_text()

s = replace_once(
    s,
    "import com.metrolist.music.constants.AiProviderKey\n",
    "import com.metrolist.music.constants.AiProviderKey\n"
    "import com.metrolist.music.constants.MusicAiEngineEnabledKey\n"
    "import com.metrolist.music.constants.MusicAiOriginalsEnabledKey\n",
    "Originali imports",
)

s = replace_once(
    s,
    "    val service = connection?.service\n\n    val aiProvider by rememberPreference(AiProviderKey, \"OpenRouter\")\n",
    "    val service = connection?.service\n\n"
    "    val aiMasterEnabled by rememberPreference(MusicAiEngineEnabledKey, true)\n"
    "    val originalsAiEnabled by rememberPreference(MusicAiOriginalsEnabledKey, true)\n\n"
    "    val aiProvider by rememberPreference(AiProviderKey, \"OpenRouter\")\n",
    "Originali switches",
)

s = replace_once(
    s,
    "        effectiveKey,\n        effectiveModel,\n    ) {\n        loading = true\n",
    "        effectiveKey,\n        effectiveModel,\n        aiMasterEnabled,\n        originalsAiEnabled,\n    ) {\n"
    "        if (!aiMasterEnabled || !originalsAiEnabled) {\n"
    "            loading = false\n"
    "            backgroundLoading = false\n"
    "            creditsLoading = false\n"
    "            searchResult = OriginalVersionSearchResult(null, emptyList())\n"
    "            return@LaunchedEffect\n"
    "        }\n\n"
    "        loading = true\n",
    "Originali launch guard",
)

p.write_text(s)
print("LAB11 source patch applicata")
