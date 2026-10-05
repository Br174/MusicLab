package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class Lab47CoverRecoveryPerformanceTest {
    private fun seed(
        artist: String,
        title: String,
        duration: Int?,
        fingerprint: String,
    ) =
        DiscogsVersionSeed(
            trackTitle = title,
            artist = artist,
            releaseTitle = "Test release",
            releaseId = 0,
            masterId = null,
            year = 1982,
            releaseDate = "1982",
            kind = DiscogsVersionKind.STUDIO,
            country = null,
            formats = emptyList(),
            formatDescriptions = emptyList(),
            labels = emptyList(),
            coverUrl = null,
            durationSeconds = duration,
            fingerprint = fingerprint,
            confidenceScore = 5,
            confidenceReasons = listOf("test"),
            sourceNames = listOf("test"),
        )

    @Test
    fun `provider title with embedded performer exposes the real song fallback anchor`() {
        assertEquals(
            "Billie Jean",
            TitleMeaningResolver.workAnchorTitle("Gianni Blu - Billie Jean"),
        )
    }

    @Test
    fun `ambiguous one-word hyphenated title is not rewritten`() {
        assertEquals(
            "Black - White",
            TitleMeaningResolver.workAnchorTitle("Black - White"),
        )
    }

    @Test
    fun `cross-source bucket groups only same artist title and kind before precise matching`() {
        val left = seed("Michael Jackson", "Billie Jean", 294, "left")
        val close = seed("Michael Jackson", "Billie Jean", 299, "close")
        val otherArtist = seed("Gianni Blu", "Billie Jean", 294, "other")

        assertEquals(
            DiscogsVersionSource.crossSourceBucketKey(left),
            DiscogsVersionSource.crossSourceBucketKey(close),
        )
        assertNotEquals(
            DiscogsVersionSource.crossSourceBucketKey(left),
            DiscogsVersionSource.crossSourceBucketKey(otherArtist),
        )
    }
}
