package com.metrolist.music.ui.component

/** Pure boundaries extracted from the native publication and manual-recovery paths. */
internal object CoverPreparationPolicy {
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
        val active = setOf("COVER.INFO", "MusicBrainz", "Apple/iTunes", "LRCLIB", "Spotify", "SecondHandSongs")
        return coverInfoComplete && diagnostics.none { diagnostic ->
            val intentionallyInactive = diagnostic.note.contains("non autenticato", true) ||
                diagnostic.note.contains("Escluso dal recupero", true) ||
                diagnostic.note.contains("Interprete di riferimento necessario", true)
            !diagnostic.available && diagnostic.name in active && !intentionallyInactive
        }
    }

    fun reservedRows(published: List<DiscogsVersionSeed>, prepared: List<DiscogsVersionSeed>): List<DiscogsVersionSeed> = published + prepared

    fun replacementInSlot(current: DiscogsVersionSeed, replacement: DiscogsVersionSeed): DiscogsVersionSeed =
        replacement.copy(coverRound = current.coverRound, roundPosition = current.roundPosition)
}
