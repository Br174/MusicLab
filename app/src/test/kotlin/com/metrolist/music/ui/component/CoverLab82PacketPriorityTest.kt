package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab82PacketPriorityTest {
    @Test fun workBindingDoesNotDateALiveVideoWithTheStudioAlbumYear() {
        val lead = CoverSourceCandidate(title="Il mondo", artist="Singer", sources=listOf("COVER.INFO"),
            year=1965, album="Studio album", workRelationConfirmed=true)
        val video = CoverSourceCandidate(title="Il mondo live", artist="Singer", sources=listOf("YouTube"),
            playbackVideoId="live0000001", playbackVideoTitle="Il mondo live", category=AiCoverCategory.LIVE)
        val linked = CoverPriorityOrder.bindVideo(video, lead)
        assertTrue(linked.workRelationConfirmed)
        assertNull("A work relationship does not date this recording", linked.year)
        assertEquals("live0000001", linked.playbackVideoId)
    }

    @Test fun readyPacketContainsTenThenTwentyThenTwentyWithoutLosingOverflow() {
        val p=CoverRoundPresentation<String>{it}
        repeat(53){p.stage(1,"row$it")}
        assertEquals(10,p.takeInitial().size)
        assertEquals(10,p.status().shown)
        assertEquals(20,p.status().ready)
        assertEquals(20,p.reveal().size)
        assertEquals(30,p.status().shown)
        assertEquals(20,p.reveal().size)
        assertEquals(50,p.status().shown)
        assertTrue(p.status().green)
        assertEquals(3,p.preparedRows(1).size)
    }
}
