package com.metrolist.music.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LAB70: real musical identities used as deterministic examples.
 * These assertions test admission policy, not live provider availability.
 */
class CoverLab70FirstRoundRecoveryTest {
    private fun video(title: String, artist: String, source: String = "YouTube Music",
        documentaryWork: Boolean = false): CoverSourceCandidate =
        CoverSourceCandidate(
            title = title,
            artist = artist,
            sources = listOf(source),
            workRelationConfirmed = documentaryWork,
            playbackVideoId = "MNvH8BzIXGY",
            playbackVideoTitle = title,
        )

    @Test fun felicitaLucioDallaOriginalIsAdmittedInFirstRound() {
        val original = video("Felicità", "Lucio Dalla")
        assertTrue(
            CoverLabTurboRoundAdmission.evaluate(
                original, "Felicità", "Lucio Dalla", 1, "Felicità"
            ).admitted
        )
        val topic = video("Felicità", "Lucio Dalla - Topic")
        assertTrue(
            CoverLabTurboRoundAdmission.evaluate(
                topic, "Felicità", "Lucio Dalla", 1, "Lucio Dalla - Felicità"
            ).admitted
        )
    }

    @Test fun sameNamedOtherWorkIsNotMisattributedToLucioDalla() {
        val homonym = video("Felicità", "Al Bano e Romina Power", "YouTube")
        assertTrue(
            CoverLabTurboRoundAdmission.evaluate(
                homonym, "Felicità", "Lucio Dalla", 1, "Felicità"
            ).admitted
        )
        assertFalse(homonym.originalWorkReference)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(homonym,"Felicità","Lucio Dalla",1,"Felicità").confidencePercent <
            CoverLabTurboRoundAdmission.evaluate(video("Felicità","Lucio Dalla"),"Felicità","Lucio Dalla",1,"Felicità").confidencePercent)
        val anonymous = video("Felicità", "Unrelated channel", "YouTube")
        assertTrue(
            CoverLabTurboRoundAdmission.evaluate(
                anonymous, "Felicità", "Lucio Dalla", 1, "Felicità - Lucio Dalla"
            ).admitted
        )
    }

    @Test fun documentedCoverInfoAndOriginalsSurviveStrictRound() {
        val documented = video(
            "El mundo", "Documented interpreter", "COVER.INFO", documentaryWork = true
        ).copy(category = AiCoverCategory.FOREIGN)
        assertTrue(
            CoverLabTurboRoundAdmission.evaluate(
                documented, "Il mondo", "Jimmy Fontana", 1, "El mundo"
            ).admitted
        )
        val original = video("Sapore di sale", "Gino Paoli")
        assertTrue(
            CoverLabTurboRoundAdmission.evaluate(
                original, "Sapore di sale", "Gino Paoli", 1, "Sapore di sale"
            ).admitted
        )
    }
}
