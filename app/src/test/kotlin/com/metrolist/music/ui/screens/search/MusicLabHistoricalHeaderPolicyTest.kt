package com.metrolist.music.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class MusicLabHistoricalHeaderPolicyTest {
    private fun date1995(): Long = Calendar.getInstance(Locale.ITALIAN).apply {
        set(1995, Calendar.SEPTEMBER, 2, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun weeklySinglesShowsFullItalianDate() {
        assertEquals("2 settembre 1995",
            MusicLabHistoricalHeaderPolicy.date(
                MusicLabHistoricalChartMode.WEEKLY_SINGLES, date1995()))
    }

    @Test fun weeklyAlbumsShowsFullItalianDate() {
        assertEquals("2 settembre 1995",
            MusicLabHistoricalHeaderPolicy.date(
                MusicLabHistoricalChartMode.WEEKLY_ALBUMS, date1995()))
    }

    @Test fun annualSinglesShowsOnlyYear() {
        assertEquals("1995",
            MusicLabHistoricalHeaderPolicy.date(
                MusicLabHistoricalChartMode.ANNUAL_SINGLES, date1995()))
    }

    @Test fun annualAlbumsShowsOnlyYear() {
        assertEquals("1995",
            MusicLabHistoricalHeaderPolicy.date(
                MusicLabHistoricalChartMode.ANNUAL_ALBUMS, date1995()))
    }

    @Test fun labelsDistinguishSinglesAndAlbums() {
        assertEquals("Hit Parade singoli",
            MusicLabHistoricalHeaderPolicy.category(
                MusicLabHistoricalChartMode.WEEKLY_SINGLES))
        assertEquals("Hit Parade singoli",
            MusicLabHistoricalHeaderPolicy.category(
                MusicLabHistoricalChartMode.ANNUAL_SINGLES))
        assertEquals("Hit Parade album",
            MusicLabHistoricalHeaderPolicy.category(
                MusicLabHistoricalChartMode.WEEKLY_ALBUMS))
        assertEquals("Hit Parade album",
            MusicLabHistoricalHeaderPolicy.category(
                MusicLabHistoricalChartMode.ANNUAL_ALBUMS))
    }
}
