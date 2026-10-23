package com.metrolist.music.ui.component

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Dormant LAB46 transport for a future explicit owner-enrolled staging device.
 * NOT invoked from app screens, playback or background services.
 * Never embeds privileged bearer/API secrets; Android signs via Keystore.
 * Any future activation requires separate approval, real endpoint and key
 * enrollment, plus device validation before shipping an APK.
 */
internal object Lab46StagingSignedTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    internal enum class Result {
        NOT_PAIRED, BAD_ENDPOINT, EMPTY, INVALID_PENDING, NETWORK_FAILED,
        REFUSED_OR_UNCERTAIN, ACKNOWLEDGED, LOCAL_ACK_FAILED
    }

    internal data class EnrollmentBinding(
        val explicitlyApprovedOrigin: String,
        val confirmedDeviceKeyId: String,
        val ownerConfirmed: Boolean,
    )

    private const val ENDPOINT = "/api/v1/turbo/decision-sync"

    internal fun safeOrigin(value: String): String? {
        if (value.isBlank() || value != value.trim()) return null
        return runCatching {
            val uri = URI(value)
            if (uri.scheme != "https" || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null ||
                uri.host.isNullOrBlank() || uri.port !in listOf(-1, 443) ||
                uri.rawPath !in listOf("", "/") ||
                uri.toASCIIString() != value ||
                uri.host.contains(':') // no direct IPv6 addresses
            ) null else uri.toASCIIString().trimEnd('/')
        }.getOrNull()
    }

    internal fun verifiedReceipt(
        responseJson: String, expectedRevision: String, expectedFingerprint: String
    ): Boolean = runCatching {
        val body = JSONObject(responseJson)
        body.optString("stato") == "salvato" &&
            body.optString("receipt") == "staging_verified_committed" &&
            body.optString("revision") == expectedRevision &&
            body.optString("fingerprint") == expectedFingerprint &&
            body.optString("versionId").isNotBlank()
    }.getOrDefault(false)

    /**
     * One pending revision per explicit caller invocation, never automatic.
     * A failed connection or uncertain receipt leaves the outbox untouched.
     */
    suspend fun sendOnePending(
        context: Context,
        workKey: String,
        binding: EnrollmentBinding,
    ): Result = withContext(Dispatchers.IO) {
        if (!binding.ownerConfirmed) return@withContext Result.NOT_PAIRED
        val origin = safeOrigin(binding.explicitlyApprovedOrigin)
            ?: return@withContext Result.BAD_ENDPOINT
        val enrollment = runCatching { Lab46StagingDeviceSigner.enrollment() }
            .getOrNull() ?: return@withContext Result.NOT_PAIRED
        if (enrollment.keyId != binding.confirmedDeviceKeyId) {
            return@withContext Result.NOT_PAIRED
        }
        val pending = CoverCloudPendingOutbox.pending(context, workKey)
        val item = pending.firstOrNull() ?: return@withContext Result.EMPTY
        val revision = item.optString("revision")
        val fingerprint = item.optString("fingerprint")
        if (!revision.matches(Regex(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-" +
                    "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
            )) || fingerprint.isBlank() ||
            item.optJSONObject("payload") == null
        ) return@withContext Result.INVALID_PENDING
        val signed = runCatching {
            Lab46StagingDeviceSigner.signSyncEnvelope(
                item.toString().toByteArray(Charsets.UTF_8)
            )
        }.getOrNull() ?: return@withContext Result.REFUSED_OR_UNCERTAIN
        val request = Request.Builder()
            .url(origin + ENDPOINT)
            .post(signed.bytes.toRequestBody(jsonType))
            .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
            .build()
        val response = runCatching { client.newCall(request).execute() }
            .getOrNull() ?: return@withContext Result.NETWORK_FAILED
        response.use {
            // Never accept redirects or unrelated 2xx success responses.
            if (it.code != 200) return@withContext Result.REFUSED_OR_UNCERTAIN
            val result = it.body?.string() ?: return@withContext Result.REFUSED_OR_UNCERTAIN
            if (result.length > 16384 || !verifiedReceipt(result, revision, fingerprint))
                return@withContext Result.REFUSED_OR_UNCERTAIN
        }
        if (CoverCloudPendingOutbox.acknowledge(
                context, workKey, fingerprint, revision, revision
            )) Result.ACKNOWLEDGED else Result.LOCAL_ACK_FAILED
    }


    /** Owner-invoked, deliberately invalid signed payload.
     * Exact HTTP 422 + invalid_decision_identity is emitted by the existing
     * D1 sync coordinator BEFORE ANY receipt lookup or database mutation.
     * This verifies device pairing without creating a musical decision.
     */
    internal enum class PairingProbeResult {
        NOT_PAIRED, BAD_ENDPOINT, NETWORK_FAILED, NOT_VERIFIED, VERIFIED_NO_WRITE
    }

    internal fun verifiedPairingProbe(code: Int, responseJson: String): Boolean =
        runCatching {
            val result=JSONObject(responseJson)
            code == 422 &&
                result.optString("stato") == "rifiutato" &&
                result.optString("reason") == "invalid_decision_identity" &&
                !result.has("receipt") && !result.has("versionId")
        }.getOrDefault(false)

    suspend fun probePairingNoWrite(
        ownerConfirmedOrigin: String, confirmedDeviceKeyId: String,
    ): PairingProbeResult = withContext(Dispatchers.IO) {
        if (confirmedDeviceKeyId.isBlank()) return@withContext PairingProbeResult.NOT_PAIRED
        val origin=safeOrigin(ownerConfirmedOrigin)
            ?: return@withContext PairingProbeResult.BAD_ENDPOINT
        val device=runCatching { Lab46StagingDeviceSigner.enrollment() }.getOrNull()
            ?: return@withContext PairingProbeResult.NOT_PAIRED
        if(device.keyId!=confirmedDeviceKeyId)
            return@withContext PairingProbeResult.NOT_PAIRED
        // No workId, revision, fingerprint or payload, so real existing
        // syncLab46Decision MUST fail before reading/writing any receipt.
        val signed=runCatching {
            Lab46StagingDeviceSigner.signSyncEnvelope(
                "{}".toByteArray(Charsets.UTF_8)
            )
        }.getOrNull() ?: return@withContext PairingProbeResult.NOT_VERIFIED
        val request=Request.Builder()
            .url(origin+ENDPOINT)
            .post(signed.bytes.toRequestBody(jsonType))
            .apply { signed.headers.forEach { (name,value)->header(name,value) } }
            .build()
        val response=runCatching { client.newCall(request).execute() }.getOrNull()
            ?: return@withContext PairingProbeResult.NETWORK_FAILED
        response.use {
            val body=runCatching { it.body?.string() }.getOrNull()
                ?: return@withContext PairingProbeResult.NOT_VERIFIED
            if(body.length>4096)return@withContext PairingProbeResult.NOT_VERIFIED
            if(verifiedPairingProbe(it.code,body))
                PairingProbeResult.VERIFIED_NO_WRITE
            else PairingProbeResult.NOT_VERIFIED
        }
    }


    /**
     * An explicitly selected *existing* outbox revision only. A fresh signed
     * replay verifies remote idempotency, then the exact selected local row
     * is acknowledged. Never create decisions or silently switch revisions.
     */
    internal suspend fun sendOneSelectedPending(
        context: Context,
        choice: CoverCloudPendingOutbox.PendingChoice,
        binding: EnrollmentBinding,
    ): Result = withContext(Dispatchers.IO) {
        if (!binding.ownerConfirmed) return@withContext Result.NOT_PAIRED
        val origin = safeOrigin(binding.explicitlyApprovedOrigin)
            ?: return@withContext Result.BAD_ENDPOINT
        // Owner-authorized LAB46 Worker only, never an arbitrary HTTPS sink.
        if (origin != "https://musiclab-turbo-lab46-isolated-staging.brunoverlezza.workers.dev")
            return@withContext Result.BAD_ENDPOINT
        val device = runCatching { Lab46StagingDeviceSigner.enrollment() }
            .getOrNull() ?: return@withContext Result.NOT_PAIRED
        if (device.keyId != binding.confirmedDeviceKeyId ||
            device.keyId != "NzGMqTSRrL3LgC5vzD2ECKqItuCFaUwCQ_UTanT1lXk")
            return@withContext Result.NOT_PAIRED
        val item = CoverCloudPendingOutbox.selectedPending(context, choice)
            ?: return@withContext Result.INVALID_PENDING
        val revision = item.optString("revision")
        val fingerprint = item.optString("fingerprint")
        if (revision != choice.revision || fingerprint != choice.fingerprint ||
            !revision.matches(Regex(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-" +
                "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
            )) || item.optJSONObject("payload") == null)
            return@withContext Result.INVALID_PENDING
        val body = item.toString().toByteArray(Charsets.UTF_8)
        suspend fun submit(): Pair<Int, String>? {
            val signed = runCatching { Lab46StagingDeviceSigner.signSyncEnvelope(body) }
                .getOrNull() ?: return null
            val request = Request.Builder()
                .url(origin + ENDPOINT)
                .post(signed.bytes.toRequestBody(jsonType))
                .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
                .build()
            val response = runCatching { client.newCall(request).execute() }
                .getOrNull() ?: return null
            return response.use {
                val responseBody = runCatching { it.body?.string() }.getOrNull()
                    ?: return@use null
                if (responseBody.length > 16384) null
                else Pair(it.code, responseBody)
            }
        }
        val first = submit() ?: return@withContext Result.NETWORK_FAILED
        if (first.first != 200 || !verifiedReceipt(first.second, revision, fingerprint))
            return@withContext Result.REFUSED_OR_UNCERTAIN
        // Same bytes, revision and fingerprint, but new nonce/signature.
        // The server must return its prior committed receipt, not write again.
        val repeat = submit() ?: return@withContext Result.REFUSED_OR_UNCERTAIN
        if (repeat.first != 200 || !verifiedReceipt(repeat.second, revision, fingerprint) ||
            !runCatching { JSONObject(repeat.second).optBoolean("replayed", false) }
                .getOrDefault(false))
            return@withContext Result.REFUSED_OR_UNCERTAIN
        if (CoverCloudPendingOutbox.acknowledgeSelected(context, choice, revision))
            Result.ACKNOWLEDGED else Result.LOCAL_ACK_FAILED
    }
}
