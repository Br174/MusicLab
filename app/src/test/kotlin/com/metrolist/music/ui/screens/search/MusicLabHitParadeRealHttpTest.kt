package com.metrolist.music.ui.screens.search

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Unlike HTML fixtures, this calls the actual Android chart loader with
 * the real first-party URLs and OkHttp user-agent. Never mock site availability.
 */
class MusicLabHitParadeRealHttpTest {
    private fun verify(
        year: Int, month: Int, day: Int, expectedSize: Int,
        firstTitle: String, firstArtist: String
    ) = runBlocking {
        val target = Calendar.getInstance().apply {
            set(year, month - 1, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val result = withTimeout(60000L) { MusicLabHistoricalHitParade.load(target) }
        assertEquals(year, Calendar.getInstance().apply {
            timeInMillis = result.week.millis
        }.get(Calendar.YEAR))
        assertEquals("Wrong number of actual chart positions", expectedSize, result.songs.size)
        assertEquals((1..expectedSize).toList(), result.songs.map { it.rank })
        assertEquals(firstTitle, result.songs.first().title)
        assertEquals(firstArtist, result.songs.first().artist)
        assertTrue("The returned week URL must be the original source",
            result.week.url.startsWith("https://www.hitparadeitalia.it/hp_weeks/"))
    }

    @Test fun live1959() = verify(1959, 8, 1, 50, "Arrivederci", "Marino Barreto jr")
    @Test fun live1980() = verify(1980, 8, 2, 50, "Luna", "Gianni Togni")
    @Test fun keepClassic1995() = verify(1995, 9, 30, 20, "Hideaway", "De'Lacy")
    @Test fun live2018() = verify(2018, 8, 4, 200, "Amore e Capoeira", "Takagi e Ketra feat. Giusy Ferreri & Sean Kingston")
    @Test fun live2020() = verify(2020, 8, 1, 200, "Karaoke", "Boomdabash & Alessandra Amoroso")
    @Test fun live2026() = verify(2026, 8, 1, 200, "Dai dai", "Shakira & Burna Boy")
}
