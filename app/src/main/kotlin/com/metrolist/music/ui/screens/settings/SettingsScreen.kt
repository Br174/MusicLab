/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.ArtworkSize
import com.metrolist.music.constants.ArtworkSizeKey
import com.metrolist.music.constants.MusicAiAlbumResolverEnabledKey
import com.metrolist.music.constants.MusicAiArtistResolverEnabledKey
import com.metrolist.music.constants.MusicAiBackgroundMetadataEnabledKey
import com.metrolist.music.constants.MusicAiCloudMemoryEnabledKey
import com.metrolist.music.constants.MusicAiCoverEnabledKey
import com.metrolist.music.constants.MusicAiCreditsEnabledKey
import com.metrolist.music.constants.MusicAiEngineEnabledKey
import com.metrolist.music.constants.MusicAiForeignEnabledKey
import com.metrolist.music.constants.MusicAiLiveEnabledKey
import com.metrolist.music.constants.MusicAiOriginalsEnabledKey
import com.metrolist.music.constants.MusicAiRemixEnabledKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.ReleaseNotesCard
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.Updater
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    latestVersionName: String,
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val isAndroid12OrLater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val showArtworkSizeDialog = remember { mutableStateOf(false) }
    val (artworkSize, onArtworkSizeChange) = rememberEnumPreference(ArtworkSizeKey, ArtworkSize.MEDIUM)

    val (musicAiEnabled, setMusicAiEnabled) = rememberPreference(MusicAiEngineEnabledKey, true)
    val (musicAiCredits, setMusicAiCredits) = rememberPreference(MusicAiCreditsEnabledKey, true)
    val (musicAiArtist, setMusicAiArtist) = rememberPreference(MusicAiArtistResolverEnabledKey, true)
    val (musicAiAlbum, setMusicAiAlbum) = rememberPreference(MusicAiAlbumResolverEnabledKey, true)
    val (musicAiCover, setMusicAiCover) = rememberPreference(MusicAiCoverEnabledKey, true)
    val (musicAiOriginals, setMusicAiOriginals) = rememberPreference(MusicAiOriginalsEnabledKey, true)
    val (musicAiLive, setMusicAiLive) = rememberPreference(MusicAiLiveEnabledKey, true)
    val (musicAiRemix, setMusicAiRemix) = rememberPreference(MusicAiRemixEnabledKey, true)
    val (musicAiForeign, setMusicAiForeign) = rememberPreference(MusicAiForeignEnabledKey, true)
    val (musicAiMemory, setMusicAiMemory) = rememberPreference(MusicAiCloudMemoryEnabledKey, true)
    val (musicAiBackground, setMusicAiBackground) = rememberPreference(MusicAiBackgroundMetadataEnabledKey, true)

    val hasAndroidAuto = remember {
        try {
            context.packageManager.getPackageInfo(
                "com.google.android.projection.gearhead", 0
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    if (showArtworkSizeDialog.value) {
        AlertDialog(
            onDismissRequest = { showArtworkSizeDialog.value = false },
            title = { Text("Dimensione copertine") },
            text = {
                Column {
                    ArtworkSize.entries.forEach { size ->
                        val label = when (size) {
                            ArtworkSize.SMALL -> "Piccola"
                            ArtworkSize.MEDIUM -> "Media"
                            ArtworkSize.LARGE -> "Grande"
                            ArtworkSize.VERY_LARGE -> "Molto grande"
                        }
                        TextButton(
                            onClick = {
                                onArtworkSizeChange(size)
                                showArtworkSizeDialog.value = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(
                                selected = artworkSize == size,
                                onClick = null,
                            )
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showArtworkSizeDialog.value = false }) {
                    Text("Chiudi")
                }
            },
        )
    }

    val artworkSizeLabel = when (artworkSize) {
        ArtworkSize.SMALL -> "Piccola"
        ArtworkSize.MEDIUM -> "Media"
        ArtworkSize.LARGE -> "Grande"
        ArtworkSize.VERY_LARGE -> "Molto grande"
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )

        Material3SettingsGroup(
            title = stringResource(R.string.settings_section_ui),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.palette),
                    title = { Text(stringResource(R.string.appearance)) },
                    onClick = { navController.navigate("settings/appearance") }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.album),
                    title = { Text("Dimensione copertine") },
                    description = {
                        Text(
                            text = artworkSizeLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { showArtworkSizeDialog.value = true },
                )
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Material3SettingsGroup(
            title = "Motore AI MusicLab",
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.discover_tune),
                    title = { Text("Motore AI MusicLab") },
                    description = { Text(if (musicAiEnabled) "Attivo in tutta l'app" else "Disattivato: MusicLab funziona in modalità classica") },
                    onClick = { setMusicAiEnabled(!musicAiEnabled) },
                    trailingContent = { Switch(checked = musicAiEnabled, onCheckedChange = setMusicAiEnabled) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.info),
                    title = { Text("Crediti AI") },
                    description = { Text("Autori, compositori, parolieri, produttori ed etichetta") },
                    onClick = { setMusicAiCredits(!musicAiCredits) },
                    trailingContent = { Switch(checked = musicAiCredits, onCheckedChange = setMusicAiCredits) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.artist),
                    title = { Text("Artista corretto") },
                    description = { Text("Usa l'identità canonica per Vai all'artista") },
                    onClick = { setMusicAiArtist(!musicAiArtist) },
                    trailingContent = { Switch(checked = musicAiArtist, onCheckedChange = setMusicAiArtist) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.album),
                    title = { Text("Album corretto") },
                    description = { Text("Risolve l'album reale senza usare la playlist dell'uploader") },
                    onClick = { setMusicAiAlbum(!musicAiAlbum) },
                    trailingContent = { Switch(checked = musicAiAlbum, onCheckedChange = setMusicAiAlbum) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.library_music),
                    title = { Text("Cover AI") },
                    onClick = { setMusicAiCover(!musicAiCover) },
                    trailingContent = { Switch(checked = musicAiCover, onCheckedChange = setMusicAiCover) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.music_note),
                    title = { Text("Originali AI") },
                    onClick = { setMusicAiOriginals(!musicAiOriginals) },
                    trailingContent = { Switch(checked = musicAiOriginals, onCheckedChange = setMusicAiOriginals) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.play),
                    title = { Text("Live") },
                    onClick = { setMusicAiLive(!musicAiLive) },
                    trailingContent = { Switch(checked = musicAiLive, onCheckedChange = setMusicAiLive) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.sync),
                    title = { Text("Remix") },
                    onClick = { setMusicAiRemix(!musicAiRemix) },
                    trailingContent = { Switch(checked = musicAiRemix, onCheckedChange = setMusicAiRemix) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.language),
                    title = { Text("Versioni straniere") },
                    onClick = { setMusicAiForeign(!musicAiForeign) },
                    trailingContent = { Switch(checked = musicAiForeign, onCheckedChange = setMusicAiForeign) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.storage),
                    title = { Text("Memoria cloud") },
                    description = { Text("Riusa subito i risultati già conosciuti") },
                    onClick = { setMusicAiMemory(!musicAiMemory) },
                    trailingContent = { Switch(checked = musicAiMemory, onCheckedChange = setMusicAiMemory) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.update),
                    title = { Text("Metadati in background") },
                    description = { Text("Il Play resta indipendente: i metadati arrivano dopo") },
                    onClick = { setMusicAiBackground(!musicAiBackground) },
                    trailingContent = { Switch(checked = musicAiBackground, onCheckedChange = setMusicAiBackground) },
                ),
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.settings_section_player_content),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.play),
                    title = { Text(stringResource(R.string.player_and_audio)) },
                    onClick = { navController.navigate("settings/player") }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.language),
                    title = { Text(stringResource(R.string.content)) },
                    onClick = { navController.navigate("settings/content") }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.translate),
                    title = { Text(stringResource(R.string.ai_lyrics_translation)) },
                    onClick = { navController.navigate("settings/ai") }
                )
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (hasAndroidAuto) {
            Material3SettingsGroup(
                title = "Android Auto",
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.ic_android_auto),
                        title = { Text(stringResource(R.string.android_auto)) },
                        onClick = { navController.navigate("settings/android_auto") }
                    )
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Material3SettingsGroup(
            title = stringResource(R.string.settings_section_privacy),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.security),
                    title = { Text(stringResource(R.string.privacy)) },
                    onClick = { navController.navigate("settings/privacy") }
                )
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.settings_section_storage),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.storage),
                    title = { Text(stringResource(R.string.storage)) },
                    onClick = { navController.navigate("settings/storage") }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.restore),
                    title = { Text(stringResource(R.string.backup_restore)) },
                    onClick = { navController.navigate("settings/backup_restore") }
                )
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.settings_section_system),
            items = buildList {
                if (isAndroid12OrLater) {
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.link),
                            title = { Text(stringResource(R.string.default_links)) },
                            onClick = {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                                        "package:${context.packageName}".toUri()
                                    )
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    when (e) {
                                        is ActivityNotFoundException -> {
                                            Toast.makeText(context, R.string.open_app_settings_error, Toast.LENGTH_LONG).show()
                                        }
                                        is SecurityException -> {
                                            Toast.makeText(context, R.string.open_app_settings_error, Toast.LENGTH_LONG).show()
                                        }
                                        else -> {
                                            Toast.makeText(context, R.string.open_app_settings_error, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        )
                    )
                }
                if (BuildConfig.UPDATER_AVAILABLE) {
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.update),
                            title = { Text(stringResource(R.string.updater)) },
                            onClick = { navController.navigate("settings/updater") }
                        )
                    )
                }
                val showChangelog = com.metrolist.music.LocalChangelogState.current
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.newspaper),
                        title = { Text(stringResource(R.string.changelog)) },
                        onClick = { showChangelog.value = true }
                    )
                )
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.info),
                        title = { Text(stringResource(R.string.about)) },
                        onClick = { navController.navigate("settings/about") }
                    )
                )
                if (BuildConfig.UPDATER_AVAILABLE && latestVersionName != BuildConfig.VERSION_NAME) {
                    val releaseInfo = Updater.getCachedLatestRelease()
                    val downloadUrl = releaseInfo?.let { Updater.getDownloadUrlForCurrentVariant(it) }
                    if (downloadUrl != null) {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.update),
                                title = { Text(text = stringResource(R.string.new_version_available)) },
                                description = {
                                    Text(
                                        text = latestVersionName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                showBadge = true,
                                onClick = { uriHandler.openUri(downloadUrl) }
                            )
                        )
                    }
                }
            }
        )
        if (BuildConfig.UPDATER_AVAILABLE && latestVersionName != BuildConfig.VERSION_NAME) {
            Spacer(modifier = Modifier.height(16.dp))
            ReleaseNotesCard()
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.settings)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null
                )
            }
        }
    )
}
