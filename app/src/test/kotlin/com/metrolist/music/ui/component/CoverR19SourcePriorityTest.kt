package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

/** Bruno R19: soft provenance priority, zero loss, no additional catalogue requests. */
class CoverR19SourcePriorityTest {
    private fun candidate(
        source: String, id: String, artist: String = "Singer",
        documented: Boolean = false, original: Boolean = false,
        chart: Int? = null, collectors: Int? = null,
    ) = CoverSourceCandidate(
        title = "Il mondo", artist = artist, sources = listOf(source),
        playbackVideoId = id, playbackVideoTitle = "Il mondo",
        workRelationConfirmed = documented,
        originalWorkReference = original,
        firstPublicationConfirmed = original && documented,
        chartPosition = chart, collectorHave = collectors,
    )

    @Test fun originalThenCoverInfoThenMusicServicesThenMusicLabThenMusicAndLastYoutube() {
        val rows = listOf(
            candidate("YouTube","youtube001"),
            candidate("YouTube Music","ytmusic001"),
            candidate("Archivio Cloud","local00001"),
            candidate("Spotify","spotify001"),
            candidate("Discogs","discogs001"),
            candidate("MusicBrainz","brainz0001"),
            candidate("Apple/iTunes","itunes0001"),
            candidate("COVER.INFO","coverinfo1",documented=true),
            candidate("COVER.INFO","original01",artist="Jimmy Fontana",documented=true,original=true),
        )
        for (round in 1..6) {
            val sorted=CoverPriorityOrder.order(rows,"Il mondo","Jimmy Fontana",round)
            assertEquals(rows.size,sorted.size)
            assertEquals(rows.toSet(),sorted.toSet())
            assertEquals("original01",sorted.first().playbackVideoId)
            assertEquals("coverinfo1",sorted[1].playbackVideoId)
            assertTrue(sorted.indexOfFirst{it.playbackVideoId=="spotify001"} <
                sorted.indexOfFirst{it.playbackVideoId=="local00001"})
            assertTrue(sorted.indexOfFirst{it.playbackVideoId=="local00001"} <
                sorted.indexOfFirst{it.playbackVideoId=="ytmusic001"})
            assertEquals("youtube001",sorted.last().playbackVideoId)
        }
    }

    @Test fun documentedForeignCoverInfoOutranksOrdinaryCatalogCandidate() {
        val foreign=candidate("COVER.INFO","foreign0001",documented=true)
            .copy(title="El mundo",category=AiCoverCategory.FOREIGN)
        val other=candidate("Spotify","catalog0001")
        assertEquals("foreign0001",CoverPriorityOrder.order(
            listOf(other,foreign),"Il mondo","Jimmy Fontana",1).first().playbackVideoId)
    }

    @Test fun chartAndCollectorDataPromoteKnownSingersOnlyInsideTheSameSourceTier() {
        val unknown=candidate("Spotify","unknown001")
        val popular=candidate("Spotify","popular001",chart=1,collectors=45)
        val genericViral=candidate("YouTube","viral00001",chart=1,collectors=5000)
        val ordered=CoverPriorityOrder.order(listOf(genericViral,unknown,popular),
            "Il mondo","Jimmy Fontana",1)
        assertEquals(listOf("popular001","unknown001","viral00001"),
            ordered.map{it.playbackVideoId})
    }

    @Test fun youtubeCanStillFillFirstRoundWhenCatalogServiceIsSlow() {
        val pending=CoverPriorityOrder.Pending()
        repeat(12){i->pending.offer(
            candidate("COVER.INFO","lead000$i",documented=true).copy(
                playbackVideoId=null,sourceUrl="https://cover.info/lead/$i")
        )}
        pending.offer(candidate("YouTube","ready00001"))
        pending.beginPass()
        repeat(3){assertNull(pending.next("Il mondo","Jimmy Fontana",1)?.playbackVideoId)}
        assertEquals("ready00001",pending.next("Il mondo","Jimmy Fontana",1)?.playbackVideoId)
        assertEquals(13,pending.size)
    }

    @Test fun publicationComparatorPrioritizesPreparedSourcesWithoutDeletingAnyPoster() {
        fun seed(c:CoverSourceCandidate)=DiscogsVersionSource.externalSeed(c,"Il mondo","Jimmy Fontana")
            .copy(resolvedVideoId=c.playbackVideoId,videoResolutionChecked=true)
        val raw=listOf(
            candidate("YouTube","vid00000001"),
            candidate("Archivio Cloud","vid00000002"),
            candidate("COVER.INFO","vid00000003",documented=true),
            candidate("YouTube Music","vid00000004"),
        ).map(::seed)
        val sorted=raw.sortedWith(CoverPriorityOrder.publicationComparator)
        assertEquals(raw.map{it.fingerprint}.toSet(),sorted.map{it.fingerprint}.toSet())
        assertEquals("vid00000003",sorted[0].resolvedVideoId)
        assertEquals("vid00000002",sorted[1].resolvedVideoId)
        assertEquals("vid00000004",sorted[2].resolvedVideoId)
        assertEquals("vid00000001",sorted[3].resolvedVideoId)
        assertEquals(raw.size,raw.size)
    }

    @Test fun musicLabAliasNeedsAnAlreadyVerifiedArchivedVideo() {
        val c=candidate("Archivio Cloud","archived001")
        val pending=DiscogsVersionSource.externalSeed(c,"Il mondo","Jimmy Fontana")
            .copy(videoResolutionChecked=false)
        assertFalse(CoverCardMetadata.sources(pending).contains("MusicLab (archivio)"))
        val verified=pending.copy(resolvedVideoId="archived001",videoResolutionChecked=true)
        assertTrue(CoverCardMetadata.sources(verified).contains("MusicLab (archivio)"))
        assertEquals(pending.sourceNames,verified.sourceNames)
    }
}
