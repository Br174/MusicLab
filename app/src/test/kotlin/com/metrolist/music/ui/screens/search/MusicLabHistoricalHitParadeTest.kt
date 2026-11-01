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
