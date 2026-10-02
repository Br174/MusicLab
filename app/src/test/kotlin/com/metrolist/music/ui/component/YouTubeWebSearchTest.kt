package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeWebSearchTest {
    @Test
    fun `regular youtube web response becomes playable SongItems with continuation`() {
        val raw =
            """
            {
              "contents": {
                "twoColumnSearchResultsRenderer": {
                  "primaryContents": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "itemSectionRenderer": {
                            "contents": [
                              {
                                "videoRenderer": {
                                  "videoId": "abc123XYZ",
                                  "title": {"runs":[{"text":"Gente di mare"}]},
                                  "ownerText": {"runs":[{"text":"Archivio TV Italia"}]},
                                  "lengthText": {"simpleText":"3:44"},
                                  "thumbnail": {"thumbnails":[{"url":"//i.ytimg.com/vi/abc123XYZ/hqdefault.jpg"}]}
                                }
                              }
                            ]
                          }
                        },
                        {
                          "continuationItemRenderer": {
                            "continuationEndpoint": {
                              "continuationCommand": {"token":"NEXT_PAGE_TOKEN"}
                            }
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent()

        val page = YouTubeWebSearch.parseSearchResponse(raw)

        assertNotNull(page)
        assertEquals("NEXT_PAGE_TOKEN", page?.continuation)
        assertEquals(1, page?.items?.size)
        assertEquals("abc123XYZ", page?.items?.single()?.id)
        assertEquals("Gente di mare", page?.items?.single()?.title)
        assertEquals("Archivio TV Italia", page?.items?.single()?.artists?.single()?.name)
        assertEquals(224, page?.items?.single()?.duration)
        assertEquals(
            "https://i.ytimg.com/vi/abc123XYZ/hqdefault.jpg",
            page?.items?.single()?.thumbnail,
        )
    }

    @Test
    fun `web parser deduplicates repeated renderers by video id`() {
        val raw =
            """
            {
              "a":{"videoRenderer":{"videoId":"dup1","title":{"simpleText":"Il mondo"},"shortBylineText":{"simpleText":"Canale"},"thumbnail":{"thumbnails":[]}}},
              "b":{"compactVideoRenderer":{"videoId":"dup1","title":{"simpleText":"Il mondo"},"shortBylineText":{"simpleText":"Canale"},"thumbnail":{"thumbnails":[]}}}
            }
            """.trimIndent()

        val page = YouTubeWebSearch.parseSearchResponse(raw)

        assertEquals(1, page?.items?.size)
        assertEquals("dup1", page?.items?.single()?.id)
    }

    @Test
    fun `malformed web response is ignored safely`() {
        assertNull(YouTubeWebSearch.parseSearchResponse("<html>not json</html>"))
    }
}
