package com.metrolist.music.ui.component

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.spotify.models.SpotifySimpleAlbum
import com.metrolist.spotify.models.SpotifySimpleArtist
import com.metrolist.spotify.models.SpotifyTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Lab23TitleSearchTest {
    private fun song(title: String, artist: String = "Archive Channel", id: String = title) =
        SongItem(
            id = id,
            title = title,
            artists = listOf(Artist(artist, null)),
            thumbnail = "https://example.invalid/$id.jpg",
        )

    @Test
    fun qualityRankingKeepsExactAndTechnicalVariantsAheadOfSimilarTitles() {
        val exact = TitleMeaningResolver.qualityScore("Il mondo", "Il mondo")
        val decorated = TitleMeaningResolver.qualityScore("Il mondo", "Il mondo (Live)")
        val different = TitleMeaningResolver.qualityScore("Il mondo", "Il mondo che vorrei")

        assertEquals(10, exact)
        assertTrue(decorated >= 9)
        assertTrue(different < decorated)
    }

    @Test
    fun titleOnlyCoverLaneKeepsSemanticTitleAndRejectsLexicalContinuation() {
        val results = MusicLabTitleSearch.toPlayables(
            targetTitle = "Il mondo",
            songs = listOf(
                song("Il mondo", id = "exact"),
                song("Il mondo (Live)", id = "live"),
                song("Il mondo che vorrei", id = "wrong"),
            ),
            currentYouTubeId = null,
            source = "test",
        )

        assertTrue(results.any { it.song.id == "exact" })
        assertTrue(results.any { it.song.id == "live" })
        assertFalse(results.any { it.song.id == "wrong" })
    }

    @Test
    fun titleOnlyClassifierSeparatesStudioLiveAndMixLocally() {
        assertEquals(AiCoverCategory.COVER, MusicLabTitleSearch.classify("Il mondo"))
        assertEquals(AiCoverCategory.LIVE, MusicLabTitleSearch.classify("Il mondo - Live in Roma"))
        assertEquals(AiCoverCategory.REMIX, MusicLabTitleSearch.classify("Il mondo (Club Mix)"))
    }

    @Test
    fun spotifyDiscoveryIsPositiveOnlyAndRespectsSemanticTitle() {
        val tracks = listOf(
            SpotifyTrack(
                id = "spotify-cover",
                name = "Il mondo",
                artists = listOf(SpotifySimpleArtist(name = "Cover Artist")),
                album = SpotifySimpleAlbum(name = "Cover Album", releaseDate = "1997"),
                durationMs = 215000,
            ),
            SpotifyTrack(
                id = "spotify-original",
                name = "Il mondo",
                artists = listOf(SpotifySimpleArtist(name = "Jimmy Fontana")),
            ),
            SpotifyTrack(
                id = "spotify-wrong",
                name = "Il mondo che vorrei",
                artists = listOf(SpotifySimpleArtist(name = "Other Artist")),
            ),
        )

        val discovered = SpotifyMusicAssist.discoverCoverCandidates(
            originalTitle = "Il mondo",
            originalArtist = "Jimmy Fontana",
            tracks = tracks,
        )

        assertTrue(discovered.any { it.spotifyTrackId == "spotify-cover" })
        assertFalse(discovered.any { it.spotifyTrackId == "spotify-original" })
        assertFalse(discovered.any { it.spotifyTrackId == "spotify-wrong" })
    }
}
