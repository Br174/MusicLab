/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.constants

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
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
        ArtworkSize.SMALL -> 56.dp
        ArtworkSize.MEDIUM -> 64.dp
        ArtworkSize.LARGE -> 76.dp
        ArtworkSize.VERY_LARGE -> 88.dp
    }

val SuggestionItemHeight = 56.dp
val SearchFilterHeight = 48.dp

val ListThumbnailSize: Dp
    get() = when (ArtworkSizeRuntime.current) {
        ArtworkSize.SMALL -> 40.dp
        ArtworkSize.MEDIUM -> 48.dp
        ArtworkSize.LARGE -> 60.dp
        ArtworkSize.VERY_LARGE -> 72.dp
    }

val SmallGridThumbnailHeight: Dp
    get() = when (ArtworkSizeRuntime.current) {
        ArtworkSize.SMALL -> 88.dp
        ArtworkSize.MEDIUM -> 104.dp
        ArtworkSize.LARGE -> 124.dp
        ArtworkSize.VERY_LARGE -> 144.dp
    }

val GridThumbnailHeight: Dp
    get() = when (ArtworkSizeRuntime.current) {
        ArtworkSize.SMALL -> 108.dp
        ArtworkSize.MEDIUM -> 128.dp
        ArtworkSize.LARGE -> 156.dp
        ArtworkSize.VERY_LARGE -> 184.dp
    }

val AlbumThumbnailSize: Dp
    get() = when (ArtworkSizeRuntime.current) {
        ArtworkSize.SMALL -> 116.dp
        ArtworkSize.MEDIUM -> 144.dp
        ArtworkSize.LARGE -> 176.dp
        ArtworkSize.VERY_LARGE -> 208.dp
    }

val ThumbnailCornerRadius = 3.dp

val PlayerHorizontalPadding = 32.dp

val NavigationBarAnimationSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow
)

val BottomSheetAnimationSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow
)

val BottomSheetSoftAnimationSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow
)
