package com.metrolist.music.discogs

import org.junit.Assert.*
import org.junit.Test

class CompilationTrackMatchPolicyTest {
    private fun track(title: String, vararg artists: String) =
        DiscogsTrack("A1",title,artists.toList(),null,null)

    @Test fun exactAndAccentInsensitiveMatching() {
        assertTrue(CompilationTrackMatchPolicy.matches(track("Sapore di sale","Gino Paoli"),"Sapore di sale","Gino Paoli"))
        assertTrue(CompilationTrackMatchPolicy.matches(track("Perche","Artista"),"Perché","Artista"))
    }

    @Test fun avoidFalseMatchesBetweenDifferentSongs() {
        assertFalse(CompilationTrackMatchPolicy.matches(track("Il mondo che vorrei","Jimmy Fontana"),"Il mondo","Jimmy Fontana"))
        assertFalse(CompilationTrackMatchPolicy.matches(track("Sapore di mare","Gino Paoli"),"Sapore di sale","Gino Paoli"))
        assertFalse(CompilationTrackMatchPolicy.matches(track("Sapore di sale","Altro Artista"),"Sapore di sale","Gino Paoli"))
    }

    @Test fun documentedVariantsAreAllowedWithoutPrefixOverreach() {
        assertTrue(CompilationTrackMatchPolicy.matches(track("Sapore di sale (Live)","Gino Paoli"),"Sapore di sale","Gino Paoli"))
        assertTrue(CompilationTrackMatchPolicy.matches(track("Sapore di sale (Remastered 2020)","Gino Paoli"),"Sapore di sale","Gino Paoli"))
    }

    @Test fun variousArtistsWithoutTrackCreditsIsTitleOnlyVerified() {
        assertTrue(CompilationTrackMatchPolicy.matches(track("Sapore di sale","Various"),"Sapore di sale","Gino Paoli"))
        assertTrue(CompilationTrackMatchPolicy.matches(track("Sapore di sale"),"Sapore di sale","Gino Paoli"))
        assertFalse(CompilationTrackMatchPolicy.matches(track("Un altro brano","Various"),"Sapore di sale","Gino Paoli"))
    }
}
