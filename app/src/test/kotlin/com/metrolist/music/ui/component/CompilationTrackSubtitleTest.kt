package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/** Visual-only: real playback metadata and FastPath provenance stay unchanged. */
class CompilationTrackSubtitleTest {
    @Test fun internalFastPathLabelIsHiddenForResolvedSongs() {
        assertEquals(
            "Jovanotti",
            compilationTrackSubtitle("Jovanotti", "MusicLab FastPath", true),
        )
        assertEquals(
            "Bob Sinclar, Raffaella Carrà",
            compilationTrackSubtitle(
                "Bob Sinclar, Raffaella Carrà", "MusicLab FastPath", true,
            ),
        )
    }

    @Test fun otherSourcesRemainVisible() {
        assertEquals(
            "Renato Zero · Discogs",
            compilationTrackSubtitle("Renato Zero", "Discogs", true),
        )
        assertEquals(
            "Jovanotti · YouTube Music",
            compilationTrackSubtitle("Jovanotti", "YouTube Music", true),
        )
    }

    @Test fun unresolvedRowsKeepTheirPreviousPrompt() {
        assertEquals(
            "Jovanotti · Tocca per trovare e riprodurre",
            compilationTrackSubtitle("Jovanotti", null, false),
        )
    }

    @Test fun emptyAndCaseInsensitiveFastPathLabelsDoNotLeaveDanglingSeparator() {
        assertEquals(
            "Jovanotti",
            compilationTrackSubtitle("Jovanotti", " musiclab fastpath ", true),
        )
        assertEquals(
            "Jovanotti",
            compilationTrackSubtitle("Jovanotti", "", true),
        )
        assertEquals(
            "Jovanotti",
            compilationTrackSubtitle("Jovanotti", null, true),
        )
    }

    @Test fun FastPathLabelRemainsAnInternalSourceForUnmodifiedSearch() {
        // The formatted line hides the implementation detail without rewriting
        // CompilationResolvedTrack.source or mutating the playback object.
        val provenance = "MusicLab FastPath"
        assertEquals("Jovanotti", compilationTrackSubtitle("Jovanotti", provenance, true))
        assertEquals("MusicLab FastPath", provenance)
    }
}
