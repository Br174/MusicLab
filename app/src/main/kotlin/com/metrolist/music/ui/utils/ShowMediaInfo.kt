/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.MediaInfo
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.intelligence.CanonicalMusicMetadata
import com.metrolist.music.intelligence.MusicIntelligenceClient
import com.metrolist.music.intelligence.MusicIntelligenceSettings
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.component.shimmer.TextPlaceholder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ShowMediaInfo(videoId: String) {
    if (videoId.isBlank()) return

    val windowInsets = WindowInsets.systemBars
    var info by remember { mutableStateOf<MediaInfo?>(null) }
    val database = LocalDatabase.current
    var song by remember { mutableStateOf<Song?>(null) }
    var currentFormat by remember { mutableStateOf<FormatEntity?>(null) }
    val playerConnection = LocalPlayerConnection.current
    val context = LocalContext.current

    LaunchedEffect(Unit, videoId) {
        info = YouTube.getMediaInfo(videoId).getOrNull()
    }
    LaunchedEffect(Unit, videoId) {
        database.song(videoId).collect { song = it }
    }
    LaunchedEffect(Unit, videoId) {
        database.format(videoId).collect { currentFormat = it }
    }

    val canonical by produceState<CanonicalMusicMetadata?>(
        initialValue = MusicIntelligenceClient.cached(videoId),
        key1 = videoId,
        key2 = song?.title,
    ) {
        val currentSong = song ?: return@produceState
        val settings = MusicIntelligenceSettings.from(context)
        if (!settings.enabled || !settings.backgroundMetadata) return@produceState
        value = withContext(Dispatchers.IO) {
            MusicIntelligenceClient.resolve(
                context = context,
                playbackId = videoId,
                title = currentSong.title,
                artist = currentSong.artists.firstOrNull()?.name.orEmpty(),
                album = currentSong.album?.title,
            )
        }
    }

    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier
            .padding(windowInsets.asPaddingValues())
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (info != null && song != null) {
            item(contentType = "MediaDetails") {
                Column {
                    val resolvedTitle = canonical?.title ?: song?.title
                    val resolvedArtist = canonical?.artist ?: song?.artists?.joinToString { it.name }
                    val baseList = listOf(
                        stringResource(R.string.song_title) to resolvedTitle,
                        stringResource(R.string.song_artists) to resolvedArtist,
                        stringResource(R.string.media_id) to song?.id
                    )

                    val baseIconsList = listOf(
                        R.drawable.music_note,
                        R.drawable.person,
                        R.drawable.media3_icon_bookmark_filled,
                    )

                    val iconsList = listOf(
                        R.drawable.media3_icon_feed,
                        R.drawable.media3_icon_thumb_up_unfilled,
                        R.drawable.media3_icon_thumb_down_unfilled,
                        R.drawable.key,
                        R.drawable.info,
                        R.drawable.radio,
                        R.drawable.gradient,
                        R.drawable.contrast,
                        R.drawable.volume_up,
                        R.drawable.volume_mute,
                        R.drawable.content_copy
                    )

                    val extendedList = if (currentFormat != null) {
                        listOf(
                            stringResource(R.string.views) to info?.viewCount?.let(::numberFormatter).orEmpty(),
                            stringResource(R.string.likes) to info?.like?.let(::numberFormatter).orEmpty(),
                            stringResource(R.string.dislikes) to info?.dislike?.let(::numberFormatter).orEmpty(),
                            "Itag" to currentFormat?.itag?.toString(),
                            stringResource(R.string.mime_type) to currentFormat?.mimeType,
                            stringResource(R.string.codecs) to currentFormat?.codecs,
                            stringResource(R.string.bitrate) to currentFormat?.bitrate?.let { "${it / 1000} Kbps" },
                            stringResource(R.string.sample_rate) to currentFormat?.sampleRate?.let { "$it Hz" },
                            stringResource(R.string.loudness) to currentFormat?.loudnessDb?.let { "$it dB" },
                            stringResource(R.string.volume) to if (playerConnection != null) "${(playerConnection.player.volume * 100).toInt()}%" else null,
                            stringResource(R.string.file_size) to currentFormat?.contentLength?.let {
                                Formatter.formatShortFileSize(context, it)
                            },
                        )
                    } else {
                        emptyList()
                    }

                    val cardsBaseList = mutableListOf<Material3SettingsItem>()
                    val cardsExtendedList = mutableListOf<Material3SettingsItem>()
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                    baseList.forEachIndexed { index, (label, text) ->
                        val displayText = text ?: stringResource(R.string.unknown)
                        cardsBaseList += Material3SettingsItem(
                            title = { Text(label) },
                            description = { Text(displayText) },
                            icon = painterResource(baseIconsList[index]),
                            onClick = {
                                cm.setPrimaryClip(ClipData.newPlainText("text", displayText))
                                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }

                    extendedList.forEachIndexed { index, (label, text) ->
                        val displayText = text ?: stringResource(R.string.unknown)
                        cardsExtendedList += Material3SettingsItem(
                            title = { Text(label) },
                            description = { Text(displayText) },
                            icon = painterResource(iconsList[index]),
                            onClick = {
                                cm.setPrimaryClip(ClipData.newPlainText("text", displayText))
                                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }

                    Material3SettingsGroup(
                        title = stringResource(R.string.general),
                        items = cardsBaseList
                    )

                    val ai = canonical
                    if (ai != null) {
                        Spacer(Modifier.height(8.dp))
                        val aiItems = mutableListOf<Material3SettingsItem>()
                        val aiInfoIcon = painterResource(R.drawable.info)
                        fun addAiItem(title: String, value: String?) {
                            val cleaned = value?.takeIf { it.isNotBlank() } ?: return
                            aiItems += Material3SettingsItem(
                                title = { Text(title) },
                                description = { Text(cleaned) },
                                icon = aiInfoIcon,
                                onClick = {
                                    cm.setPrimaryClip(ClipData.newPlainText("text", cleaned))
                                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                        addAiItem("Album canonico", ai.album)
                        addAiItem("Anno", ai.year?.toString())
                        addAiItem("Tipo di versione", ai.category)
                        addAiItem("Lingua", ai.language)
                        addAiItem("Autori", ai.credits.songwriters.joinToString(", "))
                        addAiItem("Compositori", ai.credits.composers.joinToString(", "))
                        addAiItem("Parolieri", ai.credits.lyricists.joinToString(", "))
                        addAiItem("Produttori", ai.credits.producers.joinToString(", "))
                        addAiItem("Etichetta", ai.credits.label)
                        if (aiItems.isNotEmpty()) {
                            Material3SettingsGroup(
                                title = "Crediti AI canonici",
                                items = aiItems,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Material3SettingsGroup(
                        title = stringResource(R.string.information),
                        items = cardsExtendedList
                    )

                    Spacer(Modifier.height(8.dp))

                    val descriptionText = info?.description ?: stringResource(R.string.unknown)
                    Material3SettingsGroup(
                        title = stringResource(R.string.description),
                        items = listOf(
                            Material3SettingsItem(
                                title = { Text(stringResource(R.string.description)) },
                                description = { Text(descriptionText) },
                                onClick = {
                                    cm.setPrimaryClip(ClipData.newPlainText("text", descriptionText))
                                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                                }
                            )
                        )
                    )
                }
            }
        } else {
            item(contentType = "MediaInfoLoader") {
                ShimmerHost {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(all = 16.dp)
                    ) {
                        TextPlaceholder()
                    }
                }
            }
        }
    }
}
