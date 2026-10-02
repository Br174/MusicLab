/**
 * MusicLab global artwork sizing
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.constants

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey

val ArtworkSizeKey = stringPreferencesKey("artworkSize")

enum class ArtworkSize(
    val thumbnailHeight: Dp,
) {
    // LAB26: the entire scale is larger than the old implementation.
    SMALL(124.dp),
    MEDIUM(152.dp),
    LARGE(184.dp),
    VERY_LARGE(224.dp),
}

/**
 * Home and album artwork are intentionally isolated from the global artwork
 * preference. They keep the proven pre-LAB26 grid size.
 */
val ProtectedArtworkHeight: Dp = GridThumbnailHeight
