package com.metrolist.music.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class MusicLabHistoricHtmlDecoderTest {
    private val cp1252 = Charset.forName("windows-1252")

    @Test fun historicalWindows1252RestoresRealAccentsAndRankedRows() {
        val html = """
            <html><head><meta charset="windows-1252"></head><body>
            <ol>
            <li>Chi fermerà la musica - Pooh</li>
            <li>Più su/Galeotto fu il canotto - Renato Zero</li>
            <li>Bravi ragazzi - Miguel Bosé</li>
            <li>Non sono una signora - Loredana Bertè</li>
            </ol></body></html>
        """.trimIndent()
        val bytes = html.toByteArray(cp1252)
        val decoded = MusicLabHistoricHtmlDecoder.decode(bytes, "text/html")
        assertEquals(html, decoded)
        assertFalse(decoded.contains('\uFFFD'))
        val songs = MusicLabHistoricalHitParade.parseSongs(decoded)
        assertEquals(4, songs.size)
        assertEquals("Chi fermerà la musica", songs[0].title)
        assertEquals("Più su/Galeotto fu il canotto", songs[1].title)
        assertEquals("Miguel Bosé", songs[2].artist)
        assertEquals("Loredana Bertè", songs[3].artist)
    }

    @Test fun legacyBytesWithWrongUtf8HttpHeaderNeverLoseAccents() {
        val input = "Chi fermerà la musica · Miguel Bosé · Loredana Bertè"
        val restored = MusicLabHistoricHtmlDecoder.decode(
            input.toByteArray(cp1252), "text/html; charset=utf-8"
        )
        assertEquals(input, restored)
    }

    @Test fun declaredIso88591SupportsItalianAndFrenchDiacritics() {
        val input = "<meta http-equiv='Content-Type' content='text/html; charset=iso-8859-1'>" +
            "Città · Perché · Più · Lio · Amoureux solitaires"
        assertEquals(input, MusicLabHistoricHtmlDecoder.decode(
            input.toByteArray(Charsets.ISO_8859_1), "text/html"
        ))
    }

    @Test fun realUtf8AndNfcKeepAccentsAndDoNotBecomeMojibake() {
        val input = "È · perché · città · René · Miguel Bosé · Loredana Bertè"
        assertEquals(input, MusicLabHistoricHtmlDecoder.decode(input.toByteArray(Charsets.UTF_8)))
        assertEquals(input, MusicLabHistoricHtmlDecoder.decode(
            input.toByteArray(Charsets.UTF_8), "text/html; charset=utf-8"
        ))
    }

    @Test fun utf8BomIsHonoredEvenWhenHeaderIsMisleading() {
        val body = "Più su - Renato Zero".toByteArray(Charsets.UTF_8)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body
        assertEquals("Più su - Renato Zero", MusicLabHistoricHtmlDecoder.decode(
            bom, "text/html; charset=windows-1252"
        ))
    }

    @Test fun onlyKnownBadSnapshotsRequireOneTimeRevalidation() {
        assertTrue(MusicLabHistoricHtmlDecoder.corrupted("Chi fermer\uFFFD la musica"))
        assertTrue(MusicLabHistoricHtmlDecoder.corrupted("Miguel BosÃ©"))
        assertFalse(MusicLabHistoricHtmlDecoder.corrupted("Chi fermerà la musica"))
        assertFalse(MusicLabHistoricHtmlDecoder.corrupted("Miguel Bosé"))
    }

    @Test fun decodedEntitiesStillBelongToOriginalSourceNotInventedRanking() {
        val html = "<ol><li>Chi fermer&agrave; la musica - Pooh</li>" +
            "<li>Pi&ugrave; su/Galeotto fu il canotto - Renato Zero</li></ol>"
        val songs = MusicLabHistoricalHitParade.parseSongs(
            MusicLabHistoricHtmlDecoder.decode(html.toByteArray(Charsets.UTF_8))
        )
        assertEquals(listOf(1, 2), songs.map { it.rank })
        assertEquals("Chi fermerà la musica", songs.first().title)
        assertEquals("Più su/Galeotto fu il canotto", songs.last().title)
    }
}
