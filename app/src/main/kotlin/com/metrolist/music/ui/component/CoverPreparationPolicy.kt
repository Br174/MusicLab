package com.metrolist.music.ui.component

/** Pure boundaries extracted from the native publication and manual-recovery paths. */
internal object CoverPreparationPolicy {
    private val documentarySources = setOf("COVER.INFO", "MusicBrainz", "Apple/iTunes", "LRCLIB", "Spotify", "SecondHandSongs")
    class PassWindow(private val clock: () -> Long) {
        private val started = clock()
        fun claim(timeoutMs: Long): Long = minOf(timeoutMs, (20_000L - (clock() - started)).coerceAtLeast(0L))
    }

    class SourceRetries(private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) {
        private data class Retry(val failures: Int, val after: Long, val diagnostic: CoverSourceDiagnostic)
        private val entries = linkedMapOf<Pair<String, String>, Retry>()
        fun record(context: String, diagnostic: CoverSourceDiagnostic) {
            if (diagnostic.name !in setOf("COVER.INFO", "MusicBrainz", "Apple/iTunes", "LRCLIB", "Spotify",
                    "SecondHandSongs", "Discogs", "YouTube diretto", "YouTube Music", "Metadati YouTube")) return
            val key = context to diagnostic.name
            if (!retryableFailure(diagnostic)) { entries.remove(key); return }
            val failures = ((entries[key]?.failures ?: 0) + 1).coerceAtMost(4)
            val delay = (10_000L * (1L shl (failures - 1))).coerceAtMost(60_000L)
            entries[key] = Retry(failures, clock() + delay, diagnostic)
        }
        fun cooling(context: String): Map<String, CoverSourceDiagnostic> = entries
            .filter { (key, retry) -> key.first == context && retry.after > clock() }
            .mapKeys { it.key.second }.mapValues { it.value.diagnostic }
        fun documentaryReady(context: String, coverInfoComplete: Boolean, diagnostics: List<CoverSourceDiagnostic>): Boolean {
            val failures = diagnostics.filter { it.name in documentarySources && retryableFailure(it) }
            if (failures.isEmpty()) return true
            val paused = cooling(context)
            return (!coverInfoComplete && "COVER.INFO" !in paused) || failures.any { it.name !in paused }
        }
        fun clear() { entries.clear() }
    }

    private fun retryableFailure(diagnostic: CoverSourceDiagnostic): Boolean = !diagnostic.available &&
        !diagnostic.note.contains("non autenticato", true) &&
        !diagnostic.note.contains("Escluso dal recupero", true) &&
        !diagnostic.note.contains("Interprete di riferimento necessario", true)

    /** Scoped to each video ID; pass and audio limits are preserved. */
    fun metadataAttemptAllowed(videoId: String, attemptedThisRound: Boolean): Boolean =
        videoId.isNotBlank() && !attemptedThisRound

    fun metadataIds(first: String, ordered: List<String>, cached: Set<String>, protectPlayback: Boolean): List<String> =
        if (protectPlayback) listOf(first)
        else (listOf(first) + ordered).distinct().filter { it !in cached }.take(CoverRoundPresentation.FAST_PREFIX)
    fun mayRefreshVisible(workerRound: Int, visibleRound: Int): Boolean = workerRound == visibleRound

    fun nextDocumentaryContext(
        current: Pair<String, String>,
        pending: List<Pair<String, String>>,
        excludedKeys: Set<String>,
    ): Pair<String, String>? = (listOf(current) + pending)
        .distinct()
        .firstOrNull { "${it.first}|${it.second}" !in excludedKeys }

    fun documentaryComplete(coverInfoComplete: Boolean, diagnostics: List<CoverSourceDiagnostic>): Boolean {
        return coverInfoComplete && diagnostics.none { it.name in documentarySources && retryableFailure(it) }
    }

    fun reservedRows(published: List<DiscogsVersionSeed>, prepared: List<DiscogsVersionSeed>): List<DiscogsVersionSeed> = published + prepared

    fun replacementInSlot(current: DiscogsVersionSeed, replacement: DiscogsVersionSeed): DiscogsVersionSeed =
        replacement.copy(coverRound = current.coverRound, roundPosition = current.roundPosition)
}
