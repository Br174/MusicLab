package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscogsDirectVersionDedupTest {
    private fun seed(
        releaseId: Int,
        kind: DiscogsVersionKind,
        fingerprint: String,
        releaseTitle: String,
    ) = DiscogsVersionSeed(
        trackTitle = "Brano",
        artist = "Cantante",
        releaseTitle = releaseTitle,
        releaseId = releaseId,
        masterId = releaseId,
        year = 1984,
        releaseDate = "1984-10-12",
        kind = kind,
        country = "Italy",
        formats = listOf("Vinyl"),
        formatDescriptions = emptyList(),
        labels = listOf("Label"),
        coverUrl = null,
        durationSeconds = 240,
        fingerprint = fingerprint,
    )

    @Test
    fun `identical recording is one row even across separate releases`() {
        val a = seed(1, DiscogsVersionKind.STUDIO, "song|artist|studio||80|", "Album")
        val b = seed(2, DiscogsVersionKind.STUDIO, "song|artist|studio||80|", "Single")
        assertEquals(1, DiscogsVersionSource.dedupeVersions(listOf(a, b)).size)
    }

    @Test
    fun `same title live and remix remain separate versions`() {
        val live = seed(3, DiscogsVersionKind.LIVE, "song|artist|live||82|live roma", "Live Roma")
        val remix = seed(4, DiscogsVersionKind.REMIX, "song|artist|remix|remix|85|remixes", "Remixes")
        assertEquals(2, DiscogsVersionSource.dedupeVersions(listOf(live, remix)).size)
    }
}
