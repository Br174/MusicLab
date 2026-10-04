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
    fun `deep search keeps artist title and title-only fallback queries`() {
        val queries = CompilationTrackResolver.searchQueries(track())

        assertEquals("Fiorello Cosa resterà degli anni '80", queries.first())
        assertTrue("Cosa resterà degli anni '80" in queries)
    }
}
