package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

/** R18 in-memory category tags: no provider calls and no admission/ranking side effects. */
class CoverFilterIdentityTest {
    private fun seed(
        sources: List<String> = listOf("YouTube"),
        title: String = "Il mondo",
        videoTitle: String? = null,
        releaseTitle: String = "",
        kind: DiscogsVersionKind = DiscogsVersionKind.STUDIO,
        language: String? = null,
        verified: Boolean = false,
        releaseId: Int = 0,
    ) = DiscogsVersionSeed(
        trackTitle=title, artist="Altra voce", releaseTitle=releaseTitle,
        releaseId=releaseId, masterId=null, year=null, releaseDate=null,
        kind=kind, country=null, formats=emptyList(), formatDescriptions=emptyList(),
        labels=emptyList(), coverUrl=null, durationSeconds=null, fingerprint=title+sources.joinToString(),
        sourceNames=sources, resolvedVideoTitle=videoTitle, resolvedVideoSource="YouTube",
        language=language, discogsVerificationChecked=verified,
    )

    @Test fun youtubeAndYoutubeMusicWithoutExplicitTagsStayOnlyInTutto() {
        assertTrue(CoverFilterIdentity.tags(seed()).isEmpty())
        assertTrue(CoverFilterIdentity.tags(seed(sources=listOf("YouTube Music"))).isEmpty())
        assertTrue(CoverFilterIdentity.tags(seed(sources=listOf("COVER.INFO","YouTube"))).isEmpty())
    }

    @Test fun commercialCatalogWithoutSpecialDescriptorIsStudioByExclusion() {
        assertEquals(setOf(CoverFilterTag.STUDIO),
            CoverFilterIdentity.tags(seed(sources=listOf("Spotify","YouTube"))))
        assertEquals(setOf(CoverFilterTag.STUDIO),
            CoverFilterIdentity.tags(seed(sources=listOf("Apple/iTunes","YouTube"))))
        assertEquals(setOf(CoverFilterTag.STUDIO), CoverFilterIdentity.tags(seed(
            sources=listOf("Discogs","YouTube"), releaseId=432,releaseTitle="Published Album")))
        assertFalse(CoverFilterTag.STUDIO in CoverFilterIdentity.tags(
            seed(sources=listOf("Discogs"),releaseId=0)))
    }

    @Test fun youtubeExplicitStudioAndLiveAndRemixDoNotRequireCatalog() {
        assertEquals(setOf(CoverFilterTag.STUDIO),
            CoverFilterIdentity.tags(seed(videoTitle="Il mondo (Studio Version)")))
        assertEquals(setOf(CoverFilterTag.LIVE),
            CoverFilterIdentity.tags(seed(videoTitle="Il mondo (Live at Sanremo)")))
        assertEquals(setOf(CoverFilterTag.REMIX),
            CoverFilterIdentity.tags(seed(videoTitle="Il mondo - DJ Club Mix")))
        assertEquals(setOf(CoverFilterTag.REMIX),
            CoverFilterIdentity.tags(seed(videoTitle="Il mondo AI Cover Mashup")))
    }

    @Test fun documentedLiveRemixAndInstrumentalAreNotImplicitStudio() {
        assertFalse(CoverFilterTag.STUDIO in CoverFilterIdentity.tags(
            seed(sources=listOf("Spotify"),videoTitle="Il mondo concerto")))
        assertFalse(CoverFilterTag.STUDIO in CoverFilterIdentity.tags(
            seed(sources=listOf("Apple/iTunes"),kind=DiscogsVersionKind.REMIX)))
        assertFalse(CoverFilterTag.STUDIO in CoverFilterIdentity.tags(
            seed(sources=listOf("Spotify"),title="Il mondo instrumental")))
    }

    @Test fun foreignLanguageAndVideoDescriptorsCanHaveMultipleTags() {
        val bilingual=CoverFilterIdentity.tags(seed(videoTitle="Il mondo Live Spanish Version"))
        assertTrue(CoverFilterTag.LIVE in bilingual)
        assertTrue(CoverFilterTag.FOREIGN in bilingual)
        assertFalse(CoverFilterTag.STUDIO in bilingual)
        assertTrue(CoverFilterTag.FOREIGN in CoverFilterIdentity.tags(
            seed(sources=listOf("COVER.INFO"),language="Spanish")))
    }

    @Test fun choosingCategoryNeverChangesStoredSequenceOrAllMembership() {
        val rows=listOf(seed(),seed(sources=listOf("Spotify")),
            seed(videoTitle="Il mondo live"),seed(videoTitle="Il mundo Spanish Version"))
        val before=rows.map { it.fingerprint }
        val visibleStudio=rows.filter { CoverFilterTag.STUDIO in CoverFilterIdentity.tags(it) }
        assertEquals(1,visibleStudio.size)
        assertEquals(before,rows.map { it.fingerprint })
        assertEquals(4,rows.size)
    }
}
