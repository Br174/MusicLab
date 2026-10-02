package com.metrolist.music.ui.component

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class YouTubeWebSearchPage(
    val items: List<SongItem>,
    val continuation: String?,
)

/**
 * Real youtube.com search lane.
 *
 * MusicLab's historical YouTube.search() endpoint is rooted at music.youtube.com even when it
 * asks for FILTER_VIDEO. This helper deliberately bootstraps the public WEB client from
 * www.youtube.com and sends anonymous search requests to the regular YouTube WEB endpoint.
 * No user API key and no official Data API quota are required.
 */
internal object YouTubeWebSearch {
    private data class WebConfig(
        val apiKey: String,
        val clientVersion: String,
        val visitorData: String?,
        val fetchedAtMs: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    @Volatile
    private var cachedConfig: WebConfig? = null

    suspend fun search(
        query: String,
        continuation: String? = null,
    ): YouTubeWebSearchPage? = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank() && continuation.isNullOrBlank()) return@withContext null

        val config = webConfig() ?: return@withContext null
        val locale = Locale.getDefault()
        val hl = locale.language.takeIf { it.matches(Regex("[A-Za-z]{2,3}")) } ?: "it"
        val gl = locale.country.takeIf { it.matches(Regex("[A-Za-z]{2}")) } ?: "IT"

        val body = buildJsonObject {
            put(
                "context",
                buildJsonObject {
                    put(
                        "client",
                        buildJsonObject {
                            put("clientName", "WEB")
                            put("clientVersion", config.clientVersion)
                            put("hl", hl)
                            put("gl", gl)
                        },
                    )
                },
            )
            if (!continuation.isNullOrBlank()) {
                put("continuation", continuation)
            } else {
                put("query", normalizedQuery)
            }
        }.toString()

        val request =
            Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false&key=${config.apiKey}")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .header("User-Agent", WEB_USER_AGENT)
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", config.clientVersion)
                .apply {
                    config.visitorData?.takeIf(String::isNotBlank)?.let {
                        header("X-Goog-Visitor-Id", it)
                    }
                }
                .post(body.toRequestBody(jsonMediaType))
                .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.let(::parseSearchResponse)
            }
        }.getOrNull()
    }

    private fun webConfig(): WebConfig? {
        val now = System.currentTimeMillis()
        cachedConfig?.takeIf { now - it.fetchedAtMs in 0 until CONFIG_TTL_MS }?.let { return it }

        val locale = Locale.getDefault()
        val hl = locale.language.takeIf { it.matches(Regex("[A-Za-z]{2,3}")) } ?: "it"
        val gl = locale.country.takeIf { it.matches(Regex("[A-Za-z]{2}")) } ?: "IT"
        val request =
            Request.Builder()
                .url("https://www.youtube.com/?hl=$hl&gl=$gl")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "$hl,$hl;q=0.9,en;q=0.7")
                .header("User-Agent", WEB_USER_AGENT)
                .build()

        val html =
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string()
                }
            }.getOrNull() ?: return cachedConfig

        val apiKey = API_KEY_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty()
        val clientVersion = CLIENT_VERSION_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty()
        if (apiKey.isBlank() || clientVersion.isBlank()) return cachedConfig

        return WebConfig(
            apiKey = apiKey,
            clientVersion = clientVersion,
            visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.getOrNull(1),
            fetchedAtMs = now,
        ).also { cachedConfig = it }
    }

    internal fun parseSearchResponse(raw: String): YouTubeWebSearchPage? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val videos = mutableListOf<SongItem>()
        collectVideos(root, videos)
        return YouTubeWebSearchPage(
            items = videos.distinctBy { it.id },
            continuation = findContinuation(root),
        )
    }

    private fun collectVideos(
        element: JsonElement,
        output: MutableList<SongItem>,
    ) {
        when (element) {
            is JsonObject -> {
                listOf("videoRenderer", "gridVideoRenderer", "compactVideoRenderer")
                    .mapNotNull { key -> element[key] as? JsonObject }
                    .forEach { renderer -> parseVideo(renderer)?.let(output::add) }
                element.values.forEach { child -> collectVideos(child, output) }
            }
            is JsonArray -> element.forEach { child -> collectVideos(child, output) }
            else -> Unit
        }
    }

    private fun parseVideo(renderer: JsonObject): SongItem? {
        val videoId = renderer.string("videoId").takeIf(String::isNotBlank) ?: return null
        val title = renderer["title"].text().takeIf(String::isNotBlank) ?: return null
        val owner =
            renderer["ownerText"].text()
                .ifBlank { renderer["longBylineText"].text() }
                .ifBlank { renderer["shortBylineText"].text() }
                .ifBlank { "YouTube" }
        val thumbnail =
            renderer["thumbnail"]
                .asObject()
                ?.get("thumbnails")
                .asArray()
                ?.mapNotNull { it.asObject()?.string("url")?.takeIf(String::isNotBlank) }
                ?.lastOrNull()
                ?.let(::absoluteUrl)
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        return SongItem(
            id = videoId,
            title = title,
            artists = listOf(Artist(owner, null)),
            duration = parseDuration(renderer["lengthText"].text()),
            thumbnail = thumbnail,
        )
    }

    private fun findContinuation(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
                val continuationRenderer = element["continuationItemRenderer"] as? JsonObject
                val token =
                    continuationRenderer
                        ?.get("continuationEndpoint")
                        .asObject()
                        ?.get("continuationCommand")
                        .asObject()
                        ?.string("token")
                        .orEmpty()
                if (token.isNotBlank()) return token

                val nextToken =
                    element["nextContinuationData"]
                        .asObject()
                        ?.string("continuation")
                        .orEmpty()
                if (nextToken.isNotBlank()) return nextToken

                element.values.forEach { child ->
                    findContinuation(child)?.let { return it }
                }
            }
            is JsonArray -> element.forEach { child ->
                findContinuation(child)?.let { return it }
            }
            else -> Unit
        }
        return null
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.runCatching { jsonPrimitive }?.getOrNull()?.contentOrNull?.trim().orEmpty()

    private fun JsonElement?.text(): String {
        val obj = this as? JsonObject ?: return ""
        obj["simpleText"]?.runCatching { jsonPrimitive }?.getOrNull()?.contentOrNull?.trim()?.let {
            if (it.isNotBlank()) return it
        }
        return (obj["runs"] as? JsonArray)
            ?.mapNotNull { run ->
                (run as? JsonObject)
                    ?.get("text")
                    ?.runCatching { jsonPrimitive }
                    ?.getOrNull()
                    ?.contentOrNull
            }
            ?.joinToString("")
            ?.trim()
            .orEmpty()
    }

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

    private fun absoluteUrl(value: String): String =
        if (value.startsWith("//")) "https:$value" else value

    private fun parseDuration(value: String): Int? {
        val parts = value.trim().split(':').mapNotNull(String::toIntOrNull)
        if (parts.isEmpty()) return null
        return when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    private val API_KEY_REGEX = Regex("\"INNERTUBE_API_KEY\"\\s*:\\s*\"([^\"]+)\"")
    private val CLIENT_VERSION_REGEX =
        Regex("\"INNERTUBE_CONTEXT_CLIENT_VERSION\"\\s*:\\s*\"([^\"]+)\"")
    private val VISITOR_DATA_REGEX = Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"")

    private const val CONFIG_TTL_MS = 6L * 60L * 60L * 1000L
    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
}
