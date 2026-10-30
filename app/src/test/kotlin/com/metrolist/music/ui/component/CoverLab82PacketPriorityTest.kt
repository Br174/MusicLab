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

    @Test fun idleCompletionCannotAutomaticallyTurnTenIntoThirty() {
        val p=CoverRoundPresentation<String>{it}
        repeat(30){p.stage(1,"row$it")}
        assertEquals(10,p.takeAutomatic().size)
        p.exhaust(1)
        repeat(5){assertTrue(p.takeAutomatic().isEmpty())}
        assertEquals(10,p.status().shown)
        assertEquals(20,p.status().ready)
        assertEquals(20,p.reveal().size)
        assertTrue(p.status().green)
    }

    @Test fun noCompleteFirstPacketPublishesOneResidualAndTurnsGreen() {
        val p=CoverRoundPresentation<String>{it}
        repeat(3){p.stage(1,"row$it")}
        assertTrue(p.takeAutomatic().isEmpty())
        p.exhaust(1)
        assertEquals(listOf("row0","row1","row2"),p.takeAutomatic())
        assertTrue(p.takeAutomatic().isEmpty())
        assertTrue(p.status().green)
    }

    @Test fun firstAndFollowingPacketsUseCompletePriorityRatherThanArrivalOrder() {
        val home=(1..50).map { n -> candidate("Home $n","home$n") }
        val released=candidate("Released","released001").copy(sources=listOf("YouTube Music"),album="Single",releaseVerified=true)
        val credited=candidate("Credited","credited001").copy(sources=listOf("COVER.INFO"),workRelationConfirmed=true)
        val original=candidate("Original","original001").copy(originalWorkReference=true)
        val rows=(home+credited+original+released).map { c ->
            DiscogsVersionSource.externalSeed(c,"Il mondo","Original").copy(
                musiclabScore100=CoverLabTurboRoundAdmission.evaluate(c,"Il mondo","Original",1).confidencePercent)
        }
        val p=CoverRoundPresentation<DiscogsVersionSeed>{it.resolvedVideoId!!}
        // Prepare low-priority results first, then documentary results before the packet is revealed.
        rows.take(8).forEach{p.stage(1,it)}
        rows.takeLast(3).forEach{p.stage(1,it)}
        p.prioritizePreparedWith(CoverPriorityOrder.publicationComparator)
        val first=p.takeAutomatic()
        assertEquals(10,first.size)
        assertEquals("released001",first.first().resolvedVideoId)
        assertTrue(first.take(3).map{it.resolvedVideoId}.containsAll(listOf("credited001","original001")))
        val later=DiscogsVersionSource.externalSeed(released.copy(playbackVideoId="released002"),"Il mondo")
        rows.drop(8).take(19).forEach{p.stage(1,it)}
        p.stage(1,later)
        assertEquals("released002",p.reveal().first().resolvedVideoId)
        assertEquals(30,p.status().shown)
    }

    @Test fun metadataReadyAmateurDoesNotHideAnAccreditedLeadFromLinking() {
        val lead=candidate("Credited","unused").copy(playbackVideoId=null,sources=listOf("COVER.INFO"),workRelationConfirmed=true)
        val home=candidate("Home","home0000001")
        val q=CoverPriorityOrder.Pending()
        q.offer(home);q.offer(lead)
        val input=q.orderingInput("Il mondo","",1)!!
        val ordered=CoverPriorityOrder.order(input.rows,input.title,input.original,input.round,input.twins)
        assertTrue(q.applyOrdering(input,ordered))
        assertEquals("Credited",q.next("Il mondo","",1)!!.artist)
        q.defer(lead)
        assertEquals("Home",q.next("Il mondo","",1)!!.artist)
        assertEquals(2,q.size)
    }

    @Test fun releasedOverflowCanEnterTheFirstPacketWithoutDroppingAnyHomeRecording() {
        val p=CoverRoundPresentation<DiscogsVersionSeed>{it.resolvedVideoId!!}
        repeat(50){p.stage(1,DiscogsVersionSource.externalSeed(candidate("Home $it","home$it"),"Il mondo"))}
        val album=DiscogsVersionSource.externalSeed(candidate("Released","released001").copy(
            sources=listOf("YouTube Music"),releaseVerified=true,album="Single"),"Il mondo")
        p.stage(1,album)
        p.prioritizePreparedWith(CoverPriorityOrder.publicationComparator)
        val first=p.takeAutomatic()
        assertEquals("released001",first.first().resolvedVideoId)
        assertEquals(41,p.preparedRows(1).size)
        assertEquals(51,first.size+p.preparedRows(1).size)
    }

    private fun candidate(artist:String,id:String)=CoverSourceCandidate(title="Il mondo",artist=artist,
        sources=listOf("YouTube"),playbackVideoId=id,playbackVideoTitle="Il mondo")

    @Test fun karaokeChannelRemainsExcludedEvenWithAnExactWorkTitle() {
        val channel=candidate("Singer Karaoke","channel0001")
        val singer=candidate("Singer","singer00001")
        val ordered=CoverPriorityOrder.order(listOf(channel,singer),"Il mondo","",1)
        assertEquals(listOf(singer,channel),ordered)
        assertTrue(CoverLabTurboRoundAdmission.evaluate(channel,"Il mondo","",6).rejected)
        val karaoke=channel.copy(title="Il mondo karaoke",playbackVideoTitle="Il mondo karaoke")
        assertFalse(CoverLabTurboRoundAdmission.evaluate(karaoke,"Il mondo","",1).admitted)
    }
}
