package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Lab46CrossSourceDedupTest {
    private fun seed(
        artist: String = "Lucio Dalla",
        title: String = "Balla balla ballerino",
        release: String,
        year: Int? = 1980,
        duration: Int? = 275,
        confidence: Int = 7,
        videoId: String? = null,
        videoSource: String? = null,
        sources: List<String>,
        fingerprint: String,
    ) =
        DiscogsVersionSeed(
            trackTitle = title,
            artist = artist,
            releaseTitle = release,
            releaseId = if ("Discogs" in sources) 123 else 0,
            masterId = null,
            year = year,
            releaseDate = year?.toString(),
            kind = DiscogsVersionKind.STUDIO,
            country = null,
            formats = emptyList(),
            formatDescriptions = emptyList(),
            labels = emptyList(),
            coverUrl = null,
            durationSeconds = duration,
            fingerprint = fingerprint,
            confidenceScore = confidence,
            confidenceReasons = listOf("test"),
            resolvedVideoId = videoId,
            resolvedVideoTitle = videoId?.let { "video $it" },
            resolvedVideoSource = videoSource,
            videoResolutionChecked = videoId != null,
            sourceNames = sources,
        )

    @Test
    fun `same recording from two providers is one cross-source version`() {
        val discogs =
            seed(
                release = "Dalla",
                sources = listOf("Discogs"),
                fingerprint = "discogs",
            )
        val provider =
            seed(
                release = "Dalla",
                sources = listOf("iTunes"),
                fingerprint = "itunes",
            )

        assertTrue(DiscogsVersionSource.sameCrossSourceVersion(discogs, provider))
    }

    @Test
    fun `different performer is never a duplicate even with same title and duration`() {
        val original =
            seed(
                artist = "Lucio Dalla",
                release = "Dalla",
                sources = listOf("Discogs"),
                fingerprint = "original",
            )
        val cover =
            seed(
                artist = "Fausto Papetti",
                release = "Cover",
                sources = listOf("COVER.INFO"),
                fingerprint = "cover",
            )

        assertFalse(DiscogsVersionSource.sameCrossSourceVersion(original, cover))
    }

    @Test
    fun `duplicate keeps the strongest already-resolved video evidence`() {
        val weak =
            seed(
                release = "Dalla",
                confidence = 7,
                videoId = "weakVideo",
                videoSource = "Provider A",
                sources = listOf("Provider A"),
                fingerprint = "weak",
            )
        val strong =
            seed(
                release = "Dalla",
                confidence = 9,
                videoId = "strongVideo",
                videoSource = "Provider B",
                sources = listOf("Provider B", "Provider C"),
                fingerprint = "strong",
            )

        val merged = DiscogsVersionSource.mergeCrossSourceEvidence(weak, strong)

        assertEquals("strongVideo", merged.resolvedVideoId)
        assertTrue("Provider A" in merged.sourceNames)
        assertTrue("Provider B" in merged.sourceNames)
    }
}
