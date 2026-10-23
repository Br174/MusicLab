package com.metrolist.music.ui.component

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * LAB46 staging only: owner-paired P-256 signature prototype. Private key never
 * leaves AndroidKeyStore, never enters the APK build or a GitHub/Worker secret.
 * No network dispatch, UI activation, auto-enrollment, or outbox acknowledgement.
 * Enrollment requires an explicit out-of-band owner check of the PUBLIC key
 * fingerprint and later, separately approved Worker configuration.
 */
internal object Lab46StagingDeviceSigner {
    private const val ALIAS = "musiclab-lab46-staging-owner-device-v1"
    private const val PATH = "/api/v1/turbo/decision-sync"
    private val random = SecureRandom()

    data class Enrollment(val publicSpkiBase64Url: String, val keyId: String)
    data class SignedRequest(
        val bytes: ByteArray,
        val headers: Map<String, String>,
    )

    private fun pair(): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
        val publicKey = requireNotNull(store.getCertificate(ALIAS)).publicKey
        val privateKey = requireNotNull(store.getKey(ALIAS, null)) as java.security.PrivateKey
        return KeyPair(publicKey, privateKey)
    }

    /**
     * Read-only lookup for the native manual-sync screen.
     * Never generates a key, replaces the paired one or exports private data.
     */
    internal suspend fun existingEnrollmentOrNull(): Enrollment? =
        withContext(Dispatchers.IO) {
            runCatching {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (!store.containsAlias(ALIAS)) return@runCatching null
                val publicSpki = store.getCertificate(ALIAS)?.publicKey?.encoded
                    ?: return@runCatching null
                Enrollment(
                    publicSpkiBase64Url = Lab46SignatureWireFormat.base64Url(publicSpki),
                    keyId = Lab46SignatureWireFormat.base64Url(
                        MessageDigest.getInstance("SHA-256").digest(publicSpki)
                    ),
                )
            }.getOrNull()
        }

    suspend fun enrollment(): Enrollment = withContext(Dispatchers.IO) {
        val spki = pair().public.encoded
        Enrollment(
            publicSpkiBase64Url = Lab46SignatureWireFormat.base64Url(spki),
            keyId = Lab46SignatureWireFormat.base64Url(
                MessageDigest.getInstance("SHA-256").digest(spki)
            ),
        )
    }

    /**
     * Only signs an already-created durable outbox envelope. Never sends it.
     * Acknowledgement may occur ONLY after an authenticated server receipt
     * with the exact same revision.
     */
    suspend fun signSyncEnvelope(
        jsonUtf8: ByteArray,
        timestampSeconds: Long = System.currentTimeMillis() / 1000L,
    ): SignedRequest = withContext(Dispatchers.IO) {
        require(jsonUtf8.isNotEmpty() && jsonUtf8.size <= 65536)
        require(timestampSeconds in 1_000_000_000L..9_999_999_999L)
        val pair = pair()
        val nonce = ByteArray(16).also(random::nextBytes)
        val nonceB64 = Lab46SignatureWireFormat.base64Url(nonce)
        val bodyHash = Lab46SignatureWireFormat.base64Url(
            MessageDigest.getInstance("SHA-256").digest(jsonUtf8)
        )
        val canonical = Lab46SignatureWireFormat.canonical(
            timestampSeconds.toString(), nonceB64, bodyHash,
        )
        val signedDer = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(canonical.toByteArray(Charsets.UTF_8))
            sign()
        }
        val keyId = Lab46SignatureWireFormat.base64Url(
            MessageDigest.getInstance("SHA-256").digest(pair.public.encoded)
        )
        SignedRequest(
            bytes = jsonUtf8.copyOf(),
            headers = mapOf(
                "X-Lab46-Key-Id" to keyId,
                "X-Lab46-Timestamp" to timestampSeconds.toString(),
                "X-Lab46-Nonce" to nonceB64,
                "X-Lab46-Signature" to Lab46SignatureWireFormat.base64Url(
                    Lab46SignatureWireFormat.derToRaw64(signedDer)
                ),
            ),
        )
    }
}

/** Pure, JVM-testable wire format; WebCrypto needs P1363 r||s, not Android DER. */
internal object Lab46SignatureWireFormat {
    fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun canonical(timestamp: String, nonce: String, bodyHash: String): String =
        "musiclab-lab46-device-v1\nPOST\n/api/v1/turbo/decision-sync\n" +
            timestamp + "\n" + nonce + "\n" + bodyHash

    fun derToRaw64(der: ByteArray): ByteArray {
        require(der.size in 8..72)
        var pos = 0
        fun read(): Int = der[pos++].toInt() and 0xff
        require(read() == 0x30)
        require(read() == der.size - 2) // strict short-form sequence length
        val result = ByteArray(64)
        for (part in 0..1) {
            require(pos < der.size && read() == 0x02)
            val length = read()
            require(length in 1..33 && pos + length <= der.size)
            val start = pos
            val first = der[start].toInt() and 0xff
            val next = if (length > 1) der[start + 1].toInt() and 0xff else 0
            require((first and 0x80) == 0) // no negative integer
            require(!(first == 0 && length > 1 && (next and 0x80) == 0))
            val skip = if (first == 0 && length > 1) 1 else 0
            val usable = length - skip
            require(usable in 1..32)
            der.copyInto(result, part * 32 + 32 - usable, start + skip, start + length)
            pos += length
        }
        require(pos == der.size)
        return result
    }
}
