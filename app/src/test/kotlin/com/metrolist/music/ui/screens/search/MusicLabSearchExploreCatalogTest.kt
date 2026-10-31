package com.metrolist.music.ui.screens.search

import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyPlaylistOwner
import org.junit.Assert.*
import org.junit.Test

class MusicLabSearchExploreCatalogTest {
    @Test fun featuredTitlesAreExactlyInRequestedOrder() {
        assertEquals(listOf("Brani preferiti Italia", "Top 50 Italia",
            "Top 50 Globale", "Brani preferiti globali"),
            MusicLabSearchExploreCatalog.featured.map { it.title })
        assertEquals("37i9dQZEVXbIQnj7RRhdSX",
            MusicLabSearchExploreCatalog.featured[1].confirmedSpotifyId)
        assertEquals("37i9dQZEVXbMDoHDwVN2tF",
            MusicLabSearchExploreCatalog.featured[2].confirmedSpotifyId)
    }

    @Test fun everyRequestedScreenshotCountryIsPresentOnce() {
        val entries = MusicLabSearchExploreCatalog.countries
        assertTrue(entries.size >= 65)
        assertEquals(entries.size, entries.map { it.title }.distinct().size)
        assertTrue(entries.any { it.title == "Top 50 Vietnam" })
        assertTrue(entries.any { it.title == "Top 50 Italia" })
        assertTrue(entries.any { it.title == "Top 50 Emirati Arabi Uniti" })
        assertTrue(entries.any { it.title == "Top 50 Sudafrica" })
        assertTrue(entries.any { it.title == "Top 50 Hong Kong" })
    }

    @Test fun onlyVerifiedOfficialSpotifyAccountAndExactTitleCanResolve() {
        val chart = MusicLabSearchExploreCatalog.countries.first { it.title == "Top 50 Francia" }
        val spoof = SpotifyPlaylist(id = "1234567890123456789012", name = chart.spotifySearch,
            owner = SpotifyPlaylistOwner(id = "pretender", displayName = "Spotify"))
        val officialWrong = SpotifyPlaylist(id = "1234567890123456789013", name = "Top 50 - Italy",
            owner = SpotifyPlaylistOwner(id = "spotify", displayName = "Spotify"))
        val official = SpotifyPlaylist(id = "1234567890123456789014", name = chart.spotifySearch,
            owner = SpotifyPlaylistOwner(id = "spotify", displayName = "Spotify"))
        assertNull(MusicLabSearchExploreCatalog.officialMatch(listOf(spoof, officialWrong), chart))
        assertEquals(official,
            MusicLabSearchExploreCatalog.officialMatch(listOf(spoof, officialWrong, official), chart))
    }

    @Test fun regionalChartsMapToRealIsoAndNeverSwapGlobalOrUs() {
        val charts = MusicLabSearchExploreCatalog
        fun find(title: String) = (charts.featured + charts.countries).first { it.title == title }
        assertEquals("IT", charts.countryCode(find("Top 50 Italia")))
        assertEquals("FR", charts.countryCode(find("Top 50 Francia")))
        assertEquals("US", charts.countryCode(find("Top 50 Stati Uniti")))
        assertNull(charts.countryCode(find("Top 50 Globale")))
        assertEquals("Italy", charts.lastFmCountry(find("Brani preferiti Italia")))
        assertNull(charts.lastFmCountry(find("Brani preferiti globali")))
        assertEquals(72, charts.countries.size)
    }

    @Test fun genresAreDistinctAndSearchableNotFakePlaylists() {
        val genres = MusicLabSearchExploreCatalog.genres
        assertTrue(genres.size >= 10)
        assertEquals(genres.size, genres.map { it.name }.distinct().size)
        assertTrue(genres.all { it.search.isNotBlank() })
    }
}
