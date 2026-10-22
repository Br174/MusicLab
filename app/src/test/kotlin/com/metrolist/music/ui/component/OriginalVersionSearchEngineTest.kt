package com.metrolist.music.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalVersionSearchEngineTest {
    @Test
    fun `Originali denies unverified translation alias and another song title`() {
        assertFalse(
            OriginalVersionSearchEngine.isOriginalTitleCompatible(
                value = "Bravi Ragazzi (Bravo Muchachos)",
                targetTitle = "Bravi ragazzi",
            ),
        )
        assertTrue(
            OriginalVersionSearchEngine.isOriginalTitleCompatible(
                value = "Bravi ragazzi (Remastered)",
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
