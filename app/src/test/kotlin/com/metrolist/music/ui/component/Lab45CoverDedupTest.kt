package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Lab45CoverDedupTest {
    private fun seed(
        artist: String = "Artista",
        title: String = "Canzone",
        release: String,
        year: Int?,
        date: String? = year?.toString(),
        kind: DiscogsVersionKind = DiscogsVersionKind.STUDIO,
        duration: Int? = 210,
        fingerprint: String,
    ) =
        DiscogsVersionSeed(
            trackTitle = title,
            artist = artist,
            releaseTitle = release,
            releaseId = fingerprint.hashCode().let { if (it == 0) 1 else kotlin.math.abs(it) },
            masterId = null,
            year = year,
            releaseDate = date,
            kind = kind,
            country = null,
            formats = emptyList(),
            formatDescriptions = emptyList(),
            labels = emptyList(),
            coverUrl = null,
            durationSeconds = duration,
            fingerprint = fingerprint,
            sourceNames = listOf("test"),
        )

    @Test
    fun `same studio recording keeps first original and newest remaster only`() {
        val rows =
            listOf(
                seed(release = "Album originale", year = 1968, date = "1968-03-01", fingerprint = "orig"),
                seed(release = "Compilation 1991", year = 1991, date = "1991", fingerprint = "reissue"),
                seed(release = "Album Remastered 2005", year = 2005, date = "2005", fingerprint = "rem05"),
                seed(release = "Album Remastered 2018", year = 2018, date = "2018", fingerprint = "rem18"),
                seed(release = "Album Remastered 2026", year = 2026, date = "2026", fingerprint = "rem26"),
            )

        val deduped = DiscogsVersionSource.dedupeVersions(rows)

        assertEquals(2, deduped.size)
        assertEquals(listOf(1968, 2026), deduped.map { it.year })
        assertFalse(DiscogsVersionSource.isRemasterSeed(deduped.first()))
        assertTrue(DiscogsVersionSource.isRemasterSeed(deduped.last()))
    }

    @Test
    fun `unknown-date remaster never beats a dated newest remaster`() {
        val rows =
            listOf(
                seed(release = "Remastered 2026", year = 2026, date = "2026", fingerprint = "dated"),
                seed(release = "Remastered Edition", year = null, date = null, fingerprint = "unknown"),
            )

        val deduped = DiscogsVersionSource.dedupeVersions(rows)

        assertEquals(1, deduped.size)
        assertEquals(2026, deduped.single().year)
    }

    @Test
    fun `different performers are never collapsed as duplicates`() {
        val rows =
            listOf(
                seed(artist = "Artista A", release = "Album A", year = 1970, fingerprint = "a"),
                seed(artist = "Artista B", release = "Album B", year = 1971, fingerprint = "b"),
            )

        assertEquals(2, DiscogsVersionSource.dedupeVersions(rows).size)
    }

    @Test
    fun `live and remix remain distinct musical versions`() {
        val rows =
            listOf(
                seed(release = "Live at Roma", year = 1990, kind = DiscogsVersionKind.LIVE, fingerprint = "live"),
                seed(release = "Club Remix", year = 1999, kind = DiscogsVersionKind.REMIX, fingerprint = "mix"),
            )

        assertEquals(2, DiscogsVersionSource.dedupeVersions(rows).size)
    }

    @Test
    fun `work anchor is fallback only and retains variant knowledge separately`() {
        val raw = "Balla balla ballerino (Lucio Dalla) - Live"
        assertEquals("Balla balla ballerino", TitleMeaningResolver.workAnchorTitle(raw))
        assertTrue("live" in TitleMeaningResolver.versionDescriptors(raw))
    }
}
