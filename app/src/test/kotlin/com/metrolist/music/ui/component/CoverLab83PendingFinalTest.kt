package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab83PendingFinalTest {
    private fun row() = CoverSourceCandidate("Il mondo", "Un interprete", listOf("YouTube"),
        playbackVideoId = "video000001", playbackVideoTitle = "Il mondo")

    @Test fun weakerCandidateIsRetainedAcrossPassesAndBecomesRunnableInItsRound() {
        val p = CoverPriorityOrder.Pending(); val row = row()
        p.offer(row); p.deferUntil(row, 6)
        for (round in 1..5) { p.beginPass(); assertNull(p.next("Il mondo", "Jimmy Fontana", round)) }
        assertEquals(listOf(row), p.snapshot())
        p.beginPass(); assertEquals(row, p.next("Il mondo", "Jimmy Fontana", 6))
    }
    @Test fun newDocumentedFactsReopenADeferredVideoBeforeSixthRound() {
        val p = CoverPriorityOrder.Pending(); val row = row()
        p.offer(row); p.deferUntil(row, 6)
        p.offer(row.copy(sources = listOf("COVER.INFO"), workRelationConfirmed = true))
        val upgraded = p.next("Il mondo", "Jimmy Fontana", 1)
        assertNotNull(upgraded)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(upgraded!!, "Il mondo", "Jimmy Fontana", 1).admitted)
    }
    @Test fun dormantMusicAndDocumentaryLeadsDoNotBlockAutonomousVideoPagination() {
        val q = CoverPriorityOrder.VideoQueries()
        q.offer("Il mondo", AiCoverCategory.COVER, songs = true)
        q.offer("Interprete Il mondo", AiCoverCategory.COVER, lead = row())
        q.offer("Il mondo", AiCoverCategory.COVER)
        q.beginPass()
        val first = q.next(videosOnly = true)!!
        assertFalse(first.songs); assertNull(first.lead)
        q.finish(first, "next-token")
        val next = q.next(videosOnly = true)!!
        assertEquals("next-token", next.continuation)
        q.finish(next, null)
        assertEquals(0, q.remainingVideoPages())
        assertEquals(2, q.size)
        assertNull(q.next(videosOnly = true))
    }
    @Test fun deferredDocumentedVideoCannotBecomeAPublishedCardOnColdStart() {
        val source=row().copy(sources=listOf("COVER.INFO"),workRelationConfirmed=true)
        val published=DiscogsVersionSource.externalSeed(source,"Il mondo","Jimmy Fontana")
            .copy(fingerprint="published",resolvedVideoId="public00001",videoResolutionChecked=true,coverRound=1)
        val waiting=published.copy(fingerprint="waiting",resolvedVideoId="waiting0001")
        val saved=CoverRoundStorage.Snapshot(round=1,rows=listOf(published),deferredRows=listOf(waiting))
        val (visible,deferred)=CoverRoundStorage.partitionForRestoration(saved,"Il mondo","Jimmy Fontana")
        assertEquals(listOf(published),visible)
        assertEquals(listOf(waiting),deferred)
    }
    @Test fun unverifiedMetadataCannotBypassThePublicationBoundaryOnRestore() {
        val source=row().copy(sources=listOf("COVER.INFO"),workRelationConfirmed=true)
        val waiting=DiscogsVersionSource.externalSeed(source,"Il mondo","Jimmy Fontana")
            .copy(videoResolutionChecked=false,coverRound=1)
        val (visible,deferred)=CoverRoundStorage.partitionForRestoration(
            CoverRoundStorage.Snapshot(rows=listOf(waiting)),"Il mondo","Jimmy Fontana")
        assertTrue(visible.isEmpty())
        assertEquals(listOf(waiting),deferred)
    }

}
