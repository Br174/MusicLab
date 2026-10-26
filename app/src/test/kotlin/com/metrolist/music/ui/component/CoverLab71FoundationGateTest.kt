package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

/** Device-independent regressions for the user-approved permanent CoverLab foundations. */
class CoverLab71FoundationGateTest {
    private fun candidate(
        title: String = "Felicità",
        artist: String = "Lucio Dalla",
        source: String = "YouTube",
        documented: Boolean = false,
    ) = CoverSourceCandidate(
        title = title, artist = artist, sources = listOf(source),
        workRelationConfirmed = documented, playbackVideoId = "MNvH8BzIXGY",
        playbackVideoTitle = title,
    )

    private fun seed(
        id: String, artist: String = "Lucio Dalla", title: String = "Felicità",
        kind: DiscogsVersionKind = DiscogsVersionKind.STUDIO, duration: Int? = null,
    ) = DiscogsVersionSource.externalSeed(
        candidate(title, artist), targetTitle = "Felicità",
        originalArtist = "Lucio Dalla",
    ).copy(
        kind = kind, resolvedVideoId = id,
        resolvedVideoTitle = title, durationSeconds = duration,
    )

    @Test fun titleMustNeverChangeEvenInPermissiveRounds() {
        val wrong = candidate("Felicità puttana")
        val trueTitle = candidate()
        for (round in 1..8) {
            assertFalse(CoverLabTurboRoundAdmission.evaluate(
                wrong, "Felicità", "Lucio Dalla", round, "Felicità puttana"
            ).admitted)
            assertTrue(CoverLabTurboRoundAdmission.evaluate(
                trueTitle, "Felicità", "Lucio Dalla", round, "Felicità"
            ).admitted)
        }
        assertFalse(CoverLab71FoundationGate.matchesRequestedWork(
            candidate("Felicità - lead journalist"), "Felicità", "Lucio Dalla",
            "Felicità - lead journalist"
        ))
    }

    @Test fun karaokeMidiBackingAndTutorialsAreForbiddenInEveryTier() {
        val banned = listOf(
            "Felicità Karaoke", "Felicità MIDI", "Felicità - backing track",
            "Felicità - instrumental karaoke", "Felicità - base musicale",
            "Felicità - sing along", "Felicità - piano tutorial",
            "Felicità minus-one", "Felicità - vocal removed",
        )
        for (title in banned) {
            assertTrue("Must ban $title", CoverLab71FoundationGate.isForbidden(title))
            for (round in 1..5) {
                assertFalse(CoverLabTurboRoundAdmission.evaluate(
                    candidate(title = title), "Felicità", "Lucio Dalla", round, title
                ).admitted)
            }
        }
        assertFalse(CoverLab71FoundationGate.isForbidden(
            "Felicità - performed instrumental cover"
        ))
    }

    @Test fun unrelatedHomonymsAreNotConfusedWithTheOriginalWork() {
        assertFalse(CoverLab71FoundationGate.matchesRequestedWork(
            candidate("Felicità", "Al Bano e Romina Power"), "Felicità",
            "Lucio Dalla", "Felicità"
        ))
        assertFalse(CoverLab71FoundationGate.matchesRequestedWork(
            candidate("Felicità", "Unknown Music Uploader"), "Felicità",
            "Lucio Dalla", "Felicità - Lucio Dalla"
        ))
        assertTrue(CoverLab71FoundationGate.matchesRequestedWork(
            candidate("Felicità - Lucio Dalla cover", "Altra Cantante"),
            "Felicità", "Lucio Dalla", "Felicità - Lucio Dalla cover"
        ))
    }

    @Test fun documentaryProofAllowsForeignTitleButNeverADifferentVideoTitle() {
        val confirmed = candidate("El mundo", "Interprete straniero",
            source = "COVER.INFO", documented = true)
        assertTrue(CoverLab71FoundationGate.matchesRequestedWork(
            confirmed, "Il mondo", "Jimmy Fontana", "El mundo"
        ))
        assertFalse(CoverLab71FoundationGate.matchesRequestedWork(
            confirmed, "Il mondo", "Jimmy Fontana", "Not this song"
        ))
        val unverified = candidate("El mundo", "Interprete straniero")
        assertFalse(CoverLab71FoundationGate.matchesRequestedWork(
            unverified, "Il mondo", "Jimmy Fontana", "El mundo"
        ))
    }

    @Test fun originalStudioLiveRemixCapsAccumulateAcrossRounds() {
        val studio = seed("origStudio1", duration = 225)
        val secondStudio = seed("origStudio2", duration = 227)
        assertTrue(CoverLab71FoundationGate.withinOriginalCaps(studio, "Lucio Dalla", emptyList()))
        assertFalse(CoverLab71FoundationGate.withinOriginalCaps(
            secondStudio, "Lucio Dalla", listOf(studio)
        ))
        val live1 = seed("origLive01A", title = "Felicità (Live 1988)",
            kind = DiscogsVersionKind.LIVE)
        val live2 = seed("origLive02B", title = "Felicità (Live 1991)",
            kind = DiscogsVersionKind.LIVE)
        val live3 = seed("origLive03C", title = "Felicità (Live 1999)",
            kind = DiscogsVersionKind.LIVE)
        assertTrue(CoverLab71FoundationGate.withinOriginalCaps(
            live2, "Lucio Dalla", listOf(studio, live1)
        ))
        assertFalse(CoverLab71FoundationGate.withinOriginalCaps(
            live3, "Lucio Dalla", listOf(studio, live1, live2)
        ))
        val remix1 = seed("origRemix1", kind = DiscogsVersionKind.REMIX)
        val remix2 = seed("origRemix2", kind = DiscogsVersionKind.REMIX)
        val remix3 = seed("origRemix3", kind = DiscogsVersionKind.REMIX)
        assertTrue(CoverLab71FoundationGate.withinOriginalCaps(
            remix2, "Lucio Dalla", listOf(remix1)
        ))
        assertFalse(CoverLab71FoundationGate.withinOriginalCaps(
            remix3, "Lucio Dalla", listOf(remix1, remix2)
        ))
    }

    @Test fun authenticOtherSingerCoversAreNotCappedWithTheOriginal() {
        val threeOriginals = listOf(
            seed("origStudio1"),
            seed("origLive01A", kind = DiscogsVersionKind.LIVE),
            seed("origLive02B", kind = DiscogsVersionKind.LIVE),
        )
        val rendition = seed("otherSinger", artist = "Interprete nuovo")
        assertTrue(CoverLab71FoundationGate.withinOriginalCaps(
            rendition, "Lucio Dalla", threeOriginals
        ))
    }

    @Test fun differentUploadsOfSameRecordingDoNotGetDuplicatePosters() {
        val a = seed("version0001", duration = 235)
        val duplicate = seed("version0002", duration = 235)
        assertTrue(CoverLab71FoundationGate.isDuplicate(duplicate, listOf(a)))
        val distinctLive = seed("live1999001", title = "Felicità live 1999",
            kind = DiscogsVersionKind.LIVE)
        assertFalse(CoverLab71FoundationGate.isDuplicate(distinctLive, listOf(a)))
        val otherSinger = seed("singer00001", artist = "Cantante diverso", duration = 235)
        assertFalse(CoverLab71FoundationGate.isDuplicate(otherSinger, listOf(a)))
    }
    @Test fun historicSessionRowsCannotBypassPermanentFilters() {
        val good = seed("MNvH8BzIXGY")
        assertTrue(CoverLab71FoundationGate.historicalIsEligible(
            good, "Felicità", "Lucio Dalla"
        ))
        val karaoke = good.copy(
            resolvedVideoTitle = "Felicità Karaoke",
            resolvedVideoId = "AAAAAAAAAAA",
        )
        assertFalse(CoverLab71FoundationGate.historicalIsEligible(
            karaoke, "Felicità", "Lucio Dalla"
        ))
        val wrongTitle = good.copy(
            trackTitle = "Felicità puttana",
            resolvedVideoTitle = "Felicità puttana",
            resolvedVideoId = "BBBBBBBBBBB",
        )
        assertFalse(CoverLab71FoundationGate.historicalIsEligible(
            wrongTitle, "Felicità", "Lucio Dalla"
        ))
    }

}
