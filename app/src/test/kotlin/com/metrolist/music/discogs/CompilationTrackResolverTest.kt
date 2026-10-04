package com.metrolist.music.discogs

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompilationTrackResolverTest {
    private fun track(
        artist: String = "Fiorello",
        title: String = "Cosa resterà degli anni '80",
        duration: Int? = 240,
    ) = DiscogsTrack(
        position = "1",
        title = title,
        artists = listOf(artist),
        durationText = "4:00",
        durationSeconds = duration,
    )

    private fun song(
        artist: String,
        title: String = "Cosa resterà degli anni '80",
        duration: Int? = 240,
    ) = SongItem(
        id = "abcdefghijk",
        title = title,
        artists = listOf(Artist(name = artist, id = null)),
        duration = duration,
        thumbnail = "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg",
    )

    @Test
    fun `exact title by a different explicit performer is rejected even in relaxed mode`() {
        val score =
            CompilationTrackResolver.scoreSong(
                track = track(artist = "Fiorello"),
                song = song(artist = "Raf"),
                relaxedArtist = true,
            )

        assertTrue("Raf must not satisfy a Fiorello card just because the title matches", score < 58)
    }

    @Test
    fun `target performer named in video title can recover an uploader mismatch`() {
        val score =
            CompilationTrackResolver.scoreSong(
                track = track(artist = "Fiorello"),
                song = song(
                    artist = "Archivio TV",
                    title = "Fiorello - Cosa resterà degli anni '80",
                ),
                relaxedArtist = true,
            )

        assertTrue(score >= 58)
    }

    @Test
    fun `deep search always keeps target performer and never uses title-only fallback`() {
        val queries = CompilationTrackResolver.searchQueries(track())

        assertEquals("Fiorello Cosa resterà degli anni '80", queries.first())
        assertTrue(queries.all { it.contains("Fiorello", ignoreCase = true) })
        assertTrue("Cosa resterà degli anni '80" !in queries)
    }

    @Test
    fun `different performer is hard incompatible even with exact title and duration`() {
        val compatible =
            CompilationTrackResolver.isHardCompatible(
                track = track(artist = "Fiorello"),
                song = song(artist = "Raf"),
            )

        assertTrue(!compatible)
    }

    @Test
    fun `target performer in title allows archive uploader`() {
        val compatible =
            CompilationTrackResolver.isHardCompatible(
                track = track(artist = "Fiorello"),
                song =
                    song(
                        artist = "Archivio TV",
                        title = "Fiorello - Cosa resterà degli anni '80",
                    ),
            )

        assertTrue(compatible)
    }

    @Test
    fun `live candidate cannot replace studio recording`() {
        val compatible =
            CompilationTrackResolver.isHardCompatible(
                track = track(artist = "Fiorello"),
                song =
                    song(
                        artist = "Fiorello",
                        title = "Fiorello - Cosa resterà degli anni '80 Live",
                    ),
            )

        assertTrue(!compatible)
    }
}
