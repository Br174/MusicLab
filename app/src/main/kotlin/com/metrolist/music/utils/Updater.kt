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
    val variant: String,
)

data class UpstreamReleaseInfo(
    val tagName: String,
    val versionName: String,
    val description: String,
    val releaseDate: String,
    val htmlUrl: String,
)

/**
 * Due canali volutamente separati:
 * 1) MusicLab releases -> APK realmente installabili come aggiornamenti MusicLab.
 * 2) FrancescoGrazioso/Meld -> sola sorgente upstream da esaminare, MAI APK da
 *    installare sopra MusicLab.
 */
object Updater {
    private val client = HttpClient()
    var lastCheckTime = -1L
        private set

    private var cachedReleaseInfo: ReleaseInfo? = null
    private var cachedAllReleases: List<ReleaseInfo> = emptyList()
    private var cachedUpstreamRelease: UpstreamReleaseInfo? = null
    private var lastUpstreamCheckTime = -1L

    private const val CHECK_INTERVAL_MILLIS = 2 * 60 * 60 * 1000L
    private const val MUSICLAB_API_BASE = "https://api.github.com/repos/Br174/MusicLab"
    private const val MELD_API_BASE = "https://api.github.com/repos/FrancescoGrazioso/Meld"
    private const val MUSICLAB_TAG_PREFIX = "musiclab-v"

    fun compareVersions(v1: String, v2: String): Int {
        fun parts(value: String) = value
            .removePrefix(MUSICLAB_TAG_PREFIX)
            .removePrefix("v")
            .substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
        val a = parts(v1)
        val b = parts(v2)
        val maxLength = maxOf(a.size, b.size)
        for (i in 0 until maxLength) {
            val p1 = a.getOrNull(i) ?: 0
            val p2 = b.getOrNull(i) ?: 0
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    fun isUpdateAvailable(currentVersion: String, latestVersion: String): Boolean =
        compareVersions(latestVersion, currentVersion) > 0

    private fun getCurrentAppVariant(): Pair<String, String> {
        val architecture = BuildConfig.ARCHITECTURE
        val variant = if (BuildConfig.CAST_AVAILABLE) "gms" else "foss"
        return architecture to variant
    }

    private fun parseMusicLabAssets(assetsArray: JSONArray): List<ReleaseAsset> {
        val assets = mutableListOf<ReleaseAsset>()
        for (i in 0 until assetsArray.length()) {
            val asset = assetsArray.getJSONObject(i)
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val downloadUrl = asset.optString("browser_download_url")
            val size = asset.optLong("size")
            val (arch, variant) = when {
                name.equals("MusicLab.apk", true) -> "universal" to "foss"
                name.equals("MusicLab-with-Google-Cast.apk", true) -> "universal" to "gms"
                name.startsWith("MusicLab-") && name.endsWith("-release.apk") ->
                    name.removePrefix("MusicLab-").removeSuffix("-release.apk") to "foss"
                else -> null to null
            }
            if (arch != null && variant != null && downloadUrl.isNotBlank()) {
                assets += ReleaseAsset(name, downloadUrl, size, arch, variant)
            }
        }
        return assets
    }

    private fun parseMusicLabRelease(obj: JSONObject): ReleaseInfo? {
        val tag = obj.optString("tag_name")
        if (!tag.startsWith(MUSICLAB_TAG_PREFIX) || obj.optBoolean("draft")) return null
        return ReleaseInfo(
            tagName = tag,
            versionName = tag.removePrefix(MUSICLAB_TAG_PREFIX),
            description = obj.optString("body"),
            releaseDate = obj.optString("published_at"),
            assets = parseMusicLabAssets(obj.optJSONArray("assets") ?: JSONArray()),
        )
    }

    private suspend fun fetchMusicLabReleases(): List<ReleaseInfo> {
        val json = JSONArray(client.get("$MUSICLAB_API_BASE/releases?per_page=50").bodyAsText())
        return buildList {
            for (i in 0 until json.length()) parseMusicLabRelease(json.getJSONObject(i))?.let(::add)
        }.sortedWith { a, b -> compareVersions(b.versionName, a.versionName) }
    }

    suspend fun getLatestRelease(forceRefresh: Boolean = false): Result<ReleaseInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cachedReleaseInfo != null && !forceRefresh) return@runCatching cachedReleaseInfo!!
                val release = fetchMusicLabReleases().firstOrNull()
                    ?: error("Nessuna release MusicLab pubblicata")
                cachedReleaseInfo = release
                lastCheckTime = System.currentTimeMillis()
                release
            }
        }

    suspend fun getAllReleases(forceRefresh: Boolean = false): Result<List<ReleaseInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cachedAllReleases.isNotEmpty() && !forceRefresh) return@runCatching cachedAllReleases
                fetchMusicLabReleases().also { cachedAllReleases = it }
            }
        }

    fun getDownloadUrlForCurrentVariant(releaseInfo: ReleaseInfo): String? {
        val (currentArch, currentVariant) = getCurrentAppVariant()
        return releaseInfo.assets.firstOrNull {
            (it.architecture == currentArch || it.architecture == "universal") && it.variant == currentVariant
        }?.downloadUrl
    }

    fun getAllDownloadUrls(releaseInfo: ReleaseInfo): Map<String, String> =
        releaseInfo.assets.associate { "${it.architecture}-${it.variant}" to it.downloadUrl }

    suspend fun checkForUpdate(forceRefresh: Boolean = false): Result<Pair<ReleaseInfo?, Boolean>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val shouldFetch = forceRefresh || (System.currentTimeMillis() - lastCheckTime) > CHECK_INTERVAL_MILLIS
                val release = if (!shouldFetch && cachedReleaseInfo != null) {
                    cachedReleaseInfo
                } else {
                    getLatestRelease(forceRefresh = true).getOrNull()
                }
                release to (release?.let { isUpdateAvailable(BuildConfig.VERSION_NAME, it.versionName) } == true)
            }
        }

    /** Meld è solo sorgente di codice upstream: nessun APK Meld viene restituito. */
    suspend fun checkForUpstreamUpdate(forceRefresh: Boolean = false): Result<Pair<UpstreamReleaseInfo?, Boolean>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val shouldFetch = forceRefresh ||
                    cachedUpstreamRelease == null ||
                    (System.currentTimeMillis() - lastUpstreamCheckTime) > CHECK_INTERVAL_MILLIS
                if (shouldFetch) {
                    val json = JSONObject(client.get("$MELD_API_BASE/releases/latest").bodyAsText())
                    cachedUpstreamRelease = UpstreamReleaseInfo(
                        tagName = json.optString("tag_name"),
                        versionName = json.optString("tag_name").removePrefix("v"),
                        description = json.optString("body"),
                        releaseDate = json.optString("published_at"),
                        htmlUrl = json.optString("html_url"),
                    )
                    lastUpstreamCheckTime = System.currentTimeMillis()
                }
                val release = cachedUpstreamRelease
                val baseline = BuildConfig.MELD_UPSTREAM_BASELINE
                release to (release?.let { compareVersions(it.versionName, baseline) > 0 } == true)
            }
        }

    fun getLatestDownloadUrl(): String? =
        cachedReleaseInfo?.let(::getDownloadUrlForCurrentVariant)

    fun getCachedLatestRelease(): ReleaseInfo? = cachedReleaseInfo
    fun getCachedUpstreamRelease(): UpstreamReleaseInfo? = cachedUpstreamRelease
}
