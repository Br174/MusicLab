/**
 * MusicLab cover/name search hub engine
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
import kotlin.math.abs
import kotlin.math.max

internal enum class CoverHubMode {
    COVERS,
    SAME_NAME,
}

internal data class CoverHubResult(
    val song: SongItem,
    val year: Int? = null,
    val source: String = "MusicLab",
    val confirmed: Boolean = false,
    val score: Double = 0.0,
)

internal data class CoverHubOutcome(
    val results: List<CoverHubResult>,
    val whoSampledStatus: WhoSampledStatus = WhoSampledStatus.NETWORK_ERROR,
    val whoSampledCount: Int = 0,
    val geminiCount: Int = 0,
)

internal object CoverHubSearchEngine {
    suspend fun searchCovers(
        title: String,
        originalArtist: String,
        durationSec: Int,
        currentYouTubeId: String?,
        geminiConfig: GeminiCoverVerificationConfig?,
    ): CoverHubOutcome = coroutineScope {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return@coroutineScope CoverHubOutcome(emptyList())

        val whoDeferred = async(Dispatchers.IO) {
            runCatching { WhoSampledCoverSource.lookup(cleanTitle, originalArtist) }
                .getOrElse { WhoSampledLookup(emptyList(), WhoSampledStatus.NETWORK_ERROR) }
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
        val broadDeferred = async(Dispatchers.IO) {
            broadYouTubeCovers(
                title = cleanTitle,
                originalArtist = originalArtist,
                durationSec = durationSec,
                currentYouTubeId = currentYouTubeId,
            )
        }

        val who = whoDeferred.await()
        val mb = mbDeferred.await()
        val gemini = geminiDeferred?.await().orEmpty()

        val whoResolvedDeferred = async {
            resolveReferences(
                references = who.covers.map { Ref(it.title, it.artist, null, "WhoSampled", true) },
                originalArtist = originalArtist,
                durationSec = durationSec,
                currentYouTubeId = currentYouTubeId,
                maxRefs = 60,
            )
        }
        val mbResolvedDeferred = async {
            resolveReferences(
                references = mb.covers.map { Ref(it.title, it.artist, it.year, "MusicBrainz", true) },
                originalArtist = originalArtist,
                durationSec = durationSec,
                currentYouTubeId = currentYouTubeId,
                maxRefs = 80,
            )
        }
        val geminiResolvedDeferred = async {
            resolveReferences(
                references = gemini.map {
                    Ref(
                        title = it.title,
                        artist = it.artist,
                        year = it.year,
                        source = "Gemini · web",
                        confirmed = false,
                    )
                },
                originalArtist = originalArtist,
                durationSec = durationSec,
                currentYouTubeId = currentYouTubeId,
                maxRefs = 48,
            )
        }

        val all = buildList {
            addAll(whoResolvedDeferred.await())
            addAll(mbResolvedDeferred.await())
            addAll(geminiResolvedDeferred.await())
            addAll(broadDeferred.await())
        }

        val merged = mergeResults(all)
        val enriched = enrichYears(merged, geminiConfig)
        val ordered = orderOldestFirst(enriched).take(MAX_RESULTS)

        CoverHubOutcome(
            results = ordered,
            whoSampledStatus = who.status,
            whoSampledCount = ordered.count { it.source.startsWith("WhoSampled") },
            geminiCount = gemini.size,
        )
    }

    suspend fun searchSameName(
        title: String,
        currentYouTubeId: String?,
        geminiConfig: GeminiCoverVerificationConfig?,
    ): List<CoverHubResult> = coroutineScope {
        val clean = title.trim()
        if (clean.isBlank()) return@coroutineScope emptyList()
        val target = canonicalTitle(clean)

        val queries = linkedSetOf(
            clean,
            "\"$clean\"",
            "$clean song",
            "$clean audio",
            "$clean official audio",
            "$clean music",
            "$clean track",
            "$clean remaster",
            "$clean live",
            "$clean cover",
        )

        val songs = linkedMapOf<String, SongItem>()
        for (batch in queries.chunked(5)) {
            batch.map { query ->
                async(Dispatchers.IO) {
                    YouTube.searchSummary(query, incognito = true).getOrNull()
                }
            }.awaitAll().filterNotNull().forEach { page ->
                page.summaries
                    .flatMap { it.items }
                    .filterIsInstance<SongItem>()
                    .forEach { song ->
                        if (song.id != currentYouTubeId && canonicalTitle(song.title) == target) {
                            songs.putIfAbsent(song.id, song)
                        }
                    }
            }
        }

        val raw = songs.values.map {
            CoverHubResult(
                song = it,
                source = "Stesso nome",
                score = 1.0,
            )
        }
        orderOldestFirst(enrichYears(raw, geminiConfig)).take(MAX_RESULTS)
    }

    private data class Ref(
        val title: String,
        val artist: String,
        val year: Int?,
        val source: String,
        val confirmed: Boolean,
    )

    private suspend fun resolveReferences(
        references: List<Ref>,
        originalArtist: String,
        durationSec: Int,
        currentYouTubeId: String?,
        maxRefs: Int,
    ): List<CoverHubResult> = coroutineScope {
        val deduped = references
            .distinctBy { "${canonicalTitle(it.title)}|${canonicalArtist(it.artist)}" }
            .take(maxRefs)
        val results = mutableListOf<CoverHubResult>()

        for (batch in deduped.chunked(6)) {
            results += batch.map { ref ->
                async(Dispatchers.IO) {
                    resolveReference(
                        ref = ref,
                        originalArtist = originalArtist,
                        durationSec = durationSec,
                        currentYouTubeId = currentYouTubeId,
                    )
                }
            }.awaitAll().filterNotNull()
        }
        results
    }

    private suspend fun resolveReference(
        ref: Ref,
        originalArtist: String,
        durationSec: Int,
        currentYouTubeId: String?,
    ): CoverHubResult? {
        val page = YouTube.searchSummary("${ref.title} ${ref.artist}".trim(), incognito = true).getOrNull()
            ?: return null
        val songs = page.summaries.flatMap { it.items }.filterIsInstance<SongItem>().distinctBy { it.id }
        val refTitle = canonicalTitle(ref.title)
        val refArtist = canonicalArtist(ref.artist)
        val originalArtistKey = canonicalArtist(originalArtist)

        return songs.mapNotNull { song ->
            if (song.id == currentYouTubeId) return@mapNotNull null
            if (DISALLOWED_REGEX.containsMatchIn(song.title.lowercase())) return@mapNotNull null

            val titleScore = titleSimilarity(refTitle, canonicalTitle(song.title))
            if (titleScore < 0.52) return@mapNotNull null
            val artistScore = song.artists.maxOfOrNull { artistSimilarity(refArtist, canonicalArtist(it.name)) } ?: 0.0
            if (refArtist.isNotBlank() && artistScore < 0.24 && titleScore < 0.92) return@mapNotNull null

            val differentArtist = originalArtistKey.isBlank() || song.artists.none {
                canonicalArtist(it.name) == originalArtistKey
            }
            if (!differentArtist && !ref.source.startsWith("WhoSampled")) return@mapNotNull null

            val durationScore = durationCompatibility(durationSec, song.duration ?: -1)
            val score = titleScore * 0.62 + artistScore * 0.25 + durationScore * 0.13 + if (ref.confirmed) 0.25 else 0.0
            CoverHubResult(
                song = song,
                year = ref.year,
                source = ref.source,
                confirmed = ref.confirmed,
                score = score,
            )
        }.maxByOrNull { it.score }
    }

    private suspend fun broadYouTubeCovers(
        title: String,
        originalArtist: String,
        durationSec: Int,
        currentYouTubeId: String?,
    ): List<CoverHubResult> = coroutineScope {
        val queries = linkedSetOf<String>().apply {
            if (originalArtist.isNotBlank()) {
                add("$title $originalArtist cover")
                add("$title $originalArtist version")
                add("$title $originalArtist interpretation")
            }
            add("$title cover")
            add("$title covers")
            add("$title version")
            add("$title rendition")
            add("$title interpretation")
            add("$title reinterpretation")
            add("$title performed by")
            add("$title tribute")
            add("$title acoustic cover")
            add("$title unplugged cover")
            add("$title live cover")
            add("$title remake")
            add("$title versione")
            add("$title versión")
            add("$title versão")
            add("$title reprise")
            add(title)
        }

        val songs = linkedMapOf<String, SongItem>()
        for (batch in queries.chunked(5)) {
            batch.map { query ->
                async(Dispatchers.IO) { YouTube.searchSummary(query, incognito = true).getOrNull() }
            }.awaitAll().filterNotNull().forEach { page ->
                page.summaries.flatMap { it.items }.filterIsInstance<SongItem>().forEach {
                    songs.putIfAbsent(it.id, it)
                }
            }
        }

        val target = canonicalTitle(title)
        val originalArtistKey = canonicalArtist(originalArtist)
        songs.values.mapNotNull { song ->
            if (song.id == currentYouTubeId) return@mapNotNull null
            if (DISALLOWED_REGEX.containsMatchIn(song.title.lowercase())) return@mapNotNull null
            if (originalArtistKey.isNotBlank() && song.artists.any { canonicalArtist(it.name) == originalArtistKey }) {
                return@mapNotNull null
            }
            val titleScore = titleSimilarity(target, canonicalTitle(song.title))
            if (titleScore < 0.66) return@mapNotNull null
            val durationScore = durationCompatibility(durationSec, song.duration ?: -1)
            if (durationScore < 0.15 && titleScore < 0.92) return@mapNotNull null
            CoverHubResult(
                song = song,
                source = "MusicLab",
                confirmed = false,
                score = titleScore * 0.78 + durationScore * 0.22,
            )
        }.sortedByDescending { it.score }.take(120)
    }

    private fun mergeResults(values: List<CoverHubResult>): List<CoverHubResult> {
        val byId = linkedMapOf<String, CoverHubResult>()
        values.forEach { candidate ->
            val existing = byId[candidate.song.id]
            if (existing == null || priority(candidate) > priority(existing) ||
                (priority(candidate) == priority(existing) && candidate.score > existing.score)
            ) {
                byId[candidate.song.id] = candidate
            }
        }

        return byId.values
            .groupBy {
                "${canonicalTitle(it.song.title)}|${it.song.artists.joinToString("|") { a -> canonicalArtist(a.name) }}"
            }
            .map { (_, versions) -> versions.maxWithOrNull(compareBy<CoverHubResult> { priority(it) }.thenBy { it.score })!! }
    }

    private fun priority(result: CoverHubResult): Int = when {
        result.source == "WhoSampled" -> 5
        result.source.startsWith("WhoSampled") -> 4
        result.source == "MusicBrainz" -> 4
        result.confirmed -> 3
        else -> 1
    }

    private suspend fun enrichYears(
        values: List<CoverHubResult>,
        geminiConfig: GeminiCoverVerificationConfig?,
    ): List<CoverHubResult> = coroutineScope {
        val missing = values.filter { it.year == null }
        val albumYears = mutableMapOf<String, Int?>()
        val representatives = missing.mapNotNull { item ->
            item.song.album?.id?.let { id -> id to item.song }
        }.distinctBy { it.first }

        for (batch in representatives.chunked(6)) {
            batch.map { (albumId, song) ->
                async(Dispatchers.IO) { albumId to runCatching { CoverYearResolver.resolve(song) }.getOrNull() }
            }.awaitAll().forEach { (id, year) -> albumYears[id] = year }
        }

        var enriched = values.map { value ->
            val albumYear = value.song.album?.id?.let { albumYears[it] }
            value.copy(year = value.year ?: albumYear)
        }

        if (geminiConfig != null) {
            val unresolved = enriched.filter { it.year == null }.take(36)
            val webYears = mutableMapOf<String, Int?>()
            for (batch in unresolved.chunked(4)) {
                batch.map { item ->
                    async(Dispatchers.IO) {
                        item.song.id to runCatching {
                            GeminiCoverDiscovery.resolveYear(
                                title = item.song.title,
                                artist = item.song.artists.joinToString(", ") { it.name },
                                config = geminiConfig,
                            )
                        }.getOrNull()
                    }
                }.awaitAll().forEach { (id, year) -> webYears[id] = year }
            }
            enriched = enriched.map { it.copy(year = it.year ?: webYears[it.song.id]) }
        }
        enriched
    }

    private fun orderOldestFirst(values: List<CoverHubResult>): List<CoverHubResult> =
        values.sortedWith(
            compareBy<CoverHubResult> { if (it.year == null) 1 else 0 }
                .thenBy { it.year ?: Int.MAX_VALUE }
                .thenByDescending { priority(it) }
                .thenByDescending { it.score },
        )

    private fun canonicalTitle(value: String): String {
        val clean = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(TITLE_NOISE_REGEX, " ")
            .replace(Regex("\\b(feat|ft|featuring)\\.?\\s+.*$"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return clean.replace(Regex("\\s+"), " ")
    }

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

    private fun durationCompatibility(original: Int, candidate: Int): Double {
        if (original <= 0 || candidate <= 0) return 0.55
        val difference = abs(original - candidate)
        val allowed = max(210, (original * 0.85).toInt())
        if (difference >= allowed) return 0.0
        return 1.0 - difference.toDouble() / allowed.toDouble()
    }

    private const val MAX_RESULTS = 120

    private val TITLE_NOISE_REGEX = Regex(
        "\\b(official|video|audio|lyrics?|lyric|cover|acoustic|unplugged|live|version|versione|versión|versao|versão|rendition|interpretation|reinterpretation|tribute|performance|session|remaster(?:ed)?|studio)\\b",
    )

    private val DISALLOWED_REGEX = Regex(
        "\\b(mashup|medley|reaction|tutorial|lesson|how to play|karaoke|instrumental backing track|backing track)\\b",
    )
}
