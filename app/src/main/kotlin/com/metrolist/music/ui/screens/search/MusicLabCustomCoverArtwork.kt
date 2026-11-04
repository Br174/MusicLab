package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.music.R

/**
 * Cover discovery is unchanged. Artwork piggybacks on its existing SongItem
 * thumbnail; supplemental metadata is attempted only for a known album, a
 * truly missing image, or a deliberate long-press request.
 */
@Composable
internal fun MusicLabCustomCoverArtwork(
    title: String,
    artist: String,
    nativeThumbnail: String?,
    albumHint: String?,
    retrySignal: Int,
) {
    val context = LocalContext.current
    val revision by MusicLabLightArtwork.revisions.collectAsStateWithLifecycle()
    var failed by remember(title, artist, nativeThumbnail) { mutableStateOf<String?>(null) }
    val image by produceState(
        initialValue = MusicLabLightArtwork.saved(context, title, artist, false)
            ?: nativeThumbnail,
        key1 = title, key2 = artist,
        key3 = listOf(nativeThumbnail, albumHint, retrySignal, revision, failed),
    ) {
        value = MusicLabLightArtwork.saved(context, title, artist, false)
            ?.takeUnless { it == failed } ?: nativeThumbnail?.takeUnless { it == failed }
        val needsExtra = retrySignal > 0 || value == null ||
            (albumHint?.isNotBlank() == true && MusicLabLightArtwork.isVideoPoster(value))
        if (needsExtra) {
            MusicLabLightArtwork.recover(
                context, title, artist, manual = retrySignal > 0,
            )?.let { value = it }
        }
    }
    AsyncImage(
        model = image,
        contentDescription = "Riproduci",
        contentScale = ContentScale.Crop,
        placeholder = painterResource(R.drawable.music_note),
        error = painterResource(R.drawable.music_note),
        modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)),
        onError = {
            image?.let {
                failed = it
                MusicLabLightArtwork.reject(context, title, artist, false, it)
            }
        },
    )
}
