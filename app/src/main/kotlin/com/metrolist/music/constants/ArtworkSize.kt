/**
 * MusicLab global artwork sizing
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.constants

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey

val ArtworkSizeKey = stringPreferencesKey("artworkSize")

enum class ArtworkSize(
    val listItemHeight: Dp,
    val listThumbnailSize: Dp,
    val smallGridThumbnailHeight: Dp,
    val gridThumbnailHeight: Dp,
) {
    // LAB26: every step is larger than the previous working implementation.
    SMALL(64.dp, 48.dp, 104.dp, 124.dp),
    MEDIUM(76.dp, 60.dp, 124.dp, 152.dp),
    LARGE(88.dp, 72.dp, 148.dp, 184.dp),
    VERY_LARGE(104.dp, 88.dp, 176.dp, 224.dp),
}

/**
 * Shared runtime state for legacy dimension getters. Unlike the old volatile
 * field, this is observable Compose state, so list/search artwork reacts to a
 * setting change without restarting the app.
 */
object ArtworkSizeRuntime {
    var current by mutableStateOf(ArtworkSize.MEDIUM)
}

/** Current Mother/LAB25 visual baseline used by protected surfaces. */
val ProtectedArtworkHeight: Dp = 128.dp
val ProtectedListThumbnailSize: Dp = 48.dp
val ProtectedListItemHeight: Dp = 64.dp
