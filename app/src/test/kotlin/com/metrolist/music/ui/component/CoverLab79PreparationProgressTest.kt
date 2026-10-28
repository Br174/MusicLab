package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLab79PreparationProgressTest {
    @Test fun cachedNormalizationPreservesPreviousResultsAcrossEvictionAndThreads() {
        fun previous(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[’'´]"), "")
            .replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
        val samples = listOf("", "Alejandro Sánz – Topic", "L’amòre (LIVE)", "İSTANBUL", "東京", "  Il mondo  ") +
            (0..1100).map { "Interprète $it" }
        samples.forEach { assertEquals(previous(it), TitleMeaningResolver.canonical(it)) }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..8).map { executor.submit<Boolean> {
                samples.all { previous(it) == TitleMeaningResolver.canonical(it) }
            } }
            tasks.forEach { assertTrue(it.get(10, java.util.concurrent.TimeUnit.SECONDS)) }
        } finally { executor.shutdownNow() }
        val value = samples[1]
        val oldStart = System.nanoTime()
        repeat(10_000) { previous(value) }
        val oldElapsed = System.nanoTime() - oldStart
        val newStart = System.nanoTime()
        repeat(10_000) { TitleMeaningResolver.canonical(value) }
        println("Repeated normalization 10000 calls: previous_ns=$oldElapsed cached_ns=${System.nanoTime() - newStart}")
    }
    @Test fun anotherVideoCanBeLinkedAfterTheFirstFourSeconds() {
        var now = 0L
        val budget = CoverPreparationPolicy.PassWindow { now }
        assertEquals(3_800L, budget.claim(3_800L))
        now = 4_500L
        assertEquals(3_800L, budget.claim(3_800L))
        now = 19_500L
        assertEquals(500L, budget.claim(3_800L))
        now = 20_001L
        assertEquals(0L, budget.claim(3_800L))
    }

    @Test fun verifiedPartialGroupBecomesAvailableWhileAFailedSourceIsStillPending() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        repeat(10) { p.stage(1, "first-$it") }
        assertEquals(10, p.takeInitial().size)
        repeat(8) { p.stage(1, "later-$it") }
        assertFalse(CoverPreparationPolicy.documentaryComplete(true,
            listOf(CoverSourceDiagnostic("LRCLIB", false, 0, "rete/non disponibile"))))
        assertEquals(0, p.status().ready)
        now = 4_100L
        assertEquals(0, p.status().ready)
        assertTrue(p.reveal().isEmpty())
        assertEquals(10, p.status().shown)
        assertFalse(p.status().green)
        assertEquals(1, p.visibleRound)
        repeat(32) { p.stage(1, "last-$it") }
        assertEquals(20, p.reveal().size)
        now = 8_200L
        assertEquals(20, p.reveal().size)
        assertEquals(50, p.status().shown)
        assertTrue(p.status().green)
    }

    @Test fun failedSourceWaitsIndependentlyAndBecomesRetryableWithoutDiscardingItsState() {
        var now = 0L
        val retries = CoverPreparationPolicy.SourceRetries { now }
        val failure = CoverSourceDiagnostic("LRCLIB", false, 0, "rete/non disponibile")
        retries.record("work", failure)
        assertEquals(setOf("LRCLIB"), retries.cooling("work").keys)
        assertTrue(retries.cooling("another work").isEmpty())
        retries.record("work", CoverSourceDiagnostic("Apple/iTunes", true, 19, "collegato"))
        assertEquals(setOf("LRCLIB"), retries.cooling("work").keys)
        now = 60_001L
        assertTrue(retries.cooling("work").isEmpty())
        retries.record("work", failure)
        assertTrue(retries.cooling("work").containsKey("LRCLIB"))
        retries.record("work", CoverSourceDiagnostic("LRCLIB", true, 1, "collegato"))
        assertTrue(retries.cooling("work").isEmpty())
    }

    @Test fun unauthenticatedAndInactiveSourcesDoNotCreateRetryLoops() {
        val retries = CoverPreparationPolicy.SourceRetries { 0L }
        retries.record("work", CoverSourceDiagnostic("Spotify", false, 0, "nessun hint / non autenticato"))
        retries.record("work", CoverSourceDiagnostic("SecondHandSongs", false, 0, "Escluso dal recupero approfondito"))
        assertTrue(retries.cooling("work").isEmpty())
    }

    @Test fun completedDocumentaryWorkIsNotRepeatedWhileOnlyItsFailedSourceIsCooling() {
        var now = 0L
        val retries = CoverPreparationPolicy.SourceRetries { now }
        val failed = CoverSourceDiagnostic("LRCLIB", false, 0, "rete/non disponibile")
        retries.record("work", failed)
        assertFalse(retries.documentaryReady("work", true, listOf(failed)))
        assertTrue(retries.documentaryReady("work", false, listOf(failed)))
        now = 60_001L
        assertTrue(retries.documentaryReady("work", true, listOf(failed)))
    }

    @Test fun partialGraceRestartsOnlyWhenTheReadyBufferHasBeenEmptied() {
        var now = 0L
        val p = CoverRoundPresentation<String>(clock = { now }) { it }
        repeat(10) { p.stage(1, "first-$it") }
        p.takeInitial()
        p.stage(1, "first partial")
        now = 11_000L
        p.stage(1, "second partial")
        now = 12_100L
        assertTrue(p.reveal().isEmpty())
        p.stage(1, "new partial")
        assertEquals(0, p.status().ready)
        now = 24_200L
        assertTrue(p.reveal().isEmpty())
        repeat(17) { p.stage(1, "complete-$it") }
        assertEquals(20, p.reveal().size)
        assertFalse(p.status().green)
    }
}
