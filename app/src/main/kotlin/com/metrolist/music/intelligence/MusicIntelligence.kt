package com.metrolist.music.intelligence

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.constants.AiProviderKey
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
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val LegacyCoverGeminiApiKey = stringPreferencesKey("coverGeminiApiKey")

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
                endpoint = ds.get(MusicAiCloudEndpointKey, DEFAULT_MUSIC_AI_CLOUD_ENDPOINT)
                    .trim().trimEnd('/').ifBlank { DEFAULT_MUSIC_AI_CLOUD_ENDPOINT },
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
 * Resolver globale. Viene chiamato soltanto fuori dal percorso critico del player:
 * il video/audio può partire subito; identità, crediti e destinazioni arrivano dopo.
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
        if (!settings.enabled || !settings.backgroundMetadata) return@withContext null

        // Cloudflare è la corsia primaria. Finché il Worker non è configurato o se
        // temporaneamente non risponde, la LAB resta testabile usando Gemini diretto.
        var result = if (settings.endpoint.isNotBlank()) {
            resolveFromCloud(settings, playbackId, title, artist, album)
        } else {
            null
        }
        if (result == null) {
            result = resolveDirectGemini(context, playbackId, title, artist, album, settings.credits)
        }
        if (result == null) return@withContext null

        val enriched = enrichTechnicalDestinations(result, settings)
        memory[playbackId] = enriched
        enriched
    }

    private fun resolveFromCloud(
        settings: MusicIntelligenceSettings,
        playbackId: String,
        title: String,
        artist: String,
        album: String?,
    ): CanonicalMusicMetadata? {
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
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val root = response.body?.string()?.let { json.parseToJsonElement(it).jsonObject }
                    ?: return@use null
                root.toCanonical(playbackId)
            }
        }.getOrNull()
    }

    private fun resolveDirectGemini(
        context: Context,
        playbackId: String,
        title: String,
        artist: String,
        album: String?,
        wantCredits: Boolean,
    ): CanonicalMusicMetadata? {
        val ds = context.dataStore
        val provider = ds.get(AiProviderKey, "OpenRouter")
        val sharedKey = ds.get(OpenRouterApiKey, "")
        val dedicatedKey = ds.get(LegacyCoverGeminiApiKey, "")
        val apiKey = dedicatedKey.ifBlank {
            sharedKey.takeIf { provider == "Gemini" || it.startsWith("AIza") }.orEmpty()
        }
        if (apiKey.isBlank()) return null

        val configuredModel = ds.get(OpenRouterModelKey, "")
            .takeIf { provider == "Gemini" && it.isNotBlank() && !it.contains('/') }
        val models = listOfNotNull(configuredModel, "gemini-2.5-flash-lite").distinct()
        val creditsRule = if (wantCredits) {
            "Compila autori, compositori, parolieri, produttori ed etichetta quando li conosci."
        } else {
            "I crediti non sono richiesti: restituisci gli array vuoti e label null."
        }
        val prompt = """Sei il resolver musicale canonico di MusicLab.
L'AI è l'unica autorità editoriale. Il nome osservato può provenire da un uploader YouTube e NON va assunto automaticamente come artista reale.

Playback tecnico: $playbackId
Titolo osservato: $title
Artista osservato: $artist
Album osservato: ${album.orEmpty().ifBlank { "non disponibile" }}

Identifica la specifica registrazione musicale reale. Restituisci titolo canonico, artista reale, album reale se esiste, anno, lingua e categoria (originale, cover, live, remix o adattamento).
$creditsRule
Se un dato non è noto usa null o []. Non inventare una pagina YouTube né un browse id.
Rispondi SOLO JSON:
{"title":"","artist":"","album":null,"year":null,"language":null,"category":null,"credits":{"songwriters":[],"composers":[],"lyricists":[],"producers":[],"label":null}}
"""

        for (model in models) {
            val body = buildJsonObject {
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
                    })
                })
                put("generationConfig", buildJsonObject {
                    put("temperature", 0.08)
                    put("maxOutputTokens", 1600)
                    put("responseMimeType", "application/json")
                })
            }
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(mediaType))
                .build()
            val result = runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val root = response.body?.string()?.let { json.parseToJsonElement(it).jsonObject }
                        ?: return@use null
                    val text = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("content")?.jsonObject?.get("parts")?.jsonArray
                        ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                        ?.joinToString("\n")?.trim()
                        ?: return@use null
                    extractObject(text)?.toCanonical(playbackId)?.copy(source = "ai-diretta")
                }
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private suspend fun enrichTechnicalDestinations(
        metadata: CanonicalMusicMetadata,
        settings: MusicIntelligenceSettings,
    ): CanonicalMusicMetadata = coroutineScope {
        val artistJob = if (settings.artistResolver && metadata.artistBrowseId.isNullOrBlank()) {
            async(Dispatchers.IO) { CanonicalDestinationResolver.artistBrowseId(metadata.artist) }
        } else null
        val albumJob = if (
            settings.albumResolver &&
            metadata.albumBrowseId.isNullOrBlank() &&
            !metadata.album.isNullOrBlank()
        ) {
            async(Dispatchers.IO) {
                CanonicalDestinationResolver.albumBrowseId(metadata.album, metadata.artist)
            }
        } else null
        metadata.copy(
            artistBrowseId = metadata.artistBrowseId ?: artistJob?.await(),
            albumBrowseId = metadata.albumBrowseId ?: albumJob?.await(),
        )
    }

    private fun extractObject(text: String): JsonObject? {
        val cleaned = text.replace("```json", "", ignoreCase = true).replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject
        }.getOrNull()
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
            year = metadata["year"]?.runCatching { jsonPrimitive }?.getOrNull()?.let {
                it.intOrNull ?: it.contentOrNull?.toIntOrNull()
            },
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
