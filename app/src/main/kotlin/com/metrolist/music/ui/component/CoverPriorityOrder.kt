package com.metrolist.music.ui.component

/** The native Turbo queue orders work without turning evidence into admission gates. */
internal object CoverPriorityOrder {
    fun refreshEvidence(row: DiscogsVersionSeed, candidate: CoverSourceCandidate, title: String, original: String, round: Int): DiscogsVersionSeed {
        if (row.resolvedVideoId.isNullOrBlank() || row.resolvedVideoId != candidate.playbackVideoId) return row
        val score = CoverLabTurboRoundAdmission.evaluate(candidate, title, original, round, row.resolvedVideoTitle.orEmpty()).confidencePercent
        val enriched = row.copy(
            sourceNames = (row.sourceNames + candidate.sources).distinct(),
            releaseTitle = candidate.album?.takeIf { candidate.releaseVerified && it.isNotBlank() } ?: row.releaseTitle,
            year = row.year ?: candidate.year,
            releaseDate = row.releaseDate ?: candidate.year?.toString(),
            discogsVerificationChecked = row.discogsVerificationChecked || candidate.releaseVerified,
        )
        return enriched.copy(
            artist = if (candidate.workRelationConfirmed || !row.workRelationConfirmed) candidate.artist.ifBlank { row.artist } else row.artist,
            trackTitle = if (candidate.workRelationConfirmed || !row.workRelationConfirmed) candidate.title.ifBlank { row.trackTitle } else row.trackTitle,
            sourceNames = (row.sourceNames + candidate.sources).distinct(),
            workRelationConfirmed = row.workRelationConfirmed || candidate.workRelationConfirmed,
            originalWorkReference = row.originalWorkReference || candidate.originalWorkReference || CoverLab71FoundationGate.isOriginalPerformer(candidate.artist, original),
            musiclabScore100 = maxOf(score, row.musiclabScore100 ?: 0),
            discogsVerificationChecked = row.discogsVerificationChecked || candidate.releaseVerified,
            confidenceReasons = (row.confidenceReasons + "Prove documentarie aggiornate: ${candidate.sources.joinToString()}").distinct(),
        )
    }

    class PreparationWindow(private val clock: () -> Long) {
        private var started: Long? = null
        fun claim(timeoutMs: Long): Long {
            val now = clock()
            val start = started ?: now.also { started = it }
            return minOf(timeoutMs, (4_400L - (now - start)).coerceAtLeast(0L))
        }
    }
    private val catalogue = setOf("discogs", "apple/itunes", "spotify", "youtube music")
    private val credited = catalogue + "musicbrainz"
    private val karaokeChannel = Regex("""(?i)\b(karaoke|kar[aà]oke|midi|kar)\b""")
    private val television = Regex(
        """(?i)\b(tv|television|sanremo|x factor|the voice|eurovision|amici|live|concert|remix|mashup|ai cover|intelligenza artificiale)\b"""
    )
    private val rare = Regex(
        """(?i)\b(rare|guitar|chitarra|instrumental|strumentale|cover band|piano|orchestra)\b"""
    )

    fun key(candidate: CoverSourceCandidate): String =
        candidate.playbackVideoId?.takeIf(String::isNotBlank)?.let { "video:$it" }
            ?: listOf(
                candidate.sourceUrl.orEmpty(), candidate.artist, candidate.title,
                candidate.category.name, candidate.album.orEmpty(),
                candidate.durationSeconds?.toString().orEmpty(),
            ).joinToString("|")

    private fun group(candidate: CoverSourceCandidate, originalArtist: String, round: Int): Int {
        val sources = candidate.sources.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        val details = candidate.title + " " + candidate.playbackVideoTitle.orEmpty() +
            " " + candidate.album.orEmpty()
        return when {
            candidate.releaseVerified || (!candidate.album.isNullOrBlank() && sources.any { it in catalogue && (it != "discogs" || candidate.releaseVerified) }) -> 0
            !candidate.workRelationConfirmed && karaokeChannel.containsMatchIn(candidate.artist) -> 6
            candidate.originalWorkReference || (originalArtist.isNotBlank() &&
                CoverLab71FoundationGate.isOriginalPerformer(candidate.artist, originalArtist)) -> 1
            candidate.category != AiCoverCategory.FOREIGN && sources.any { it in credited } -> 2
            round == 2 && television.containsMatchIn(details) -> 3
            round >= 3 && rare.containsMatchIn(details) -> 3
            candidate.category == AiCoverCategory.FOREIGN && candidate.workRelationConfirmed -> 4
            else -> 5
        }
    }

    fun order(
        candidates: List<CoverSourceCandidate>, targetTitle: String,
        originalArtist: String, round: Int, delayedKeys: Set<String> = emptySet(),
    ): List<CoverSourceCandidate> {
        data class Ranked(val row: CoverSourceCandidate, val index: Int, val late: Boolean,
            val minimumRound: Int, val preference: Int)
        return candidates.mapIndexed { index, candidate ->
            Ranked(candidate, index, key(candidate) in delayedKeys,
                CoverLabTurboRoundAdmission.minimumRound(
                    CoverLabTurboRoundAdmission.facts(candidate, targetTitle, originalArtist)),
                group(candidate, originalArtist, round))
        }.sortedWith(
            compareBy<Ranked> { it.late }
                .thenBy { it.preference }
                .thenBy { it.row.chartPosition ?: Int.MAX_VALUE }
                .thenByDescending { it.row.collectorHave ?: 0 }.thenBy { it.index }
        ).map { it.row }
    }

    fun isPublished(row: DiscogsVersionSeed): Boolean = row.discogsVerificationChecked ||
        (row.releaseTitle.isNotBlank() && row.releaseTitle != row.sourceNames.joinToString(" + ") &&
            row.sourceNames.any { it.lowercase(java.util.Locale.ROOT) in catalogue && !it.equals("Discogs", true) })

    private fun publicationGroup(row: DiscogsVersionSeed): Int = when {
        isPublished(row) -> 0
        !row.workRelationConfirmed && karaokeChannel.containsMatchIn(row.artist) -> 6
        row.originalWorkReference -> 1
        row.sourceNames.any { it.lowercase(java.util.Locale.ROOT) in credited } -> 2
        row.workRelationConfirmed -> 3
        else -> 5
    }

    val publicationComparator: Comparator<DiscogsVersionSeed> =
        compareBy<DiscogsVersionSeed>(::publicationGroup)
            .thenByDescending { it.collectorHave ?: 0 }

    fun originalReference(candidates: List<CoverSourceCandidate>): CoverSourceCandidate? {
        val documented = candidates.filter { candidate ->
            candidate.originalWorkReference && candidate.firstPublicationConfirmed && candidate.artist.isNotBlank() &&
                candidate.sources.any { it.equals("COVER.INFO", true) || it.equals("MusicBrainz", true) || it.equals("Discogs", true) }
        }.groupBy { TitleMeaningResolver.canonical(it.artist) }
            .values.sortedByDescending { it.size }
        val first = documented.firstOrNull() ?: return null
        if (documented.size != 1) return null
        return first.first()
    }

    fun bindVideo(raw: CoverSourceCandidate, lead: CoverSourceCandidate?): CoverSourceCandidate {
        if (lead == null) return raw
        val title = raw.playbackVideoTitle ?: raw.title
        val artist = TitleMeaningResolver.canonical(lead.artist)
        val bound = TitleMeaningResolver.matchesBaseTitle(lead.title, title, setOf(lead.artist)) &&
            (CoverLab71FoundationGate.isOriginalPerformer(raw.artist, lead.artist) ||
                (artist.isNotBlank() && TitleMeaningResolver.canonical(title).startsWith("$artist ")))
        if (!bound) return raw
        // A title/performer match links the WORK. It does not identify a particular
        // catalogue recording, so never copy its release/track coordinates onto a search hit.
        return lead.copy(
            sources = (lead.sources + raw.sources).distinct(),
            artist = if (CoverLab71FoundationGate.isOriginalPerformer(raw.artist, lead.artist)) raw.artist else lead.artist,
            category = if (lead.category == AiCoverCategory.FOREIGN) lead.category else raw.category,
            playbackVideoId = raw.playbackVideoId, playbackVideoTitle = title,
            playbackVideoSource = "YouTube documentario", durationSeconds = raw.durationSeconds,
            album = raw.album, year = raw.year, releaseVerified = raw.releaseVerified,
            chartPosition = raw.chartPosition,
            recordingReleaseId = null, recordingMasterId = null, recordingTrackPosition = null,
        )
    }

    fun mergeEvidence(first: CoverSourceCandidate, second: CoverSourceCandidate): CoverSourceCandidate = merge(first, second)

    private fun merge(first: CoverSourceCandidate, second: CoverSourceCandidate): CoverSourceCandidate {
        fun weight(row: CoverSourceCandidate): Int =
            (if (row.workRelationConfirmed) 20 else 0) +
                (if (row.originalWorkReference) 10 else 0) +
                (if (!row.album.isNullOrBlank()) 5 else 0) + row.sources.distinct().size
        val best = if (weight(second) > weight(first)) second else first
        val other = if (best === first) second else first
        return best.copy(
            sources = (first.sources + second.sources).distinct(),
            album = best.album ?: other.album,
            year = best.year ?: other.year,
            durationSeconds = best.durationSeconds ?: other.durationSeconds,
            language = best.language ?: other.language,
            sourceUrl = best.sourceUrl ?: other.sourceUrl,
            playbackVideoId = best.playbackVideoId ?: other.playbackVideoId,
            playbackVideoTitle = best.playbackVideoTitle ?: other.playbackVideoTitle,
            playbackVideoSource = best.playbackVideoSource ?: other.playbackVideoSource,
            workRelationConfirmed = first.workRelationConfirmed || second.workRelationConfirmed,
            originalWorkReference = first.originalWorkReference || second.originalWorkReference,
            firstPublicationConfirmed = first.firstPublicationConfirmed || second.firstPublicationConfirmed,
            credits = (first.credits + second.credits).distinct(),
            evidenceScore = maxOf(first.evidenceScore, second.evidenceScore),
            recordingReleaseId = best.recordingReleaseId ?: other.recordingReleaseId,
            recordingMasterId = best.recordingMasterId ?: other.recordingMasterId,
            recordingTrackPosition = best.recordingTrackPosition ?: other.recordingTrackPosition,
            collectorHave = listOfNotNull(first.collectorHave, second.collectorHave).maxOrNull(),
            chartPosition = listOfNotNull(first.chartPosition, second.chartPosition).minOrNull(),
            releaseVerified = first.releaseVerified || second.releaseVerified,
        )
    }

    fun retainedRows(
        rows: List<DiscogsVersionSeed>, originalArtist: String,
    ): List<DiscogsVersionSeed> {
        val videos = hashSetOf<String>()
        val artists = hashMapOf<String, MutableList<DiscogsVersionSeed>>()
        val kept = mutableListOf<DiscogsVersionSeed>()
        var originals = 0
        for (row in rows) {
            if (!CoverLab71FoundationGate.historicalIsEligible(row, "", originalArtist)) continue
            val video = row.resolvedVideoId ?: continue
            if (video in videos) continue
            val bucket = artists.getOrPut(TitleMeaningResolver.canonical(row.artist)) {
                mutableListOf()
            }
            if (bucket.any { CoverLabTurboTwinGuard.sameRecording(it, row) }) continue
            val isOriginal = originalArtist.isNotBlank() &&
                CoverLab71FoundationGate.isOriginalPerformer(row.artist, originalArtist)
            if (isOriginal && originals >= 5) continue
            videos += video
            bucket += row
            kept += row
            if (isOriginal) originals++
        }
        return kept
    }

    class DocumentaryProgress {
        data class Page(val url: String, val related: Boolean)
        private val pages = linkedMapOf<String, Page>()
        private val done = hashSetOf<String>()
        private val attempted = hashSetOf<String>()
        private val rows = linkedMapOf<String, CoverSourceCandidate>()
        val lanes = java.util.concurrent.ConcurrentHashMap<String, Pair<List<CoverSourceCandidate>, CoverSourceDiagnostic>>()
        var initialized = false
        var reachable = false
        val remainingPages: Int get() = pages.size
        val complete: Boolean get() = initialized && pages.isEmpty()
        val hasRunnablePages: Boolean get() = pages.keys.any { it !in attempted }

        fun enqueue(url: String, related: Boolean) {
            if (url !in done) {
                pages[url] = Page(url, related || pages[url]?.related == true)
            }
        }

        fun beginPass() { attempted.clear() }
        fun followRelations(parent: Page, urls: List<String>) {
            if (parent.related) urls.forEach { enqueue(it, true) }
        }

        fun nextPage(): Page? {
            val next = pages.values.firstOrNull { it.url !in attempted } ?: return null
            attempted += next.url
            pages.remove(next.url)
            pages[next.url] = next
            return next
        }

        fun completePage(page: Page) {
            pages.remove(page.url)
            done += page.url
        }

        fun add(candidate: CoverSourceCandidate) {
            val key = key(candidate)
            val previous = rows[key]
            rows[key] = previous?.let { merge(it, candidate) } ?: candidate
        }

        fun candidates(): List<CoverSourceCandidate> = rows.values.toList()
    }

    class VideoQueries {
        data class Page(
            val text: String, val category: AiCoverCategory,
            val songs: Boolean = false, val continuation: String? = null,
            val lead: CoverSourceCandidate? = null,
        )
        private val pages = linkedMapOf<String, Page>()
        private val done = hashSetOf<String>()
        private val attempted = hashSetOf<String>()
        private fun key(page: Page) = "${page.text}|${page.songs}|${page.continuation.orEmpty()}|${page.lead?.let(CoverPriorityOrder::key).orEmpty()}"
        val size: Int get() = pages.size
        val remainingOrdinary: Int get() = pages.values.count { it.lead == null }
        val hasRunnable: Boolean get() = pages.keys.any { it !in attempted }
        val hasRunnableOrdinary: Boolean get() = pages.any { it.key !in attempted && it.value.lead == null }
        fun offer(text: String, category: AiCoverCategory, songs: Boolean = false, lead: CoverSourceCandidate? = null, retryCompleted: Boolean = false) {
            val page = Page(text, category, songs, lead = lead)
            val key = key(page)
            if (retryCompleted) done.remove(key)
            if (key !in done) pages.putIfAbsent(key, page)
        }
        fun beginPass() { attempted.clear() }
        fun defer(page: Page) {
            val key = key(page)
            pages.remove(key)?.let { pages[key] = it }
        }
        fun remainingVideoPages(): Int = pages.values.count { !it.songs && it.lead == null }
        fun next(includeLeads: Boolean = true, texts: Set<String>? = null, videosOnly: Boolean = false): Page? = pages.entries.firstOrNull { (!videosOnly || (!it.value.songs && it.value.lead == null)) && it.key !in attempted && (includeLeads || it.value.lead == null) && (texts == null || it.value.text in texts) }?.let {
            attempted += it.key
            it.value
        }
        fun forLead(lead: CoverSourceCandidate): Page? {
            val rowKey = CoverPriorityOrder.key(lead)
            return pages.entries.firstOrNull { it.key !in attempted && it.value.lead?.let(CoverPriorityOrder::key) == rowKey }?.let {
                attempted += it.key
                it.value
            }
        }
        fun retryUnresolved(page: Page) {
            val key = key(page)
            done.remove(key)
            pages[key] = page
            attempted += key
        }
        fun finish(page: Page, continuation: String?) {
            val key = key(page)
            pages.remove(key)
            done += key
            if (!continuation.isNullOrBlank()) {
                val next = page.copy(continuation = continuation)
                val nextKey = key(next)
                if (nextKey !in done) pages.putIfAbsent(nextKey, next)
            }
        }
        fun clear() { pages.clear(); done.clear(); attempted.clear() }
    }

    /** One visit-scoped queue; failed work is retained and retried only on a later pass. */
    class Pending {
        data class OrderingInput(val rows: List<CoverSourceCandidate>, val twins: Set<String>,
            val title: String, val original: String, val round: Int, val revision: Int)
        private var revision = 0
        fun orderingInput(title: String, original: String, round: Int): OrderingInput? =
            if (dirty || orderContext != "$title|$original|$round")
                OrderingInput(snapshot(), twins.toSet(), title, original, round, revision) else null
        fun applyOrdering(input: OrderingInput, ordered: List<CoverSourceCandidate>): Boolean {
            if (input.revision != revision) return false
            orderedKeys = ordered.map(CoverPriorityOrder::key)
            orderContext = "${input.title}|${input.original}|${input.round}"
            dirty = false
            return true
        }
        private val linkedLeads = hashSetOf<String>()
        fun hasLinked(candidate: CoverSourceCandidate): Boolean = key(candidate) in linkedLeads
        fun lookupComplete(candidate: CoverSourceCandidate, linked: Boolean, morePages: Boolean = false) {
            if (linked) linkedLeads += key(candidate)
            if (hasLinked(candidate) && !morePages) complete(candidate) else defer(candidate)
        }
        private val rows = linkedMapOf<String, CoverSourceCandidate>()
        private val attempted = hashSetOf<String>()
        private val completed = hashSetOf<String>()
        private val twins = hashSetOf<String>()
        private var orderedKeys = emptyList<String>()
        private var orderContext = ""
        private var dirty = true
        private val retryAfter = hashMapOf<String, Int>()
        private val heldUntilRound = hashMapOf<String, Int>()
        private var pass = 0
        fun defer(candidate: CoverSourceCandidate) {
            retryAfter[key(candidate)] = pass + 2
        }
        fun reconsiderEvidence() { heldUntilRound.clear(); attempted.clear(); revision++; dirty = true }
        fun deferUntil(candidate: CoverSourceCandidate, round: Int) {
            heldUntilRound[key(candidate)] = round
        }
        fun eligible(candidate: CoverSourceCandidate, round: Int): Boolean =
            (heldUntilRound[key(candidate)] ?: 1) <= round
        val size: Int get() = rows.size
        val twinCount: Int get() = rows.keys.count { it in twins }

        fun offer(candidate: CoverSourceCandidate) {
            val key = key(candidate)
            if (key in completed) return
            val previous = rows[key]
            rows[key] = previous?.let { merge(it, candidate) } ?: candidate
            if (previous != rows[key]) { heldUntilRound.remove(key); attempted.remove(key) }
            revision++
            dirty = true
        }

        fun beginPass() {
            pass++
            revision++
            attempted.clear()
            dirty = true
        }

        fun next(title: String, original: String, round: Int, includeTwins: Boolean = true): CoverSourceCandidate? {
            val context = "$title|$original|$round"
            if (dirty || context != orderContext) {
                orderedKeys = order(rows.values.toList(), title, original, round, twins).map(::key)
                orderContext = context
                dirty = false
            }
            val key = orderedKeys.firstOrNull { (heldUntilRound[it] ?: 1) <= round && it in rows && it !in attempted && (retryAfter[it] ?: 0) <= pass && (includeTwins || it !in twins) } ?: return null
            attempted += key
            return rows[key]
        }

        fun complete(candidate: CoverSourceCandidate) {
            val key = key(candidate)
            rows.remove(key)
            revision++
            completed += key
            twins.remove(key)
            retryAfter.remove(key)
            heldUntilRound.remove(key)
            linkedLeads.remove(key)
        }

        fun markTwin(candidate: CoverSourceCandidate) {
            val key = key(candidate)
            if (twins.add(key)) attempted.remove(key)
            revision++
            dirty = true
        }

        fun isTwin(candidate: CoverSourceCandidate): Boolean = key(candidate) in twins

        fun hasRunnable(normalOnly: Boolean = false): Boolean =
            rows.keys.any { it !in attempted && (retryAfter[it] ?: 0) <= pass && (!normalOnly || it !in twins) }

        fun snapshot(): List<CoverSourceCandidate> = rows.values.toList()

        fun clear() {
            rows.clear()
            revision++
            retryAfter.clear()
            heldUntilRound.clear()
            linkedLeads.clear()
            pass = 0
            attempted.clear()
            completed.clear()
            twins.clear()
            orderedKeys = emptyList()
            dirty = true
        }
    }
}
