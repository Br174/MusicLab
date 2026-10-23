package com.metrolist.music.ui.component

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class Lab46StagingSigningTest {
    @Test fun canonicalBindsPathTimestampNonceAndBodyHash() {
        val body = """{"revision":"123e4567-e89b-42d3-a456-426614174000"}"""
            .toByteArray(Charsets.UTF_8)
        val hash = Lab46SignatureWireFormat.base64Url(
            MessageDigest.getInstance("SHA-256").digest(body)
        )
        val nonce = Lab46SignatureWireFormat.base64Url(ByteArray(16) { it.toByte() })
        val wire = Lab46SignatureWireFormat.canonical("1792720000", nonce, hash)
        assertEquals(
            "musiclab-lab46-device-v1\nPOST\n/api/v1/turbo/decision-sync\n1792720000\n$nonce\n$hash",
            wire
        )
        assertEquals(22, nonce.length)
        assertEquals(43, hash.length)
        assertFalse(wire.contains("LAB46_WRITE_TOKEN"))
    }

    @Test fun androidDerEcdsaConvertsToWebCryptoRaw64WithVerifiedSignature() {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val pair = generator.generateKeyPair()
        val message = "musiclab-lab46-device-v1\nPOST\n/api/v1/turbo/decision-sync\n" +
            "1792720000\nAAAAAAAAAAAAAAAAAAAAAA\nsha256"
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(message.toByteArray(Charsets.UTF_8))
            sign()
        }
        val raw = Lab46SignatureWireFormat.derToRaw64(der)
        assertEquals(64, raw.size)
        val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
        verifier.initVerify(pair.public)
        verifier.update(message.toByteArray(Charsets.UTF_8))
        assertTrue(verifier.verify(raw))
        val tampered = message.replace("POST", "GET")
        verifier.initVerify(pair.public)
        verifier.update(tampered.toByteArray(Charsets.UTF_8))
        assertFalse(verifier.verify(raw))
        assertArrayEquals(raw, Base64.getUrlDecoder().decode(
            Lab46SignatureWireFormat.base64Url(raw)
        ))
    }

    @Test fun malformedDerAndTruncatedSignaturesNeverBecomeValidRawProof() {
        for (malformed in listOf(
            byteArrayOf(),
            byteArrayOf(0x30, 0x02, 0x01, 0x01),
            byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x80.toByte(),
                0x02, 0x01, 0x01),
            byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01,
                0x02, 0x01, 0x80.toByte())
        )) {
            var rejected = false
            try { Lab46SignatureWireFormat.derToRaw64(malformed) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Malformed DER not rejected", rejected)
        }
    }
}
