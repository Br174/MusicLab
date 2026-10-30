package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicRankExperimentalTest {
    @Test fun separatePackageOnly() {
        assertTrue(MusicRankExperimental.enabled("it.verlezza.musiclab.musicrank"))
        assertFalse(MusicRankExperimental.enabled("it.verlezza.musiclab.turbo01"))
    }

    @Test fun rankIsBoundedAndMonotonic() {
        val empty = MusicRankExperimental.score(false, false, false, false, false, false, false)
        val documented = MusicRankExperimental.score(false, true, true, false, false, false, true)
        val complete = MusicRankExperimental.score(true, true, true, true, true, true, true)
        assertEquals(0, empty)
        assertTrue(documented > empty)
        assertEquals(100, complete)
    }

    @Test fun videoAloneCannotLookLikeCertifiedRecording() {
        val videoOnly = MusicRankExperimental.score(false, false, false, true, true, false, false)
        assertTrue(videoOnly < 30)
    }
}
