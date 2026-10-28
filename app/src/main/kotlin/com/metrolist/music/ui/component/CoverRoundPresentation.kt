package com.metrolist.music.ui.component

/** Presentation buffer only: discovery, identity and ranking remain owned by the native queue. */
internal class CoverRoundPresentation<T>(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val identity: (T) -> String,
) {
    companion object {
        /** First paint is deliberately small; subsequent presses publish 20 at a time. */
        const val INITIAL_SIZE = 10
        const val BLOCK_SIZE = 20
        const val ROUND_SIZE = 50
        const val FAST_PREFIX = 5
    }

    data class Status(val shown: Int, val prepared: Int, val ready: Int,
        val green: Boolean, val nextReady: Int,
        val terminal: Boolean = false, val noResults: Boolean = false, val retryRequired: Boolean = false)

    private class Round<T> {
        val shown = linkedSetOf<String>()
        val prepared = linkedMapOf<String, T>()
        var exhausted = false
        var preparedSince: Long? = null
        val overflow = linkedMapOf<String, T>()
        var lastProgress: Long? = null
        var finalChecked = false
        var finalContinuation = false
    }
    private val rounds = linkedMapOf<Int, Round<T>>()
    private var publicationPreference: (T) -> Boolean = { false }
    var visibleRound = 1
        private set
    private fun round(number: Int) = rounds.getOrPut(number) { Round() }
    var finalRound: Int = Int.MAX_VALUE
    fun beginPreparation(number: Int) {
        val state = round(number)
        if (state.lastProgress == null) state.lastProgress = clock()
    }
    fun pauseIdleClock(number: Int) { round(number).lastProgress = clock() }
    fun closeIfIdle(number: Int): Boolean {
        val state = round(number)
        if (!state.exhausted && state.lastProgress?.let { clock() - it >= 7_000L } == true) {
            state.exhausted = true
            return true
        }
        return false
    }
    fun finalChecked(number: Int) { finalProgress(number, complete = true, blocked = false) }
    fun finalProgress(number: Int, complete: Boolean, blocked: Boolean) {
        round(number).apply {
            finalChecked = complete
            finalContinuation = !complete && !blocked
        }
    }
    fun isFinalChecked(number: Int): Boolean = round(number).finalChecked
    fun verificationPending(number: Int) { finalProgress(number, complete = false, blocked = true) }
    fun retry(number: Int) {
        round(number).apply { exhausted = false; finalChecked = false; finalContinuation = false; lastProgress = clock() }
    }
    val preparationRound: Int get() = if (status().green) visibleRound + 1 else visibleRound

    fun stage(number: Int, row: T): Boolean {
        val state = round(number)
        val id = identity(row)
        if (state.exhausted) return false
        if (id in state.shown) return false
        if (id in state.overflow) { state.overflow[id] = row; return true }
        if (id !in state.prepared && id !in state.overflow &&
            state.shown.size + state.prepared.size >= ROUND_SIZE) {
            // Verified late arrivals are retained for the next round instead
            // of being discarded when this round's visible capacity is full.
            state.overflow[id] = row
            return true
        }
        if (id !in state.prepared) state.lastProgress = clock()
        if (state.prepared.isEmpty()) state.preparedSince = clock()
        state.prepared[id] = row
        return true
    }

    fun total(number: Int): Int = round(number).let { it.shown.size + it.prepared.size }
    fun exhausted(number: Int): Boolean = round(number).exhausted
    fun exhaust(number: Int) { round(number).exhausted = true }
    fun reopen(number: Int) { round(number).exhausted = false }
    fun preparedRows(number: Int? = null): List<T> =
        if (number == null) rounds.values.flatMap { it.prepared.values + it.overflow.values }
        else round(number).prepared.values.toList() + round(number).overflow.values

    fun mapPrepared(transform: (T) -> T) {
        rounds.values.forEach { state ->
            state.prepared.replaceAll { _, row -> transform(row) }
            state.overflow.replaceAll { _, row -> transform(row) }
        }
    }
    fun prioritizePrepared(preferred: (T) -> Boolean) { publicationPreference = preferred }
    fun retainPrepared(predicate: (T) -> Boolean) {
        rounds.values.forEach { state ->
            state.prepared.entries.removeAll { !predicate(it.value) }
            state.overflow.entries.removeAll { !predicate(it.value) }
            if (state.prepared.isEmpty()) state.preparedSince = null
        }
    }

    private fun take(number: Int, limit: Int): List<T> {
        val state = round(number)
        val keys = state.prepared.keys.sortedBy { !publicationPreference(state.prepared.getValue(it)) }
            .take(limit.coerceAtLeast(0))
        return keys.mapNotNull { key -> state.prepared.remove(key)?.also { state.shown += key } }
            .also { if (state.prepared.isEmpty()) state.preparedSince = null }
    }

    fun takeInitial(): List<T> {
        val remaining = (INITIAL_SIZE - round(visibleRound).shown.size).coerceAtLeast(0)
        val state = round(visibleRound)
        return if (state.prepared.size >= remaining || state.exhausted) take(visibleRound, remaining) else emptyList()
    }
    fun reveal(): List<T> = take(visibleRound, status().ready)
    fun advance(): List<T> {
        if (!status().green) return emptyList()
        visibleRound++
        val previous = round(visibleRound - 1)
        val next = round(visibleRound)
        (previous.prepared.values + previous.overflow.values).forEach { row ->
            val id = identity(row)
            if (id in next.shown || id in next.prepared || id in next.overflow) return@forEach
            if (next.shown.size + next.prepared.size < ROUND_SIZE) next.prepared[id] = row
            else next.overflow[id] = row
        }
        previous.overflow.clear()
        previous.prepared.clear()
        return takeInitial()
    }

    private fun ready(number: Int): Int {
        val state = round(number)
        val target = if (state.shown.size < INITIAL_SIZE) INITIAL_SIZE - state.shown.size
            else minOf(BLOCK_SIZE, (ROUND_SIZE - state.shown.size).coerceAtLeast(0))
        val size = state.prepared.size
        return if (size >= target || state.exhausted) minOf(size, target) else 0
    }
    fun status(): Status {
        val state = round(visibleRound)
        val finalEmpty = visibleRound >= finalRound && state.exhausted && state.prepared.isEmpty() && state.shown.size < ROUND_SIZE
        val terminal = finalEmpty && state.finalChecked
        val retryRequired = finalEmpty && !state.finalChecked && !state.finalContinuation
        val green = !terminal && !retryRequired && (state.shown.size >= ROUND_SIZE || state.exhausted && ready(visibleRound) == 0)
        return Status(state.shown.size, state.prepared.size, ready(visibleRound), green,
            if (green) ready(visibleRound + 1) else 0,
            terminal, terminal && rounds.values.all { it.shown.isEmpty() && it.prepared.isEmpty() && it.overflow.isEmpty() }, retryRequired)
    }
    fun needsPreparation(): Boolean {
        val number = preparationRound
        val target = if (number == visibleRound) ROUND_SIZE else BLOCK_SIZE
        return !round(number).exhausted && total(number) < target
    }
    fun restore(number: Int, shownIds: List<String>) {
        visibleRound = number.coerceAtLeast(1)
        round(visibleRound).shown.addAll(shownIds)
    }
    fun synchronizeShown(number: Int, shownIds: List<String>) {
        round(number).shown.apply { clear(); addAll(shownIds) }
    }
    fun clear() { rounds.clear(); visibleRound = 1 }
}
