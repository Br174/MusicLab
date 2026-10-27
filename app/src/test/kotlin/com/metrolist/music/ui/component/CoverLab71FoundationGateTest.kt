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

    @Test fun uncertainTitleUsesWeakerEvidenceWithoutAdmissionVeto() {
        for (round in 1..8) {
            val wrong = CoverLabTurboRoundAdmission.evaluate(candidate("Felicità puttana"), "Felicità", "Lucio Dalla", round, "Felicità puttana")
            val original = CoverLabTurboRoundAdmission.evaluate(candidate(), "Felicità", "Lucio Dalla", round, "Felicità")
            assertTrue(wrong.admitted)
            assertTrue(original.admitted)
            assertTrue(wrong.confidencePercent < original.confidencePercent)
        }
        assertFalse(CoverLab71FoundationGate.matchesRequestedWork(candidate("Felicità - lead journalist"), "Felicità", "Lucio Dalla", "Felicità - lead journalist"))
    }

    @Test fun onlyExplicitKaraokeMidiAndKarExceptionsApplyInEveryTier() {
        for (title in listOf("Felicità Karaoke", "Felicità MIDI", "Felicità KAR", "Felicità - instrumental karaoke")) {
            assertTrue(CoverLab71FoundationGate.isForbidden(title))
            for (round in 1..5) assertFalse(CoverLabTurboRoundAdmission.evaluate(candidate(title = title), "Felicità", "Lucio Dalla", round, title).admitted)
        }
        for (title in listOf("Felicità - performed instrumental cover", "Felicità backing track", "Felicità piano tutorial", "Felicità base musicale")) {
            assertFalse(CoverLab71FoundationGate.isForbidden(title))
            assertTrue(CoverLabTurboRoundAdmission.evaluate(candidate(title = title), "Felicità", "Lucio Dalla", 1, title).admitted)
        }
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
            source = "COVER.INFO", documented = true).copy(
            category = AiCoverCategory.FOREIGN
        )
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

    @Test fun originalTotalFiveAccumulatesAcrossAllKindsAndRounds() {
        val rows = listOf(
            seed("original001"),
            seed("liveRoma001", title="Felicità live Roma", kind=DiscogsVersionKind.LIVE),
            seed("duetto00001", artist="Lucio Dalla & Altro", title="Felicità duetto"),
            seed("remix000001", title="Felicità remix", kind=DiscogsVersionKind.REMIX),
            seed("acoustic001", title="Felicità acoustic", kind=DiscogsVersionKind.ACOUSTIC),
        )
        rows.forEachIndexed { index, row ->
            assertTrue(CoverLab71FoundationGate.withinOriginalCaps(row,"Lucio Dalla",rows.take(index)))
        }
        assertFalse(CoverLab71FoundationGate.withinOriginalCaps(seed("sixth000001", title="Felicità live 2000"),"Lucio Dalla",rows))
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
        assertFalse(CoverLab71FoundationGate.isDuplicate(duplicate, listOf(a)))
        assertEquals(CoverLabTurboTwinGuard.Relation.UNCERTAIN, CoverLabTurboTwinGuard.relation(a, duplicate))
        assertTrue(CoverLab71FoundationGate.isDuplicate(a, listOf(a)))
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
        assertTrue(CoverLab71FoundationGate.historicalIsEligible(
            wrongTitle, "Felicità", "Lucio Dalla"
        ))
    }

}
