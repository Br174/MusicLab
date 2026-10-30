package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab78SourceProgressTest {
    @Test fun attemptedOrFailedPageStillCountsAsUnfinishedSearch() {
        val pages = CoverPriorityOrder.VideoQueries()
        pages.offer("Il mondo official", AiCoverCategory.COVER)
        pages.beginPass()
        val page = pages.next()!!
        pages.defer(page)
        assertFalse(pages.hasRunnableOrdinary)
        assertEquals(1, pages.remainingOrdinary)
        pages.beginPass()
        assertEquals(page, pages.next())
        pages.finish(page, "page-two")
        assertEquals(1, pages.remainingOrdinary)
        val next = pages.next()!!
        pages.finish(next, null)
        assertEquals(0, pages.remainingOrdinary)
    }

    @Test fun unresolvedDocumentaryLeadIsRetainedApartFromOrdinaryPageCount() {
        val pages = CoverPriorityOrder.VideoQueries()
        val lead = CoverSourceCandidate("Il mondo", "Singer", listOf("COVER.INFO"))
        pages.offer("Singer Il mondo", AiCoverCategory.COVER, lead = lead)
        val lookup = pages.forLead(lead)!!
        pages.finish(lookup, null)
        pages.retryUnresolved(lookup)
        assertEquals(0, pages.remainingOrdinary)
        assertEquals(1, pages.size)
        pages.beginPass()
        assertEquals(lead, pages.forLead(lead)!!.lead)
    }
}
