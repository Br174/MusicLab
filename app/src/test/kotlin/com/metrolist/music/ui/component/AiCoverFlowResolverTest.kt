package com.metrolist.music.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCoverFlowResolverTest {
    @Test
    fun `same-language cover title tolerates typography but rejects another Italian title`() {
        assertTrue(
            AiCoverFlowResolver.sameItalianTitle(
                "Cosa resterà degli anni 80",
                "COSA RESTERA' DEGLI ANNI 80",
            ),
        )
        assertTrue(AiCoverFlowResolver.sameItalianTitle("Morire qui", "Morire qui"))
        assertFalse(AiCoverFlowResolver.sameItalianTitle("Il mondo", "Il mondo che vorrei"))
        assertFalse(
            AiCoverFlowResolver.sameItalianTitle(
                "Cosa resterà degli anni 80",
                "Cosa rimane degli anni 80",
            ),
        )
    }
}
