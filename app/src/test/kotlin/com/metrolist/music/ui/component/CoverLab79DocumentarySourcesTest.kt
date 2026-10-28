package com.metrolist.music.ui.component

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoverLab79DocumentarySourcesTest {
    @Test(timeout = 3_000L) fun coolingSourcesLeaveOtherReadyResultsAndDocumentaryCursorsAvailable() = runBlocking {
        val candidate = CoverSourceCandidate(
            title = "Donna donna mia", artist = "Other Singer", sources = listOf("Apple/iTunes"),
            playbackVideoId = "ready000001", playbackVideoTitle = "Donna donna mia",
        )
        val progress = CoverPriorityOrder.DocumentaryProgress()
        progress.initialized = true
        progress.enqueue("https://cover.info/en/search?find=Donna+donna+mia", false)
        progress.lanes["Apple/iTunes"] = listOf(candidate) to CoverSourceDiagnostic("Apple/iTunes", true, 1, "collegato")
        val paused = listOf("COVER.INFO", "MusicBrainz", "Apple/iTunes", "Spotify", "LRCLIB", "SecondHandSongs")
            .associateWith { CoverSourceDiagnostic(it, false, 0, "rete/non disponibile") }
        val emitted = mutableListOf<CoverSourceCandidate>()
        val outcome = CoverDiscoverySources.discover(
            "Donna donna mia", "Toto Cutugno", DiscogsDirectMode.COVER, null,
            maxNetworkFanOut = 1, documentaryProgress = progress, sourceCooldowns = paused,
            onSourceCompleted = { rows, _, _ -> synchronized(emitted) { emitted.addAll(rows) } },
        )
        assertEquals(listOf("ready000001"), emitted.mapNotNull { it.playbackVideoId })
        assertEquals(listOf("ready000001"), outcome.candidates.mapNotNull { it.playbackVideoId })
        assertEquals(1, progress.remainingPages)
        assertFalse(progress.complete)
        assertFalse(CoverPreparationPolicy.documentaryComplete(progress.complete, outcome.diagnostics))
    }
}
