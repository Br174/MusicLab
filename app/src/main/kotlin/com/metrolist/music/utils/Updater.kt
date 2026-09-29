/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.music.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val description: String,
    val releaseDate: String,
    val assets: List<ReleaseAsset>,
)

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val architecture: String,
    val variant: String, // "foss" or "gms"
)

/**
 * Meld is an upstream SOURCE for MusicLab, not MusicLab's APK update channel.
 *
 * The imported Meld baseline advances only after an upstream release has been
 * reviewed, integrated in a LAB, tested and approved. It must never be inferred
 * from BuildConfig.VERSION_NAME because MusicLab has its own version lifecycle.
 */
object Updater {
    private val client = HttpClient()

    var lastCheckTime = -1L
        private set

    private var cachedReleaseInfo: ReleaseInfo? = null
    private var cachedAllReleases: List<ReleaseInfo> = emptyList()

    private const val CHECK_INTERVAL_MILLIS = 2 * 60 * 60 * 1000L // 2 hours
    private const val GITHUB_API_BASE = "https://api.github.com/repos/FrancescoGrazioso/Meld"
    private const val GITHUB_WEB_BASE = "https://github.com/FrancescoGrazioso/Meld"

    const val IMPORTED_MELD_VERSION = "0.8.9"
    const val IMPORTED_MELD_TAG = "v0.8.9"

    /**
     * Compares two version strings.
     * Returns: 1 if v1 > v2, -1 if v1 < v2, 0 if equal.
     */
    fun compareVersions(v1: String, v2: String): Int {
        val v1Parts = v1.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val v2Parts = v2.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val maxLength = maxOf(v1Parts.size, v2Parts.size)

        for (i in 0 until maxLength) {
            val part1 = v1Parts.getOrNull(i) ?: 0
            val part2 = v2Parts.getOrNull(i) ?: 0
            when {
                part1 > part2 -> return 1
                part1 < part2 -> return -1
            }
        }
        return 0
    }

    fun isUpdateAvailable(currentVersion: String, latestVersion: String): Boolean =
        compareVersions(latestVersion, currentVersion) > 0

    /** True when Meld contains source changes newer than the last imported baseline. */
    fun isUpstreamUpdateAvailable(latestVersion: String): Boolean =
        isUpdateAvailable(IMPORTED_MELD_VERSION, latestVersion)

    /** Release page used only for reviewing Meld source/changelog. */
    fun getUpstreamReleasePageUrl(releaseInfo: ReleaseInfo): String =
        "$GITHUB_WEB_BASE/releases/tag/${releaseInfo.tagName}"

    private fun getCurrentAppVariant(): Pair<String, String> {
        val architecture = BuildConfig.ARCHITECTURE
        val variant = if (BuildConfig.CAST_AVAILABLE) "gms" else "foss"
        return architecture to variant
    }

    private fun parseAssets(assetsArray: JSONArray): List<ReleaseAsset> {
        val assets = mutableListOf<ReleaseAsset>()

        for (i in 0 until assetsArray.length()) {
            val asset = assetsArray.getJSONObject(i)
            val name = asset.getString("name")
            if (!name.endsWith(".apk")) continue

            val downloadUrl = asset.getString("browser_download_url")
            val size = asset.getLong("size")
            val (arch, variant) =
                when {
                    name == "Meld.apk" -> "universal" to "foss"
                    name == "Meld-with-Google-Cast.apk" -> "universal" to "gms"
                    name.startsWith("app-") && name.endsWith("-release.apk") -> {
                        name.removePrefix("app-").removeSuffix("-release.apk") to "foss"
                    }
                    name.startsWith("app-") && name.endsWith("-with-Google-Cast.apk") -> {
                        name.removePrefix("app-").removeSuffix("-with-Google-Cast.apk") to "gms"
                    }
                    else -> null to null
                }

            if (arch != null && variant != null) {
                assets.add(ReleaseAsset(name, downloadUrl, size, arch, variant))
            }
        }

        return assets
    }

    suspend fun getLatestRelease(forceRefresh: Boolean = false): Result<ReleaseInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cachedReleaseInfo != null && !forceRefresh) {
                    return@runCatching cachedReleaseInfo!!
                }

                val response = client.get("$GITHUB_API_BASE/releases/latest").bodyAsText()
                val json = JSONObject(response)

                ReleaseInfo(
                    tagName = json.getString("tag_name"),
                    versionName = json.getString("name"),
                    description = json.getString("body"),
                    releaseDate = json.getString("published_at"),
                    assets = parseAssets(json.getJSONArray("assets")),
                ).also {
                    cachedReleaseInfo = it
                    lastCheckTime = System.currentTimeMillis()
                }
            }
        }

    suspend fun getAllReleases(forceRefresh: Boolean = false): Result<List<ReleaseInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cachedAllReleases.isNotEmpty() && !forceRefresh) {
                    return@runCatching cachedAllReleases
                }

                val releases = mutableListOf<ReleaseInfo>()
                var page = 1
                var hasMore = true

                while (hasMore && page <= 10) {
                    val response = client.get("$GITHUB_API_BASE/releases?page=$page&per_page=30").bodyAsText()
                    val json = JSONArray(response)
                    if (json.length() == 0) {
                        hasMore = false
                        break
                    }

                    for (i in 0 until json.length()) {
                        val releaseObj = json.getJSONObject(i)
                        releases.add(
                            ReleaseInfo(
                                tagName = releaseObj.getString("tag_name"),
                                versionName = releaseObj.getString("name"),
                                description = releaseObj.getString("body"),
                                releaseDate = releaseObj.getString("published_at"),
                                assets = parseAssets(releaseObj.getJSONArray("assets")),
                            ),
                        )
                    }
                    page++
                }

                cachedAllReleases = releases
                releases
            }
        }

    /**
     * Legacy Meld APK lookup retained for provenance/debugging only.
     * MusicLab must never use this URL as an install/update action.
     */
    fun getDownloadUrlForCurrentVariant(releaseInfo: ReleaseInfo): String? {
        val (currentArch, currentVariant) = getCurrentAppVariant()
        return releaseInfo.assets
            .find { it.architecture == currentArch && it.variant == currentVariant }
            ?.downloadUrl
    }

    fun getAllDownloadUrls(releaseInfo: ReleaseInfo): Map<String, String> =
        releaseInfo.assets.associate { "${it.architecture}-${it.variant}" to it.downloadUrl }

    /**
     * Checks Meld for SOURCE updates, not installable MusicLab APK updates.
     * The boolean compares the latest Meld release with IMPORTED_MELD_VERSION.
     */
    suspend fun checkForUpdate(forceRefresh: Boolean = false): Result<Pair<ReleaseInfo?, Boolean>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val shouldFetch =
                    forceRefresh ||
                        (System.currentTimeMillis() - lastCheckTime) > CHECK_INTERVAL_MILLIS

                if (!shouldFetch && cachedReleaseInfo != null) {
                    return@runCatching cachedReleaseInfo!! to
                        isUpstreamUpdateAvailable(cachedReleaseInfo!!.versionName)
                }

                val result = getLatestRelease(forceRefresh = true)
                if (result.isSuccess) {
                    val releaseInfo = result.getOrThrow()
                    releaseInfo to isUpstreamUpdateAvailable(releaseInfo.versionName)
                } else {
                    throw result.exceptionOrNull() ?: Exception("Unknown error")
                }
            }
        }

    /** Legacy Meld APK URL; never use as a MusicLab installer action. */
    fun getLatestDownloadUrl(): String? =
        cachedReleaseInfo?.let { getDownloadUrlForCurrentVariant(it) }

    fun getCachedLatestRelease(): ReleaseInfo? = cachedReleaseInfo
}
