package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab77PriorityQueueTest {
    @Test fun searchBindingDoesNotInventDiscographicRecordingProof() {
        val lead = row("Jimmy Fontana", "").copy(playbackVideoId = null,
            recordingReleaseId = 123, recordingTrackPosition = "A1", album = "Album", releaseVerified = true,
            workRelationConfirmed = true, sources = listOf("Discogs"))
        val raw = row("Jimmy Fontana", "video000001", title = "Il mondo live")
        val linked = CoverPriorityOrder.bindVideo(raw, lead)
        assertEquals("video000001", linked.playbackVideoId)
        assertNull(linked.recordingReleaseId)
        assertNull(linked.recordingTrackPosition)
        assertFalse(linked.releaseVerified)
    }
    private fun row(
        artist: String, id: String, source: String = "YouTube",
        album: String? = null, title: String = "Il mondo",
        documented: Boolean = false, original: Boolean = false,
        category: AiCoverCategory = AiCoverCategory.COVER,
    ) = CoverSourceCandidate(
        title = title, artist = artist, sources = listOf(source), album = album,
        playbackVideoId = id, playbackVideoTitle = title,
        workRelationConfirmed = documented, originalWorkReference = original,
        category = category,
    )

    @Test fun publishedOriginalCreditedAndForeignVersionsPrecedeAmateurAtEqualProof() {
        val amateur = row("Home Singer", "amateur0001")
        val foreign = row("Foreign Singer", "foreign0001", "COVER.INFO",
            title = "El mundo", documented = true, category = AiCoverCategory.FOREIGN)
        val known = row("Credited Singer", "known000001", "MusicBrainz", documented = true)
        val original = row("Jimmy Fontana", "original001", "COVER.INFO",
            documented = true, original = true)
        val released = row("Released Singer", "released001", "MusicBrainz",
            album = "Published album", documented = true).copy(
                sources = listOf("MusicBrainz", "Apple/iTunes")
            )
        assertEquals(
            listOf("released001", "original001", "known000001", "foreign0001", "amateur0001"),
            CoverPriorityOrder.order(
                listOf(amateur, foreign, known, original, released),
                "Il mondo", "Jimmy Fontana", 1
            ).map { it.playbackVideoId }
        )
    }

    @Test fun uncertainSecondTwinIsLastEvenWhenItsWorkEvidenceIsStrong() {
        val ordinary = row("Unknown Singer", "normal00001")
        val possible = row("Jimmy Fontana", "possible001", "COVER.INFO",
            documented = true, original = true)
        val result = CoverPriorityOrder.order(
            listOf(possible, ordinary), "Il mondo", "Jimmy Fontana", 1,
            setOf(CoverPriorityOrder.key(possible))
        )
        assertEquals(listOf("normal00001", "possible001"), result.map { it.playbackVideoId })
    }

    @Test fun rankingDoesNotRemoveAnyWeakOrIncompleteCandidate() {
        val rows = (1..80).map { n ->
            row("Singer $n", "candidate$n", title = if(n % 2 == 0) "Il mondo" else "Uncertain $n")
        }
        val result = CoverPriorityOrder.order(rows, "Il mondo", "", 1)
        assertEquals(80, result.size)
        assertEquals(rows.toSet(), result.toSet())
    }

    @Test fun queueRetainsMoreThan48VideoIdsAndMoreThanFourUnlinkedLeads() {
        val queue = CoverPriorityOrder.Pending()
        for (n in 1..70) queue.offer(row("Singer $n", "video$n"))
        for (n in 1..12) queue.offer(row("Lead $n", "").copy(
            playbackVideoId = null, sourceUrl = "https://cover.info/song/$n"
        ))
        assertEquals(82, queue.size)
    }

    @Test fun laterManualPassResumesUnattemptedWorkWithoutLosingTheRest() {
        val queue = CoverPriorityOrder.Pending()
        for(n in 1..60) queue.offer(row("Singer $n", "video$n"))
        queue.beginPass()
        repeat(50) {
            val next = queue.next("Il mondo", "Jimmy Fontana", 1)
            assertNotNull("A queued candidate must be resumable", next)
            queue.complete(next!!)
        }
        assertEquals(10, queue.size)
        queue.beginPass()
        val rest = generateSequence { queue.next("Il mondo", "Jimmy Fontana", 1) }
            .onEach { queue.complete(it) }.toList()
        assertEquals(10, rest.size)
        assertEquals(0, queue.size)
    }

    @Test fun failedLeadIsRetainedButNotRetriedInAnInfiniteSinglePass() {
        val queue = CoverPriorityOrder.Pending()
        val first = row("Singer", "first000001")
        queue.offer(first)
        queue.beginPass()
        assertNotNull(queue.next("Il mondo", "", 1))
        assertNull(queue.next("Il mondo", "", 1))
        assertEquals(1, queue.size)
        queue.beginPass()
        assertNotNull(queue.next("Il mondo", "", 1))
    }

    @Test fun laterEvidenceForTheSameVideoMergesSourcesInsteadOfDuplicatingIt() {
        val queue = CoverPriorityOrder.Pending()
        queue.offer(row("Uploader", "shared00001"))
        queue.offer(row("True Singer", "shared00001", "COVER.INFO", documented = true))
        assertEquals(1, queue.size)
        val merged = queue.next("Il mondo", "", 1)!!
        assertEquals("True Singer", merged.artist)
        assertTrue(merged.sources.containsAll(listOf("YouTube", "COVER.INFO")))
        assertTrue(merged.workRelationConfirmed)
    }

    @Test fun completedSourceCandidateDoesNotReappearFromCache() {
        val queue = CoverPriorityOrder.Pending()
        val candidate = row("Singer", "cache000001")
        queue.offer(candidate)
        queue.complete(candidate)
        queue.offer(candidate)
        assertEquals(0, queue.size)
    }

    @Test fun sourceCandidateFlaggedAsTwinWaitsUntilOrdinaryWorkIsTaken() {
        val queue = CoverPriorityOrder.Pending()
        val twin = row("Jimmy Fontana", "twin0000001", "COVER.INFO",
            documented = true, original = true)
        val normal = row("Other Singer", "normal00001")
        queue.offer(twin)
        queue.offer(normal)
        queue.markTwin(twin)
        queue.beginPass()
        assertEquals("normal00001", queue.next("Il mondo", "Jimmy Fontana", 1)?.playbackVideoId)
        assertEquals("twin0000001", queue.next("Il mondo", "Jimmy Fontana", 1)?.playbackVideoId)
    }

    @Test fun onlyAnExplicitOriginalWorkReferenceCanReplaceTheSelectedSinger() {
        val cover = row("Cover Singer", "cover000001", "MusicBrainz", documented = true)
        val original = row("Jimmy Fontana", "original001", "COVER.INFO",
            documented = true, original = true)
        assertEquals("Jimmy Fontana", CoverPriorityOrder.originalReference(
            listOf(cover, cover.copy(playbackVideoId = "cover000002"), original)
        )?.artist)
        assertNull(CoverPriorityOrder.originalReference(listOf(cover)))
    }
}
