/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * 
 * Snap utilities for Thumbnail grid navigation
 * Copyright (C) OuterTune Project - Custom SnapLayoutInfoProvider idea belongs to OuterTune
 */

package com.metrolist.music.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.util.fastForEach
import kotlin.math.abs

/**
 * Custom SnapLayoutInfoProvider for horizontal grid snapping behavior.
 * Provides smooth snapping to items based on velocity and position.
 *
 * @param lazyGridState The state of the LazyHorizontalGrid
 * @param positionInLayout Function to calculate the desired snap position
 * @param velocityThreshold Minimum velocity required to trigger directional snap
 * @param distanceThresholdFraction Fraction of one item that must be dragged before a low-velocity gesture commits to the adjacent item
 */
@ExperimentalFoundationApi
fun ThumbnailSnapLayoutInfoProvider(
    lazyGridState: LazyGridState,
    positionInLayout: (layoutSize: Float, itemSize: Float) -> Float = { layoutSize, itemSize ->
        (layoutSize / 2f - itemSize / 2f)
    },
    velocityThreshold: Float = 1000f,
    distanceThresholdFraction: Float = 0.5f,
): SnapLayoutInfoProvider = object : SnapLayoutInfoProvider {
    private val layoutInfo: LazyGridLayoutInfo
        get() = lazyGridState.layoutInfo

    override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = 0f
    
    override fun calculateSnapOffset(velocity: Float): Float {
        val bounds = calculateSnappingOffsetBounds()
        val lower = bounds.start
        val upper = bounds.endInclusive

        // At a queue boundary there may be only one finite snap candidate.
        if (!lower.isFinite() || !upper.isFinite()) {
            return listOf(lower, upper)
                .filter { it.isFinite() }
                .minByOrNull { abs(it) }
                ?: 0f
        }

        // A real fling keeps the original directional behavior.
        if (abs(velocity) >= velocityThreshold) {
            return when {
                velocity < 0 -> lower
                velocity > 0 -> upper
                else -> 0f
            }
        }

        // For a slower drag, do not require crossing the 50% midpoint. The nearest
        // bound is the item we started from; once it has moved by the configured
        // fraction of an item, finish the gesture toward the adjacent item instead.
        val nearestIsLower = abs(lower) <= abs(upper)
        val nearest = if (nearestIsLower) lower else upper
        val adjacent = if (nearestIsLower) upper else lower
        val itemDistance = abs(upper - lower)
        val threshold = itemDistance * distanceThresholdFraction.coerceIn(0f, 0.5f)

        return if (abs(nearest) >= threshold) adjacent else nearest
    }

    private fun calculateSnappingOffsetBounds(): ClosedFloatingPointRange<Float> {
        var lowerBoundOffset = Float.NEGATIVE_INFINITY
        var upperBoundOffset = Float.POSITIVE_INFINITY

        layoutInfo.visibleItemsInfo.fastForEach { item ->
            val offset = calculateDistanceToDesiredSnapPosition(layoutInfo, item, positionInLayout)

            // Find item that is closest to the center
            if (offset <= 0 && offset > lowerBoundOffset) {
                lowerBoundOffset = offset
            }

            // Find item that is closest to center, but after it
            if (offset >= 0 && offset < upperBoundOffset) {
                upperBoundOffset = offset
            }
        }

        return lowerBoundOffset.rangeTo(upperBoundOffset)
    }
}

/**
 * Calculates the distance from an item's current position to its desired snap position.
 *
 * @param layoutInfo The layout information of the grid
 * @param item The item to calculate distance for
 * @param positionInLayout Function to determine the desired position
 * @return The distance in pixels to the desired snap position
 */
fun calculateDistanceToDesiredSnapPosition(
    layoutInfo: LazyGridLayoutInfo,
    item: LazyGridItemInfo,
    positionInLayout: (layoutSize: Float, itemSize: Float) -> Float,
): Float {
    val containerSize =
        layoutInfo.singleAxisViewportSize - layoutInfo.beforeContentPadding - layoutInfo.afterContentPadding

    val desiredDistance = positionInLayout(containerSize.toFloat(), item.size.width.toFloat())
    val itemCurrentPosition = item.offset.x.toFloat()

    return itemCurrentPosition - desiredDistance
}

/**
 * Extension property to get the viewport size along the scroll axis.
 */
val LazyGridLayoutInfo.singleAxisViewportSize: Int
    get() = if (orientation == Orientation.Vertical) viewportSize.height else viewportSize.width
