package com.metrolist.music.ui.component

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzCoverSourceTest {
    @Test
    fun recordingSearchPrefersMatchingTitleAndArtist() {
        val document = xml(
            """
            <metadata xmlns="http://musicbrainz.org/ns/mmd-2.0#">
              <recording-list count="2">
                <recording id="wrong">
                  <title>Hallelujah</title>
                  <artist-credit><name-credit><artist><name>Another Artist</name></artist></name-credit></artist-credit>
                </recording>
                <recording id="good">
                  <title>Hallelujah</title>
                  <artist-credit><name-credit><artist><name>Leonard Cohen</name></artist></name-credit></artist-credit>
                </recording>
              </recording-list>
            </metadata>
            """.trimIndent()
        )

        val result = MusicBrainzCoverSource.parseRecordingSearch(
            document = document,
            wantedTitle = "Hallelujah",
            wantedArtist = "Leonard Cohen",
        )

        assertEquals("good", result.first().id)
    }

    @Test
    fun recordingDetailExtractsPerformanceWorkId() {
        val document = xml(
            """
            <metadata xmlns="http://musicbrainz.org/ns/mmd-2.0#">
              <recording id="r1">
                <relation-list target-type="work">
                  <relation type="performance">
                    <work id="work-123"><title>Hallelujah</title></work>
                  </relation>
                </relation-list>
              </recording>
            </metadata>
            """.trimIndent()
        )

        assertEquals("work-123", MusicBrainzCoverSource.parseWorkId(document))
    }

    @Test
    fun workBrowseExcludesOriginalAndDeduplicatesPerformances() {
        val document = xml(
            """
            <metadata xmlns="http://musicbrainz.org/ns/mmd-2.0#">
              <recording-list count="4">
                <recording id="original">
                  <title>Hallelujah</title>
                  <artist-credit><name-credit><artist><name>Leonard Cohen</name></artist></name-credit></artist-credit>
                </recording>
                <recording id="cover-1">
                  <title>Hallelujah</title>
                  <artist-credit><name-credit><artist><name>Jeff Buckley</name></artist></name-credit></artist-credit>
                  <first-release-date>1994-08-23</first-release-date>
                </recording>
                <recording id="cover-2">
                  <title>Hallelujah</title>
                  <artist-credit><name-credit><artist><name>Jeff Buckley</name></artist></name-credit></artist-credit>
                </recording>
                <recording id="cover-3">
                  <title>Aleluya</title>
                  <artist-credit><name-credit><artist><name>Spanish Singer</name></artist></name-credit></artist-credit>
                </recording>
              </recording-list>
            </metadata>
            """.trimIndent()
        )

        val result = MusicBrainzCoverSource.parseBrowseRecordings(
            document = document,
            originalRecordingId = "original",
            workId = "work-123",
        )

        assertEquals(2, result.size)
        assertEquals(1994, result.first { it.artist == "Jeff Buckley" }.year)
        assertTrue(result.any { it.title == "Aleluya" && it.workId == "work-123" })
    }

    private fun xml(value: String) = Jsoup.parse(value, "", Parser.xmlParser())
}
