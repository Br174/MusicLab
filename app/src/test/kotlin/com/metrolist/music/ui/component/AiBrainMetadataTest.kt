package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBrainMetadataTest {
    @Test
    fun wireStatusIsParsedWithoutTurningUnknownValuesIntoRejections() {
        assertEquals(AiBrainDecisionStatus.APPROVED, AiBrainDecisionStatus.fromWire("approved"))
        assertEquals(AiBrainDecisionStatus.PROBABLE, AiBrainDecisionStatus.fromWire("PROBABLE"))
        assertEquals(AiBrainDecisionStatus.UNCERTAIN, AiBrainDecisionStatus.fromWire(" uncertain "))
        assertEquals(AiBrainDecisionStatus.REJECTED, AiBrainDecisionStatus.fromWire("REJECTED"))
        assertNull(AiBrainDecisionStatus.fromWire("legacy"))
        assertNull(AiBrainDecisionStatus.fromWire(null))
    }

    @Test
    fun legacyCandidateKeepsNeutralBrainDefaults() {
        val candidate = AiCoverCandidate(
            title = "Il mondo",
            artist = "Altro interprete",
            category = AiCoverCategory.COVER,
        )

        assertNull(candidate.sameWorkScore)
        assertNull(candidate.versionTypeScore)
        assertNull(candidate.brainStatus)
        assertNull(candidate.brainAdmission)
        assertEquals(emptyList<AiBrainSignal>(), candidate.brainSignals)
    }

    @Test
    fun brainMetadataCanTravelWithCandidateWithoutChangingStableIdentity() {
        val plain = AiCoverCandidate(
            title = "El mundo",
            artist = "Interprete X",
            category = AiCoverCategory.FOREIGN,
            language = "es",
        )
        val decorated = plain.copy(
            sameWorkScore = 82,
            versionTypeScore = 76,
            brainStatus = AiBrainDecisionStatus.PROBABLE,
            brainAdmission = "two_key_match",
            brainSignals = listOf(
                AiBrainSignal(kind = "title_match", strength = "medium"),
                AiBrainSignal(kind = "composer_match", strength = "strong"),
            ),
        )

        assertEquals(plain.stableKey, decorated.stableKey)
        assertEquals(82, decorated.sameWorkScore)
        assertEquals(AiBrainDecisionStatus.PROBABLE, decorated.brainStatus)
        assertEquals(2, decorated.brainSignals.size)
    }

    @Test
    fun wikidataEntityParserKeepsRealMultilingualAliasesAsEvidence() {
        val payload =
            """
            {
              "entities": {
                "Q123": {
                  "id": "Q123",
                  "labels": {
                    "it": {"language":"it","value":"Il mondo"},
                    "en": {"language":"en","value":"Il mondo"}
                  },
                  "aliases": {
                    "es": [{"language":"es","value":"El mundo"}],
                    "en": [{"language":"en","value":"My World"}]
                  }
                }
              }
            }
            """.trimIndent()

        val parsed = WikidataCoverSource.parseEntityPayload(payload, "Q123")

        assertEquals("Q123", parsed?.entityId)
        assertTrue(parsed?.titles.orEmpty().contains("Il mondo"))
        assertTrue(parsed?.aliases.orEmpty().contains("El mundo"))
        assertTrue(parsed?.aliases.orEmpty().contains("My World"))
    }

    @Test
    fun emptyWikidataPayloadStaysNeutralInsteadOfBecomingARejection() {
        val lookup = WikidataCoverSource.lookupFromPayloads(
            searchPayload = "{\"search\":[]}",
            entityPayloadProvider = { null },
        )

        assertEquals(WikidataStatus.NO_MATCH, lookup.status)
        assertNull(lookup.work)
    }
}
