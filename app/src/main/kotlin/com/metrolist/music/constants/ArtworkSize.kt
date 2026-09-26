/**
 * MusicLab global artwork sizing
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.constants

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.stringPreferencesKey

val ArtworkSizeKey = stringPreferencesKey("artworkSize")

enum class ArtworkSize {
    SMALL,
    MEDIUM,
    LARGE,
    VERY_LARGE,
}

/**
 * In-memory mirror of the persisted preference. App.kt keeps this synchronized
 * with DataStore so common dimension constants can react without each screen
 * owning a separate preference reader.
 */
object ArtworkSizeRuntime {
    var current by mutableStateOf(ArtworkSize.MEDIUM)
}
