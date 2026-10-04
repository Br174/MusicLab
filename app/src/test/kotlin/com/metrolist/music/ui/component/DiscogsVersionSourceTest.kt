package com.metrolist.music.ui.component

import com.metrolist.music.discogs.DiscogsTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscogsVersionSourceTest {
    private fun seed(
        releaseId: Int,
        masterId: Int? = 100,
        releaseTitle: String = "Album originale",
        releaseDate: String? = "1984-10-12",
        kind: DiscogsVersionKind = DiscogsVersionKind.STUDIO,
        fingerprint: String = "song|artist|studio|m100||80",
        durationSeconds: Int? = 240,
    ) = DiscogsVersionSeed(
        trackTitle = "Canzone",
        artist = "Artista",
        releaseTitle = releaseTitle,
        releaseId = releaseId,
        masterId = masterId,
        year = releaseDate?.take(4)?.toIntOrNull() ?: 1984,
        releaseDate = releaseDate,
        kind = kind,
        country = "Italy",
        formats = listOf("Vinyl"),
        formatDescriptions = listOf("LP"),
        labels = listOf("Etichetta"),
        coverUrl = "https://example.invalid/$releaseId.jpg",
        durationSeconds = durationSeconds,
        fingerprint = fingerprint,
    )

    @Test
    fun `same musical version across equivalent releases is shown once`() {
        val older = seed(releaseId = 1, releaseDate = "1984-10-12")
        val reissue = seed(releaseId = 2, releaseDate = "1991-03-01")

        val result = DiscogsVersionSource.dedupeVersions(listOf(reissue, older))

        assertEquals(1, result.size)
        assertEquals(1, result.single().releaseId)
        assertEquals("1984-10-12", result.single().releaseDate)
    }

    @Test
    fun `same title remains separate when studio live and remix are distinct versions`() {
        val studio = seed(
            releaseId = 1,
            kind = DiscogsVersionKind.STUDIO,
            fingerprint = "song|artist|studio|m100||80",
        )
        val live = seed(
            releaseId = 2,
            masterId = 200,
            releaseTitle = "Live a Roma",
            kind = DiscogsVersionKind.LIVE,
            fingerprint = "song|artist|live|m200|live|82",
        )
        val remix = seed(
            releaseId = 3,
            masterId = 300,
            releaseTitle = "Remixes",
            kind = DiscogsVersionKind.REMIX,
            fingerprint = "song|artist|remix|m300|remix|85",
        )

        val result = DiscogsVersionSource.dedupeVersions(listOf(studio, live, remix))

        assertEquals(3, result.size)
        assertTrue(result.map { it.kind }.toSet().containsAll(
            setOf(DiscogsVersionKind.STUDIO, DiscogsVersionKind.LIVE, DiscogsVersionKind.REMIX),
        ))
    }

    @Test
    fun `unverified candidate is preserved at low score while verified status stays distinct`() {
        val unverified = seed(releaseId = 9).copy(confidenceScore = 1)
        assertFalse(DiscogsVersionSource.isVerifiedDirectSeed(unverified))
        assertTrue(DiscogsVersionSource.isDisplayableDirectSeed(unverified))

        val verified = unverified.copy(
            confidenceScore = 7,
            track = DiscogsTrack(
                position = "A1",
                title = "Canzone",
                artists = listOf("Artista"),
                durationText = "4:00",
                durationSeconds = 240,
            ),
        )
        assertTrue(DiscogsVersionSource.isVerifiedDirectSeed(verified))
        assertTrue(DiscogsVersionSource.isDisplayableDirectSeed(verified))
    }

    @Test
    fun `independent evidence is merged without deleting the candidate`() {
        val discogs = seed(releaseId = 9).copy(
            confidenceScore = 1,
            sourceNames = listOf("Discogs"),
        )
        val apple =
            DiscogsVersionSource.externalSeed(
                CoverSourceCandidate(
                    title = "Canzone",
                    artist = "Artista",
                    sources = listOf("Apple/iTunes"),
                    evidenceScore = 2,
                ),
            )

        val merged = DiscogsVersionSource.mergeEvidence(discogs, apple)

        assertTrue("Discogs" in merged.sourceNames)
        assertTrue("Apple/iTunes" in merged.sourceNames)
        assertTrue(merged.confidenceScore >= 3)
        assertEquals(9, merged.releaseId)
    }

    @Test
    fun `publication date preserves Discogs precision without inventing missing parts`() {
        assertEquals("12/10/1984", formatDiscogsPublicationDate("1984-10-12", 1984))
        assertEquals("10/1984", formatDiscogsPublicationDate("1984-10", 1984))
        assertEquals("1984", formatDiscogsPublicationDate("1984", 1984))
        assertEquals("1984", formatDiscogsPublicationDate(null, 1984))
    }
}
