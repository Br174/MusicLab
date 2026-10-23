package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Lab46SignedTransportSafetyTest {
    private val revision = "123e4567-e89b-42d3-a456-426614174000"
    private val fingerprint = "test|artist|version|01"
    private fun receipt(revision: String = this.revision,
                        fingerprint: String = this.fingerprint,
                        code: String = "staging_verified_committed",
                        status: String = "salvato"): String =
        """{"stato":"$status","receipt":"$code","revision":"$revision","fingerprint":"$fingerprint","versionId":"version-123"}"""

    @Test fun onlyExplicitCanonicalHttpsOriginIsAccepted() {
        assertEquals(
            "https://musiclab-staging.example.test",
            Lab46StagingSignedTransport.safeOrigin("https://musiclab-staging.example.test")
        )
        assertEquals(
            "https://musiclab-staging.example.test",
            Lab46StagingSignedTransport.safeOrigin("https://musiclab-staging.example.test/")
        )
        for (bad in listOf(
            "", "http://musiclab-staging.example.test",
            "https://musiclab-staging.example.test/api",
            "https://musiclab-staging.example.test/?token=x",
            "https://musiclab-staging.example.test/#fragment",
            "https://user:secret@musiclab-staging.example.test",
            "https://musiclab-staging.example.test:8443",
            " https://musiclab-staging.example.test",
        )) {
            assertEquals("Unsafe origin accepted: $bad",null,
                Lab46StagingSignedTransport.safeOrigin(bad))
        }
    }

    @Test fun exactCommittedReceiptRequiredBeforeClearingOutbox() {
        assertTrue(Lab46StagingSignedTransport.verifiedReceipt(
            receipt(), revision, fingerprint
        ))
        assertFalse(Lab46StagingSignedTransport.verifiedReceipt(
            receipt(revision="other-revision"),revision,fingerprint
        ))
        assertFalse(Lab46StagingSignedTransport.verifiedReceipt(
            receipt(fingerprint="other-fingerprint"),revision,fingerprint
        ))
        assertFalse(Lab46StagingSignedTransport.verifiedReceipt(
            receipt(code="fake_receipt"),revision,fingerprint
        ))
        assertFalse(Lab46StagingSignedTransport.verifiedReceipt(
            receipt(status="pending"),revision,fingerprint
        ))
        assertFalse(Lab46StagingSignedTransport.verifiedReceipt(
            """{"stato":"salvato","revision":"$revision","fingerprint":"$fingerprint","versionId":"x"}""",
            revision,fingerprint
        ))
        assertFalse(Lab46StagingSignedTransport.verifiedReceipt(
            "not-json",revision,fingerprint
        ))
        assertFalse(Lab46StagingSignedTransport.verifiedReceipt(
            """{"stato":"salvato","receipt":"staging_verified_committed","revision":"$revision","fingerprint":"$fingerprint","versionId":""}""",
            revision,fingerprint
        ))
    }


    @Test fun publicKeyPairingProbeNeedsExactAuthenticatedNonWriting422() {
        val proof = """{"stato":"rifiutato","reason":"invalid_decision_identity"}"""
        assertTrue(Lab46StagingSignedTransport.verifiedPairingProbe(422,proof))
        for ((status,response) in listOf(
            200 to proof,
            401 to proof,
            403 to proof,
            422 to """{"stato":"rifiutato","reason":"wrong_key"}""",
            422 to """{"stato":"salvato","reason":"invalid_decision_identity"}""",
            422 to """{"stato":"rifiutato","reason":"invalid_decision_identity","receipt":"fake"}""",
            422 to """{"stato":"rifiutato","reason":"invalid_decision_identity","versionId":"X"}""",
            422 to "not-json",
        )) {
            assertFalse(Lab46StagingSignedTransport.verifiedPairingProbe(status,response))
        }
    }


    /** Signed server response is the only authority for local outbox removal. */
    private fun committed(replayed: String, version: String = "version-123") =
        """{"stato":"salvato","receipt":"staging_verified_committed","revision":"$revision","fingerprint":"$fingerprint","versionId":"$version","replayed":$replayed}"""

    @Test fun newCommitMustBeVerifiedByExactlyOneReplayBeforeLocalAcknowledgement() {
        val disposition = Lab46StagingSignedTransport
        assertEquals(disposition.ReceiptStep.VERIFY_BY_REPLAY,
            disposition.receiptStep(200, committed("false"), revision, fingerprint, false))
        assertEquals(disposition.ReceiptStep.RETAIN_LOCAL,
            disposition.receiptStep(200, committed("false"), revision, fingerprint, true))
        assertEquals(disposition.ReceiptStep.ACKNOWLEDGE,
            disposition.receiptStep(200, committed("true"), revision, fingerprint, true))
    }

    @Test fun committedReplayAfterInterruptedResponseNeedsNoSecondPost() {
        // Server committed but Android lost its HTTP response (or restarted).
        // Same durable revision survives locally. Its first valid replay is
        // proof of previous commit; no second network write is required.
        assertEquals(Lab46StagingSignedTransport.ReceiptStep.ACKNOWLEDGE,
            Lab46StagingSignedTransport.receiptStep(
                200, committed("true"), revision, fingerprint, false))
    }

    @Test fun uncertainNetworkErrorsAndPendingReceiptPreserveOriginalOutbox() {
        val s = Lab46StagingSignedTransport
        val pending = """{"stato":"bloccato","reason":"pending_requires_recovery","revision":"$revision"}"""
        for ((http, body) in listOf(
            401 to committed("true"), 409 to pending,
            500 to committed("true"), 503 to committed("true"),
            200 to "", 200 to "invalid-json",
            200 to committed("null"), 200 to committed("\"true\""),
            200 to receipt(), // legacy response without replay flag
            200 to committed("true").replace(revision, "other-revision"),
            200 to committed("true").replace(fingerprint, "wrong-fingerprint"),
            200 to committed("true").replace("version-123", ""),
            200 to committed("true").replace("staging_verified_committed","fake"),
        )) {
            assertEquals("Must retain local revision for HTTP $http / $body",
                s.ReceiptStep.RETAIN_LOCAL,
                s.receiptStep(http, body, revision, fingerprint, false))
        }
    }

    @Test fun replayCannotMasqueradeAsNewReceiptOrSkipSecondProof() {
        val s = Lab46StagingSignedTransport
        val fakeSuccess = """{"stato":"salvato","receipt":"staging_verified_committed","revision":"$revision","fingerprint":"$fingerprint","versionId":"version-123","replayed":false}"""
        assertEquals(s.ReceiptStep.RETAIN_LOCAL,
            s.receiptStep(200, fakeSuccess, revision, fingerprint, true))
        assertEquals(s.ReceiptStep.RETAIN_LOCAL,
            s.receiptStep(201, committed("true"), revision, fingerprint, true))
    }


}
