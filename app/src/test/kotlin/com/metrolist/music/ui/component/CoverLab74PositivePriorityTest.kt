package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab74PositivePriorityTest {
    private fun candidate(
        artist: String = "Altro cantante",
        title: String = "Il mondo",
        album: String? = null,
        sources: List<String> = listOf("YouTube"),
        sameWork: Boolean = false,
        video: String = "Il mondo cover",
    ) = CoverSourceCandidate(
        title=title, artist=artist, album=album, sources=sources,
        workRelationConfirmed=sameWork, playbackVideoTitle=video,
    )

    @Test fun albumAndDiscogsArePositiveBonusesNeverAdmissionGates() {
        val plain = candidate()
        val disc = candidate(album="Cover pubblicate", sources=listOf("Discogs"))
        val itunes = candidate(album="Album in studio", sources=listOf("Apple/iTunes"))
        assertTrue(CoverLab74PositivePriority.score(disc,"Originale") >
            CoverLab74PositivePriority.score(plain,"Originale"))
        assertTrue(CoverLab74PositivePriority.score(itunes,"Originale") >
            CoverLab74PositivePriority.score(plain,"Originale"))
        // A no-album, YouTube-only cover is STILL present, never vetoed.
        val sorted=CoverLab74PositivePriority.order(listOf(plain,disc,itunes),"Originale")
        assertEquals(3,sorted.size)
        assertTrue(sorted.contains(plain))
    }

    @Test fun coverInfoForeignDocumentedAdaptationIsPrioritized() {
        val foreign=candidate(artist="Interprete spagnolo",
            title="El mundo", sources=listOf("COVER.INFO","YouTube"), sameWork=true)
        val ordinary=candidate()
        val sorted=CoverLab74PositivePriority.order(listOf(ordinary,foreign),"Jimmy Fontana")
        assertEquals(foreign,sorted.first())
        assertEquals(ordinary,sorted.last())
    }

    @Test fun originalSingerIsPreferredButOtherSingersNeverRemoved() {
        val other=candidate(artist="Nuovo cantante")
        val original=candidate(artist="Jimmy Fontana")
        val ordered=CoverLab74PositivePriority.order(listOf(other,original),"Jimmy Fontana")
        assertEquals(original,ordered.first())
        assertEquals(2,ordered.size)
    }

    @Test fun stableOrderingForTiesPreservesAllRows() {
        val a=candidate(artist="Singer A")
        val b=candidate(artist="Singer B")
        val c=candidate(artist="Singer C")
        assertEquals(listOf(a,b,c),CoverLab74PositivePriority.order(listOf(a,b,c),""))
        assertEquals(0,CoverLab74PositivePriority.videoScore("Un brano","Sconosciuto",""))
    }

    @Test fun studioAudioRanksAboveUnmarkedAmateurRecordingWithoutRejectingIt() {
        val studio=CoverLab74PositivePriority.videoScore(
            "Il mondo Official Audio studio recording","Interprete","Jimmy Fontana")
        val ordinary=CoverLab74PositivePriority.videoScore(
            "Il mondo cover","Interprete","Jimmy Fontana")
        assertTrue(studio > ordinary)
        assertTrue(ordinary >= 0)
    }
}
