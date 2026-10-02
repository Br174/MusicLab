package com.metrolist.music.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalVersionSearchEngineTest {
    @Test
    fun `Originali accepts bracketed translated alias but rejects another song title`() {
        assertTrue(
            OriginalVersionSearchEngine.isOriginalTitleCompatible(
                value = "Bravi Ragazzi (Bravo Muchachos)",
                targetTitle = "Bravi ragazzi",
            ),
        )
        assertFalse(
            OriginalVersionSearchEngine.isOriginalTitleCompatible(
                value = "Il mondo che vorrei",
                targetTitle = "Il mondo",
            ),
        )
    }
}
