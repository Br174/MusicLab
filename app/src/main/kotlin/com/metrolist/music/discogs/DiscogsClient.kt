package com.metrolist.music.discogs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object DiscogsClient {
    private const val API_BASE = "https://api.discogs.com"
    private const val USER_AGENT = "MusicLab-Compilation/1.0 Android"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val detailCache = ConcurrentHashMap<Int, DiscogsCompilationDetail>()

    suspend fun searchCompilations(
        token: String,
        query: String,
        year: Int?,
        genre: String?,
        style: String?,
        country: String?,
        page: Int = 1,
        perPage: Int = 30,
    ): Result<List<DiscogsCompilationSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            require(token.isNotBlank()) { "Token Discogs mancante" }

            val url = "$API_BASE/database/search".toHttpUrl().newBuilder()
                .addQueryParameter("type", "release")
                .addQueryParameter("format", "Compilation")
                .addQueryParameter("page", page.coerceAtLeast(1).toString())
                .addQueryParameter("per_page", perPage.coerceIn(1, 50).toString())
                .apply {
                    query.trim().takeIf(String::isNotBlank)?.let { addQueryParameter("q", it) }
                    year?.let { addQueryParameter("year", it.toString()) }
                    genre?.trim()?.takeIf(String::isNotBlank)?.let { addQueryParameter("genre", it) }
                    style?.trim()?.takeIf(String::isNotBlank)?.let { addQueryParameter("style", it) }
                    country?.trim()?.takeIf(String::isNotBlank)?.let { addQueryParameter("country", it) }
                }
                .build()

            val root = requestJson(url.toString(), token)
            val results = root.optJSONArray("results") ?: JSONArray()
            buildList {
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val formats = item.stringList("format")
                    if (formats.none { it.equals("Compilation", ignoreCase = true) }) continue

                    add(
                        DiscogsCompilationSummary(
                            id = item.optInt("id").takeIf { it > 0 } ?: continue,
                            masterId = item.optInt("master_id").takeIf { it > 0 },
                            title = item.optString("title").cleanDiscogsText(),
                            year = item.optInt("year").takeIf { it in 1900..3000 },
                            country = item.optString("country").takeIf(String::isNotBlank),
                            formats = formats,
                            genres = item.stringList("genre"),
                            styles = item.stringList("style"),
                            labels = item.stringList("label"),
                            thumbnailUrl = item.optString("thumb").takeIf(String::isNotBlank),
                            coverUrl = item.optString("cover_image").takeIf(String::isNotBlank),
                        ),
                    )
                }
            }.distinctBy { summary ->
                summary.masterId?.let { master -> "m:$master" } ?: "r:" + summary.id
            }
        }
    }

    suspend fun getRelease(
        token: String,
        releaseId: Int,
    ): Result<DiscogsCompilationDetail> = withContext(Dispatchers.IO) {
        detailCache[releaseId]?.let { return@withContext Result.success(it) }

        runCatching {
            require(token.isNotBlank()) { "Token Discogs mancante" }
            val root = requestJson("$API_BASE/releases/$releaseId", token)
            val detail = parseRelease(root, token)
            detailCache[releaseId] = detail
            detail
        }
    }

    private fun parseRelease(root: JSONObject, token: String): DiscogsCompilationDetail {
        val releaseArtists = root.artistNames()
        val masterId = root.optInt("master_id").takeIf { it > 0 }

        val tracks = buildList {
            val array = root.optJSONArray("tracklist") ?: JSONArray()
            for (index in 0 until array.length()) {
                val track = array.optJSONObject(index) ?: continue
                val type = track.optString("type_").ifBlank { "track" }
                if (!type.equals("track", ignoreCase = true)) continue
                val title = track.optString("title").cleanDiscogsText()
                if (title.isBlank()) continue
                val durationText = track.optString("duration").trim().takeIf(String::isNotBlank)
                add(
                    DiscogsTrack(
                        position = track.optString("position").trim(),
                        title = title,
                        artists = track.artistNames().ifEmpty { releaseArtists },
                        durationText = durationText,
                        durationSeconds = durationText?.toDurationSeconds(),
                    ),
                )
            }
        }

        var videos = root.videoList()
        if (videos.isEmpty() && masterId != null) {
            videos = runCatching {
                requestJsonBlocking("$API_BASE/masters/$masterId", token).videoList()
            }.getOrDefault(emptyList())
        }

        val imageArray = root.optJSONArray("images")
        var cover: String? = null
        if (imageArray != null) {
            for (index in 0 until imageArray.length()) {
                val image = imageArray.optJSONObject(index) ?: continue
                val uri = image.optString("uri").takeIf(String::isNotBlank)
                    ?: image.optString("uri150").takeIf(String::isNotBlank)
                if (uri != null && (cover == null || image.optString("type").equals("primary", true))) {
                    cover = uri
                    if (image.optString("type").equals("primary", true)) break
                }
            }
        }

        return DiscogsCompilationDetail(
            id = root.optInt("id"),
            masterId = masterId,
            title = root.optString("title").cleanDiscogsText(),
            year = root.optInt("year").takeIf { it in 1900..3000 },
            country = root.optString("country").takeIf(String::isNotBlank),
            artists = releaseArtists,
            labels = root.objectNameList("labels"),
            genres = root.stringList("genres"),
            styles = root.stringList("styles"),
            coverUrl = cover,
            tracks = tracks,
            videos = videos,
        )
    }

    private fun requestJson(url: String, token: String): JSONObject =
        requestJsonBlocking(url, token)

    private fun requestJsonBlocking(url: String, token: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.discogs.v2.discogs+json")
            .header("Authorization", "Discogs token=$token")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(body).optString("message")
                }.getOrNull().orEmpty()
                throw IOException(
                    "Discogs HTTP " + response.code +
                        message.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
                )
            }
            if (body.isBlank()) throw IOException("Risposta Discogs vuota")
            return JSONObject(body)
        }
    }

    private fun JSONObject.artistNames(): List<String> =
        objectNameList("artists").map { it.substringBefore(" (").trim() }.filter(String::isNotBlank)

    private fun JSONObject.objectNameList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val name = array.optJSONObject(index)?.optString("name")?.cleanDiscogsText().orEmpty()
                if (name.isNotBlank()) add(name)
            }
        }.distinct()
    }

    private fun JSONObject.stringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).cleanDiscogsText()
                if (value.isNotBlank()) add(value)
            }
        }.distinct()
    }

    private fun JSONObject.videoList(): List<DiscogsVideo> {
        val array = optJSONArray("videos") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val video = array.optJSONObject(index) ?: continue
                val uri = video.optString("uri").trim()
                if (uri.isBlank()) continue
                add(
                    DiscogsVideo(
                        title = video.optString("title").cleanDiscogsText(),
                        uri = uri,
                        durationSeconds = video.optInt("duration").takeIf { it > 0 },
                        description = video.optString("description").cleanDiscogsText().takeIf(String::isNotBlank),
                    ),
                )
            }
        }.distinctBy { it.uri }
    }

    private fun String.toDurationSeconds(): Int? {
        val parts = split(":").mapNotNull { it.trim().toIntOrNull() }
        if (parts.isEmpty()) return null
        val seconds = when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
        return seconds?.takeIf { it > 0 }
    }

    private fun String.cleanDiscogsText(): String =
        replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
}
