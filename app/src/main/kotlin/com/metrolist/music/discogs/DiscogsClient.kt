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

    suspend fun searchReleases(
        token: String,
        query: String? = null,
        track: String? = null,
        artist: String? = null,
        releaseTitle: String? = null,
        year: Int? = null,
        format: String? = null,
        country: String? = null,
        label: String? = null,
        genre: String? = null,
        style: String? = null,
        catalogNumber: String? = null,
        page: Int = 1,
        perPage: Int = 100,
        sort: String = "year",
        sortOrder: String = "asc",
    ): Result<DiscogsReleasePage> = withContext(Dispatchers.IO) {
        runCatching {
            require(token.isNotBlank()) { "Token Discogs mancante" }

            val url = "$API_BASE/database/search".toHttpUrl().newBuilder()
                .addQueryParameter("type", "release")
                .addQueryParameter("page", page.coerceAtLeast(1).toString())
                .addQueryParameter("per_page", perPage.coerceIn(1, 100).toString())
                .addQueryParameter("sort", sort)
                .addQueryParameter("sort_order", sortOrder)
                .apply {
                    query?.trim()?.takeIf(String::isNotBlank)?.let { addQueryParameter("q", it) }
                    track?.trim()?.takeIf(String::isNotBlank)?.let { addQueryParameter("track", it) }
                    artist?.trim()?.takeIf(String::isNotBlank)?.let { addQueryParameter("artist", it) }
                    releaseTitle?.trim()?.takeIf(String::isNotBlank)?.let { addQueryParameter("release_title", it) }
                    year?.let { addQueryParameter("year", it.toString()) }
                    format?.trim()?.takeIf(String::isNotBlank)?.let { addQueryParameter("format", it) }
                    country?.trim()?.takeIf(String::isNotBlank)?.let { addQueryParameter("country", it) }
                    label?.trim()?.takeIf(String::isNotBlank)?.let { addQueryParameter("label", it) }
                    genre?.trim()?.takeIf(String::isNotBlank)?.let { addQueryParameter("genre", it) }
                    style?.trim()?.takeIf(String::isNotBlank)?.let { addQueryParameter("style", it) }
                    catalogNumber?.trim()?.takeIf(String::isNotBlank)?.let { addQueryParameter("catno", it) }
                }
                .build()

            val root = requestJson(url.toString(), token)
            val results = root.optJSONArray("results") ?: JSONArray()
            val pagination = root.optJSONObject("pagination")
            val summaries = buildList {
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val id = item.optInt("id").takeIf { it > 0 } ?: continue
                    add(
                        DiscogsReleaseSummary(
                            id = id,
                            masterId = item.optInt("master_id").takeIf { it > 0 },
                            title = item.optString("title").cleanDiscogsText(),
                            year = item.optInt("year").takeIf { it in 1800..3000 },
                            country = item.optString("country").takeIf(String::isNotBlank),
                            formats = item.stringList("format"),
                            genres = item.stringList("genre"),
                            styles = item.stringList("style"),
                            labels = item.stringList("label"),
                            catalogNumber = item.optString("catno").cleanDiscogsText().takeIf(String::isNotBlank),
                            thumbnailUrl = item.optString("thumb").takeIf(String::isNotBlank),
                            coverUrl = item.optString("cover_image").takeIf(String::isNotBlank),
                        ),
                    )
                }
            }.distinctBy { it.id }

            DiscogsReleasePage(
                items = summaries,
                page = pagination?.optInt("page")?.takeIf { it > 0 } ?: page.coerceAtLeast(1),
                pages = pagination?.optInt("pages")?.takeIf { it > 0 } ?: page.coerceAtLeast(1),
                perPage = pagination?.optInt("per_page")?.takeIf { it > 0 } ?: perPage.coerceIn(1, 100),
                totalItems = pagination?.optInt("items")?.takeIf { it >= 0 } ?: summaries.size,
            )
        }
    }

    suspend fun searchCompilations(
        token: String,
        query: String,
        year: Int?,
        genre: String?,
        style: String?,
        country: String?,
        page: Int = 1,
        perPage: Int = 100,
    ): Result<DiscogsCompilationPage> = withContext(Dispatchers.IO) {
        runCatching {
            require(token.isNotBlank()) { "Token Discogs mancante" }

            val url = "$API_BASE/database/search".toHttpUrl().newBuilder()
                .addQueryParameter("type", "release")
                .addQueryParameter("format", "Compilation")
                .addQueryParameter("page", page.coerceAtLeast(1).toString())
                .addQueryParameter("per_page", perPage.coerceIn(1, 100).toString())
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
            val pagination = root.optJSONObject("pagination")
            val summaries = buildList {
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    add(
                        DiscogsCompilationSummary(
                            id = item.optInt("id").takeIf { it > 0 } ?: continue,
                            masterId = item.optInt("master_id").takeIf { it > 0 },
                            title = item.optString("title").cleanDiscogsText(),
                            year = item.optInt("year").takeIf { it in 1900..3000 },
                            country = item.optString("country").takeIf(String::isNotBlank),
                            formats = item.stringList("format"),
                            genres = item.stringList("genre"),
                            styles = item.stringList("style"),
                            labels = item.stringList("label"),
                            thumbnailUrl = item.optString("thumb").takeIf(String::isNotBlank),
                            coverUrl = item.optString("cover_image").takeIf(String::isNotBlank),
                        ),
                    )
                }
            }.distinctBy { it.id }

            DiscogsCompilationPage(
                items = summaries,
                page = pagination?.optInt("page")?.takeIf { it > 0 } ?: page.coerceAtLeast(1),
                pages = pagination?.optInt("pages")?.takeIf { it > 0 } ?: page.coerceAtLeast(1),
                perPage = pagination?.optInt("per_page")?.takeIf { it > 0 } ?: perPage.coerceIn(1, 100),
                totalItems = pagination?.optInt("items")?.takeIf { it >= 0 } ?: summaries.size,
            )
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
            year = root.optInt("year").takeIf { it in 1800..3000 },
            releaseDate = normalizeDiscogsDate(root.optString("released")),
            country = root.optString("country").takeIf(String::isNotBlank),
            artists = releaseArtists,
            labels = root.objectNameList("labels"),
            genres = root.stringList("genres"),
            styles = root.stringList("styles"),
            formats = root.formatNames(),
            formatDescriptions = root.formatDescriptions(),
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

    private fun JSONObject.formatNames(): List<String> {
        val array = optJSONArray("formats") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val name = array.optJSONObject(index)?.optString("name")?.cleanDiscogsText().orEmpty()
                if (name.isNotBlank()) add(name)
            }
        }.distinct()
    }

    private fun JSONObject.formatDescriptions(): List<String> {
        val array = optJSONArray("formats") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val format = array.optJSONObject(index) ?: continue
                val descriptions = format.optJSONArray("descriptions") ?: continue
                for (descriptionIndex in 0 until descriptions.length()) {
                    val description = descriptions.optString(descriptionIndex).cleanDiscogsText()
                    if (description.isNotBlank()) add(description)
                }
            }
        }.distinct()
    }

    private fun normalizeDiscogsDate(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val match = Regex("""^(\d{4})(?:-(\d{2}))?(?:-(\d{2}))?""").find(value) ?: return null
        val year = match.groupValues.getOrNull(1).orEmpty()
        val month = match.groupValues.getOrNull(2).orEmpty().takeUnless { it == "00" }
        val day = match.groupValues.getOrNull(3).orEmpty().takeUnless { it == "00" }
        return when {
            year.isBlank() -> null
            !month.isNullOrBlank() && !day.isNullOrBlank() -> "$year-$month-$day"
            !month.isNullOrBlank() -> "$year-$month"
            else -> year
        }
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
