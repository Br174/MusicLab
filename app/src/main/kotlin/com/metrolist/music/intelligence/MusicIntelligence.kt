package com.metrolist.music.intelligence

import android.content.Context
import com.metrolist.music.constants.DEFAULT_MUSIC_AI_CLOUD_ENDPOINT
import com.metrolist.music.constants.MusicAiAlbumResolverEnabledKey
import com.metrolist.music.constants.MusicAiArtistResolverEnabledKey
import com.metrolist.music.constants.MusicAiBackgroundMetadataEnabledKey
import com.metrolist.music.constants.MusicAiCloudEndpointKey
import com.metrolist.music.constants.MusicAiCloudMemoryEnabledKey
import com.metrolist.music.constants.MusicAiCoverEnabledKey
import com.metrolist.music.constants.MusicAiCreditsEnabledKey
import com.metrolist.music.constants.MusicAiEngineEnabledKey
import com.metrolist.music.constants.MusicAiForeignEnabledKey
import com.metrolist.music.constants.MusicAiLiveEnabledKey
import com.metrolist.music.constants.MusicAiOriginalsEnabledKey
import com.metrolist.music.constants.MusicAiRemixEnabledKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Configurazione unica del cervello musicale. Il master switch prevale sempre.
 */
data class MusicIntelligenceSettings(
    val enabled: Boolean,
    val credits: Boolean,
    val artistResolver: Boolean,
    val albumResolver: Boolean,
    val cover: Boolean,
    val originals: Boolean,
    val live: Boolean,
    val remix: Boolean,
    val foreign: Boolean,
    val cloudMemory: Boolean,
    val backgroundMetadata: Boolean,
    val endpoint: String,
) {
    companion object {
        fun from(context: Context): MusicIntelligenceSettings {
            val ds = context.dataStore
            val master = ds.get(MusicAiEngineEnabledKey, true)
            return MusicIntelligenceSettings(
                enabled = master,
                credits = master && ds.get(MusicAiCreditsEnabledKey, true),
                artistResolver = master && ds.get(MusicAiArtistResolverEnabledKey, true),
                albumResolver = master && ds.get(MusicAiAlbumResolverEnabledKey, true),
                cover = master && ds.get(MusicAiCoverEnabledKey, true),
                originals = master && ds.get(MusicAiOriginalsEnabledKey, true),
                live = master && ds.get(MusicAiLiveEnabledKey, true),
                remix = master && ds.get(MusicAiRemixEnabledKey, true),
                foreign = master && ds.get(MusicAiForeignEnabledKey, true),
                cloudMemory = master && ds.get(MusicAiCloudMemoryEnabledKey, true),
                backgroundMetadata = master && ds.get(MusicAiBackgroundMetadataEnabledKey, true),
                endpoint = ds.get(MusicAiCloudEndpointKey, DEFAULT_MUSIC_AI_CLOUD_ENDPOINT).trim().trimEnd('/'),
            )
        }
    }
}

data class CanonicalCredits(
    val songwriters: List<String> = emptyList(),
    val composers: List<String> = emptyList(),
    val lyricists: List<String> = emptyList(),
    val producers: List<String> = emptyList(),
    val label: String? = null,
)

data class CanonicalMusicMetadata(
    val playbackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val year: Int? = null,
    val language: String? = null,
    val category: String? = null,
    val credits: CanonicalCredits = CanonicalCredits(),
    val artistBrowseId: String? = null,
    val albumBrowseId: String? = null,
    val source: String = "ai",
    val updatedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Client cloud non bloccante rispetto al player. Questa classe non viene mai chiamata
 * dal percorso critico che prepara/avvia lo stream audio/video.
 */
object MusicIntelligenceClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val memory = ConcurrentHashMap<String, CanonicalMusicMetadata>()

    fun cached(playbackId: String): CanonicalMusicMetadata? = memory[playbackId]

    suspend fun resolve(
        context: Context,
        playbackId: String,
        title: String,
        artist: String,
        album: String? = null,
    ): CanonicalMusicMetadata? = withContext(Dispatchers.IO) {
        memory[playbackId]?.let { return@withContext it }
        val settings = MusicIntelligenceSettings.from(context)
        if (!settings.enabled || !settings.backgroundMetadata || settings.endpoint.isBlank()) {
            return@withContext null
        }

        val body = buildJsonObject {
            put("playbackId", playbackId)
            put("title", title)
            put("artist", artist)
            album?.takeIf { it.isNotBlank() }?.let { put("albumHint", it) }
            put("wantCredits", settings.credits)
            put("wantArtistPage", settings.artistResolver)
            put("wantAlbumPage", settings.albumResolver)
            put("useMemory", settings.cloudMemory)
        }
        val request = Request.Builder()
            .url("${settings.endpoint}/api/v1/resolve")
            .post(body.toString().toRequestBody(mediaType))
            .addHeader("Content-Type", "application/json")
            .build()

        val result = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val root = response.body?.string()?.let { json.parseToJsonElement(it).jsonObject }
                    ?: return@use null
                root.toCanonical(playbackId)
            }
        }.getOrNull()
        if (result != null) memory[playbackId] = result
        result
    }

    private fun JsonObject.toCanonical(fallbackPlaybackId: String): CanonicalMusicMetadata? {
        val metadata = this["metadata"]?.runCatching { jsonObject }?.getOrNull() ?: this
        val title = metadata.string("title")
        val artist = metadata.string("artist")
        if (title.isBlank() || artist.isBlank()) return null
        val creditsObj = metadata["credits"]?.runCatching { jsonObject }?.getOrNull()
        return CanonicalMusicMetadata(
            playbackId = metadata.string("playbackId").ifBlank { fallbackPlaybackId },
            title = title,
            artist = artist,
            album = metadata.nullableString("album"),
            year = metadata["year"]?.runCatching { jsonPrimitive }?.getOrNull()?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() },
            language = metadata.nullableString("language"),
            category = metadata.nullableString("category"),
            credits = CanonicalCredits(
                songwriters = creditsObj?.strings("songwriters").orEmpty(),
                composers = creditsObj?.strings("composers").orEmpty(),
                lyricists = creditsObj?.strings("lyricists").orEmpty(),
                producers = creditsObj?.strings("producers").orEmpty(),
                label = creditsObj?.nullableString("label"),
            ),
            artistBrowseId = metadata.nullableString("artistBrowseId"),
            albumBrowseId = metadata.nullableString("albumBrowseId"),
            source = metadata.string("source").ifBlank { "ai" },
        )
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.runCatching { jsonPrimitive }?.getOrNull()?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.nullableString(key: String): String? =
        string(key).takeIf { it.isNotBlank() && !it.equals("null", true) }

    private fun JsonObject.strings(key: String): List<String> {
        val e = get(key) ?: return emptyList()
        val arr: JsonArray = e.runCatching { jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { it.runCatching { jsonPrimitive }.getOrNull()?.contentOrNull?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
}

internal fun canonicalMusicKey(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
