package com.metrolist.music.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Preferenze del motore musicale intelligente globale di MusicLab.
 * Il master switch ha precedenza su tutte le opzioni figlie.
 */
val MusicAiEngineEnabledKey = booleanPreferencesKey("musicAiEngineEnabled")
val MusicAiCreditsEnabledKey = booleanPreferencesKey("musicAiCreditsEnabled")
val MusicAiArtistResolverEnabledKey = booleanPreferencesKey("musicAiArtistResolverEnabled")
val MusicAiAlbumResolverEnabledKey = booleanPreferencesKey("musicAiAlbumResolverEnabled")
val MusicAiCoverEnabledKey = booleanPreferencesKey("musicAiCoverEnabled")
val MusicAiOriginalsEnabledKey = booleanPreferencesKey("musicAiOriginalsEnabled")
val MusicAiLiveEnabledKey = booleanPreferencesKey("musicAiLiveEnabled")
val MusicAiRemixEnabledKey = booleanPreferencesKey("musicAiRemixEnabled")
val MusicAiForeignEnabledKey = booleanPreferencesKey("musicAiForeignEnabled")
val MusicAiCloudMemoryEnabledKey = booleanPreferencesKey("musicAiCloudMemoryEnabled")
val MusicAiBackgroundMetadataEnabledKey = booleanPreferencesKey("musicAiBackgroundMetadataEnabled")

/** Endpoint del Worker centrale. Vuoto = backend cloud non configurato. */
val MusicAiCloudEndpointKey = stringPreferencesKey("musicAiCloudEndpoint")

const val DEFAULT_MUSIC_AI_CLOUD_ENDPOINT = ""
