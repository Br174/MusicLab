package com.metrolist.music.ui.screens.search

import java.util.Calendar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLabHistoricalExtraChartsTest {
    @Test fun categoryCatalogHasExactlyFourKinds() {
        assertEquals(4, MusicLabHistoricalChartMode.entries.size)
        assertTrue(MusicLabHistoricalChartMode.WEEKLY_SINGLES.title.contains("singoli"))
        assertTrue(MusicLabHistoricalChartMode.WEEKLY_ALBUMS.isAlbum)
        assertTrue(MusicLabHistoricalChartMode.ANNUAL_SINGLES.annual)
        assertTrue(MusicLabHistoricalChartMode.ANNUAL_ALBUMS.isAlbum)
    }

    @Test fun annualSingleSourceAndOrderedListParsesWithoutInventingEntries() {
        val html = """
          <html><body><ol>
            <li>Gangsta's Paradise - Coolio feat. L.V. [#1, 1995/96]</li>
            <li>Boombastic - Shaggy [#1, 1995/96]</li>
          </ol></body></html>
        """.trimIndent()
        val rows = MusicLabHistoricalExtraRepository.parseAnnualSingles(html)
        assertEquals(2, rows.size)
        assertEquals("Gangsta's Paradise", rows.first().title)
        assertEquals("Coolio feat. L.V.", rows.first().artist)
        assertEquals("Boombastic", rows[1].title)
    }

    @Test fun annualAlbumSourceParsesRankAndArtistsWithoutTreatingTracklistAsAnAlbum() {
        val html = """
          <table><tr><th>POS.</th><th>ALBUM</th><th>ARTISTA</th><th>LABEL</th></tr>
          <tr><td>1</td><td>Spirito diVino</td><td>Zucchero</td><td>Polydor</td></tr>
          <tr><td colspan="4">Voodoo Voodoo - Datemi una pompa - Pane e sale</td></tr>
          <tr><td>2</td><td>Non calpestare i fiori nel deserto</td><td>Pino Daniele</td></tr>
          </table>
        """.trimIndent()
        val rows = MusicLabHistoricalExtraRepository.parseAnnualAlbums(html)
        assertEquals(2, rows.size)
        assertEquals("Spirito diVino", rows.first().title)
        assertEquals("Pino Daniele", rows[1].artist)
    }

    @Test fun weeklyAlbumIndexUsesPublishedWeeksInsteadOfSynthesizingDates() {
        val html = """
          <a href="https://hitparadeitalia.it/hp_weeka/1995/hpa19950603.html">03-06</a>
          <a href="hpa19950610.html">10-06</a>
          <a href="https://somewhere-else.com/hpa19950617.html">fake</a>
        """.trimIndent()
        val weeks = MusicLabHistoricalExtraRepository.publishedAlbumWeeks(html, 1995)
        assertEquals(2, weeks.size)
        assertTrue(weeks[0].url.endsWith("hpa19950603.html"))
    }

    @Test fun weeklyAlbumRowParserKeepsRecordAndArtist() {
        val html = """
          <ul><li><h5>1</h5><div class="album-dettagli-titolo">
          Non calpestare i fiori nel deserto</div>
          <div class="album-dettagli-artista">Pino Daniele</div></li></ul>
        """.trimIndent()
        val rows = MusicLabHistoricalExtraRepository.parseWeeklyAlbums(html)
        assertEquals(1, rows.size)
        assertEquals("Pino Daniele", rows.first().artist)
    }

    @Test fun officialAnnualPagePathsAreExact() {
        assertEquals("https://hitparadeitalia.it/hp_yends/hpe1995.htm",
            MusicLabHistoricalExtraRepository.annualUrl(
                MusicLabHistoricalChartMode.ANNUAL_SINGLES, 1995))
        assertEquals("https://hitparadeitalia.it/hp_yenda/lpe1995.htm",
            MusicLabHistoricalExtraRepository.annualUrl(
                MusicLabHistoricalChartMode.ANNUAL_ALBUMS, 1995))
    }
}

class MusicLabHistoricalExtraLiveSourceTest {
    @Test fun liveOfficialThreeAdditionalArchives1995RemainReadable() = runBlocking {
        val year1995 = Calendar.getInstance().apply {
            set(1995, Calendar.JUNE, 3, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        for (mode in listOf(
            MusicLabHistoricalChartMode.ANNUAL_SINGLES,
            MusicLabHistoricalChartMode.ANNUAL_ALBUMS,
            MusicLabHistoricalChartMode.WEEKLY_ALBUMS,
        )) {
            val result = MusicLabHistoricalExtraRepository.load(mode, year1995)
            assertTrue("Original HPI page was empty for " + mode, result.rows.size >= 10)
            assertTrue(result.sourceUrl.startsWith("https://hitparadeitalia.it/") ||
                result.sourceUrl.startsWith("https://www.hitparadeitalia.it/"))
            assertEquals(1, result.rows.first().rank)
        }
    }
}
