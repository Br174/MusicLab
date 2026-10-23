package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverRecordingMergeRegressionTest {
    private fun seed(
        artist: String = "Giusy Ferreri",
        year: Int? = 2008,
        duration: Int? = null,
        fingerprint: String = "musicbrainz",
        source: String = "MusicBrainz",
        video: String? = null,
        kind: DiscogsVersionKind = DiscogsVersionKind.STUDIO,
        releaseTitle: String = "Album",
    ) = DiscogsVersionSeed(
        trackTitle = "Che cosa c'è",
        artist = artist,
        releaseTitle = releaseTitle,
        releaseId = if (source == "Discogs") 22 else 0,
        masterId = null,
        year = year,
        releaseDate = year?.toString(),
        kind = kind,
        country = "Italy",
        formats = emptyList(),
        formatDescriptions = emptyList(),
        labels = emptyList(),
        coverUrl = null,
        durationSeconds = duration,
        fingerprint = fingerprint,
        sourceNames = listOf(source),
        resolvedVideoId = video,
        resolvedVideoTitle = video?.let { "Che cosa c'è" },
        videoResolutionChecked = video != null,
    )

    @Test fun sameStudioRecordingAcrossSourcesMergesAndKeepsVideo() {
        val apple = seed(duration = 221, fingerprint = "apple", source = "Apple/iTunes")
        val brainz = seed(duration = 238, video = "abcdefghijk")
        val merged = DiscogsVersionSource.dedupeVersions(listOf(apple, brainz))
        assertEquals(1, merged.size)
        assertEquals("abcdefghijk", merged.single().resolvedVideoId)
        assertTrue(merged.single().sourceNames.contains("Apple/iTunes"))
        assertTrue(merged.single().sourceNames.contains("MusicBrainz"))
    }

    @Test fun sameTitleDifferentArtistsStaySeparate() {
        assertEquals(2, DiscogsVersionSource.dedupeVersions(
            listOf(seed(), seed(artist = "Un altro interprete", fingerprint = "other"))
        ).size)
    }

    @Test fun distantStudioRecordingsWithDifferentDurationsStaySeparate() {
        assertEquals(2, DiscogsVersionSource.dedupeVersions(
            listOf(seed(year = 2008, duration = 221), seed(
                year = 2025, duration = 281, fingerprint = "rerecord"))
        ).size)
    }

    @Test fun remasterDoesNotEraseOriginal() {
        val original = seed()
        val remaster = seed(fingerprint = "remaster", releaseTitle = "Album Remastered")
        assertFalse(DiscogsVersionSource.sameCrossSourceVersion(original, remaster))
    }

    @Test fun extraQueriesOnlyAffectLaterRounds() {
        assertTrue(CoverRoundSearchPlan.queries("Che cosa c'è", 1).isEmpty())
        assertEquals(4, CoverRoundSearchPlan.queries("Che cosa c'è", 2).size)
        val fourth = CoverRoundSearchPlan.queries("Che cosa c'è", 4)
        val fifth = CoverRoundSearchPlan.queries("Che cosa c'è", 5)
        assertEquals(4, fourth.map { it.text }.distinct().size)
        assertTrue(fourth.map { it.text }.intersect(fifth.map { it.text }.toSet()).isEmpty())
    }
}
