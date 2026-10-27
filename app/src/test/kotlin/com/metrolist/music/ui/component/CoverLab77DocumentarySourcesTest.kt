package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab77DocumentarySourcesTest {
    @Test fun selectedMusicBrainzSingerIsNotAutomaticallyTheOriginalInterpreter() {
        val lookup = MusicBrainzLookup(
            covers = emptyList(), status = MusicBrainzStatus.OK,
            originalVersions = listOf(MusicBrainzCover("Hallelujah", "Jeff Buckley", "recording", "work", 1994)),
        )
        val method = CoverDiscoverySources::class.java.getDeclaredMethod(
            "discoverMusicBrainz", String::class.java, DiscogsDirectMode::class.java, MusicBrainzLookup::class.java,
        ).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(CoverDiscoverySources, "Hallelujah", DiscogsDirectMode.COVER, lookup)
            as Pair<List<CoverSourceCandidate>, CoverSourceDiagnostic>
        assertEquals(1, result.first.size)
        assertTrue(result.first.single().workRelationConfirmed)
        assertFalse("Selected performer is not documentary first-interpreter attribution", result.first.single().originalWorkReference)
        assertNull(CoverPriorityOrder.originalReference(result.first))
    }
}
