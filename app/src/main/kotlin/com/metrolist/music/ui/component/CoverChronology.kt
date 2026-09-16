/**
 * MusicLab cover chronology helpers
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

/**
 * Groups values by year from newest to oldest, keeping unknown years at the end.
 * The helper is UI-independent so the chronology contract can be unit-tested.
 */
internal fun <T> groupCoverResultsNewestFirst(
    values: List<T>,
    yearOf: (T) -> Int?,
): List<Pair<Int?, List<T>>> =
    values
        .groupBy(yearOf)
        .entries
        .sortedByDescending { it.key ?: Int.MIN_VALUE }
        .map { it.key to it.value }
