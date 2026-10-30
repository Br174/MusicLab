package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLabTurboRoundAdmissionTest {
    private fun candidate(
        title: String = "Sapore di sale",
        artist: String = "Unknown Singer",
        sources: List<String> = listOf("YouTube"),
        confirmed: Boolean = false,
    ) = CoverSourceCandidate(
        title = title, artist = artist, sources = sources,
        workRelationConfirmed = confirmed,
        playbackVideoId = "VID12345678",
    )

    @Test fun firstRoundDefersInsufficientFactsWithoutDiscardingCandidates() {
        val loose = candidate()
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            loose, "Sapore di sale", "Gino Paoli", 6
        ).admitted)
        val originalOfficial = candidate(artist = "Gino Paoli")
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            originalOfficial, "Sapore di sale", "Gino Paoli", 2,
            "Gino Paoli - Sapore di sale (official audio)"
        ).admitted)
    }

    @Test fun secondRoundAllowsTwoMusicalClues() {
        val cover = candidate()
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            cover, "Sapore di sale", "Gino Paoli", 2,
            "Sapore di sale - Gino Paoli live cover"
        ).admitted)
        // Two actual facts do not override a later instrumental category.
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            cover, "Sapore di sale", "Gino Paoli", 2,
            "Sapore di sale - Gino Paoli piano cover"
        ).admitted)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            cover, "Sapore di sale", "Gino Paoli", 4,
            "Sapore di sale - Gino Paoli piano cover"
        ).admitted)
        assertFalse(CoverLabTurboRoundAdmission.evaluate(
            candidate(title = "An unrelated track"), "Sapore di sale", "Gino Paoli", 2,
            "An unrelated track"
        ).admitted)
    }

    @Test fun thirdAndFourthAllowProgressivelyWeakerMatches() {
        val creditedCover = candidate(title = "Sapore di sale - Gino Paoli piano cover")
        // Piano versions belong to the fourth tier under the six-round spec.
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            creditedCover, "Sapore di sale", "Gino Paoli", 3
        ).admitted)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            creditedCover, "Sapore di sale", "Gino Paoli", 4
        ).admitted)
        val ambiguous = candidate()
        // Latest allocation: title alone is sufficient only in the sixth round.
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            ambiguous, "Sapore di sale", "Gino Paoli", 6
        ).admitted)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            candidate(title = "Yeniden başlasın", sources = listOf("COVER.INFO"), confirmed = true)
                .copy(category = AiCoverCategory.FOREIGN),
            "Se mi lasci non vale", "Julio Iglesias", 1,
            "Yeniden başlasın"
        ).admitted)
    }

    @Test fun unrelatedUploaderDoesNotBecomeOriginalFromVideoTitleAlone() {
        val parishUpload = candidate(title = "Felicità", artist = "Parrocchia Immacolata")
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            parishUpload, "Felicità", "Lucio Dalla", 2,
            "Felicità - Lucio Dalla"
        ).admitted)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            parishUpload, "Felicità", "Lucio Dalla", 3,
            "Felicità - Lucio Dalla"
        ).admitted)
    }

    @Test fun authoritativeTopicChannelStillQualifiesOriginal() {
        val originalChannel = candidate(title = "Felicità", artist = "Lucio Dalla - Topic")
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            originalChannel, "Felicità", "Lucio Dalla", 2,
            "Lucio Dalla - Felicità"
        ).admitted)
    }

    @Test fun estimateIsPercentageNotHardAdmissionThreshold() {
        val v = CoverLabTurboRoundAdmission.evaluate(
            candidate(title = "Sapore di sale - Gino Paoli acoustic cover"), "Sapore di sale", "Gino Paoli", 4
        )
        assertTrue(v.admitted)
        assertTrue(v.confidencePercent in 1..100)
    }
}
