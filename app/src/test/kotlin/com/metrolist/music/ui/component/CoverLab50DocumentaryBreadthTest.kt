package com.metrolist.music.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thirty synthetic Italian/international cases, not a live-provider yield claim. */
class CoverLab50DocumentaryBreadthTest {
    @Test fun thirtyDocumentedAdaptationsRemainEligibleWithDeepRecovery() {
        val cases = (1..30).map { index ->
            CoverSourceCandidate(
                title = if (index <= 15) "Adattamento italiano $index"
                        else "International adaptation $index",
                artist = "Performer $index",
                sources = listOf(
                    when (index % 3) {
                        0 -> "MusicBrainz"
                        1 -> "SecondHandSongs"
                        else -> "COVER.INFO"
                    },
                ),
                workRelationConfirmed = true,
            )
        }
        assertTrue(cases.size == 30)
        assertTrue(cases.take(15).all { it.title.startsWith("Adattamento italiano") })
        assertTrue(cases.drop(15).all { it.title.startsWith("International") })
        cases.forEach { candidate ->
            assertTrue(CoverDiscoverySources.admitsAnchoredAlternateTitle(candidate, true))
            assertFalse(CoverDiscoverySources.admitsAnchoredAlternateTitle(candidate, false))
        }
    }

    @Test fun sameTitleYoutubeSocialAndCloudAreNeverWorkProof() {
        val unsafeSources = listOf("YouTube/Video", "Archivio Cloud", "iTunes", "Spotify", "AI Scout")
        unsafeSources.forEach { source ->
            val candidate = CoverSourceCandidate(
                title = "Stesso titolo",
                artist = "Artista differente",
                sources = listOf(source),
                workRelationConfirmed = false,
            )
            assertFalse(CoverDiscoverySources.admitsAnchoredAlternateTitle(candidate, true))
            val seed = DiscogsVersionSource.externalSeed(candidate)
            assertFalse(CoverSameWorkGate.hasDocumentaryRelation(seed))
        }
    }

    @Test fun namedDocumentaryProviderWithoutActualRelationIsNotProof() {
        listOf("MusicBrainz", "SecondHandSongs", "COVER.INFO").forEach { name ->
            val candidate = CoverSourceCandidate(
                title = "Ambiguous translation",
                artist = "Unknown performer",
                sources = listOf(name),
                workRelationConfirmed = false,
            )
            assertFalse(CoverDiscoverySources.admitsAnchoredAlternateTitle(candidate, true))
            assertFalse(CoverSameWorkGate.hasDocumentaryRelation(
                DiscogsVersionSource.externalSeed(candidate),
            ))
        }
    }

    @Test fun playbackVideoIdentifierCannotCreateAWorkRelationship() {
        val video = CoverSourceCandidate(
            title = "Una canzone omonima",
            artist = "Autore sconosciuto",
            sources = listOf("YouTube/Video"),
            playbackVideoId = "demo-id",
            workRelationConfirmed = false,
        )
        assertFalse(CoverDiscoverySources.admitsAnchoredAlternateTitle(video, true))
        assertFalse(CoverSameWorkGate.hasDocumentaryRelation(
            DiscogsVersionSource.externalSeed(video),
        ))
    }
}
