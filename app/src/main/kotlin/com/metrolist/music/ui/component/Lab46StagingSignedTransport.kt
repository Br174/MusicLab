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

    internal const val STAGING_ORIGIN = "https://musiclab-turbo-lab46-isolated-staging.brunoverlezza.workers.dev"
    internal const val PAIRED_KEY_ID = "NzGMqTSRrL3LgC5vzD2ECKqItuCFaUwCQ_UTanT1lXk"
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
     * Pure protocol decision for a received response, used by recovery and
     * JVM tests. A committed server replay from a previous interrupted run
     * is FINAL proof and needs no extra POST. New commits still require one
     * signed idempotency replay before deleting the selected local item.
     * 409/pending, 5xx, malformed or mismatched receipts always retain it.
     */
    internal enum class ReceiptStep { RETAIN_LOCAL, VERIFY_BY_REPLAY, ACKNOWLEDGE }

    internal fun receiptStep(
        httpCode: Int, responseJson: String, revision: String,
        fingerprint: String, mustBeReplay: Boolean,
    ): ReceiptStep = runCatching {
        if (httpCode != 200 || !verifiedReceipt(responseJson, revision, fingerprint))
            return@runCatching ReceiptStep.RETAIN_LOCAL
        val body = JSONObject(responseJson)
        val replayed = body.opt("replayed")
        if (replayed !is Boolean) return@runCatching ReceiptStep.RETAIN_LOCAL
        if (replayed) ReceiptStep.ACKNOWLEDGE
        else if (mustBeReplay) ReceiptStep.RETAIN_LOCAL
        else ReceiptStep.VERIFY_BY_REPLAY
    }.getOrDefault(ReceiptStep.RETAIN_LOCAL)

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
        // CANDIDATA 01 is a new Android family with NO staging enrollment.
        // There is no Cloud submission until owner separately approves an
        // authenticated candidate pairing and the release gate is rebuilt.
        if (com.metrolist.music.BuildConfig.APPLICATION_ID ==
            "it.verlezza.musiclab.candidate01") return@withContext Result.NOT_PAIRED
        if (!binding.ownerConfirmed) return@withContext Result.NOT_PAIRED
        val origin = safeOrigin(binding.explicitlyApprovedOrigin)
            ?: return@withContext Result.BAD_ENDPOINT
        // Owner-authorized LAB46 Worker only, never an arbitrary HTTPS sink.
        if (origin != STAGING_ORIGIN)
            return@withContext Result.BAD_ENDPOINT
        // Do not generate a new key when manually syncing: the original paired
        // AndroidKeyStore alias must still exist and match this enrolled phone.
        val device = Lab46StagingDeviceSigner.existingEnrollmentOrNull()
            ?: return@withContext Result.NOT_PAIRED
        if (device.keyId != binding.confirmedDeviceKeyId ||
            device.keyId != PAIRED_KEY_ID)
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
        val firstStep = receiptStep(
            first.first, first.second, revision, fingerprint, mustBeReplay = false
        )
        if (firstStep == ReceiptStep.RETAIN_LOCAL)
            return@withContext Result.REFUSED_OR_UNCERTAIN
        if (firstStep == ReceiptStep.VERIFY_BY_REPLAY) {
            // A freshly committed decision needs ONE read-only-idempotent
            // server replay of the identical bytes with a new ECDSA nonce.
            // Any network error or uncertain receipt leaves the outbox intact.
            val repeat = submit() ?: return@withContext Result.REFUSED_OR_UNCERTAIN
            if (receiptStep(
                    repeat.first, repeat.second, revision, fingerprint,
                    mustBeReplay = true
                ) != ReceiptStep.ACKNOWLEDGE ||
                !runCatching {
                    JSONObject(repeat.second).optString("versionId") ==
                        JSONObject(first.second).optString("versionId")
                }.getOrDefault(false)
            ) return@withContext Result.REFUSED_OR_UNCERTAIN
        }
        // If firstStep is ACKNOWLEDGE, the server recovered a previously
        // committed revision after a lost response. Do NOT POST it again.
        if (CoverCloudPendingOutbox.acknowledgeSelected(context, choice, revision))
            Result.ACKNOWLEDGED else Result.LOCAL_ACK_FAILED
    }
}
