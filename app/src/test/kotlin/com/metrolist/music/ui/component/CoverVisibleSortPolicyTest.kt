package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverVisibleSortPolicyTest {
    private fun row(
        id: String,
        year: Int? = null,
        date: String? = null,
        points: Int = 7,
        percent: Int? = null,
    ) = DiscogsVersionSeed(
        trackTitle = "Acqua azzurra, acqua chiara",
        artist = id,
        releaseTitle = "Versione $id",
        releaseId = 0,
        masterId = null,
        year = year,
        releaseDate = date,
        kind = DiscogsVersionKind.STUDIO,
        country = null,
        formats = emptyList(),
        formatDescriptions = emptyList(),
        labels = emptyList(),
        coverUrl = null,
        durationSeconds = null,
        fingerprint = id,
        confidenceScore = points,
        musiclabScore100 = percent,
    )

    @Test fun oldestUsesRealReleaseDatesAndKeepsUnknownAtEnd() {
        val unknown = row("unknown")
        val newest = row("2020", year = 2020, date = "2020-05-21")
        val oldest = row("1969", year = 1969, date = "1969-09-10")
        val yearOnly = row("1970", year = 1970)
        val source = listOf(unknown, newest, yearOnly, oldest)
        assertEquals(
            listOf("1969", "1970", "2020", "unknown"),
            CoverVisibleSortPolicy.sort(source, "OLDEST").map { it.fingerprint },
        )
        assertEquals(source, listOf(unknown, newest, yearOnly, oldest))
    }

    @Test fun newestUsesPublicationDateButUnknownStillGoesLast() {
        val unknown = row("undated")
        val old = row("old", date = "1980-01-01")
        val recent = row("recent", date = "2019-11")
        val sameYear = row("january", year = 2019, date = "2019-01-01")
        assertEquals(
            listOf("recent", "january", "old", "undated"),
            CoverVisibleSortPolicy.sort(listOf(unknown, old, recent, sameYear), "NEWEST")
                .map { it.fingerprint },
        )
    }

    @Test fun pointsUsesShownPercentThenConfidenceAndKeepsTiesStable() {
        val low = row("low", points = 2, percent = 20)
        val high = row("high", points = 6, percent = 85)
        val mid = row("mid", points = 12)
        val equal = row("equal", points = 12)
        assertEquals(
            listOf("high", "mid", "equal", "low"),
            CoverVisibleSortPolicy.sort(listOf(low, high, mid, equal), "RELEVANCE")
                .map { it.fingerprint },
        )
    }

    @Test fun unverifiedAmateurKeepsUnknownYearInsteadOfInventingUploadYear() {
        val amateur = row("home-video")
        val documented = CoverVisibleYearResolver.applyDate(amateur, 1971, "YouTube Music")
        assertEquals(1971, documented.year)
        assertEquals("1971", documented.releaseDate)
        assertEquals(listOf("Discogs", "YouTube Music"), documented.sourceNames)
        assertNull(amateur.year)
        assertNull(amateur.releaseDate)
    }

    @Test fun youtubeOnlyUploadYearCannotClaimToBeAnOlderMusicalRelease() {
        val amateur = row("amateur", year = 1960).copy(sourceNames = listOf("YouTube"))
        val documented = row("documented", year = 1970)
        assertEquals(
            listOf("documented", "amateur"),
            CoverVisibleSortPolicy.sort(listOf(amateur, documented), "OLDEST")
                .map { it.fingerprint },
        )
    }

    @Test fun existingPublicationYearIsNeverOverwritten() {
        val studio = row("studio", year = 1970, date = "1970-05-17")
        assertEquals(studio, CoverVisibleYearResolver.applyDate(studio, 2024, "YouTube Music"))
    }
}
