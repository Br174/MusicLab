package com.metrolist.music.ui.component

import com.metrolist.innertube.models.SongItem
import com.metrolist.music.discogs.DiscogsCredit

/** R16: verify title plus ANY two different corroborations against native YouTube metadata. */
internal object CoverR16FlexibleVideoMatch {
    private fun documented(row: CoverSourceCandidate) = row.workRelationConfirmed &&
        row.sources.any { it.equals("COVER.INFO", true) || it.equals("MusicBrainz", true) }

    private fun external(row: CoverSourceCandidate) = row.sources.any {
        it.equals("COVER.INFO", true) || it.equals("MusicBrainz", true) ||
            it.equals("Discogs", true) || it.equals("Apple/iTunes", true) ||
            it.equals("Spotify", true)
    }

    private fun trustedTrack(row: CoverSourceCandidate) = row.sources.any {
        when {
            it.equals("Apple/iTunes", true) ->
                row.sourceUrl?.startsWith("https://music.apple.com/") == true &&
                    (row.sourceUrl?.contains("/song/") == true || row.sourceUrl?.contains("?i=") == true)
            it.equals("Spotify", true) -> row.sourceUrl?.startsWith("https://open.spotify.com/track/") == true
            it.equals("Discogs", true) ->
                row.releaseVerified && row.recordingReleaseId != null && !row.recordingTrackPosition.isNullOrBlank()
            else -> false
        }
    }

    private fun aliases(row: CoverSourceCandidate, original: String) =
        setOf(row.artist, original).filter(String::isNotBlank).toSet()

    /** A preliminary check must NOT demand video evidence not fetched yet. */
    fun plausible(row: CoverSourceCandidate, title: String, original: String): Boolean {
        if (CoverLab71FoundationGate.isForbidden(row.title, row.artist,
                row.playbackVideoTitle.orEmpty(), row.album.orEmpty(), row.sourceUrl.orEmpty())) return false
        return CoverLabTurboRoundAdmission.matchesWorkTitle(title, row.title, aliases(row, original)) ||
            (row.category == AiCoverCategory.FOREIGN && documented(row))
    }

    private fun isWriter(credit: DiscogsCredit) =
        listOf("writ", "compos", "lyric", "music by", "words by").any {
            credit.role.lowercase(java.util.Locale.ROOT).contains(it)
        }
    private fun names(credit: DiscogsCredit): Set<String> =
        (listOf(credit.name) + credit.aliases).map(TitleMeaningResolver::canonical)
            .filter { it.isNotBlank() && it !in setOf("unknown", "anonymous", "anonimo", "various") }.toSet()

    fun facts(row: CoverSourceCandidate, title: String, original: String,
              video: SongItem, referenceCredits: List<DiscogsCredit> = emptyList()): Set<String> {
        if (row.playbackVideoId != video.id || video.title.isBlank() ||
            !plausible(row, title, original) ||
            CoverLab71FoundationGate.isForbidden(video.title,
                video.artists.joinToString { it.name }, video.album?.name.orEmpty())) return emptySet()
        val a = aliases(row, original)
        val italian = CoverLabTurboRoundAdmission.matchesWorkTitle(title, row.title, a) &&
            CoverLabTurboRoundAdmission.matchesWorkTitle(title, video.title, a)
        // Foreign title requires a proven relation to the original work.
        val foreign = !italian && row.category == AiCoverCategory.FOREIGN && documented(row) &&
            CoverLabTurboRoundAdmission.matchesWorkTitle(row.title, video.title, a)
        if (!italian && !foreign) return emptySet()

        val performer = row.artist.takeIf {
            it.isNotBlank() && !it.equals("YouTube", true) && !it.equals("Various Artists", true)
        }
        val artistMatch = performer != null && (
            video.artists.any { TitleMeaningResolver.sameArtist(it.name, performer) } ||
                run {
                    val videoTitle = TitleMeaningResolver.canonical(video.title)
                    val named = TitleMeaningResolver.canonical(performer)
                    named.isNotBlank() &&
                        (videoTitle.startsWith("$named ") || videoTitle.endsWith(" $named"))
                }
        )
        // Catalogue fields are independent ONLY if they came from the source
        // before video association. Raw YouTube values must never self-confirm.
        val fromSource = external(row)
        val albumMatch = fromSource && !row.album.isNullOrBlank() &&
            video.album?.name?.let {
                TitleMeaningResolver.canonical(it) == TitleMeaningResolver.canonical(row.album)
            } == true
        val durationMatch = fromSource && row.durationSeconds != null &&
            video.duration != null && row.durationSeconds > 0 && video.duration > 0 &&
            kotlin.math.abs(row.durationSeconds - video.duration) <= 4
        val anchored = artistMatch || albumMatch || durationMatch
        return buildSet {
            add("title")
            if (artistMatch) add("performer")
            if (albumMatch) add("album")
            if (durationMatch) add("duration")
            if (anchored && documented(row)) add("documented-work")
            if (anchored && artistMatch && trustedTrack(row)) add("catalogue-track")
            if (anchored && fromSource && row.credits.filter(::isWriter).any { c ->
                val cNames = names(c)
                cNames.isNotEmpty() && referenceCredits.filter(::isWriter).any { r ->
                    names(r).intersect(cNames).isNotEmpty()
                }
            }) add("work-writer")
            if (anchored && artistMatch && fromSource && row.originalWorkReference &&
                original.isNotBlank() &&
                CoverLab71FoundationGate.isOriginalPerformer(row.artist, original))
                add("original-performer")
        }
    }

    fun evaluate(row: CoverSourceCandidate, title: String, original: String, round: Int,
                 video: SongItem, referenceCredits: List<DiscogsCredit> = emptyList()):
        CoverLabTurboRoundAdmission.Verdict {
        val evidence = facts(row, title, original, video, referenceCredits)
        if (evidence.isEmpty()) return CoverLabTurboRoundAdmission.Verdict(
            admitted = false, confidencePercent = 1, rejected = true)
        val minRound = CoverLabTurboRoundAdmission.minimumRound(evidence)
        val score = CoverLabTurboRoundAdmission.evaluate(row, title, original,
            round, video.title, referenceCredits).confidencePercent
        // Release format/category influences order, NOT first-round admission.
        return CoverLabTurboRoundAdmission.Verdict(
            admitted = round >= minRound, confidencePercent = score,
            evidence = evidence, minimumRound = minRound)
    }

    /** Multiple bounded query variants expose all usable descriptors without
     * submitting a single long noisy search string to YouTube. */
    fun queries(row: CoverSourceCandidate): List<String> {
        val title = row.title.trim()
        if (title.isBlank()) return emptyList()
        val artist = row.artist.takeUnless { it.equals("YouTube", true) }.orEmpty().trim()
        return listOf("$artist $title", "$title $artist",
            "$artist $title ${row.album.orEmpty()}",
            "$artist $title ${row.year?.toString().orEmpty()}")
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in 3..140 }.distinct().take(4)
    }
}
