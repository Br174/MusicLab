package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiCoverVerificationTest {
    @Test
    fun groundedResponseCountsUniqueWebSources() {
        val response =
            """
            {
              "candidates": [{
                "content": {"parts": [{"text": "{\"verdict\":\"same_work\",\"adapted_title\":true}"}]},
                "groundingMetadata": {
                  "groundingChunks": [
                    {"web": {"uri": "https://example.com/a", "title": "A"}},
                    {"web": {"uri": "https://example.com/a", "title": "A duplicate"}},
                    {"web": {"uri": "https://example.com/b", "title": "B"}}
                  ]
                }
              }]
            }
            """.trimIndent()

        val parsed = GeminiCoverVerification.parseGroundedResponse(response)

        assertNotNull(parsed)
        assertEquals(2, parsed?.webSourceCount)
        assertTrue(parsed?.text.orEmpty().contains("same_work"))
    }

    @Test
    fun verificationParserAcceptsCodeFencedJson() {
        val verdict =
            GeminiCoverVerification.parseVerificationText(
                text = """```json
                    {"verdict":"same_work","adapted_title":true}
                    ```""".trimIndent(),
                webSourceCount = 2,
            )

        assertNotNull(verdict)
        assertEquals(GeminiCoverDecision.SAME_WORK, verdict?.decision)
        assertTrue(verdict?.translatedOrAdaptedTitle == true)
        assertEquals(2, verdict?.webSourceConfirmations)
    }

    @Test
    fun differentWorkVerdictIsPreserved() {
        val verdict =
            GeminiCoverVerification.parseVerificationText(
                text = """{"verdict":"different_work","adapted_title":false}""",
                webSourceCount = 1,
            )

        assertEquals(GeminiCoverDecision.DIFFERENT_WORK, verdict?.decision)
        assertFalse(verdict?.translatedOrAdaptedTitle ?: true)
        assertEquals(1, verdict?.webSourceConfirmations)
    }

    @Test
    fun discoveryParserKeepsTranslatedTitlesAndDropsIncompleteRows() {
        val references =
            GeminiCoverVerification.parseDiscoveryText(
                """
                {
                  "covers": [
                    {"title":"My Way","artist":"Frank Sinatra","adapted_title":true},
                    {"title":"Comme d'habitude","artist":"Claude François","adapted_title":false},
                    {"title":"Missing artist","artist":"","adapted_title":false}
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(2, references.size)
        assertEquals("My Way", references.first().title)
        assertTrue(references.first().translatedOrAdaptedTitle)
    }

    @Test
    fun unknownVerdictIsRejectedByParser() {
        val verdict =
            GeminiCoverVerification.parseVerificationText(
                text = """{"verdict":"probably","adapted_title":false}""",
                webSourceCount = 2,
            )

        assertEquals(null, verdict)
    }
}
