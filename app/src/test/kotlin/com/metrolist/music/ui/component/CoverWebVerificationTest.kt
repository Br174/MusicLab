package com.metrolist.music.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class CoverWebVerificationTest {
    @Test
    fun googleQueryContainsOriginalAndCandidateIdentity() {
        val url = CoverWebVerification.googleSearchUrl(
            originalTitle = "Comme d'habitude",
            originalArtist = "Claude François",
            candidateTitle = "My Way",
            candidateArtist = "Frank Sinatra",
        )

        assertTrue(url.startsWith("https://www.google.com/search?q="))

        val decoded = URLDecoder.decode(url.substringAfter("q="), "UTF-8")
        assertTrue(decoded.contains("My Way"))
        assertTrue(decoded.contains("Frank Sinatra"))
        assertTrue(decoded.contains("Comme d'habitude"))
        assertTrue(decoded.contains("Claude François"))
        assertTrue(decoded.contains("cover adaptation version same song"))
    }
}
