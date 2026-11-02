package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class CompilationSearchTitlePolicyTest {
    @Test fun remasterSuffixDoesNotEnterDiscogsTrackQuery() {
        assertEquals("L'anno che verrà",
            CompilationSearchTitlePolicy.clean("L'anno che verrà (Remastered in 192 KHz)"))
        assertEquals("Sapore di sale",
            CompilationSearchTitlePolicy.clean("Sapore di sale [2024 Remaster]"))
    }

    @Test fun unrelatedSongTitlesAndArtisticParentheticalsArePreserved() {
        assertEquals("Il mondo che vorrei",
            CompilationSearchTitlePolicy.clean("Il mondo che vorrei"))
        assertEquals("La canzone (per te)",
            CompilationSearchTitlePolicy.clean("La canzone (per te)"))
        assertEquals("Vivo (Acustico)",
            CompilationSearchTitlePolicy.clean("Vivo (Acustico)"))
    }

    @Test fun chainedTechnicalSuffixesAreRemovedWithoutErasingTitle() {
        assertEquals("Il mondo",
            CompilationSearchTitlePolicy.clean("Il mondo (Live) (Remastered 2020)"))
    }
}
