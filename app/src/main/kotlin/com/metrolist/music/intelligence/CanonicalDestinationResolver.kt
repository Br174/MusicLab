package com.metrolist.music.intelligence

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YouTube/YouTube Music are used here only after the AI has decided the canonical
 * musical identity. They only provide the technical browse id needed for navigation.
 */
object CanonicalDestinationResolver {
    suspend fun artistBrowseId(canonicalArtist: String): String? = withContext(Dispatchers.IO) {
        val target = canonicalMusicKey(canonicalArtist)
        if (target.isBlank()) return@withContext null
        val page = runCatching {
            YouTube.search(canonicalArtist, YouTube.SearchFilter.FILTER_ARTIST).getOrThrow()
        }.getOrNull() ?: return@withContext null

        page.items.filterIsInstance<ArtistItem>()
            .map { item -> item to nameScore(canonicalMusicKey(item.title), target) }
            .filter { it.second >= MIN_ARTIST_SCORE }
            .maxByOrNull { it.second }
            ?.first
            ?.id
    }

    suspend fun albumBrowseId(canonicalAlbum: String, canonicalArtist: String): String? = withContext(Dispatchers.IO) {
        val targetAlbum = canonicalMusicKey(canonicalAlbum)
        val targetArtist = canonicalMusicKey(canonicalArtist)
        if (targetAlbum.isBlank() || targetArtist.isBlank()) return@withContext null

        val query = "$canonicalAlbum $canonicalArtist"
        val page = runCatching {
            YouTube.search(query, YouTube.SearchFilter.FILTER_ALBUM).getOrThrow()
        }.getOrNull() ?: return@withContext null

        page.items.filterIsInstance<AlbumItem>()
            .map { item ->
                val albumScore = nameScore(canonicalMusicKey(item.title), targetAlbum)
                val artistScore = item.artists.orEmpty()
                    .maxOfOrNull { nameScore(canonicalMusicKey(it.name), targetArtist) }
                    ?: 0
                item to (albumScore + artistScore)
            }
            .filter { it.second >= MIN_ALBUM_SCORE }
            .maxByOrNull { it.second }
            ?.first
            ?.browseId
    }

    private fun nameScore(candidate: String, target: String): Int {
        if (candidate.isBlank() || target.isBlank()) return 0
        if (candidate == target) return 100
        if (candidate.removePrefix("the ") == target.removePrefix("the ")) return 95
        if (candidate.startsWith("$target ") || target.startsWith("$candidate ")) return 72
        if (candidate.contains(" $target ") || target.contains(" $candidate ")) return 65
        return 0
    }

    private const val MIN_ARTIST_SCORE = 72
    private const val MIN_ALBUM_SCORE = 150
}
