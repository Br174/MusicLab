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
}
