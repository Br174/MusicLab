package com.metrolist.music.ui.component

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Corsia cloud condivisa dal motore Cover e dal motore Originali.
 * Il Worker/D1 conserva esclusivamente decisioni editoriali AI; YouTube non entra
 * mai in questa risposta. Se il cloud non risponde, i chiamanti usano Gemini locale.
 */
internal data class CloudMemoryState(
    val discovery: AiCoverDiscoveryResult?,
    val rejectedKeys: Set<String>,
)

internal data class CloudArchiveItem(
    val title: String,
    val artist: String,
    val category: String,
    val language: String?,
    val year: Int?,
    val album: String?,
    val coverUrl: String?,
    val playbackVideoId: String?,
    val playbackVideoTitle: String?,
    val playbackVideoSource: String?,
    val workTitle: String,
    val originalArtist: String,
    val sameWorkScore: Int?,
    val versionTypeScore: Int?,
)

internal object CloudMusicDiscovery {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .build()

    private val activeArchiveCalls = ConcurrentHashMap.newKeySet<okhttp3.Call>()

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun discoverCover(
        title: String,
        artist: String,
        config: GeminiCoverVerificationConfig,
        phase: String,
        existing: List<AiCoverCandidate> = emptyList(),
        focus: String? = null,
    ): AiCoverDiscoveryResult? = withContext(Dispatchers.IO) {
        val root = request(
            title = title,
            artist = artist,
            mode = "cover",
            phase = phase,
            existing = existing,
            focus = focus,
            config = config,
        ) ?: return@withContext null
        parseCover(root)
    }

    suspend fun discoverMemory(
        title: String,
        artist: String,
        config: GeminiCoverVerificationConfig,
        mode: String = "cover",
        limit: Int = 150,
    ): AiCoverDiscoveryResult? =
        discoverMemoryState(
            title = title,
            artist = artist,
            config = config,
            mode = mode,
            limit = limit,
        )?.discovery

    suspend fun discoverMemoryState(
        title: String,
        artist: String,
        config: GeminiCoverVerificationConfig,
        mode: String = "cover",
        limit: Int = 150,
    ): CloudMemoryState? = withContext(Dispatchers.IO) {
        val endpoint = config.cloudEndpoint.trim().trimEnd('/')
        if (endpoint.isBlank() || title.isBlank() || artist.isBlank() || !config.useCloudMemory) {
            return@withContext null
        }

        val body = buildJsonObject {
            put("title", title.trim())
            put("artist", artist.trim())
            put("mode", if (mode == "originals") "originals" else "cover")
            put("limit", limit.coerceIn(1, 150))
        }
        val request =
            Request.Builder()
                .url("$endpoint/api/v1/memory/discover")
                .addHeader("Content-Type", "application/json")
                .addHeader("x-musiclab-client", "android-lab39")
                .post(body.toString().toRequestBody(mediaType))
                .build()

        val root = runCatching {
            executeCancellable(request).use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body?.string() ?: return@use null
                json.parseToJsonElement(text).jsonObject
            }
        }.getOrNull() ?: return@withContext null

        val rejectedKeys =
            root["rejectedVersions"]
                ?.runCatching { jsonArray }
                ?.getOrNull()
                ?.mapNotNull { element ->
                    val obj = element.runCatching { jsonObject }.getOrNull() ?: return@mapNotNull null
                    val candidateTitle = obj.string("title")
                    val candidateArtist = obj.string("artist")
                    val category = obj.string("category")
                    if (candidateTitle.isBlank() || candidateArtist.isBlank()) {
                        null
                    } else {
                        memoryKey(candidateTitle, candidateArtist, category)
                    }
                }
                ?.toSet()
                .orEmpty()

        CloudMemoryState(
            discovery = parseCover(root),
            rejectedKeys = rejectedKeys,
        )
    }


    suspend fun saveBrainDecision(
        originalTitle: String,
        originalArtist: String,
        candidate: AiCoverCandidate,
        status: AiBrainDecisionStatus,
        config: GeminiCoverVerificationConfig,
        categoryOverride: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val endpoint = config.cloudEndpoint.trim().trimEnd('/')
        if (endpoint.isBlank() || originalTitle.isBlank() || originalArtist.isBlank()) return@withContext false

        val body = buildJsonObject {
            put("originalTitle", originalTitle.trim())
            put("originalArtist", originalArtist.trim())
            put("status", status.name)
            candidate.sameWorkScore?.let { put("sameWorkScore", it) }
            candidate.versionTypeScore?.let { put("versionTypeScore", it) }
            put("reason", "manual_android_lab39")
            put(
                "candidate",
                buildJsonObject {
                    put("title", candidate.title)
                    put("artist", candidate.artist)
                    put(
                        "category",
                        categoryOverride?.trim()?.takeIf(String::isNotBlank)
                            ?: candidate.category.cloudName,
                    )
                    candidate.language?.takeIf { it.isNotBlank() }?.let { put("language", it) }
                    candidate.year?.let { put("year", it) }
                    candidate.album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
                    candidate.coverUrl?.takeIf { it.isNotBlank() }?.let { put("coverUrl", it) }
                    candidate.playbackVideoId?.takeIf { it.isNotBlank() }?.let { put("playbackVideoId", it) }
                    candidate.playbackVideoTitle?.takeIf { it.isNotBlank() }?.let { put("playbackVideoTitle", it) }
                    candidate.playbackVideoSource?.takeIf { it.isNotBlank() }?.let { put("playbackVideoSource", it) }
                    put(
                        "credits",
                        buildJsonObject {
                            put("songwriters", buildJsonArray { candidate.songwriters.forEach { add(it) } })
                            put("composers", buildJsonArray { candidate.composers.forEach { add(it) } })
                            put("lyricists", buildJsonArray { candidate.lyricists.forEach { add(it) } })
                            put("producers", buildJsonArray { candidate.producers.forEach { add(it) } })
                            candidate.label?.takeIf { it.isNotBlank() }?.let { put("label", it) }
                        },
                    )
                },
            )
            put(
                "evidence",
                buildJsonArray {
                    candidate.brainSignals.forEach { signal ->
                        add(
                            buildJsonObject {
                                put("kind", signal.kind)
                                put("strength", signal.strength)
                                signal.direction?.takeIf { it.isNotBlank() }?.let { put("direction", it) }
                            },
                        )
                    }
                },
            )
        }
        val request = Request.Builder()
            .url("$endpoint/api/v1/brain/decision")
            .addHeader("Content-Type", "application/json")
            .addHeader("x-musiclab-client", "android-lab39")
            .post(body.toString().toRequestBody(mediaType))
            .build()

        runCatching {
            executeCancellable(request).use { response -> response.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun savePlaybackBinding(
        originalTitle: String,
        originalArtist: String,
        candidate: AiCoverCandidate,
        config: GeminiCoverVerificationConfig,
    ): Boolean = withContext(Dispatchers.IO) {
        val endpoint = config.cloudEndpoint.trim().trimEnd('/')
        val playbackVideoId = candidate.playbackVideoId?.trim().orEmpty()
        if (
            endpoint.isBlank() ||
            originalTitle.isBlank() ||
            originalArtist.isBlank() ||
            candidate.title.isBlank() ||
            candidate.artist.isBlank() ||
            playbackVideoId.isBlank()
        ) {
            return@withContext false
        }

        val body = buildJsonObject {
            put("originalTitle", originalTitle.trim())
            put("originalArtist", originalArtist.trim())
            put(
                "candidate",
                buildJsonObject {
                    put("title", candidate.title.trim())
                    put("artist", candidate.artist.trim())
                    put("category", candidate.category.cloudName)
                    candidate.language?.takeIf { it.isNotBlank() }?.let { put("language", it) }
                    candidate.coverUrl?.takeIf { it.isNotBlank() }?.let { put("coverUrl", it) }
                    put("playbackVideoId", playbackVideoId)
                    candidate.playbackVideoSource?.takeIf { it.isNotBlank() }?.let { put("playbackVideoSource", it) }
                },
            )
        }
        val request =
            Request.Builder()
                .url("$endpoint/api/v1/playback/binding")
                .addHeader("Content-Type", "application/json")
                .addHeader("x-musiclab-client", "android-lab39")
                .post(body.toString().toRequestBody(mediaType))
                .build()

        runCatching {
            executeCancellable(request).use { response -> response.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun searchArchive(
        query: String,
        config: GeminiCoverVerificationConfig,
        limit: Int = 60,
    ): List<CloudArchiveItem> = withContext(Dispatchers.IO) {
        val endpoint = config.cloudEndpoint.trim().trimEnd('/')
        if (endpoint.isBlank()) return@withContext emptyList()

        val body = buildJsonObject {
            put("query", query.trim())
            put("limit", limit.coerceIn(1, 100))
        }
        val request =
            Request.Builder()
                .url("$endpoint/api/v1/archive/search")
                .addHeader("Content-Type", "application/json")
                .addHeader("x-musiclab-client", "android-lab39")
                .post(body.toString().toRequestBody(mediaType))
                .build()

        val root = runCatching {
            executeCancellable(request, trackArchive = true).use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body?.string() ?: return@use null
                json.parseToJsonElement(text).jsonObject
            }
        }.getOrNull() ?: return@withContext emptyList()

        root["items"]
            ?.runCatching { jsonArray }
            ?.getOrNull()
            ?.mapNotNull { element ->
                val obj = element.runCatching { jsonObject }.getOrNull() ?: return@mapNotNull null
                val title = obj.string("title")
                val artist = obj.string("artist")
                if (title.isBlank() || artist.isBlank()) return@mapNotNull null
                CloudArchiveItem(
                    title = title,
                    artist = artist,
                    category = obj.string("category").ifBlank { "cover" },
                    language = obj.nullableString("language"),
                    year = obj.year("year"),
                    album = obj.nullableString("album"),
                    coverUrl = obj.nullableString("coverUrl"),
                    playbackVideoId = obj.nullableString("playbackVideoId"),
                    playbackVideoTitle = obj.nullableString("playbackVideoTitle"),
                    playbackVideoSource = obj.nullableString("playbackVideoSource"),
                    workTitle = obj.string("workTitle"),
                    originalArtist = obj.string("originalArtist"),
                    sameWorkScore = obj.scoreOrNull("sameWorkScore"),
                    versionTypeScore = obj.scoreOrNull("versionTypeScore"),
                )
            }
            .orEmpty()
    }

    internal fun memoryKey(
        title: String,
        artist: String,
        category: String,
    ): String =
        listOf(
            canonicalMemory(title),
            canonicalMemory(artist),
            canonicalMemory(category),
        ).joinToString("|")

    private fun canonicalMemory(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    suspend fun verifyCandidate(
        originalTitle: String,
        originalArtist: String,
        candidate: AiCoverCandidate,
        mode: String,
        config: GeminiCoverVerificationConfig,
    ): AiCoverCandidate? = withContext(Dispatchers.IO) {
        val focus = "Verifica meglio esclusivamente il candidato «${candidate.title}» di ${candidate.artist}. " +
            "Riesamina stessa opera e tipo di versione con tutte le evidenze disponibili; non sostituirlo con altri candidati."
        val root = request(
            title = originalTitle,
            artist = originalArtist,
            mode = if (mode == "originals") "originals" else "cover",
            phase = "expand",
            existing = emptyList(),
            focus = focus,
            config = config.copy(useCloudMemory = false),
        ) ?: return@withContext null

        val versions = parseCover(root)?.versions.orEmpty()
        versions.firstOrNull { it.stableKey == candidate.stableKey }
            ?: versions.firstOrNull {
                it.title.equals(candidate.title, ignoreCase = true) &&
                    it.artist.equals(candidate.artist, ignoreCase = true)
            }
    }

    suspend fun identifyOriginal(
        title: String,
        artist: String,
        config: GeminiCoverVerificationConfig,
    ): GeminiOriginalIdentity? = withContext(Dispatchers.IO) {
        val root = request(
            title = title,
            artist = artist,
            mode = "originals",
            phase = "initial",
            existing = emptyList(),
            focus = null,
            config = config,
        ) ?: return@withContext null
        val obj = root["original"]?.runCatching { jsonObject }?.getOrNull() ?: return@withContext null
        val canonicalTitle = obj.string("title")
        val canonicalArtist = obj.string("artist")
        if (canonicalTitle.isBlank() || canonicalArtist.isBlank()) return@withContext null
        val credits = obj.objectOrNull("credits")
        GeminiOriginalIdentity(
            title = canonicalTitle,
            originalArtists = listOf(canonicalArtist),
            year = obj.year("year"),
            songwriters = credits?.strings("songwriters").orEmpty(),
            composers = credits?.strings("composers").orEmpty(),
            lyricists = credits?.strings("lyricists").orEmpty(),
            producers = credits?.strings("producers").orEmpty(),
            label = credits?.nullableString("label"),
            album = obj.nullableString("album"),
            mode = GeminiOriginalMode.MODEL_KNOWLEDGE,
            webSourceCount = 0,
        )
    }

    private suspend fun request(
        title: String,
        artist: String,
        mode: String,
        phase: String,
        existing: List<AiCoverCandidate>,
        focus: String?,
        config: GeminiCoverVerificationConfig,
    ): JsonObject? {
        val endpoint = config.cloudEndpoint.trim().trimEnd('/')
        if (endpoint.isBlank() || title.isBlank()) return null
        val route = if (phase == "expand") "expand" else "initial"
        val body = buildJsonObject {
            put("title", title.trim())
            put("artist", artist.trim())
            put("mode", mode)
            put("useMemory", config.useCloudMemory)
            focus?.takeIf { it.isNotBlank() }?.let { put("focus", it) }
            if (existing.isNotEmpty()) {
                put(
                    "existing",
                    buildJsonArray {
                        existing.take(160).forEach { candidate ->
                            add(
                                buildJsonObject {
                                    put("title", candidate.title)
                                    put("artist", candidate.artist)
                                    put("category", candidate.category.cloudName)
                                    candidate.language?.takeIf { it.isNotBlank() }?.let { put("language", it) }
                                    candidate.year?.let { put("year", it) }
                                },
                            )
                        }
                    },
                )
            }
        }
        val request =
            Request.Builder()
                .url("$endpoint/api/v1/discover/$route")
                .addHeader("Content-Type", "application/json")
                .addHeader("x-musiclab-client", "android-lab39")
                .post(body.toString().toRequestBody(mediaType))
                .build()
        return runCatching {
            executeCancellable(request).use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body?.string() ?: return@use null
                json.parseToJsonElement(text).jsonObject
            }
        }.getOrNull()
    }

    /**
     * Couples the OkHttp call to coroutine cancellation. Leaving the screen or
     * cancelling a Cover/Cloud job now aborts the actual HTTP call immediately.
     */
    internal fun cancelArchiveRequests() {
        activeArchiveCalls.toList().forEach { call ->
            call.cancel()
            activeArchiveCalls.remove(call)
        }
    }

    private suspend fun executeCancellable(
        request: Request,
        trackArchive: Boolean = false,
    ): okhttp3.Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            if (trackArchive) activeArchiveCalls.add(call)
            continuation.invokeOnCancellation {
                if (trackArchive) activeArchiveCalls.remove(call)
                call.cancel()
            }
            call.enqueue(
                object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, error: IOException) {
                        if (trackArchive) activeArchiveCalls.remove(call)
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(error))
                        }
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        if (trackArchive) activeArchiveCalls.remove(call)
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.success(response))
                        } else {
                            response.close()
                        }
                    }
                },
            )
        }

    private fun parseCover(root: JsonObject): AiCoverDiscoveryResult? {
        val originalObj = root["original"]?.runCatching { jsonObject }?.getOrNull()
        val original = originalObj?.let { obj ->
            val title = obj.string("title")
            val artist = obj.string("artist")
            if (title.isBlank() || artist.isBlank()) {
                null
            } else {
                val credits = obj.objectOrNull("credits")
                AiCoverOriginalInfo(
                    title = title,
                    artist = artist,
                    year = obj.year("year"),
                    album = obj.nullableString("album"),
                    language = obj.nullableString("language"),
                    songwriters = credits?.strings("songwriters").orEmpty(),
                    composers = credits?.strings("composers").orEmpty(),
                    lyricists = credits?.strings("lyricists").orEmpty(),
                    producers = credits?.strings("producers").orEmpty(),
                    label = credits?.nullableString("label"),
                )
            }
        }

        val versions = root["versions"]?.runCatching { jsonArray }?.getOrNull()?.mapNotNull { element ->
            val obj = element.runCatching { jsonObject }.getOrNull() ?: return@mapNotNull null
            val title = obj.string("title")
            val artist = obj.string("artist")
            if (title.isBlank() || artist.isBlank()) return@mapNotNull null
            val credits = obj.objectOrNull("credits")
            AiCoverCandidate(
                title = title,
                artist = artist,
                category = when (obj.string("category").lowercase()) {
                    "live", "dal vivo" -> AiCoverCategory.LIVE
                    "remix", "mix", "rework" -> AiCoverCategory.REMIX
                    "straniera", "foreign", "adattamento", "adaptation" -> AiCoverCategory.FOREIGN
                    else -> AiCoverCategory.COVER
                },
                language = obj.nullableString("language"),
                year = obj.year("year"),
                album = obj.nullableString("album"),
                coverUrl = obj.nullableString("coverUrl"),
                playbackVideoId = obj.nullableString("playbackVideoId"),
                playbackVideoTitle = obj.nullableString("playbackVideoTitle"),
                playbackVideoSource = obj.nullableString("playbackVideoSource"),
                songwriters = credits?.strings("songwriters").orEmpty(),
                composers = credits?.strings("composers").orEmpty(),
                lyricists = credits?.strings("lyricists").orEmpty(),
                producers = credits?.strings("producers").orEmpty(),
                label = credits?.nullableString("label"),
                sameWorkScore = obj.scoreOrNull("sameWorkScore"),
                versionTypeScore = obj.scoreOrNull("versionTypeScore"),
                brainStatus = AiBrainDecisionStatus.fromWire(obj.nullableString("brainStatus")),
                brainAdmission = obj.nullableString("brainAdmission"),
                brainSignals = obj.brainSignals("brainSignals"),
            )
        }.orEmpty()

        if (original == null && versions.isEmpty()) return null
        return AiCoverDiscoveryResult(original = original, versions = versions)
    }

    private val AiCoverCategory.cloudName: String
        get() = when (this) {
            AiCoverCategory.COVER -> "cover"
            AiCoverCategory.LIVE -> "live"
            AiCoverCategory.REMIX -> "remix"
            AiCoverCategory.FOREIGN -> "straniera"
        }

    private fun JsonObject.objectOrNull(key: String): JsonObject? =
        get(key)?.runCatching { jsonObject }?.getOrNull()

    private fun JsonObject.string(key: String): String =
        get(key)?.runCatching { jsonPrimitive }?.getOrNull()?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.nullableString(key: String): String? =
        string(key).takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private fun JsonObject.strings(key: String): List<String> {
        val element = get(key) ?: return emptyList()
        val values = element.runCatching { jsonArray }.getOrNull() ?: return emptyList()
        return values.mapNotNull {
            it.runCatching { jsonPrimitive }.getOrNull()?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        }.distinct()
    }

    private fun JsonObject.scoreOrNull(key: String): Int? {
        val primitive = get(key)?.runCatching { jsonPrimitive }?.getOrNull() ?: return null
        val value = primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
        return value?.takeIf { it in 0..100 }
    }

    private fun JsonObject.brainSignals(key: String): List<AiBrainSignal> {
        val element = get(key) ?: return emptyList()
        val values = element.runCatching { jsonArray }.getOrNull() ?: return emptyList()
        return values.mapNotNull { signalElement ->
            val signal = signalElement.runCatching { jsonObject }.getOrNull() ?: return@mapNotNull null
            val kind = signal.string("kind")
            val strength = signal.string("strength")
            if (kind.isBlank() || strength.isBlank()) {
                null
            } else {
                AiBrainSignal(
                    kind = kind,
                    strength = strength,
                    direction = signal.nullableString("direction"),
                )
            }
        }.distinct()
    }

    private fun JsonObject.year(key: String): Int? {
        val primitive = get(key)?.runCatching { jsonPrimitive }?.getOrNull() ?: return null
        val value = primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
        return value?.takeIf { it in 1800..2100 }
    }
}
