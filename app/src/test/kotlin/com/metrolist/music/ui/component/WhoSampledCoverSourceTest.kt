package com.metrolist.music.ui.component

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhoSampledCoverSourceTest {
    @Test
    fun searchParserPrefersMatchingTrackAndArtist() {
        val document = Jsoup.parse(
            """
            <html><body>
              <a href="/Yesterday/">Yesterday artist page</a>
              <a href="/Lisa-Ono/Yesterday/">Yesterday</a>
              <a href="/The-Beatles/Yesterday/">Yesterday</a>
            </body></html>
            """.trimIndent(),
            "https://www.whosampled.com/search/?q=Yesterday+Beatles",
        )

        val urls = WhoSampledCoverSource.parseSearchTrackUrls(
            document = document,
            title = "Yesterday",
            artist = "The Beatles",
        )

        assertTrue(urls.isNotEmpty())
        assertEquals("https://www.whosampled.com/The-Beatles/Yesterday/", urls.first())
    }

    @Test
    fun coverParserKeepsTranslatedTitleWhenRelationshipIsExplicit() {
        val document = Jsoup.parse(
            """
            <html><body>
              <section>
                <h3>Was covered in 2 songs</h3>
                <div>
                  <a href="/Josipa-Lisac/Ju%C4%8Der/">Jučer</a>
                  by <a href="/Josipa-Lisac/">Josipa Lisac</a>
                </div>
                <div>
                  <a href="/The-Beatles/Yesterday/">Yesterday</a>
                  by <a href="/The-Beatles/">The Beatles</a>
                </div>
              </section>
            </body></html>
            """.trimIndent(),
            "https://www.whosampled.com/The-Beatles/Yesterday/",
        )

        val covers = WhoSampledCoverSource.parseCoverRefs(
            document = document,
            originalTitle = "Yesterday",
            originalArtist = "The Beatles",
        )

        assertEquals(1, covers.size)
        assertEquals("Jučer", covers.first().title)
        assertEquals("Josipa Lisac", covers.first().artist)
    }

    @Test
    fun relationshipPageExtractsCoverButNotOriginal() {
        val document = Jsoup.parse(
            """
            <html><body>
              <div><a href="/Prince-Royce/Yesterday/">Yesterday</a></div>
              <div><a href="/The-Beatles/Yesterday/">Yesterday</a></div>
              <section>
                <h3>Other covers of The Beatles's Yesterday</h3>
                <a href="/Marvin-Gaye/Yesterday/">Yesterday</a>
              </section>
            </body></html>
            """.trimIndent(),
            "https://www.whosampled.com/cover/1296588/Prince-Royce-Yesterday-The-Beatles-Yesterday/",
        )

        val covers = WhoSampledCoverSource.parseCoverRefs(
            document = document,
            originalTitle = "Yesterday",
            originalArtist = "The Beatles",
        )

        assertTrue(covers.any { it.artist == "Prince Royce" })
        assertTrue(covers.none { it.artist == "The Beatles" })
    }
}
