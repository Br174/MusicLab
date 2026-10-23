package com.metrolist.music.ui.component

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// org.json is an Android platform API. JVM tests require Robolectric's
// actual Android implementation, never a production org.json jar that would
// conflict with the device runtime.
@RunWith(RobolectricTestRunner::class)
class CoverEvidenceRecoveryTest {
    @Test fun shsCommonNameIsRecognizedAsPerformer() {
        val item = JSONObject(
            """{"performer":{"uri":"https://secondhandsongs.com/artist/31","commonName":"Riccardo Cocciante"}}"""
        )
        assertEquals(
            "Riccardo Cocciante",
            SecondHandSongsCoverSource.performerFromReference(item),
        )
    }

    @Test fun shsLegacyNameRemainsSupported() {
        val item = JSONObject("""{"performer":{"name":"Interprete prova"}}""")
        assertEquals("Interprete prova", SecondHandSongsCoverSource.performerFromReference(item))
    }

    @Test fun shsStringPerformerRemainsSupported() {
        val item = JSONObject("""{"performer":"Interprete prova"}""")
        assertEquals("Interprete prova", SecondHandSongsCoverSource.performerFromReference(item))
    }

    @Test fun itunesComposerIsEvidenceNotJustSongTitle() {
        val credits = CoverDiscoverySources.composerCreditsFromITunes("Autore Uno; Autore Due")
        assertEquals(2, credits.size)
        assertEquals("Composer", credits[0].role)
        assertEquals("Autore Uno", credits[0].name)
        assertEquals("Autore Due", credits[1].name)
    }

    @Test fun emptyComposerIsNotEvidence() {
        assertTrue(CoverDiscoverySources.composerCreditsFromITunes("").isEmpty())
    }
}
