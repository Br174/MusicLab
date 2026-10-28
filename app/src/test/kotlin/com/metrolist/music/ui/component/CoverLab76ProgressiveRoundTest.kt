package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab76ProgressiveRoundTest {
    private fun row(
        title: String,
        artist: String = "Interprete straniero",
        source: String = "YouTube",
        foreign: Boolean = false,
        work: Boolean = false,
    ) = CoverSourceCandidate(
        title=title,
        artist=artist,
        sources=listOf(source),
        category=if (foreign) AiCoverCategory.FOREIGN else AiCoverCategory.COVER,
        workRelationConfirmed=work,
        playbackVideoId="MNvH8BzIXGY",
        playbackVideoTitle=title,
    )

    @Test fun exactSongTitleLateRoundsAcceptRealPerformanceDescriptors() {
        val valid=listOf("Soli live", "Soli cover band", "Soli guitar instrumental",
            "Soli concerto 1990", "Soli stadium concert", "Soli The Voice",
            "Soli Festival Sanremo", "Soli remix", "Soli chitarra cover")
        for (name in valid) {
            assertTrue(name, CoverLab76BroadTitle.matches("Soli",name))
            assertTrue(name, CoverLab71FoundationGate.matchesRequestedWork(
                row(name), "Soli", "Adriano Celentano", name, allowExactTitleOnly=true,
            ))
        }
    }

    @Test fun otherSongTitlesAreNeverAdmitted() {
        for (name in listOf("Felicità puttana","Felicità è sempre bella",
            "Non è Felicità", "Soli siamo noi","Solitudine","Il mondo che vorrei")) {
            val target=if (name.startsWith("Il mondo")) "Il mondo"
                else if (name.startsWith("Soli") || name=="Solitudine") "Soli"
                else "Felicità"
            assertFalse(name, CoverLab76BroadTitle.matches(target,name))
            assertFalse(name, CoverLab71FoundationGate.matchesRequestedWork(
                row(name),target,"Interprete originale",name,allowExactTitleOnly=true,
            ))
        }
    }

    @Test fun onlyDocumentedForeignVersionMayUseADifferentMusicalTitle() {
        val foreign=row("El mundo",source="COVER.INFO",foreign=true,work=true)
        assertTrue(CoverLab71FoundationGate.matchesRequestedWork(
            foreign,"Il mondo","Jimmy Fontana","El mundo",allowExactTitleOnly=true,
        ))
        assertFalse(CoverLab71FoundationGate.matchesRequestedWork(
            row("El mundo",source="COVER.INFO",foreign=false,work=true),
            "Il mondo","Jimmy Fontana","El mundo",allowExactTitleOnly=true,
        ))
        assertFalse(CoverLab71FoundationGate.matchesRequestedWork(
            row("El mundo",source="YouTube",foreign=true,work=false),
            "Il mondo","Jimmy Fontana","El mundo",allowExactTitleOnly=true,
        ))
    }

    @Test fun firstThreeRoundsRankWeakEvidenceWithoutDiscardingIt() {
        val lowMetadata=row("Soli", artist="Uncredited uploader")
        for(round in 1..3) {
            assertTrue(CoverLabTurboRoundAdmission.evaluate(
                lowMetadata,"Soli","Adriano Celentano",round,"Soli",
            ).admitted)
        }
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            lowMetadata,"Soli","Adriano Celentano",4,"Soli",
        ).admitted)
    }

    @Test fun forbiddenKaraokeAndMidiRemainBannedInFinalRound() {
        for (name in listOf("Soli karaoke", "Soli MIDI",
            "Soli KAR","Soli.mid")) {
            assertFalse(CoverLabTurboRoundAdmission.evaluate(
                row(name),"Soli","Adriano Celentano",9,name,
            ).admitted)
        }
        assertTrue(CoverLabTurboRoundAdmission.evaluate(
            row("Soli e non soli"),"Soli","Adriano Celentano",10,"Soli e non soli",
        ).admitted)
    }

    @Test fun laterRoundQueriesKeepExactTitleAndUseRotatingMultilingualDescriptors() {
        for(round in 4 until CoverRoundSearchPlan.finalRound) {
            val queries=CoverRoundSearchPlan.queries("Soli",round,"Adriano Celentano")
            assertEquals("Soli",queries.first().text)
            assertTrue(queries.size>=5)
            assertTrue(queries.any { it.text.contains("Soli ") })
        }
        assertEquals(listOf("Soli"), CoverRoundSearchPlan.queries("Soli", CoverRoundSearchPlan.finalRound, "Adriano Celentano").map { it.text })
        val second=CoverRoundSearchPlan.queries("Soli",2,"Adriano Celentano")
        assertTrue(second.any { it.text.contains("Sanremo") })
        assertTrue(second.any { it.text.contains("Eurovision") })
        assertTrue(second.any { it.text.contains("The Voice") })
        val third=CoverRoundSearchPlan.queries("Soli",3,"Adriano Celentano")
        assertTrue(third.any { it.text.contains("guitar instrumental") })
        assertTrue(third.any { it.text.contains("stadium live") })
    }
}
