/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.constants

import androidx.compose.animation.core.Spring
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp

const val CONTENT_TYPE_HEADER = 0
const val CONTENT_TYPE_LIST = 1
const val CONTENT_TYPE_SONG = 2
const val CONTENT_TYPE_ARTIST = 3
const val CONTENT_TYPE_ALBUM = 4
const val CONTENT_TYPE_PLAYLIST = 5

val NavigationBarHeight = 80.dp
val SlimNavBarHeight = 64.dp
val MiniPlayerHeight = 64.dp
val MinMiniPlayerHeight = 16.dp
val MiniPlayerBottomSpacing = 8.dp // Space between MiniPlayer and NavigationBar
val QueuePeekHeight = 64.dp
val AppBarHeight = 64.dp

val ListItemHeight: Dp
    get() = when (ArtworkSizeRuntime.current) {
        ArtworkSize.SMALL -> 60.dp
        ArtworkSize.MEDIUM -> 68.dp
        ArtworkSize.LARGE -> 80.dp
        ArtworkSize.VERY_LARGE -> 92.dp
    }

val SuggestionItemHeight = 56.dp
val SearchFilterHeight = 48.dp

val ListThumbnailSize: Dp
    get() = when (ArtworkSizeRuntime.current) {
        ArtworkSize.SMALL -> 44.dp
        ArtworkSize.MEDIUM -> 52.dp
        ArtworkSize.LARGE -> 64.dp
        ArtworkSize.VERY_LARGE -> 76.dp
    }

val SmallGridThumbnailHeight: Dp
    get() = when (ArtworkSizeRuntime.current) {
        ArtworkSize.SMALL -> 108.dp
        ArtworkSize.MEDIUM -> 128.dp
        ArtworkSize.LARGE -> 152.dp
        ArtworkSize.VERY_LARGE -> 184.dp
    }

val GridThumbnailHeight: Dp
    get() = when (ArtworkSizeRuntime.current) {
        ArtworkSize.SMALL -> 124.dp
        ArtworkSize.MEDIUM -> 152.dp
        ArtworkSize.LARGE -> 184.dp
        ArtworkSize.VERY_LARGE -> 224.dp
    }

val HomeGridThumbnailHeight = 128.dp
val HomeListThumbnailSize = 48.dp
val AlbumGridThumbnailHeight = 128.dp

val AlbumThumbnailSize = 144.dp

val ThumbnailCornerRadius = 3.dp

val PlayerHorizontalPadding = 32.dp

val NavigationBarAnimationSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow
)

val BottomSheetAnimationSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow
)

val BottomSheetSoftAnimationSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow
)
