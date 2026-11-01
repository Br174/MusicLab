package com.metrolist.music.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real, complete HitParadeItalia weekly HTML snapshots.
 * These are not synthetic examples: the regression is gated against actual
 * historical pages, with exact top track and artist, and expected list size.
 */
class MusicLabHistoricalHitParadeLiveFixtureTest {
    private fun verify(year: String, minimum: Int, title: String, artist: String) {
        val path = "hitparade/live-$year.html"
        val input = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing real-world fixture $path"
        }
        val html = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val result = MusicLabHistoricalHitParade.parseSongs(html)
        assertTrue("$year parsed only ${result.size} real tracks; expected at least $minimum",
            result.size >= minimum)
        assertEquals("$year first rank", 1, result.first().rank)
        assertEquals("$year top song", title, result.first().title)
        assertEquals("$year top performer", artist, result.first().artist)
        assertTrue("$year no numeric artist aliases", result.take(10).all {
            it.artist.any(Char::isLetter) && it.title.any(Char::isLetter)
        })
    }


    private fun verifyIndex(year: Int, minimum: Int, expectedSuffix: String) {
        val path = "hitparade/index-$year.html"
        val input = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing authentic weekly index $path"
        }
        val html = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val weeks = MusicLabHistoricalHitParade.weeksFromIndex(html, year)
        assertTrue("$year archive resolved only ${weeks.size} weeks", weeks.size >= minimum)
        assertTrue("$year published week link missing: $expectedSuffix",
            weeks.any { it.url.endsWith(expectedSuffix) })
        assertEquals("$year week dates not in chronological order",
            weeks.map { it.millis }.sorted(), weeks.map { it.millis })
    }

    @Test fun actualIndex1959() = verifyIndex(1959, 50, "hps19590801.html")
    @Test fun actualIndex1984() = verifyIndex(1984, 50, "hp840804.htm")
    @Test fun actualIndex1995() = verifyIndex(1995, 50, "hp950930.htm")
    @Test fun actualIndex2017() = verifyIndex(2017, 48, "hp170805.htm")
    @Test fun actualIndex2026() = verifyIndex(2026, 40, "hps20260801.html")

    @Test fun actual1959Top50() = verify("1959", 40, "Arrivederci", "Marino Barreto jr")
    @Test fun actual1980Top50() = verify("1980", 40, "Luna", "Gianni Togni")
    @Test fun preserveActual1981Top10() = verify("1981", 8, "Enola Gay", "OMD")
    @Test fun preserveActual1984Top10() = verify("1984", 8, "Self control", "Raf")
    @Test fun preserveActual1995Top20() = verify("1995", 18, "Hideaway", "De'Lacy")
    @Test fun actual2017Top200() = verify("2017", 180, "Voglio ballare con te", "Baby K feat. Andres Dvicio")
    @Test fun actual2020Top200() = verify("2020", 180, "Karaoke", "Boomdabash & Alessandra Amoroso")
    @Test fun actual2026Top200() = verify("2026", 180, "Dai dai", "Shakira & Burna Boy")
}
