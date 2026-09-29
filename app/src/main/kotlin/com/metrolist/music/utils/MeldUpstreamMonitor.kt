package com.metrolist.music.utils

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Monitora esclusivamente il fork italiano FrancescoGrazioso/Meld.
 * Non scarica né installa APK Meld: segnala soltanto che esiste una nuova
 * base upstream da valutare e integrare in MusicLab tramite MotorLab/LAB.
 */
data class MeldUpstreamRelease(
    val tagName: String,
    val versionName: String,
    val description: String,
    val publishedAt: String,
    val releaseUrl: String,
)

object MeldUpstreamMonitor {
    private const val GITHUB_API_BASE = "https://api.github.com/repos/FrancescoGrazioso/Meld"
    private val client = HttpClient()

    suspend fun checkForUpdate(integratedVersion: String): Result<Pair<MeldUpstreamRelease?, Boolean>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = client.get("$GITHUB_API_BASE/releases/latest").bodyAsText()
                val json = JSONObject(response)
                val release =
                    MeldUpstreamRelease(
                        tagName = json.optString("tag_name"),
                        versionName = json.optString("name").ifBlank { json.optString("tag_name").removePrefix("v") },
                        description = json.optString("body"),
                        publishedAt = json.optString("published_at"),
                        releaseUrl = json.optString("html_url"),
                    )
                val hasUpdate = Updater.compareVersions(release.versionName, integratedVersion) > 0
                release to hasUpdate
            }
        }
}
