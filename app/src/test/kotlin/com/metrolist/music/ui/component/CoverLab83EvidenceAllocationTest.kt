package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab83EvidenceAllocationTest {
    private fun row(title:String="Aspettando il sole",artist:String="Altro interprete")=CoverSourceCandidate(
        title=title,artist=artist,sources=listOf("YouTube"),playbackVideoId="video000001",playbackVideoTitle=title)

    @Test fun exactTitleWithoutOtherCorroborationWaitsUntilSixthRound() {
        val candidate=row()
        for(round in 1..5) assertFalse("round $round",CoverLabTurboRoundAdmission.evaluate(candidate,"Aspettando il sole","Neffa",round).admitted)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(candidate,"Aspettando il sole","Neffa",6).admitted)
    }
    @Test fun explicitReferenceIsOneAdditionalFactAndCannotFillTheFirstRound() {
        val candidate=row("Aspettando il sole - Neffa cover")
        assertFalse(CoverLabTurboRoundAdmission.evaluate(candidate,"Aspettando il sole","Neffa",1).admitted)
        for(round in 2..5) assertTrue("round $round",CoverLabTurboRoundAdmission.evaluate(candidate,"Aspettando il sole","Neffa",round).admitted)
    }
    @Test fun anAlbumDoesNotTurnAnUnrelatedHomonymIntoTheRequestedWork() {
        val candidate=row().copy(sources=listOf("YouTube Music"),album="Album pubblicato",releaseVerified=true)
        for(round in 1..5) assertFalse(CoverLabTurboRoundAdmission.evaluate(candidate,"Aspettando il sole","Neffa",round).admitted)
    }
    @Test fun documentedWorkAndCreditedPerformanceCanQualifyWithoutThreeDifferentSites() {
        val candidate=row().copy(sources=listOf("COVER.INFO"),workRelationConfirmed=true)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(candidate,"Aspettando il sole","Neffa",1).admitted)
    }
    @Test fun pureTitleQueryIsReservedForTheSixthObjective() {
        for(round in 1..5) assertFalse("round $round",CoverRoundSearchPlan.queries("Aspettando il sole",round,"Neffa").any{it.text=="Aspettando il sole"})
        assertEquals("Aspettando il sole",CoverRoundSearchPlan.queries("Aspettando il sole",6,"Neffa").first().text)
    }
    @Test fun anUnprovenOldRecordingIsNotAutomaticallyTheFirstPublishedVersion() {
        val oldest=row().copy(sources=listOf("MusicBrainz"),year=1930,originalWorkReference=true,workRelationConfirmed=true)
        assertNull(CoverPriorityOrder.originalReference(listOf(oldest)))
    }

    @Test fun displayedPercentagesNeverBreakAPublicationPriorityTie() {
        val seed=DiscogsVersionSource.externalSeed(row(),"Aspettando il sole","Neffa")
        val low=seed.copy(musiclabScore100=1,confidenceScore=1)
        val high=seed.copy(musiclabScore100=99,confidenceScore=20)
        assertEquals(0,CoverPriorityOrder.publicationComparator.compare(low,high))
        val p=CoverRoundPresentation<DiscogsVersionSeed>{it.fingerprint}
        p.prioritizePreparedWith(CoverPriorityOrder.publicationComparator)
        p.stage(1,low.copy(fingerprint="first",resolvedVideoId="first000001"))
        p.stage(1,high.copy(fingerprint="later",resolvedVideoId="later000001"))
        p.exhaust(1)
        assertEquals("first",p.takeAutomatic().first().fingerprint)
    }
}
