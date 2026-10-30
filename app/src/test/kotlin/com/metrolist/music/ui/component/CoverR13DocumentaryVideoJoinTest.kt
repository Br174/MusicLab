package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

/** R13: real first-round work link must survive independent arrival order. */
class CoverR13DocumentaryVideoJoinTest {
    private fun documented(
        singer: String, work: String = "Il mondo",
        source: String = "COVER.INFO",
    ) = CoverSourceCandidate(
        title = work, artist = singer, sources = listOf(source),
        workRelationConfirmed = true, playbackVideoId = null,
        recordingReleaseId = 12345, recordingTrackPosition = "A1",
        firstPublicationConfirmed = true, releaseVerified = true,
    )

    private fun native(
        singer: String, title: String = "Il mondo", id: String = "video000001",
    ) = CoverSourceCandidate(
        title = title, artist = singer, sources = listOf("YouTube"),
        playbackVideoId = id, playbackVideoTitle = "$singer - $title",
        durationSeconds = 178,
    )

    @Test fun documentaryWorkAndIndependentNativeVideoQualifyInFirstRound() {
        val documented = documented("Verified Singer")
        val raw = native("Verified Singer")
        assertTrue(CoverLabTurboRoundAdmission.evaluate(raw,
            "Il mondo", "Jimmy Fontana", 1).admitted)
        val joined = CoverPriorityOrder.bindDocumentedVideo(raw, listOf(documented))
        assertTrue(joined.workRelationConfirmed)
        assertEquals("video000001", joined.playbackVideoId)
        assertTrue(joined.sources.containsAll(listOf("YouTube", "COVER.INFO")))
        assertTrue(CoverLabTurboRoundAdmission.evaluate(joined,
            "Il mondo", "Jimmy Fontana", 1, joined.playbackVideoTitle!!).admitted)
        assertNull(joined.recordingReleaseId)
        assertNull(joined.recordingTrackPosition)
        assertFalse(joined.firstPublicationConfirmed)
        assertFalse(joined.releaseVerified)
    }

    @Test fun lateDocumentaryEvidenceUpgradesAnAlreadyQueuedNativeVideoWithoutDuplication() {
        val raw = native("Verified Singer")
        val q = CoverPriorityOrder.Pending()
        q.offer(raw)
        val joined = CoverPriorityOrder.bindDocumentedVideo(raw,
            listOf(documented("Verified Singer")))
        q.offer(joined)
        assertEquals(1, q.size)
        assertEquals(1, q.snapshot().count { it.playbackVideoId == "video000001" })
        assertTrue(q.snapshot().single().workRelationConfirmed)
    }

    @Test fun titleCollisionWithADifferentPerformerCannotBorrowEvidence() {
        val other = documented("Altra Cantante")
        val raw = native("Unrelated Singer")
        assertEquals(raw, CoverPriorityOrder.bindDocumentedVideo(raw, listOf(other)))
    }

    @Test fun titleExtensionForDifferentWorkCannotBorrowCoverInfoProof() {
        val source = documented("Verified Singer")
        val unrelated = native("Verified Singer", "Il mondo che vorrei")
        assertEquals(unrelated, CoverPriorityOrder.bindDocumentedVideo(unrelated, listOf(source)))
    }

    @Test fun undocumentedOrUntrustedCatalogueRowsCannotCreateDocumentaryEvidence() {
        val raw = native("Verified Singer")
        val untrusted = documented("Verified Singer", source="YouTube")
        assertEquals(raw, CoverPriorityOrder.bindDocumentedVideo(raw, listOf(untrusted)))
        assertEquals(raw, CoverPriorityOrder.bindDocumentedVideo(raw,
            listOf(documented("Verified Singer").copy(workRelationConfirmed=false))))
    }

    @Test fun documentedForeignAdaptationStaysInFirstRoundWhenNativeInterpreterAgrees() {
        val foreign = documented("Foreign Singer", "El mundo")
            .copy(category = AiCoverCategory.FOREIGN)
        val raw = native("Foreign Singer", "El mundo")
        val linked = CoverPriorityOrder.bindDocumentedVideo(raw, listOf(foreign))
        assertEquals(AiCoverCategory.FOREIGN, linked.category)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(linked,
            "Il mondo", "Jimmy Fontana", 1).admitted)
    }

    @Test fun tenLinkedNativeVideosStillPublishAtomically() {
        val presentation = CoverRoundPresentation<CoverSourceCandidate> {
            it.playbackVideoId.orEmpty()
        }
        val source = documented("Verified Singer")
        repeat(9) { index ->
            val joined = CoverPriorityOrder.bindDocumentedVideo(
                native("Verified Singer", id = "video0000${index + 10}"),
                listOf(source))
            assertTrue(CoverLabTurboRoundAdmission.evaluate(joined,
                "Il mondo", "Jimmy Fontana", 1).admitted)
            presentation.stage(1, joined)
            assertTrue(presentation.takeAutomatic().isEmpty())
        }
        presentation.stage(1, CoverPriorityOrder.bindDocumentedVideo(
            native("Verified Singer", id = "video000019"), listOf(source)))
        assertEquals(10, presentation.takeAutomatic().size)
        assertEquals(10, presentation.status().shown)
    }
}
