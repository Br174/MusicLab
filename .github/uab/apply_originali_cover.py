from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 occurrence, found {count}")
    return text.replace(old, new, 1)


# 1) Universal YouTube override key
p = Path("app/src/main/kotlin/com/metrolist/music/constants/PreferenceKeys.kt")
t = p.read_text()
anchor = 'val QobuzMatchOverridesKey = stringPreferencesKey("qobuzMatchOverrides")\n'
if "YouTubeMatchOverridesKey" not in t:
    t = replace_once(
        t,
        anchor,
        anchor + 'val YouTubeMatchOverridesKey = stringPreferencesKey("youtubeMatchOverrides")\n',
        "PreferenceKeys",
    )
p.write_text(t)


# 2) Universal YouTube override model
Path("app/src/main/kotlin/com/metrolist/music/playback/YouTubeMatchOverride.kt").write_text(
'''/**
 * Universal manual YouTube source override.
 * Keeps the original MusicLab media id/metadata while resolving audio from another YouTube video.
 */
package com.metrolist.music.playback

import org.json.JSONObject

data class YouTubeMatchOverride(
    val videoId: String,
    val title: String = "",
    val artist: String = "",
    val thumbnail: String? = null,
)

object YouTubeMatchOverrides {
    @Volatile private var cachedRaw: String? = null
    @Volatile private var cachedMap: Map<String, YouTubeMatchOverride> = emptyMap()

    fun decode(value: String?): MutableMap<String, YouTubeMatchOverride> {
        if (value.isNullOrBlank()) return mutableMapOf()
        val snapshot = if (value == cachedRaw) cachedMap else parse(value).also {
            cachedRaw = value
            cachedMap = it
        }
        return LinkedHashMap(snapshot)
    }

    private fun parse(value: String): Map<String, YouTubeMatchOverride> = runCatching {
        val root = JSONObject(value)
        buildMap {
            root.keys().forEach { mediaId ->
                val obj = root.optJSONObject(mediaId) ?: return@forEach
                val videoId = obj.optString("videoId").takeIf { it.isNotBlank() } ?: return@forEach
                put(
                    mediaId,
                    YouTubeMatchOverride(
                        videoId = videoId,
                        title = obj.optString("title"),
                        artist = obj.optString("artist"),
                        thumbnail = obj.optString("thumbnail").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())

    fun encode(overrides: Map<String, YouTubeMatchOverride>): String {
        val root = JSONObject()
        overrides.forEach { (mediaId, value) ->
            root.put(
                mediaId,
                JSONObject()
                    .put("videoId", value.videoId)
                    .put("title", value.title)
                    .put("artist", value.artist)
                    .put("thumbnail", value.thumbnail ?: ""),
            )
        }
        return root.toString()
    }
}
'''
)


# 3) MusicService: persist override + use effective video id for YouTube stream resolution
p = Path("app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt")
t = p.read_text()
import_anchor = "import com.metrolist.music.constants.QobuzTryptEndpointKey\n"
if "import com.metrolist.music.constants.YouTubeMatchOverridesKey" not in t:
    t = replace_once(
        t,
        import_anchor,
        import_anchor + "import com.metrolist.music.constants.YouTubeMatchOverridesKey\n",
        "MusicService import",
    )

methods = '''    /** Return the user-selected YouTube source for a MusicLab media id. */
    suspend fun getYouTubeMatchOverride(mediaId: String): YouTubeMatchOverride? =
        withContext(Dispatchers.IO) {
            YouTubeMatchOverrides.decode(dataStore.get(YouTubeMatchOverridesKey, ""))[mediaId]
        }

    private suspend fun resolveYouTubePlaybackId(mediaId: String): String =
        getYouTubeMatchOverride(mediaId)?.videoId?.takeIf { it.isNotBlank() } ?: mediaId

    /**
     * Store or clear a universal YouTube source override. The queue item and its metadata
     * keep the original media id; only stream resolution changes to the selected video.
     */
    fun setYouTubeMatchOverride(mediaId: String, override: YouTubeMatchOverride?) {
        if (mediaId.isBlank()) return
        scope.launch(Dispatchers.IO) {
            dataStore.edit { prefs ->
                val current = YouTubeMatchOverrides.decode(prefs[YouTubeMatchOverridesKey])
                if (override == null || override.videoId == mediaId) current.remove(mediaId)
                else current[mediaId] = override
                prefs[YouTubeMatchOverridesKey] = YouTubeMatchOverrides.encode(current)
            }
            songUrlCache.remove(mediaId)
            try {
                playerCache.removeResource(mediaId)
                downloadCache.removeResource(mediaId)
            } catch (e: Exception) {
                Timber.tag("MusicService").w(e, "Failed to clear cache after YouTube override for %s", mediaId)
            }
            bypassCacheForQualityChange.add(mediaId)
            withContext(Dispatchers.Main) {
                if (player.currentMediaItem?.mediaId == mediaId) {
                    val index = player.currentMediaItemIndex
                    val wasPlaying = player.playWhenReady
                    player.stop()
                    player.seekTo(index, 0L)
                    player.prepare()
                    if (wasPlaying) player.play()
                }
            }
        }
    }

'''
if "suspend fun getYouTubeMatchOverride" not in t:
    qobuz_anchor = "    suspend fun getQobuzMatchOverride(mediaId: String): QobuzMatchOverride? =\n"
    if qobuz_anchor not in t:
        raise SystemExit("MusicService Qobuz insertion anchor missing")
    t = t.replace(qobuz_anchor, methods + qobuz_anchor, 1)

# Positional calls (precache + main playback)
t, positional_count = re.subn(
    r"(YTPlayerUtils\.playerResponseForPlayback\(\s*)mediaId,",
    r"\1resolveYouTubePlaybackId(mediaId),",
    t,
)
if positional_count < 2:
    raise SystemExit(f"MusicService positional resolver replacements too low: {positional_count}")

# Named call (Cast)
named_old = "videoId = mediaId,\n                            audioQuality = audioQuality,"
named_new = "videoId = resolveYouTubePlaybackId(mediaId),\n                            audioQuality = audioQuality,"
if named_old in t:
    t = t.replace(named_old, named_new, 1)
p.write_text(t)


# 4) Find one result: the oldest verifiable recording available
Path("app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionSearchEngine.kt").write_text(
'''/** MusicLab: find the oldest verifiable recording of a song. */
package com.metrolist.music.ui.component

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal object OriginalVersionSearchEngine {
    suspend fun findOldest(
        title: String,
        currentArtist: String,
        durationSec: Int,
        currentYouTubeId: String,
    ): CoverHubResult? = coroutineScope {
        val coversDeferred = async {
            runCatching {
                CoverHubSearchEngine.searchCovers(
                    title = title,
                    originalArtist = currentArtist,
                    durationSec = durationSec,
                    currentYouTubeId = currentYouTubeId,
                    geminiConfig = null,
                ).results
            }.getOrDefault(emptyList())
        }
        val sameDeferred = async {
            runCatching {
                CoverHubSearchEngine.searchSameName(
                    title = title,
                    currentYouTubeId = currentYouTubeId,
                    geminiConfig = null,
                )
            }.getOrNull()
        }

        val versions = linkedMapOf<String, CoverHubResult>()
        coversDeferred.await().forEach { versions.putIfAbsent(it.song.id, it) }

        var page = sameDeferred.await()
        page?.results.orEmpty().forEach { versions.putIfAbsent(it.song.id, it) }
        var continuation = page?.continuation
        var passes = 0
        while (continuation != null && versions.size < 80 && passes < 4) {
            passes++
            page = runCatching {
                CoverHubSearchEngine.searchSameNameMore(
                    title = title,
                    continuation = continuation!!,
                    currentYouTubeId = currentYouTubeId,
                    existingResults = versions.values.toList(),
                    geminiConfig = null,
                )
            }.getOrNull() ?: break
            page.results.forEach { versions[it.song.id] = it }
            continuation = page.continuation
        }

        val dated = versions.values.mapNotNull { result ->
            val year = result.year ?: runCatching { CoverYearResolver.resolve(result.song) }.getOrNull()
            year?.let { result.copy(year = it) }
        }

        dated.minWithOrNull(
            compareBy<CoverHubResult> { it.year ?: Int.MAX_VALUE }
                .thenByDescending { it.confirmed }
                .thenByDescending { it.score },
        )
    }
}
'''
)


# 5) Navigation bridge
Path("app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionNavigationBridge.kt").write_text(
'''package com.metrolist.music.ui.component

import androidx.navigation.NavHostController
import java.lang.ref.WeakReference

internal data class OriginalVersionRequest(
    val title: String,
    val artist: String,
    val durationSec: Int,
    val currentYouTubeId: String,
)

internal object OriginalVersionNavigationBridge {
    const val ROUTE = "original_version"
    private var navControllerRef: WeakReference<NavHostController>? = null

    @Volatile
    var currentRequest: OriginalVersionRequest? = null
        private set

    fun bind(navController: NavHostController) {
        navControllerRef = WeakReference(navController)
    }

    fun open(request: OriginalVersionRequest): Boolean {
        currentRequest = request
        val navController = navControllerRef?.get() ?: return false
        navController.navigate(ROUTE) { launchSingleTop = true }
        return true
    }
}
'''
)


# 6) Originali screen: one candidate + manual link + preview + replace
Path("app/src/main/kotlin/com/metrolist/music/ui/component/OriginalVersionScreen.kt").write_text(
'''package com.metrolist.music.ui.component

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.YouTubeMatchOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun OriginalVersionScreen(
    request: OriginalVersionRequest,
    navController: NavHostController,
) {
    val connection = LocalPlayerConnection.current
    val service = connection?.service
    var loading by remember(request.currentYouTubeId) { mutableStateOf(true) }
    var autoCandidate by remember(request.currentYouTubeId) { mutableStateOf<CoverHubResult?>(null) }
    var failed by remember(request.currentYouTubeId) { mutableStateOf(false) }
    var link by remember(request.currentYouTubeId) { mutableStateOf("") }
    var manualCandidate by remember(request.currentYouTubeId) { mutableStateOf<SongItem?>(null) }
    var manualLoading by remember(request.currentYouTubeId) { mutableStateOf(false) }
    var manualError by remember(request.currentYouTubeId) { mutableStateOf<String?>(null) }
    var currentOverride by remember(request.currentYouTubeId) { mutableStateOf<YouTubeMatchOverride?>(null) }

    LaunchedEffect(request.currentYouTubeId) {
        currentOverride = service?.getYouTubeMatchOverride(request.currentYouTubeId)
        loading = true
        failed = false
        autoCandidate = runCatching {
            OriginalVersionSearchEngine.findOldest(
                title = request.title,
                currentArtist = request.artist,
                durationSec = request.durationSec,
                currentYouTubeId = request.currentYouTubeId,
            )
        }.onFailure { failed = true }.getOrNull()
        loading = false
    }

    LaunchedEffect(link) {
        val id = extractYouTubeVideoId(link)
        if (id == null) {
            manualCandidate = null
            manualError = null
            manualLoading = false
            return@LaunchedEffect
        }
        manualLoading = true
        manualError = null
        manualCandidate = withContext(Dispatchers.IO) {
            YouTube.queue(listOf(id)).getOrNull()?.firstOrNull()
        }
        if (manualCandidate == null) manualError = "Video non trovato"
        manualLoading = false
    }

    val selectedSong = manualCandidate ?: autoCandidate?.song
    val selectedYear = if (manualCandidate == null) autoCandidate?.year else null

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Originali", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { navController.popBackStack() }) { Text("Chiudi") }
            }

            Text(
                text = "Cerco la versione con la data di pubblicazione più antica disponibile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Incolla link YouTube") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))

            when {
                manualLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                manualError != null -> Text(manualError!!, color = MaterialTheme.colorScheme.error)
                loading && manualCandidate == null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Cerco la prima incisione…")
                    }
                }
                selectedSong != null -> {
                    Text(
                        text = if (manualCandidate != null) "Versione scelta dal link" else "Versione più antica trovata",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                connection?.playNext(selectedSong.toMediaItem())
                                connection?.seekToNext()
                                PlayerBottomSheetBridge.expandSoft()
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        AsyncImage(
                            model = selectedSong.thumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(selectedSong.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                selectedSong.artists.joinToString(", ") { it.name },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            selectedYear?.let {
                                Text("Anno: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Text("Tocca la locandina per ascoltare", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        enabled = selectedSong.id != request.currentYouTubeId,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            service?.setYouTubeMatchOverride(
                                request.currentYouTubeId,
                                YouTubeMatchOverride(
                                    videoId = selectedSong.id,
                                    title = selectedSong.title,
                                    artist = selectedSong.artists.joinToString(", ") { it.name },
                                    thumbnail = selectedSong.thumbnail,
                                ),
                            )
                            navController.popBackStack()
                        },
                    ) { Text("Sostituisci") }
                }
                failed -> Text("Ricerca non riuscita. Puoi comunque incollare un link YouTube.")
                else -> Text("Non ho trovato una versione con una data verificabile. Puoi incollare un link YouTube.")
            }

            if (currentOverride != null) {
                Spacer(Modifier.height(12.dp))
                TextButton(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    onClick = {
                        service?.setYouTubeMatchOverride(request.currentYouTubeId, null)
                        navController.popBackStack()
                    },
                ) { Text("Ripristina versione automatica") }
            }
        }
    }
}

private fun extractYouTubeVideoId(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return trimmed
    return runCatching {
        val uri = Uri.parse(trimmed)
        when {
            uri.host == "youtu.be" -> uri.pathSegments.firstOrNull()?.takeIf { it.length == 11 }
            uri.host?.contains("youtube.com") == true && uri.path == "/watch" -> uri.getQueryParameter("v")?.takeIf { it.length == 11 }
            else -> null
        }
    }.getOrNull()
}
'''
)


# 7) Add route to NavigationBuilder
p = Path("app/src/main/kotlin/com/metrolist/music/ui/screens/NavigationBuilder.kt")
t = p.read_text()
bind_anchor = "    CoverNavigationBridge.bind(navController)\n"
if "OriginalVersionNavigationBridge.bind" not in t:
    t = replace_once(
        t,
        bind_anchor,
        bind_anchor + "    com.metrolist.music.ui.component.OriginalVersionNavigationBridge.bind(navController)\n",
        "Navigation bind",
    )
route_anchor = '''    composable(CoverNavigationBridge.ROUTE) {
        CoverNavigationBridge.currentRequest?.let { request ->
            CoverSearchScreen(
                request = request,
                navController = navController,
            )
        }
    }
'''
route_new = route_anchor + '''
    composable(com.metrolist.music.ui.component.OriginalVersionNavigationBridge.ROUTE) {
        com.metrolist.music.ui.component.OriginalVersionNavigationBridge.currentRequest?.let { request ->
            com.metrolist.music.ui.component.OriginalVersionScreen(
                request = request,
                navController = navController,
            )
        }
    }
'''
if "OriginalVersionNavigationBridge.ROUTE" not in t:
    t = replace_once(t, route_anchor, route_new, "Navigation route")
p.write_text(t)


# 8) Player menu: add two top tiles and remove lower duplicate entries
p = Path("app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt")
t = p.read_text()
copy_action_tail = '''                        NewAction(
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
'''
extra_actions = copy_action_tail + '''                        NewAction(
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
'''
if 'text = "Originali"' not in t:
    t = replace_once(t, copy_action_tail, extra_actions, "PlayerMenu grid actions")

old_spotify_youtube = '''                        if (resolvedSpotifyMatch != null && !qobuzEnabled) {
                            add(
                                Material3MenuItemData(
                                    title = { Text(text = stringResource(R.string.change_youtube_version)) },
                                    description = { Text(text = stringResource(R.string.change_youtube_version_desc)) },
                                    icon = {
                                        Icon(
                                            painter = painterResource(R.drawable.link),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    onClick = {
                                        showYouTubeMatchDialog = true
                                    },
                                ),
                            )
                        }

'''
if old_spotify_youtube in t:
    t = t.replace(old_spotify_youtube, "", 1)

old_cover = '''                        add(
                            Material3MenuItemData(
                                title = { Text(text = stringResource(R.string.find_covers)) },
                                description = { Text(text = stringResource(R.string.find_covers_desc)) },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.link),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                onClick = {
                                    playerBottomSheetState.collapseSoft()
                                    showCoverSearchDialog = true
                                },
                            ),
                        )

'''
if old_cover not in t:
    raise SystemExit("PlayerMenu lower Cover anchor missing")
t = t.replace(old_cover, "", 1)
p.write_text(t)

print("Originali/Cover patch prepared successfully")
