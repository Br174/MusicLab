package com.metrolist.music.ui.component

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
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

    private fun song(
        title: String,
        artist: String = "Uploader Channel",
        album: String? = null,
    ) = SongItem(
        id = "video-id",
        title = title,
        artists = listOf(Artist(artist, null)),
        album = album?.let { Album(it, "album-id") },
        thumbnail = "https://example.invalid/thumb.jpg",
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
    fun `locator accepts a trailing translated alias in brackets but not a different Italian title`() {
        assertTrue(
            AiCoverSearchEngine.isPlaybackTitleCompatible(
                candidate("Bravi ragazzi", artist = "Miguel Bosé"),
                "Bravi Ragazzi (Bravo Muchachos)",
            ),
        )
        assertFalse(
            AiCoverSearchEngine.isPlaybackTitleCompatible(
                candidate("Il mondo"),
                "Il mondo che vorrei",
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

    @Test
    fun `deep locator reuses Brain metadata in retrieval queries`() {
        val enriched = AiCoverCandidate(
            title = "Bravi ragazzi",
            artist = "Example Cover Artist",
            category = AiCoverCategory.COVER,
            year = 1993,
            album = "Example Album",
            songwriters = listOf("Example Writer"),
        )

        val queries = AiCoverSearchEngine.locatorQueries(enriched)

        assertTrue(queries.first() == "Bravi ragazzi Example Cover Artist")
        assertTrue(queries.any { "Example Album" in it })
        assertTrue(queries.any { "1993" in it })
        assertTrue(queries.any { "Example Writer" in it })
    }

    @Test
    fun `exact-title YouTube upload can survive an uploader-channel mismatch`() {
        val cover = candidate("Il mondo", artist = "Example Cover Artist")
        val upload = song(title = "Il mondo", artist = "Archive Upload Channel")

        val strictScore = AiCoverSearchEngine.scoreForTest(
            song = upload,
            candidate = cover,
            allowUploaderFallback = false,
        )
        val deepVideoScore = AiCoverSearchEngine.scoreForTest(
            song = upload,
            candidate = cover,
            allowUploaderFallback = true,
        )

        assertTrue(strictScore < 68)
        assertTrue(deepVideoScore >= 68)
    }

    @Test
    fun `uploader fallback never revives a different longer title`() {
        val cover = candidate("Il mondo", artist = "Example Cover Artist")
        val wrong = song(title = "Il mondo che vorrei", artist = "Archive Upload Channel")

        val score = AiCoverSearchEngine.scoreForTest(
            song = wrong,
            candidate = cover,
            allowUploaderFallback = true,
        )

        assertTrue(score < 68)
    }

    @Test
    fun `matching album can support a locator result even when channel name differs`() {
        val cover = AiCoverCandidate(
            title = "Bravi ragazzi",
            artist = "Example Cover Artist",
            category = AiCoverCategory.COVER,
            album = "Rare Covers 1993",
        )
        val upload = song(
            title = "Bravi ragazzi",
            artist = "Archive Upload Channel",
            album = "Rare Covers 1993",
        )

        assertTrue(
            AiCoverSearchEngine.scoreForTest(
                song = upload,
                candidate = cover,
                allowUploaderFallback = false,
            ) >= 68,
        )
    }
}
