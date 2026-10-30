package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab81SixRoundTest {
    @Test fun sixObjectivesKeepFinalTitleAutonomousAndDescriptorsOptional() {
        assertEquals(6, CoverRoundSearchPlan.finalRound)
        for (round in 6..9) {
            val queries = CoverRoundSearchPlan.queries("Il mondo", round, "Jimmy Fontana")
            assertEquals("Il mondo", queries.first().text)
            assertTrue(queries.any { it.text == "Il mondo cover" })
            assertTrue(queries.none { it.text.contains("Jimmy Fontana") })
        }
    }

    @Test fun secondCollectsAllTalentAndDigitalFamilies() {
        val texts = CoverRoundSearchPlan.queries("Soli", 2).map { it.text }.joinToString("|")
        for (keyword in listOf("X Factor", "The Voice", "Amici", "Sanremo", "Eurovision", "stadium", "videoclip", "remix", "mashup", "AI cover")) {
            assertTrue(keyword, texts.contains(keyword))
        }
    }

    @Test fun amateurBandsAndInstrumentalGenresHaveTheirOwnObjectives() {
        val third = CoverRoundSearchPlan.queries("Soli", 3).map { it.text }.joinToString("|")
        assertTrue(third.contains("amateur cover"))
        assertTrue(third.contains("cover band"))
        assertTrue(third.contains("tribute band"))
        val fourth = CoverRoundSearchPlan.queries("Soli", 4).map { it.text }.joinToString("|")
        for (keyword in listOf("guitar", "piano", "orchestra", "jazz", "soul", "folk", "choir")) assertTrue(keyword, fourth.contains(keyword))
        assertTrue(CoverRoundSearchPlan.queries("Soli", 5).any { it.text.contains("radio") })
    }

    @Test fun documentedCoverInfoPrecedesYoutubeMusicWithoutFiltering() {
        val documented = CoverSourceCandidate(title="Soli", artist="Credited", sources=listOf("COVER.INFO"),
            workRelationConfirmed=true, playbackVideoId="strong00001", playbackVideoTitle="Soli")
        val released = CoverSourceCandidate(title="Soli", artist="Independent", sources=listOf("YouTube Music"),
            album="Published single", releaseVerified=true, playbackVideoId="record00001", playbackVideoTitle="Soli")
        for (round in 1..8) {
            val ordered = CoverPriorityOrder.order(listOf(documented,released),"Soli","",round)
            // R19 approved precedence: COVER.INFO comes before direct YouTube
            // Music, including a published single, without removing either.
            assertEquals("round $round", documented, ordered.first())
            assertEquals(setOf(documented,released),ordered.toSet())
            assertEquals(listOf(documented),CoverPriorityOrder.order(listOf(documented),"Soli","",round))
        }
    }

    @Test fun finalQueriesKeepContinuationsAndDoNotRestartAnExhaustedRoot() {
        val queue=CoverPriorityOrder.VideoQueries()
        queue.offer("Soli",AiCoverCategory.COVER)
        val root=queue.next()!!
        queue.finish(root,"next-page")
        queue.offer("Soli",AiCoverCategory.COVER)
        queue.beginPass()
        assertEquals("next-page",queue.next()!!.continuation)
        assertEquals(1,queue.remainingOrdinary)
    }
}
