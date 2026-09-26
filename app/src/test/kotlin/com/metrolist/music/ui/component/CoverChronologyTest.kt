package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverChronologyTest {
    private data class Item(val name: String, val year: Int?)

    @Test
    fun groupsNewestToOldestAndUnknownLast() {
        val groups = groupCoverResultsNewestFirst(
            values = listOf(
                Item("old", 1971),
                Item("unknown", null),
                Item("new", 2026),
                Item("middle", 1998),
                Item("new-2", 2026),
            ),
            yearOf = { it.year },
        )

        assertEquals(listOf(2026, 1998, 1971, null), groups.map { it.first })
        assertEquals(listOf("new", "new-2"), groups.first().second.map { it.name })
        assertEquals(listOf("unknown"), groups.last().second.map { it.name })
    }
}
