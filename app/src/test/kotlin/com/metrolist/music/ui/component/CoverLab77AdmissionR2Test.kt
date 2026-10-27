package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab77AdmissionR2Test {
    private fun candidate(
        title: String = "Il mondo",
        artist: String = "Altra cantante",
        sources: List<String> = listOf("YouTube"),
        relation: Boolean = false,
        original: Boolean = false,
    ) = CoverSourceCandidate(
        title = title, artist = artist, sources = sources,
        playbackVideoId = "video000001", playbackVideoTitle = title,
        workRelationConfirmed = relation, originalWorkReference = original,
    )

    private fun seed(
        id: String, title: String = "Il mondo", artist: String = "Jimmy Fontana",
        kind: DiscogsVersionKind = DiscogsVersionKind.STUDIO,
        album: String = "Album", duration: Int? = 210,
    ) = DiscogsVersionSeed(
        trackTitle = title, artist = artist, releaseTitle = album,
        releaseId = 0, masterId = null, year = null, releaseDate = null,
        kind = kind, country = null, formats = emptyList(),
        formatDescriptions = emptyList(), labels = emptyList(), coverUrl = null,
        durationSeconds = duration, fingerprint = id,
        resolvedVideoId = id, resolvedVideoTitle = title,
    )

    @Test fun karFormatIsExcludedInEveryRound() {
        assertTrue(CoverLab71FoundationGate.isForbidden("Il mondo (KAR)"))
        for (round in 1..6) {
            assertFalse(CoverLabTurboRoundAdmission.evaluate(
                candidate("Il mondo .kar", "Jimmy Fontana"),
                "Il mondo", "Jimmy Fontana", round, "Il mondo .kar"
            ).admitted)
        }
    }

    @Test fun karaokeAndMidiRemainExcludedInEveryRound() {
        for (label in listOf("Karaoke", "MIDI")) for (round in 1..6) {
            assertFalse(CoverLabTurboRoundAdmission.evaluate(
                candidate("Il mondo $label", "Jimmy Fontana"),
                "Il mondo", "Jimmy Fontana", round, "Il mondo $label"
            ).admitted)
        }
    }

    @Test fun artistNameContainingKarIsNotAForbiddenFormat() {
        assertFalse(CoverLab71FoundationGate.isForbidden("Il mondo", "Karol G"))
        assertFalse(CoverLab71FoundationGate.isForbidden("Karma", "Karima"))
    }

    @Test fun noAdditionalBackingOrTutorialVetoIsIntroduced() {
        assertFalse(CoverLab71FoundationGate.isForbidden("Il mondo guitar tutorial"))
        assertFalse(CoverLab71FoundationGate.isForbidden("Il mondo backing track"))
    }

    @Test fun weakCandidateEntersFirstRoundWithoutAlbumOrOfficialWords() {
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            candidate(), "Il mondo", "Jimmy Fontana", 1, "Il mondo"
        ).admitted)
    }

    @Test fun unresolvedOriginalDoesNotBlockTheCandidate() {
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            candidate(), "Il mondo", "", 1, "Il mondo"
        ).admitted)
    }

    @Test fun differentTitleIsLowEvidenceNotANewAdmissionVeto() {
        val weak = CoverLabTurboRoundAdmission.evaluate(
            candidate("Un altro titolo"), "Il mondo", "Jimmy Fontana", 1,
            "Un altro titolo"
        )
        assertTrue(weak.admitted)
        assertTrue(weak.confidencePercent < 40)
    }

    @Test fun fiveGenuinelyDistinctOriginalTakesAreNotLimitedPerCategory() {
        val history = (1..4).map {
            seed("take000000$it", "Il mondo nuova incisione $it")
        }
        assertTrue(CoverLab71FoundationGate.withinOriginalCaps(
            seed("take0000005", "Il mondo nuova incisione 5"),
            "Jimmy Fontana", history
        ))
    }

    @Test fun sixthOriginalCannotBypassTheTotalCapAsADuet() {
        val history = (1..5).map {
            seed("take000000$it", "Il mondo live $it", kind = DiscogsVersionKind.LIVE)
        }
        assertFalse(CoverLab71FoundationGate.withinOriginalCaps(
            seed("take0000006", "Il mondo duetto con altra artista"),
            "Jimmy Fontana", history
        ))
    }

    @Test fun originalAsSecondDuetCreditStillCountsTowardsFive() {
        val history = (1..5).map {
            seed("take000000$it", "Il mondo live $it", kind = DiscogsVersionKind.LIVE)
        }
        assertFalse(CoverLab71FoundationGate.withinOriginalCaps(
            seed("take0000006", "Il mondo duetto", artist = "Altra Artista feat Jimmy Fontana"),
            "Jimmy Fontana", history
        ))
    }

    @Test fun otherPerformersAreNotGivenTheOriginalFiveCap() {
        val history = (1..8).map {
            seed("other00000$it", artist = "Altra cantante $it")
        }
        assertTrue(CoverLab71FoundationGate.withinOriginalCaps(
            seed("newcover001", artist = "Cantante nuova"), "Jimmy Fontana", history
        ))
    }

    @Test fun exactVideoIdRemainsAConfirmedDuplicate() {
        val first = seed("samevideo01")
        assertTrue(CoverLab71FoundationGate.isDuplicate(
            first.copy(releaseTitle = "Compilation"), listOf(first)
        ))
    }

    @Test fun equalDurationAndTitleAloneDoNotProveTheSameStudioTake() {
        val first = seed("studio00001", album = "Primo album")
        val possible = seed("studio00002", album = "Secondo album")
        assertFalse(CoverLab71FoundationGate.isDuplicate(possible, listOf(first)))
    }

    @Test fun distinctLiveVenuesAreNotCollapsedBecauseDurationsMatch() {
        val first = seed("live0000001", "Il mondo live Roma 1990",
            kind = DiscogsVersionKind.LIVE, album = "Roma")
        val second = seed("live0000002", "Il mondo live Milano 1992",
            kind = DiscogsVersionKind.LIVE, album = "Milano")
        assertFalse(CoverLab71FoundationGate.isDuplicate(second, listOf(first)))
    }

    @Test fun documentedForeignWorkRanksAboveAnUnverifiedLexicalCover() {
        val foreign = candidate("El mundo", "Interprete straniero",
            listOf("COVER.INFO"), relation = true).copy(category = AiCoverCategory.FOREIGN)
        val amateur = candidate("Il mondo Jimmy Fontana cover")
        val documented = CoverLabTurboRoundAdmission.evaluate(
            foreign, "Il mondo", "Jimmy Fontana", 1, "El mundo"
        )
        val lexical = CoverLabTurboRoundAdmission.evaluate(
            amateur, "Il mondo", "Jimmy Fontana", 1, "Il mondo Jimmy Fontana cover"
        )
        assertTrue(documented.admitted)
        assertTrue(documented.confidencePercent > lexical.confidencePercent)
    }

    @Test fun documentedOriginalHasStrongMatchWithoutOfficialKeyword() {
        val original = candidate("Il mondo", "Jimmy Fontana",
            listOf("COVER.INFO"), relation = true, original = true)
        val verdict = CoverLabTurboRoundAdmission.evaluate(
            original, "Il mondo", "Jimmy Fontana", 1, "Il mondo"
        )
        assertTrue(verdict.admitted)
        assertTrue(verdict.confidencePercent >= 90)
    }
}
