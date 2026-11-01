package com.metrolist.music.discogs

import java.text.Normalizer
import java.util.Locale

/**
 * Only confirmed tracklist entries enter the Song -> Compilation results.
 * A release-level "Various" credit isn't proof of a wrong performer.
 */
internal object CompilationTrackMatchPolicy {
    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036f]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim().replace(Regex("\\s+"), " ")

    fun matches(track: DiscogsTrack, title: String, artist: String): Boolean {
        val wanted = normalize(title)
        val actual = normalize(track.title)
        if (wanted.isEmpty() || actual.isEmpty()) return false
        // Exact work label; allow a documented edition suffix, not a different song.
        if (actual != wanted && !actual.startsWith("$"+"wanted ")) return false
        val requestedArtist = normalize(artist)
        if (requestedArtist.isEmpty()) return true
        val attributed = track.artists.map(::normalize).filter(String::isNotBlank)
        if (attributed.isEmpty() || attributed.all { it == "various" || it == "various artists" }) {
            return true // title verified, recording credit not supplied by source
        }
        return attributed.any { it == requestedArtist ||
            it.startsWith("$"+"requestedArtist ") || requestedArtist.startsWith(it + " ") }
    }
}
