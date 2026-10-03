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
        ArtworkSize.SMALL -> 64.dp
        ArtworkSize.MEDIUM -> 72.dp
        ArtworkSize.LARGE -> 84.dp
        ArtworkSize.VERY_LARGE -> 96.dp
    }

val SuggestionItemHeight = 56.dp
val SearchFilterHeight = 48.dp

val ListThumbnailSize: Dp
    get() = when (ArtworkSizeRuntime.current) {
        ArtworkSize.SMALL -> 48.dp
        ArtworkSize.MEDIUM -> 56.dp
        ArtworkSize.LARGE -> 68.dp
        ArtworkSize.VERY_LARGE -> 80.dp
    }

val SmallGridThumbnailHeight: Dp
    get() = when (ArtworkSizeRuntime.current) {
        ArtworkSize.SMALL -> 108.dp
        ArtworkSize.MEDIUM -> 124.dp
        ArtworkSize.LARGE -> 148.dp
        ArtworkSize.VERY_LARGE -> 176.dp
    }

val GridThumbnailHeight: Dp
    get() = when (ArtworkSizeRuntime.current) {
        ArtworkSize.SMALL -> 124.dp
        ArtworkSize.MEDIUM -> 152.dp
        ArtworkSize.LARGE -> 184.dp
        ArtworkSize.VERY_LARGE -> 224.dp
    }

// Album artwork is deliberately independent from the global poster-size selector.
val AlbumThumbnailSize: Dp = 144.dp
val AlbumGridThumbnailHeight: Dp = 128.dp

// Home keeps the approved LAB25 density even when the global poster scale changes.
val HomeGridThumbnailHeight: Dp = 128.dp
val HomeSmallGridThumbnailHeight: Dp = 104.dp

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
