package com.metrolist.music.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class MusicLabHistoricalHitParadeTest {
    @Test
    fun indexUsesPublishedWeekLinksAndChronologicalOrder() {
        val index = """
            <html><body>
              <a href="hps19801004.html">04-10 (40)</a>
              <a href="hps19800927.html">27-09 (39)</a>
              <a href="https://example.com/hps19800920.html">fake</a>
              <a href="hps19800927.html">duplicate</a>
            </body></html>
        """.trimIndent()
        val weeks = MusicLabHistoricalHitParade.weeksFromIndex(index, 1980)
        assertEquals(2, weeks.size)
        assertTrue(weeks[0].url.endsWith("hps19800927.html"))
        assertTrue(weeks[1].url.endsWith("hps19801004.html"))
    }

    @Test
    fun chartUsesActualOriginalTitleArtistAndPositionsWithoutPlaybackGate() {
        // Match the public 1980 layout: an h5 rank, title/artist, then tracklist.
        val html = """
            <html><body><ul>
            <li><p><h5>1</h5></p>Amico  Renato Zero
              <p>(Dario Baldan Bembo - Franca Evangelisti)</p>
              <p><b>P:100% | MAX: 1 | NS: 13 | Pre: 1</b></p>
              <p>Zerolandia - Tracklist: <b>Amico \ Amore sì, amore no!</b></p>
            </li>
            <li><p><h5>2</h5></p>Olympic games  Miguel Bosè
              <p>(Toto Cutugno)</p>
              <p><b>P:83% | MAX: 1 | NS: 20 | Pre: 2</b></p>
              <p>CBS - Tracklist: <b>Olympic games \ Ti amerò</b></p>
            </li>
            </ul></body></html>
        """.trimIndent()
        val songs = MusicLabHistoricalHitParade.parseSongs(html)
        assertEquals(2, songs.size)
        assertEquals("Amico", songs[0].title)
        assertEquals("Renato Zero", songs[0].artist)
        assertEquals(1, songs[0].rank)
        assertEquals("13", songs[0].weeks)
        assertEquals("Olympic games", songs[1].title)
        assertEquals("Miguel Bosè", songs[1].artist)
    }

    @Test
    fun modernTop200UsesLinkedTitleAndSeparatePerformerNotStatistics() {
        val modern = """
            <ul>
              <li><h5>1</h5>3<a href="https://www.youtube.com/watch?v=abc">Il filo rosso</a>
                <p>Alfa</p><p>Artist First</p>
                <p><b>P:100,00% | MAX: 1 | NS: 10</b></p>
              </li>
              <li><h5>2</h5>11<a href="https://www.youtube.com/watch?v=def">Last Christmas</a>
                <p>Wham!</p><p>Sony</p>
                <p><b>P:97,13% | MAX: 2 | NS: 7</b></p>
              </li>
            </ul>
        """.trimIndent()
        val songs = MusicLabHistoricalHitParade.parseSongs(modern)
        assertEquals(2, songs.size)
        assertEquals("Il filo rosso", songs[0].title)
        assertEquals("Alfa", songs[0].artist)
        assertEquals("Wham!", songs[1].artist)
        assertEquals("7", songs[1].weeks)
    }

    @Test
    fun calendarUsesRealLegacyIndexFor1995And2010() {
        assertTrue(MusicLabHistoricalHitParade.indexUrl(1995).endsWith("hpw_1995.htm"))
        assertTrue(MusicLabHistoricalHitParade.indexUrl(2010).endsWith("hpw_2010.htm"))
        assertTrue(MusicLabHistoricalHitParade.indexUrl(1980).endsWith("/1980/index.html"))
        assertTrue(MusicLabHistoricalHitParade.indexUrl(2024).endsWith("/2024/index.html"))
        val old95 = """
            <a href="95/hp950923.htm">23/09</a>
            <a href="95/hp950930.htm">30/09</a>
            <a href="10/hp100515.htm">foreign-year</a>
        """.trimIndent()
        val weeks = MusicLabHistoricalHitParade.weeksFromIndex(old95, 1995)
        assertEquals(2, weeks.size)
        assertTrue(weeks[0].url.endsWith("hp950923.htm"))
        assertTrue(weeks[1].url.endsWith("hp950930.htm"))
        val old2010 = "<a href='10/hp100515.htm'>15/05</a>"
        val weeks2010 = MusicLabHistoricalHitParade.weeksFromIndex(old2010, 2010)
        assertEquals(1, weeks2010.size)
        assertTrue(weeks2010[0].url.endsWith("hp100515.htm"))
    }

    @Test
    fun realStyleTop20AndTop100TableKeepExactArtistsAndRanks() {
        // The actual 1995 /95/hp950930.htm and 2010 /10/hp100515.htm layout.
        val top1995 = """
            <table class="piccolo"><tr><td>Pos.<br>Att.</td><td>Prec.</td>
            <td>Titolo</td><td>Interprete</td></tr>
            <tr><td>1</td><td>1</td><td>Hideaway</td><td>De'Lacy</td></tr>
            <tr><td>2</td><td>6</td><td>Me and you</td>
                <td>Alexia feat. Double You</td></tr></table>
        """.trimIndent()
        val songs95 = MusicLabHistoricalHitParade.parseSongs(top1995)
        assertEquals(2, songs95.size)
        assertEquals("Hideaway", songs95[0].title)
        assertEquals("De'Lacy", songs95[0].artist)
        assertEquals("6", songs95[1].previous)

        val top2010 = """
            <table class="piccolo"><tr><td>Pos.</td><td>Prec.</td>
            <td>Titolo</td><td>Interprete</td></tr>
            <tr><td>1</td><td>4</td><td>Alors on danse</td><td>Stromae</td></tr>
            <tr><td>2</td><td>1</td><td>Un colpo all'anima</td><td>Ligabue</td></tr>
            <tr><td>7</td><td>NE</td><td>Waka Waka (This time for Africa)</td>
            <td>Shakira / Freshlyground</td></tr></table>
        """.trimIndent()
        val songs10 = MusicLabHistoricalHitParade.parseSongs(top2010)
        assertEquals(3, songs10.size)
        assertEquals("Alors on danse", songs10[0].title)
        assertEquals("Stromae", songs10[0].artist)
        assertEquals(7, songs10[2].rank)
        assertEquals("Shakira / Freshlyground", songs10[2].artist)
    }

    @Test
    fun actual1980ListUsesTheRankedDiscSide() {
        val old = """
            <ul><li><p><h5>1</h5></p>Amico  Renato Zero
            <p>(Dario Baldan Bembo \ Franca Evangelisti - Renato Zero)</p>
            <p><b>P:100% | MAX: 1 | NS: 16 | Pre: 1</b></p>
            <p>Zerolandia, PB 6473 - Tracklist:
                <b>Amico \ Amore sì, amore no!</b></p></li>
            <li><p><h5>2</h5></p>1Upside down  Diana Ross
            <p>(Bernard Edwards - Nile Rodgers)</p>
            <p><b>P:84% | MAX: 2 | NS: 9 | Pre: 3</b></p>
            <p>Motown - Tracklist: <b>Upside down \ Friend to friend</b></p>
            </li></ul>
        """.trimIndent()
        val songs = MusicLabHistoricalHitParade.parseSongs(old)
        assertEquals(2, songs.size)
        assertEquals("Amico", songs[0].title)
        assertEquals("Renato Zero", songs[0].artist)
        assertEquals("Upside down", songs[1].title)
        assertEquals("Diana Ross", songs[1].artist)
    }

    @Test
    fun anyDateResolvesToTheWeeklySaturday() {
        val friday = Calendar.getInstance().apply { set(1980, Calendar.SEPTEMBER, 26) }
        val saturday = MusicLabHistoricalHitParade.saturdayOf(friday)
        assertEquals(27, saturday.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.SATURDAY, saturday.get(Calendar.DAY_OF_WEEK))
        assertFalse(MusicLabHistoricalHitParade.parseSongs(
            "<html><body><li><h5>1</h5>Missing tracklist</li></body></html>"
        ).isNotEmpty())
    }
}
