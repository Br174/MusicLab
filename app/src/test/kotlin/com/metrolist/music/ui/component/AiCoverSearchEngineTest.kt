package com.metrolist.music.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCoverSearchEngineTest {
    private fun candidate(
        title: String,
        artist: String = "Jimmy Fontana",
        category: AiCoverCategory = AiCoverCategory.COVER,
    ) = AiCoverCandidate(
        title = title,
        artist = artist,
        category = category,
    )

    @Test
    fun `Italian cover locator rejects a different song that only contains the title`() {
        assertFalse(
            AiCoverSearchEngine.isPlaybackTitleCompatible(
                candidate("Il mondo"),
                "Il mondo che vorrei",
            ),
        )
    }

    @Test
    fun `locator accepts artist prefix and technical YouTube suffix for the same song`() {
        assertTrue(
            AiCoverSearchEngine.isPlaybackTitleCompatible(
                candidate("Il mondo"),
                "Jimmy Fontana - Il mondo (Official Audio)",
            ),
        )
    }

    @Test
    fun `foreign adaptation locates its own translated candidate title exactly`() {
        assertTrue(
            AiCoverSearchEngine.isPlaybackTitleCompatible(
                candidate("The World", artist = "Example Artist", category = AiCoverCategory.FOREIGN),
                "The World (Remastered)",
            ),
        )
        assertFalse(
            AiCoverSearchEngine.isPlaybackTitleCompatible(
                candidate("The World", artist = "Example Artist", category = AiCoverCategory.FOREIGN),
                "World of Love",
            ),
        )
    }
}
