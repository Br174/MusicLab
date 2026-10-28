package com.metrolist.music.ui.component

/** Presentation buffer only: discovery, identity and ranking remain owned by the native queue. */
internal class CoverRoundPresentation<T>(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val identity: (T) -> String,
) {
    companion object {
        const val BLOCK_SIZE = 25
        const val ROUND_SIZE = 50
        const val FAST_PREFIX = 5
    }

    data class Status(val shown: Int, val prepared: Int, val ready: Int,
        val green: Boolean, val nextReady: Int)

    private class Round<T> {
        val shown = linkedSetOf<String>()
        val prepared = linkedMapOf<String, T>()
        var exhausted = false
        var preparedSince: Long? = null
    }
    private val rounds = linkedMapOf<Int, Round<T>>()
    var visibleRound = 1
        private set
    private fun round(number: Int) = rounds.getOrPut(number) { Round() }
    val preparationRound: Int get() = if (status().green) visibleRound + 1 else visibleRound

    fun stage(number: Int, row: T): Boolean {
        val state = round(number)
        val id = identity(row)
        if (id in state.shown) return false
        if (id !in state.prepared && state.shown.size + state.prepared.size >= ROUND_SIZE) return false
        if (state.prepared.isEmpty()) state.preparedSince = clock()
        state.prepared[id] = row
        return true
    }

    fun total(number: Int): Int = round(number).let { it.shown.size + it.prepared.size }
    fun exhausted(number: Int): Boolean = round(number).exhausted
    fun exhaust(number: Int) { round(number).exhausted = true }
    fun reopen(number: Int) { round(number).exhausted = false }
    fun preparedRows(number: Int? = null): List<T> =
        if (number == null) rounds.values.flatMap { it.prepared.values } else round(number).prepared.values.toList()

    fun mapPrepared(transform: (T) -> T) {
        rounds.values.forEach { state -> state.prepared.replaceAll { _, row -> transform(row) } }
    }
    fun retainPrepared(predicate: (T) -> Boolean) {
        rounds.values.forEach { state ->
            state.prepared.entries.removeAll { !predicate(it.value) }
            if (state.prepared.isEmpty()) state.preparedSince = null
        }
    }

    private fun take(number: Int, limit: Int): List<T> {
        val state = round(number)
        val keys = state.prepared.keys.take(limit.coerceAtLeast(0))
        return keys.mapNotNull { key -> state.prepared.remove(key)?.also { state.shown += key } }
            .also { if (state.prepared.isEmpty()) state.preparedSince = null }
    }

    fun takeInitial(): List<T> = take(visibleRound, BLOCK_SIZE - round(visibleRound).shown.size)
    fun reveal(): List<T> = take(visibleRound, status().ready)
    fun advance(): List<T> {
        if (!status().green) return emptyList()
        visibleRound++
        return takeInitial()
    }

    private fun ready(number: Int): Int {
        val state = round(number)
        val target = minOf(BLOCK_SIZE, (ROUND_SIZE - state.shown.size).coerceAtLeast(0))
        val size = state.prepared.size
        val graceElapsed = state.preparedSince?.let { clock() - it >= 12_000L } == true
        return if (size >= target || state.exhausted || graceElapsed) minOf(size, target) else 0
    }
    fun status(): Status {
        val state = round(visibleRound)
        val green = state.shown.size >= ROUND_SIZE || state.exhausted && state.prepared.isEmpty()
        return Status(state.shown.size, state.prepared.size, ready(visibleRound), green,
            if (green) ready(visibleRound + 1) else 0)
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
