package com.metrolist.music.ui.component

/** LAB66: resumable YouTube discovery; a cursor advances ONLY after each query. */
internal object CoverRoundSearchPlan {
    internal data class Query(val text: String, val categoryHint: AiCoverCategory)

    private val official = listOf(
        "official audio", "official music video", "original recording", "studio version",
        "official cover", "famous cover version", "duet official", "live official",
        "foreign language cover", "adaptation cover", "cover studio", "official performance",
    )
    private val talent = listOf(
        "X Factor cover live", "The Voice cover live", "live concert cover",
        "acoustic cover", "talent show performance", "unplugged cover",
        "festival live cover", "TV audition cover", "live studio session",
        "semi official cover", "TV show cover", "singer interpretation",
    )
    private val amateur = listOf(
        "guitar cover", "piano cover", "home studio cover", "independent artist cover",
        "instrumental interpretation", "bedroom cover", "street performance cover",
        "acoustic vocal cover", "amateur cover", "local band cover",
        "karaoke singer cover", "solo voice cover",
    )
    private val longTail = listOf(
        "rare cover version", "foreign language adaptation", "tribute band cover",
        "local singer cover", "jazz reinterpretation", "orchestra cover",
        "folk reinterpretation", "live radio session", "soul cover",
        "indie acoustic cover", "choir cover", "alternative rock cover",
        "classical guitar version", "piano solo performance", "unusual cover",
        "regional language version",
    )
    /** No percentage quotas, no fixed publication blocks. */
    fun queries(title: String, round: Int, originalArtist: String = ""): List<Query> {
        if (title.isBlank()) return emptyList()
        val clean = title.trim()
        val artist = originalArtist.trim()
        val family = when (round) {
            1 -> official
            2 -> talent
            3 -> amateur
            else -> longTail
        }
        // Strongest quick discovery is always first, also in later rounds.
        val first = if (round == 1) {
            listOf(
                Query("$clean $artist", AiCoverCategory.COVER),
                Query("$clean $artist official", AiCoverCategory.COVER),
            )
        } else {
            listOf(Query("$clean cover", AiCoverCategory.COVER))
        }
        val offset = if (round <= 3) 0 else ((round - 4) * 3) % family.size
        val targeted = family.indices.map { idx ->
            val keyword = family[(offset + idx) % family.size]
            Query(
                text = "$clean $keyword",
                categoryHint = when {
                    keyword.contains("remix", true) -> AiCoverCategory.REMIX
                    keyword.contains("foreign", true) || keyword.contains("language", true) ||
                        keyword.contains("adaptation", true) -> AiCoverCategory.FOREIGN
                    keyword.contains("live", true) || keyword.contains("concert", true) ||
                        keyword.contains("festival", true) -> AiCoverCategory.LIVE
                    else -> AiCoverCategory.COVER
                },
            )
        }
        return (first + targeted).distinctBy { it.text.trim().lowercase() }
    }
}
