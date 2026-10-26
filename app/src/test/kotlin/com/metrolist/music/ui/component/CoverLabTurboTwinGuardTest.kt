package com.metrolist.music.ui.component

import org.junit.Assert.*
import org.junit.Test

class CoverLabTurboTwinGuardTest {
    private fun seed(
        id: String,
        artist: String = "Jimmy Fontana",
        videoTitle: String = "Jimmy Fontana - Il mondo (Official Audio)",
        kind: DiscogsVersionKind = DiscogsVersionKind.STUDIO,
    ): DiscogsVersionSeed = DiscogsVersionSource.externalSeed(
        CoverSourceCandidate(
            title = "Il mondo", artist = artist,
            sources = listOf("YouTube"),
            playbackVideoId = id,
            playbackVideoTitle = videoTitle,
        ),
        targetTitle = "Il mondo",
        originalArtist = "Jimmy Fontana",
    ).copy(
        kind = kind,
        resolvedVideoId = id,
        resolvedVideoTitle = videoTitle,
    )

    @Test fun exactVideoIdIsAlwaysDuplicate() {
        assertTrue(CoverLabTurboTwinGuard.sameRecording(
            seed("abcdefghijk"), seed("abcdefghijk")
        ))
    }

    @Test fun studioUploadAndIdenticalLyricsUploadAreTwins() {
        assertTrue(CoverLabTurboTwinGuard.sameRecording(
            seed("abcdefghijk", videoTitle = "Jimmy Fontana - Il mondo (Official Audio)"),
            seed("lmnopqrstuv", videoTitle = "Jimmy Fontana - Il mondo (Lyrics HD)")
        ))
    }

    @Test fun anotherSingerIsNotTheSameRecording() {
        assertFalse(CoverLabTurboTwinGuard.sameRecording(
            seed("abcdefghijk"), seed("lmnopqrstuv", artist = "Different Singer")
        ))
    }

    @Test fun studioAndLiveRemainSeparate() {
        assertFalse(CoverLabTurboTwinGuard.sameRecording(
            seed("abcdefghijk"),
            seed("lmnopqrstuv", videoTitle = "Jimmy Fontana - Il mondo live 1965",
                 kind = DiscogsVersionKind.LIVE)
        ))
    }

    @Test fun differentLiveVenuesRemainSeparate() {
        assertFalse(CoverLabTurboTwinGuard.sameRecording(
            seed("abcdefghijk", videoTitle = "Jimmy Fontana - Il mondo live Roma",
                 kind = DiscogsVersionKind.LIVE),
            seed("lmnopqrstuv", videoTitle = "Jimmy Fontana - Il mondo live Milano",
                 kind = DiscogsVersionKind.LIVE)
        ))
    }
}
