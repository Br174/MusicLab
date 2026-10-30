package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab77ResumeAndHistoryTest {
    @Test fun legacySelectedSingerFlagsCannotCreateAnOriginalCapDuringRestoration() {
        val legacy = (1..6).map { row("legacy0000$it", artist = "Jeff Buckley").copy(
            originalWorkReference = true, workRelationConfirmed = true, sourceNames = listOf("MusicBrainz")) }
        val method = CoverRoundStorage::class.java.getDeclaredMethod("retained", List::class.java, String::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val restored = method.invoke(CoverRoundStorage, legacy, null) as List<DiscogsVersionSeed>
        assertEquals(6, restored.size)
        @Suppress("UNCHECKED_CAST")
        val capped = method.invoke(CoverRoundStorage, legacy, "Jeff Buckley") as List<DiscogsVersionSeed>
        assertEquals(5, capped.size)
    }
    @Test fun linkedLeadRetainsContinuationUntilEveryReturnedPageIsProcessed() {
        val pending = CoverPriorityOrder.Pending()
        val lead = CoverSourceCandidate("Il mondo", "Singer", listOf("COVER.INFO"))
        pending.offer(lead); pending.beginPass()
        pending.lookupComplete(lead, linked = true, morePages = true)
        assertEquals(1, pending.size)
        pending.lookupComplete(lead, linked = false, morePages = false)
        assertEquals(0, pending.size)
    }
    @Test fun ordinaryProducerCannotTakeDocumentaryPreparationLane() {
        val searches = CoverPriorityOrder.VideoQueries()
        val lead = CoverSourceCandidate("Il mondo", "Singer", listOf("COVER.INFO"))
        searches.offer("Singer Il mondo", AiCoverCategory.COVER, lead = lead)
        assertNull(searches.next(includeLeads = false))
        assertNotNull(searches.forLead(lead))
    }
    @Test fun laterDocumentaryEvidenceUpdatesOriginalScoreWithoutMovingCard() {
        val prior = row("original001", artist = "Jimmy Fontana").copy(musiclabScore100 = 45, roundPosition = 7)
        val evidence = CoverSourceCandidate("Il mondo", "Jimmy Fontana", listOf("COVER.INFO"),
            playbackVideoId = "original001", playbackVideoTitle = "Il mondo",
            workRelationConfirmed = true, originalWorkReference = true)
        val updated = CoverPriorityOrder.refreshEvidence(prior, evidence, "Il mondo", "Jimmy Fontana", 1)
        assertEquals(prior.fingerprint, updated.fingerprint)
        assertEquals(7, updated.roundPosition)
        assertTrue(updated.musiclabScore100!! >= 90)
        assertTrue(updated.sourceNames.contains("COVER.INFO"))
    }
    @Test fun priorityPreparationSharesOne4400MillisecondWindowPerPress() {
        var now = 0L
        val window = CoverPriorityOrder.PreparationWindow { now }
        assertEquals(1_900L, window.claim(1_900L))
        now = 1_900L
        assertEquals(2_500L, window.claim(3_800L))
        now = 4_400L
        assertEquals(0L, window.claim(3_800L))
    }

    @Test fun emptyLookupRetainsDocumentaryLeadForLaterManualRecovery() {
        val pending = CoverPriorityOrder.Pending()
        val lead = CoverSourceCandidate("Il mondo", "Singer", listOf("COVER.INFO"), workRelationConfirmed = true)
        pending.offer(lead); pending.beginPass()
        pending.lookupComplete(lead, linked = false)
        assertEquals(1, pending.size)
        pending.lookupComplete(lead, linked = true)
        assertEquals(0, pending.size)
    }

    @Test fun relatedDocumentaryPageCanEnqueueItsOwnRelatedContributions() {
        val progress = CoverPriorityOrder.DocumentaryProgress()
        progress.followRelations(CoverPriorityOrder.DocumentaryProgress.Page("https://cover.info/en/song/root", true),
            listOf("https://cover.info/en/song/child"))
        assertEquals("https://cover.info/en/song/child", progress.nextPage()!!.url)
    }
    @Test fun failedProviderPageCannotStarveUntouchedQueriesOnNextPress() {
        val searches = CoverPriorityOrder.VideoQueries()
        searches.offer("first", AiCoverCategory.COVER)
        searches.offer("second", AiCoverCategory.COVER)
        searches.beginPass()
        searches.defer(searches.next()!!)
        searches.beginPass()
        assertEquals("second", searches.next()!!.text)
        assertEquals(2, searches.size)
    }

    @Test fun unavailableHighPriorityLeadLeavesNextPassForOtherRetainedWork() {
        val pending = CoverPriorityOrder.Pending()
        val lead = CoverSourceCandidate("Il mondo", "Jimmy Fontana", listOf("COVER.INFO"), originalWorkReference = true, workRelationConfirmed = true)
        val other = CoverSourceCandidate("Il mondo", "Other", listOf("YouTube"), playbackVideoId = "other000001")
        pending.offer(lead); pending.offer(other); pending.beginPass()
        val first = pending.next("Il mondo", "Jimmy Fontana", 1)!!
        pending.defer(first)
        pending.beginPass()
        assertEquals("Other", pending.next("Il mondo", "Jimmy Fontana", 1)!!.artist)
        assertEquals(2, pending.size)
    }
    @Test fun videoPagesResumeEveryItemWithoutRepeatingCompletedFirstPages() {
        val searches = CoverPriorityOrder.VideoQueries()
        searches.offer("Il mondo official", AiCoverCategory.COVER)
        searches.beginPass()
        val first = searches.next()!!
        searches.finish(first, "page-two")
        searches.offer("Il mondo official", AiCoverCategory.COVER)
        assertEquals("page-two", searches.next()!!.continuation)
        searches.beginPass()
        val resumed = searches.next()!!
        assertEquals("page-two", resumed.continuation)
        searches.finish(resumed, null)
        assertEquals(0, searches.size)
    }

    @Test fun uncertainTwinsCannotBeTakenWhileOrdinarySearchesRemain() {
        val pending = CoverPriorityOrder.Pending()
        val twin = CoverSourceCandidate("Il mondo", "Jimmy Fontana", listOf("YouTube"), playbackVideoId = "twin0000001")
        pending.offer(twin)
        pending.markTwin(twin)
        assertNull(pending.next("Il mondo", "Jimmy Fontana", 1, includeTwins = false))
        assertNotNull(pending.next("Il mondo", "Jimmy Fontana", 1, includeTwins = true))
    }
    private fun row(id: String, title: String = "Il mondo", artist: String = "Jimmy Fontana") =
        DiscogsVersionSeed(
            trackTitle = title, artist = artist, releaseTitle = "Album",
            releaseId = 0, masterId = null, year = null, releaseDate = null,
            kind = DiscogsVersionKind.STUDIO, country = null, formats = emptyList(),
            formatDescriptions = emptyList(), labels = emptyList(), coverUrl = null,
            durationSeconds = 210, fingerprint = id,
            resolvedVideoId = id, resolvedVideoTitle = title,
        )

    @Test fun restoredRowsRespectFiveOriginalsAndContentExceptionsWithoutReranking() {
        val originals = (1..6).map { row("original00$it", "Il mondo nuova incisione $it") }
        val other = row("other000001", artist = "Another Singer")
        val karaoke = row("karaoke0001", "Il mondo Karaoke", "Another Singer")
        val rows = CoverPriorityOrder.retainedRows(originals + other + karaoke, "Jimmy Fontana")
        assertEquals(listOf("original001", "original002", "original003", "original004",
            "original005", "other000001"), rows.map { it.resolvedVideoId })
    }

    @Test fun restoredUncertainStudioTwinsRemainSeparateRatherThanFuzzyMerged() {
        val first = row("studio00001")
        val second = row("studio00002").copy(releaseTitle = "Compilation")
        val rows = CoverPriorityOrder.retainedRows(listOf(first, second), "")
        assertEquals(2, rows.size)
    }

    @Test fun restoredExactVideoDuplicateIsOneRecordingAcrossAlbumContainers() {
        val first = row("studio00001")
        assertEquals(1, CoverPriorityOrder.retainedRows(
            listOf(first, first.copy(releaseTitle = "Compilation")), ""
        ).size)
    }

    @Test fun documentaryPageFailureResumesWithoutRepeatingCompletedPages() {
        val pages = CoverPriorityOrder.DocumentaryProgress()
        pages.enqueue("https://cover.info/page1", false)
        pages.enqueue("https://cover.info/page2", true)
        pages.beginPass()
        val first = pages.nextPage()
        assertEquals("https://cover.info/page1", first?.url)
        pages.completePage(first!!)
        assertEquals("https://cover.info/page2", pages.nextPage()?.url)
        assertNull(pages.nextPage())
        pages.beginPass()
        assertEquals("https://cover.info/page2", pages.nextPage()?.url)
    }

    @Test fun allDocumentaryRowsSurviveAcrossPaginationAndDirectVideoCount() {
        val pages = CoverPriorityOrder.DocumentaryProgress()
        repeat(90) { n -> pages.add(CoverSourceCandidate(
            title = "Versione $n", artist = "Singer $n", sources = listOf("COVER.INFO"),
            sourceUrl = "https://cover.info/song/$n",
        )) }
        assertEquals(90, pages.candidates().size)
    }

    @Test fun sameReleasedTrackIsConfirmedAcrossEditionAlbumsButNotByDurationAlone() {
        val a = row("studio00001").copy(
            releaseId = 100, masterId = 77,
            track = com.metrolist.music.discogs.DiscogsTrack("A1", "Il mondo",
                listOf("Jimmy Fontana"), "3:30", 210)
        )
        val b = a.copy(resolvedVideoId = "studio00002", releaseId = 101,
            releaseTitle = "Reissue")
        assertTrue(CoverLabTurboTwinGuard.sameRecording(a, b))
        val unknown = b.copy(masterId = null, track = null)
        assertFalse(CoverLabTurboTwinGuard.sameRecording(a, unknown))
    }
}
