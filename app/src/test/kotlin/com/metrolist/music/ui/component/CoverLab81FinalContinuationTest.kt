package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab81FinalContinuationTest {
    @Test fun pendingFinalPagesProduceGreenAfterIdleInsteadOfFalseTerminal() {
        var now=0L
        val p=CoverRoundPresentation<String>(clock={now}){it}
        p.finalRound=6
        p.restore(6,emptyList())
        p.beginPreparation(6)
        p.finalProgress(6,complete=false,blocked=false)
        now=7_000L
        p.closeIfIdle(6)
        assertTrue(p.status().green)
        assertFalse(p.status().terminal)
        assertFalse(p.status().noResults)
        assertTrue(p.advance().isEmpty())
        assertEquals(7,p.visibleRound)
    }

    @Test fun failedFinalPageIsRetryableAndCursorRemainsQueued() {
        val q=CoverPriorityOrder.VideoQueries()
        q.offer("Soli",AiCoverCategory.COVER)
        val page=q.next()!!
        q.defer(page)
        q.beginPass()
        assertEquals(page,q.next())
        val p=CoverRoundPresentation<String>{it}
        p.finalRound=6
        p.restore(6,emptyList())
        p.finalProgress(6,complete=false,blocked=true)
        p.exhaust(6)
        assertTrue(p.status().retryRequired)
        assertFalse(p.status().terminal)
    }

    @Test fun descriptorExhaustionCannotErasePlainTitleContinuation() {
        val q=CoverPriorityOrder.VideoQueries()
        q.offer("Soli",AiCoverCategory.COVER)
        q.offer("Soli live",AiCoverCategory.LIVE)
        val title=q.next(texts=setOf("Soli"))!!
        q.finish(title,"page-2")
        val descriptor=q.next(texts=setOf("Soli live"))!!
        q.finish(descriptor,null)
        q.beginPass()
        assertEquals("page-2",q.next(texts=setOf("Soli"))!!.continuation)
        assertEquals(1,q.remainingOrdinary)
    }

    @Test fun aFailedLeadPreventsTerminalEvenWhenOrdinaryPagesAreFinished() {
        val q=CoverPriorityOrder.VideoQueries()
        val lead=CoverSourceCandidate(title="Soli",artist="Singer",sources=listOf("COVER.INFO"))
        q.offer("Singer Soli",AiCoverCategory.COVER,lead=lead)
        val failed=q.forLead(lead)!!
        q.defer(failed)
        assertEquals(0,q.remainingOrdinary)
        assertEquals(1,q.size)
        val p=CoverRoundPresentation<String>{it}
        p.finalRound=6
        p.restore(6,emptyList())
        p.finalProgress(6,complete=q.size==0,blocked=true)
        p.exhaust(6)
        assertFalse(p.status().terminal)
        assertTrue(p.status().retryRequired)
        q.beginPass()
        assertEquals(failed,q.forLead(lead))
        q.finish(failed,null)
        p.finalProgress(6,complete=q.size==0,blocked=false)
        assertTrue(p.status().terminal)
    }

    @Test fun trueFinalExhaustionDistinguishesEmptySearchAndFoundOriginal() {
        for(found in listOf(false,true)) {
            val p=CoverRoundPresentation<String>{it}
            p.finalRound=6
            p.restore(6,emptyList())
            if(found) p.stage(6,"actual-thumbnail")
            p.finalProgress(6,complete=true,blocked=false)
            p.exhaust(6)
            if(found) assertEquals(listOf("actual-thumbnail"),p.reveal())
            assertTrue(p.status().terminal)
            assertEquals(!found,p.status().noResults)
        }
    }

    @Test fun releasedRowsArePreferredOnlyInsideUnpublishedPackets() {
        val p=CoverRoundPresentation<String>{it}
        repeat(10){p.stage(1,"first$it")}
        assertEquals((0..9).map{"first$it"},p.takeInitial())
        repeat(19){p.stage(1,"home$it")}
        p.stage(1,"album")
        p.prioritizePrepared{it=="album"}
        val second=p.reveal()
        assertEquals(20,second.size)
        assertEquals("album",second.first())
        assertEquals(30,p.status().shown)
        assertEquals(0,p.status().ready)
    }

    @Test fun finalPartialPacketRemainsAtomicAndOverflowSurvivesContinuation() {
        val p=CoverRoundPresentation<String>{it}
        p.finalRound=6
        p.restore(6,emptyList())
        repeat(53){p.stage(6,"row$it")}
        assertEquals(10,p.takeInitial().size)
        assertEquals(20,p.reveal().size)
        assertEquals(20,p.reveal().size)
        assertTrue(p.status().green)
        assertTrue(p.advance().isEmpty())
        p.finalProgress(7,complete=true,blocked=false)
        p.exhaust(7)
        assertEquals(listOf("row50","row51","row52"),p.reveal())
        assertTrue(p.status().terminal)
        assertFalse(p.status().noResults)
    }
}
