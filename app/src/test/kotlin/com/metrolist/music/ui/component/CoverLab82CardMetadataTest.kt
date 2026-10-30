package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab82CardMetadataTest {
    private fun row()=DiscogsVersionSource.externalSeed(CoverSourceCandidate(
        title="Lontano dagli occhi",artist="Nome completo dell'interprete",
        sources=listOf("YouTube"),playbackVideoId="video000001",playbackVideoTitle="Lontano dagli occhi"))

    @Test fun unverifiedVideoIsNotCalledStudioAndHasNoInventedYear() {
        val seed=row().copy(resolvedVideoTitle="Lontano dagli occhi uploaded 2026")
        assertFalse(CoverCardMetadata.isStudio(seed))
        assertNull(CoverCardMetadata.publicationYear(seed))
    }

    @Test fun recordingYearComesFromReleaseFieldsAndNeverLocalizedDisplayDate() {
        val seed=row().copy(year=1969,releaseDate="1969-02-01",sourceNames=listOf("Discogs"),discogsVerificationChecked=true)
        assertEquals("1969",CoverCardMetadata.publicationYear(seed))
        assertEquals("1969",CoverCardMetadata.publicationYear(seed.copy(releaseDate=null)))
        assertNull(CoverCardMetadata.publicationYear(seed.copy(year=null,releaseDate="01/02/1969")))
        assertFalse("Publication alone does not prove a studio session",CoverCardMetadata.isStudio(seed))
        assertTrue(CoverCardMetadata.isStudio(seed.copy(trackTitle="Lontano dagli occhi (studio version)")))
    }

    @Test fun aYouTubeOnlyUploadDateCannotBecomeAPublicationYear() {
        assertNull(CoverCardMetadata.publicationYear(row().copy(year=2026,releaseDate="2026-10-28")))
    }

    @Test fun allActualSourcesSurviveAndPlaybackServiceIsSeparate() {
        val seed=row().copy(sourceNames=listOf("YouTube","COVER.INFO","Discogs","cover.info"),resolvedVideoSource="YouTube documentario")
        assertEquals(listOf("COVER.INFO","Discogs","YouTube"),CoverCardMetadata.sources(seed))
        assertEquals("YouTube",CoverCardMetadata.playbackService(seed))
        assertEquals("Lontano dagli occhi",seed.trackTitle)
        assertEquals("Nome completo dell'interprete",seed.artist)
    }

    @Test fun laterEvidenceAddsRecordingMetadataWithoutLoweringTheExistingIndex() {
        val initial=row().copy(musiclabScore100=95)
        val release=CoverSourceCandidate(title=initial.trackTitle,artist=initial.artist,
            sources=listOf("Apple/iTunes"),year=1970,album="Published single",releaseVerified=true,
            playbackVideoId=initial.resolvedVideoId,playbackVideoTitle=initial.trackTitle)
        val enriched=CoverPriorityOrder.refreshEvidence(initial,release,initial.trackTitle,"",1)
        assertEquals(95,enriched.musiclabScore100 ?: -1)
        assertEquals("1970",CoverCardMetadata.publicationYear(enriched))
        assertEquals("Published single",enriched.releaseTitle)
        assertTrue(CoverCardMetadata.sources(enriched).contains("Apple/iTunes"))
    }
}
