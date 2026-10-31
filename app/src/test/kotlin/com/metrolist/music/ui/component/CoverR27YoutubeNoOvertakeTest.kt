package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

/** R27: YouTube may enter, but early readiness never steals documentary slots. */
class CoverR27YoutubeNoOvertakeTest {
    private fun candidate(source: String, id: String, documented: Boolean = false) =
        CoverSourceCandidate(
            title = "Il mondo", artist = "Singer", sources = listOf(source),
            playbackVideoId = id, playbackVideoTitle = "Il mondo",
            workRelationConfirmed = documented,
        )

    @Test fun threeDocumentaryAttemptsCannotGiveStandaloneYoutubeFastPass() {
        val q = CoverPriorityOrder.Pending()
        repeat(12) { i ->
            q.offer(candidate("COVER.INFO", "lead-$i", documented = true).copy(
                playbackVideoId = null, sourceUrl = "https://cover.info/$i"
            ))
        }
        q.offer(candidate("YouTube", "yt-standalone"))
        q.beginPass()
        repeat(3) {
            assertNull(q.next("Il mondo", "", 1)?.playbackVideoId)
        }
        assertNull("The fourth slot must still investigate the documentary source",
            q.next("Il mondo", "", 1)?.playbackVideoId)
        assertEquals(13, q.size)
        assertEquals("yt-standalone", q.next("Il mondo", "", 1,
            allowYoutubeFallback = true)?.playbackVideoId)
        assertEquals(13, q.size)
    }

    @Test fun accreditedReadyVideoCanAdvanceAfterThreeDocumentaryAttempts() {
        val q = CoverPriorityOrder.Pending()
        repeat(7) { i ->
            q.offer(candidate("COVER.INFO", "lead-$i", true).copy(
                playbackVideoId = null, sourceUrl = "https://cover.info/$i"))
        }
        q.offer(candidate("YouTube", "ready-youtube"))
        q.offer(candidate("Discogs", "ready-discogs"))
        q.beginPass()
        repeat(3) { assertNull(q.next("Il mondo", "", 1)?.playbackVideoId) }
        assertEquals("ready-discogs", q.next("Il mondo", "", 1)?.playbackVideoId)
        assertEquals(9, q.size)
    }

    @Test fun youtubeHostedVerifiedCatalogVideoIsNotStandaloneDiscovery() {
        assertFalse(CoverPriorityOrder.isIndependentYoutubeSource(
            listOf("COVER.INFO", "YouTube"), true))
        assertFalse(CoverPriorityOrder.isIndependentYoutubeSource(
            listOf("Discogs", "YouTube Music"), true))
        assertFalse(CoverPriorityOrder.isIndependentYoutubeSource(
            listOf("MusicLab", "YouTube"), true))
        assertTrue(CoverPriorityOrder.isIndependentYoutubeSource(listOf("YouTube"), true))
        assertTrue(CoverPriorityOrder.isIndependentYoutubeSource(listOf("YouTube Music"), true))
    }

    @Test fun firstTenSlotsWaitOnlyDuringBoundedSourceWindow() {
        fun row(source: String, id: String) =
            DiscogsVersionSource.externalSeed(candidate(source, id), "Il mondo", "")
                .copy(resolvedVideoId = id, videoResolutionChecked = true)
        val youtube = row("YouTube", "yt-1")
        val music = row("YouTube Music", "ytm-1")
        val discogs = row("Discogs", "dis-1")
        val documented = row("COVER.INFO", "cover-1")
        val prepared = listOf(youtube, music, discogs, documented)
        assertTrue(CoverPriorityOrder.reserveYoutubeUntilFirstSourceWindow(
            prepared, 1_000L, true))
        assertFalse(CoverPriorityOrder.reserveYoutubeUntilFirstSourceWindow(
            prepared, 7_000L, true))
        assertFalse(CoverPriorityOrder.reserveYoutubeUntilFirstSourceWindow(
            prepared, 1_000L, false))
        assertFalse(CoverPriorityOrder.reserveYoutubeUntilFirstSourceWindow(
            listOf(discogs, documented), 1_000L, true))
        assertFalse(CoverPriorityOrder.reserveYoutubeUntilFirstSourceWindow(
            listOf(row("Discogs", "d-y").copy(sourceNames = listOf("Discogs", "YouTube"))),
            1_000L, true))
    }

    @Test fun youtubeOnlyFallbackRetainsEverySongAndOriginalRoundCaps() {
        val p = CoverRoundPresentation<DiscogsVersionSeed> { it.resolvedVideoId ?: it.fingerprint }
        repeat(53) { i ->
            val c = candidate("YouTube", "youtube-$i")
            assertTrue(p.stage(1, DiscogsVersionSource.externalSeed(c, "Il mondo", "")
                .copy(resolvedVideoId = "youtube-$i", videoResolutionChecked = true)))
        }
        p.prioritizePreparedWith(CoverPriorityOrder.publicationComparator)
        assertEquals(10, p.takeInitial().size)
        assertEquals(20, p.reveal().size)
        assertEquals(20, p.reveal().size)
        assertEquals(50, p.status().shown)
        assertEquals(3, p.preparedRows(1).size)
        assertTrue(p.status().green)
        p.advance()
        assertEquals(2, p.visibleRound)
        assertEquals(3, p.preparedRows(2).size)
    }
}
