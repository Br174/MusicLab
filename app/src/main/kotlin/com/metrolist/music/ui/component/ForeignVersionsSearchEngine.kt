/**
 * MusicLab foreign-language/adapted version discovery
 * Licensed under GPL-3.0 | See repository history for contributors
 */
package com.metrolist.music.ui.component

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer
import kotlin.math.max

internal data class ForeignVersionsOutcome(
    val results: List<CoverHubResult>,
    val whoSampledStatus: WhoSampledStatus = WhoSampledStatus.NETWORK_ERROR,
    val secondHandSongsStatus: SecondHandSongsStatus = SecondHandSongsStatus.NETWORK_ERROR,
    val musicBrainzStatus: MusicBrainzStatus = MusicBrainzStatus.NETWORK_ERROR,
    val geminiConfigured: Boolean = false,
    val whoSampledStats: CoverSourceStats = CoverSourceStats(),
    val secondHandSongsStats: CoverSourceStats = CoverSourceStats(),
    val musicBrainzStats: CoverSourceStats = CoverSourceStats(),
    val geminiStats: CoverSourceStats = CoverSourceStats(),
)

/**
 * Finds translated/adapted titles of the same underlying composition.
 *
 * Structured sources are trusted only when they already associate a recording
 * with the same work/performance family. Gemini is used as a discovery aid and
 * only candidates explicitly marked as translated/adapted are accepted. Every
 * candidate must then resolve to a real YouTube Music SongItem before it is
 * shown to the user.
 */
internal object ForeignVersionsSearchEngine {
    suspend fun search(
        title: String,
        originalArtist: String,
        currentYouTubeId: String?,
        geminiConfig: GeminiCoverVerificationConfig?,
    ): ForeignVersionsOutcome = coroutineScope {
        val cleanTitle = cleanLookupTitle(title, originalArtist)
        if (cleanTitle.isBlank()) return@coroutineScope ForeignVersionsOutcome(emptyList())

        val whoDeferred = async(Dispatchers.IO) {
            runCatching { WhoSampledCoverSource.lookup(cleanTitle, originalArtist) }
                .getOrElse { WhoSampledLookup(emptyList(), WhoSampledStatus.NETWORK_ERROR) }
        }
        val secondDeferred = async(Dispatchers.IO) {
            runCatching { SecondHandSongsCoverSource.lookup(cleanTitle, originalArtist) }
                .getOrElse { SecondHandSongsLookup(emptyList(), SecondHandSongsStatus.NETWORK_ERROR) }
        }
        val mbDeferred = async(Dispatchers.IO) {
            runCatching { MusicBrainzCoverSource.lookup(cleanTitle, originalArtist) }
                .getOrElse { MusicBrainzLookup(emptyList(), MusicBrainzStatus.NETWORK_ERROR) }
        }
        val geminiDeferred = geminiConfig?.let { config ->
            async(Dispatchers.IO) {
                runCatching {
                    GeminiCoverDiscovery.discover(
                        originalTitle = cleanTitle,
                        originalArtist = originalArtist,
                        config = config,
                    )
                }.getOrDefault(emptyList())
            }
        }

        val who = whoDeferred.await()
        val second = secondDeferred.await()
        val mb = mbDeferred.await()
        val gemini = geminiDeferred?.await().orEmpty()

        val originalKey = canonicalTitle(cleanTitle)
        fun isDifferentTitle(candidate: String): Boolean {
            val key = canonicalTitle(candidate)
            if (key.isBlank()) return false
            return titleSimilarity(originalKey, key) < SAME_TITLE_THRESHOLD
        }

        val whoRefs = who.covers
            .filter { isDifferentTitle(it.title) }
            .map { Ref(it.title, it.artist, null, "WhoSampled", true) }

        val secondRefs = second.covers
            .filter { isDifferentTitle(it.title) }
            .map { Ref(it.title, it.artist, it.year, "SecondHandSongs", true) }

        val mbRefs = mb.covers
            .filter { isDifferentTitle(it.title) }
            .map { Ref(it.title, it.artist, it.year, "MusicBrainz", true) }

        val geminiRefs = gemini
            .filter { it.translatedOrAdaptedTitle && isDifferentTitle(it.title) }
            .map { Ref(it.title, it.artist, it.year, "Gemini AI", false) }

        val whoResolvedDeferred = async { resolveReferences(whoRefs, currentYouTubeId, 50) }
        val secondResolvedDeferred = async { resolveReferences(secondRefs, currentYouTubeId, 80) }
        val mbResolvedDeferred = async { resolveReferences(mbRefs, currentYouTubeId, 80) }
        val geminiResolvedDeferred = async { resolveReferences(geminiRefs, currentYouTubeId, 48) }

        val whoResolved = whoResolvedDeferred.await()
        val secondResolved = secondResolvedDeferred.await()
        val mbResolved = mbResolvedDeferred.await()
        val geminiResolved = geminiResolvedDeferred.await()

        val ordered = mergeResults(
            whoResolved + secondResolved + mbResolved + geminiResolved,
        ).sortedWith(
            compareBy<CoverHubResult> { if (it.year == null) 1 else 0 }
                .thenBy { it.year ?: Int.MAX_VALUE }
                .thenByDescending { it.confirmed }
                .thenByDescending { it.score },
        ).take(MAX_RESULTS)

        fun used(source: String): Int = ordered.count { hasSource(it.source, source) }

        ForeignVersionsOutcome(
            results = ordered,
            whoSampledStatus = who.status,
            secondHandSongsStatus = second.status,
            musicBrainzStatus = mb.status,
            geminiConfigured = geminiConfig != null,
            whoSampledStats = CoverSourceStats(
                found = whoRefs.size,
                resolved = whoResolved.size,
                used = used("WhoSampled"),
            ),
            secondHandSongsStats = CoverSourceStats(
                found = secondRefs.size,
                resolved = secondResolved.size,
                used = used("SecondHandSongs"),
            ),
            musicBrainzStats = CoverSourceStats(
                found = mbRefs.size,
                resolved = mbResolved.size,
                used = used("MusicBrainz"),
            ),
            geminiStats = CoverSourceStats(
                found = geminiRefs.size,
                resolved = geminiResolved.size,
                used = used("Gemini AI"),
            ),
        )
    }

    private data class Ref(
        val title: String,
        val artist: String,
        val year: Int?,
        val source: String,
        val confirmed: Boolean,
    )

    private suspend fun resolveReferences(
        refs: List<Ref>,
        currentYouTubeId: String?,
        maxRefs: Int,
    ): List<CoverHubResult> = coroutineScope {
        val uniqueRefs = refs
            .distinctBy { "${canonicalTitle(it.title)}|${canonicalArtist(it.artist)}" }
            .take(maxRefs)
        val results = mutableListOf<CoverHubResult>()

        for (batch in uniqueRefs.chunked(6)) {
            results += batch.map { ref ->
                async(Dispatchers.IO) { resolveReference(ref, currentYouTubeId) }
            }.awaitAll().filterNotNull()
        }
        results
    }

    private suspend fun resolveReference(
        ref: Ref,
        currentYouTubeId: String?,
    ): CoverHubResult? {
        val query = "${ref.title} ${ref.artist}".trim()
        val page = YouTube.searchSummary(query, incognito = true).getOrNull()
            ?: return null
        val songs = page.summaries
            .flatMap { it.items }
            .filterIsInstance<SongItem>()
            .distinctBy { it.id }

        val wantedTitle = canonicalTitle(ref.title)
        val wantedArtist = canonicalArtist(ref.artist)

        return songs.mapNotNull { song ->
            if (song.id == currentYouTubeId) return@mapNotNull null
            if (DISALLOWED_REGEX.containsMatchIn(song.title.lowercase())) return@mapNotNull null

            val titleScore = titleSimilarity(wantedTitle, canonicalTitle(song.title))
            if (titleScore < 0.56) return@mapNotNull null
            val artistScore = song.artists.maxOfOrNull {
                artistSimilarity(wantedArtist, canonicalArtist(it.name))
            } ?: 0.0
            if (wantedArtist.isNotBlank() && artistScore < 0.24 && titleScore < 0.93) {
                return@mapNotNull null
            }

            CoverHubResult(
                song = song,
                year = ref.year,
                source = ref.source,
                confirmed = ref.confirmed,
                score = titleScore * 0.72 + artistScore * 0.28 + if (ref.confirmed) 0.20 else 0.0,
            )
        }.maxByOrNull { it.score }
    }

    private fun mergeResults(values: List<CoverHubResult>): List<CoverHubResult> {
        val byId = linkedMapOf<String, CoverHubResult>()
        values.forEach { candidate ->
            val existing = byId[candidate.song.id]
            if (existing == null) {
                byId[candidate.song.id] = candidate
            } else {
                val winner = if (candidate.score > existing.score) candidate else existing
                val other = if (winner === candidate) existing else candidate
                byId[candidate.song.id] = winner.copy(
                    year = winner.year ?: other.year,
                    source = combineSources(winner.source, other.source),
                    confirmed = winner.confirmed || other.confirmed,
                    score = maxOf(winner.score, other.score),
                )
            }
        }
        return byId.values.toList()
    }

    private fun combineSources(a: String, b: String): String =
        linkedSetOf<String>().apply {
            addAll(sourceNames(a))
            addAll(sourceNames(b))
        }.joinToString(SOURCE_DELIMITER)

    private fun sourceNames(source: String): List<String> =
        source.split(SOURCE_DELIMITER)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun hasSource(sourceLabel: String, source: String): Boolean =
        sourceNames(sourceLabel).any { it.equals(source, ignoreCase = true) }

    private fun cleanLookupTitle(value: String, originalArtist: String): String {
        val original = value.trim()
        if (original.isBlank()) return original
        var clean = original
        val artist = originalArtist.trim()
        if (artist.isNotBlank()) {
            clean = clean.replaceFirst(
                Regex("^\\s*${Regex.escape(artist)}\\s*[-–—:|]\\s*", RegexOption.IGNORE_CASE),
                "",
            ).trim()
        }
        clean = TECHNICAL_ANNOTATION_REGEX.replace(clean) { match ->
            val inner = match.value.drop(1).dropLast(1)
            if (TECHNICAL_MARKERS.containsMatchIn(inner)) " " else match.value
        }
        return clean.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', ':', '|').ifBlank { original }
    }

    private fun canonicalTitle(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(Regex("\\b(official|video|audio|lyrics?|lyric|cover|live|version|versione|versión|remaster(?:ed)?)\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun canonicalArtist(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun titleSimilarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (a.startsWith(b) || b.startsWith(a)) return 0.94
        val aa = a.split(' ').filter { it.length > 1 }.toSet()
        val bb = b.split(' ').filter { it.length > 1 }.toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        val overlap = aa.intersect(bb).size.toDouble()
        val containment = overlap / max(1, minOf(aa.size, bb.size)).toDouble()
        val jaccard = overlap / aa.union(bb).size.toDouble()
        return containment * 0.65 + jaccard * 0.35
    }

    private fun artistSimilarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return 0.90
        val aa = a.split(' ').filter { it.length > 1 }.toSet()
        val bb = b.split(' ').filter { it.length > 1 }.toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        return aa.intersect(bb).size.toDouble() / max(1, minOf(aa.size, bb.size)).toDouble()
    }

    private const val SAME_TITLE_THRESHOLD = 0.88
    private const val MAX_RESULTS = 100
    private const val SOURCE_DELIMITER = " + "

    private val TECHNICAL_ANNOTATION_REGEX = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val TECHNICAL_MARKERS = Regex(
        "\\b(remaster(?:ed)?|official|video|audio|lyrics?|lyric|live|version|visualizer|hd|4k)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val DISALLOWED_REGEX = Regex(
        "\\b(mashup|medley|reaction|tutorial|lesson|how to play|karaoke|instrumental backing track|backing track)\\b",
    )
}
