package com.metrolist.music.ui.screens.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicLabHitParadeDiscArtworkTest {
    @Test fun normalizedSongIdentitySurvivesPunctuationAndAccents() {
        assertEquals("un estate fa",
            MusicLabHitParadeDiscArtwork.norm("Un'estate fa"))
        assertEquals("spirito divino",
            MusicLabHitParadeDiscArtwork.norm("Spirito diVino"))
    }

    @Test fun exactSameSongAndArtistKeepsOriginalSleeve() {
        assertTrue(MusicLabHitParadeDiscArtwork.sameRecord(
            "Sapore di sale", "Gino Paoli",
            "Sapore di sale (official audio)", "Gino Paoli"))
    }

    @Test fun differentSongOrDifferentArtistMustNotReuseSleeve() {
        assertFalse(MusicLabHitParadeDiscArtwork.sameRecord(
            "Sapore di sale", "Gino Paoli", "Il cielo in una stanza", "Gino Paoli"))
        assertFalse(MusicLabHitParadeDiscArtwork.sameRecord(
            "Sapore di sale", "Gino Paoli", "Sapore di sale", "Altra cantante"))
    }

    @Test fun classicAlbumMatchingAcceptsCatalogueArtistWhenArchiveRepeatsTitle() {
        assertTrue(MusicLabHitParadeDiscArtwork.eligibleAlbum(
            "Spirito diVino", "Spirito diVino", "Spirito diVino", "Zucchero"))
        assertFalse(MusicLabHitParadeDiscArtwork.eligibleAlbum(
            "Spirito diVino", "Spirito diVino", "Made in Heaven", "Queen"))
    }

    @Test fun aCorrectHistoricalArtistPreventsWrongAlbumAssociation() {
        assertTrue(MusicLabHitParadeDiscArtwork.eligibleAlbum(
            "Made in Heaven", "Queen", "Made in Heaven", "Queen"))
        assertFalse(MusicLabHitParadeDiscArtwork.eligibleAlbum(
            "Made in Heaven", "Queen", "Made in Heaven", "Other Singer"))
    }
}
