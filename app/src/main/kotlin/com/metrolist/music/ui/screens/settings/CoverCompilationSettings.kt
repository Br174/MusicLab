package com.metrolist.music.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.*
import com.metrolist.music.ui.component.Lab46ManualSyncActivity
import com.metrolist.music.ui.component.CompilationNavigationBridge
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.utils.rememberPreference

/** LAB60: native single destination for user-added Cover and Compilation settings.
 * Existing DataStore keys remain identical, so upgrades do not reset switches.
 */
@Composable
fun CoverCompilationSettings(navController: NavController) {
    val context = LocalContext.current
    val (titleTapSearchEnabled, setTitleTapSearchEnabled) = rememberPreference(TitleTapSearchEnabledKey, true)
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
    val (coverArchiveEnabled, setCoverArchiveEnabled) = rememberPreference(MusicLabCoverArchiveEnabledKey, true)
    val (coverCloudEnabled, setCoverCloudEnabled) = rememberPreference(MusicLabCloudEnabledKey, true)

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
            ))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.windowInsetsPadding(
            LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
        ))
        Material3SettingsGroup(
            title = "Cover e Compilation",
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.close),
                    title = { Text("Torna alle Impostazioni") },
                    onClick = { navController.popBackStack() },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.search),
                    title = { Text("Ricerca toccando il titolo") },
                    description = {
                        Text(
                            if (titleTapSearchEnabled) {
                                "Attiva: tocca il titolo di un brano per cercarlo in MusicLab"
                            } else {
                                "Disattiva: il titolo resta testo normale"
                            },
                        )
                    },
                    onClick = { setTitleTapSearchEnabled(!titleTapSearchEnabled) },
                    trailingContent = {
                        Switch(
                            checked = titleTapSearchEnabled,
                            onCheckedChange = setTitleTapSearchEnabled,
                        )
                    },
                )
            ),
        )
        Spacer(Modifier.height(16.dp))
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
                    icon = painterResource(R.drawable.library_music),
                    title = { Text("Archivio Cover") },
                    description = { Text(if (coverArchiveEnabled)
                        "Include l'Archivio Cover nella ricerca"
                        else "Archivio disattivato; dati salvati conservati") },
                    onClick = { setCoverArchiveEnabled(!coverArchiveEnabled) },
                    trailingContent = { Switch(checked = coverArchiveEnabled, onCheckedChange = setCoverArchiveEnabled) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.sync),
                    title = { Text("Cloud MusicLab") },
                    description = { Text(if (coverCloudEnabled)
                        "Cloud abilitato per Cover e Compilation"
                        else "Cloud disabilitato; nessun dato cancellato") },
                    onClick = { setCoverCloudEnabled(!coverCloudEnabled) },
                    trailingContent = { Switch(checked = coverCloudEnabled, onCheckedChange = setCoverCloudEnabled) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.storage),
                    title = { Text("Memoria cloud") },
                    description = { Text("Riusa subito i risultati già conosciuti") },
                    onClick = { setMusicAiMemory(!musicAiMemory) },
                    trailingContent = { Switch(checked = musicAiMemory, onCheckedChange = setMusicAiMemory) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.sync),
                    title = { Text("Sincronizzazione Cloud (LAB46)") },
                    description = { Text("Invia manualmente una decisione alla volta allo staging") },
                    onClick = {
                        context.startActivity(Intent(context, Lab46ManualSyncActivity::class.java))
                    },
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
        Spacer(Modifier.height(16.dp))
        Material3SettingsGroup(
            title = "Compilation",
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.album),
                    title = { Text("Apri Compilation") },
                    description = { Text("Ricerca e riproduzione delle compilation") },
                    onClick = { navController.navigate(CompilationNavigationBridge.ROUTE) },
                ),
            ),
        )
    }
}
