package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompilationUnifiedStyleFilterTest {
    @Test fun oneStyleDropdownContainsOldGenreAndOldStyleValues() {
        val choices = CompilationUnifiedStyleFilter.options(
            listOf("Tutto", "Pop", "Electronic"),
            listOf("Tutto", "House", "Disco"))
        assertEquals(listOf("Tutto", "Genere · Pop", "Genere · Electronic",
            "Stile · House", "Stile · Disco"), choices)
    }

    @Test fun genreAndStyleSelectionsUseCorrectSingleDiscogsParameter() {
        assertEquals("Pop" to null, CompilationUnifiedStyleFilter.parse("Genere · Pop"))
        assertEquals(null to "Disco", CompilationUnifiedStyleFilter.parse("Stile · Disco"))
        assertEquals(null to null, CompilationUnifiedStyleFilter.parse("Tutto"))
    }

    @Test fun oldStoredGenreOrStyleIsStillReadable() {
        assertEquals("Genere · Electronic",
            CompilationUnifiedStyleFilter.label("Electronic", null))
        assertEquals("Stile · House", CompilationUnifiedStyleFilter.label(null, "House"))
        assertEquals("Tutto", CompilationUnifiedStyleFilter.label(null, null))
        assertTrue(CompilationUnifiedStyleFilter.label("Pop", "House").contains(" + "))
    }
}
