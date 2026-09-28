package com.metrolist.music.ui.component

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.util.concurrent.TimeUnit

/**
 * Corsia cloud condivisa dal motore Cover e dal motore Originali.
 * Il Worker/D1 conserva esclusivamente decisioni editoriali AI; YouTube non entra
 * mai in questa risposta. Se il cloud non risponde, i chiamanti usano Gemini locale.
 */
internal object CloudMusicDiscovery {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .build()

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

    private fun request(
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
                .addHeader("x-musiclab-client", "android-lab11")
                .post(body.toString().toRequestBody(mediaType))
                .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body?.string() ?: return@use null
                json.parseToJsonElement(text).jsonObject
            }
        }.getOrNull()
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
                songwriters = credits?.strings("songwriters").orEmpty(),
                composers = credits?.strings("composers").orEmpty(),
                lyricists = credits?.strings("lyricists").orEmpty(),
                producers = credits?.strings("producers").orEmpty(),
                label = credits?.nullableString("label"),
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

    private fun JsonObject.year(key: String): Int? {
        val primitive = get(key)?.runCatching { jsonPrimitive }?.getOrNull() ?: return null
        val value = primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
        return value?.takeIf { it in 1800..2100 }
    }
}
