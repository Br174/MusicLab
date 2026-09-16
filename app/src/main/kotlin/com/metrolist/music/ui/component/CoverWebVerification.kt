/**
 * MusicLab cover web verification
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import java.net.URLEncoder

/**
 * Builds a user-driven Google verification search without requiring an API key.
 * MusicLab does not scrape or automatically trust Google's AI output; the search
 * is opened for human inspection and remains a supporting verification path.
 */
internal object CoverWebVerification {
    fun googleSearchUrl(
        originalTitle: String,
        originalArtist: String,
        candidateTitle: String,
        candidateArtist: String,
    ): String {
        val query = buildString {
            append('"').append(candidateTitle.trim()).append('"')
            if (candidateArtist.isNotBlank()) {
                append(' ').append('"').append(candidateArtist.trim()).append('"')
            }
            append(' ').append('"').append(originalTitle.trim()).append('"')
            if (originalArtist.isNotBlank()) {
                append(' ').append('"').append(originalArtist.trim()).append('"')
            }
            append(" cover adaptation version same song")
        }

        return "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}" 
    }
}
