package com.metrolist.music.ui.component

/**
 * LAB46: bounded multi-round discovery. The first LAB45 round stays unchanged.
 * Every later round rotates independent query families, without assuming a
 * matching title on YouTube proves a matching musical composition.
 */
internal object CoverRoundSearchPlan {
    internal data class Query(val text: String, val categoryHint: AiCoverCategory)
    private val talent = listOf(
        "X Factor cover live", "The Voice cover live", "live concert cover",
        "acoustic cover", "talent show performance", "unplugged cover",
        "festival live cover", "TV audition cover",
    )
    private val amateur = listOf(
        "guitar cover", "piano cover", "home studio cover", "independent artist cover",
        "instrumental interpretation", "bedroom cover", "street performance cover",
        "acoustic vocal cover",
    )
    private val longTail = listOf(
        "rare cover version", "foreign language adaptation", "tribute band cover",
        "local singer cover", "jazz reinterpretation", "orchestra cover",
        "folk reinterpretation", "live radio session", "soul cover",
        "indie acoustic cover", "choir cover", "alternative rock cover",
        "classical guitar version", "piano solo performance", "unusual cover",
        "regional language version",
    )
    fun queries(title: String, round: Int): List<Query> {
        if (round <= 1 || title.isBlank()) return emptyList()
        val family = when (round) {
            2 -> talent
            3 -> amateur
            else -> longTail
        }
        val offset = if (round <= 3) 0 else ((round - 4) * 2) % family.size
        return (0 until 2).map { index ->
            val query = family[(offset + index) % family.size]
            Query(
                text = "${title.trim()} $query",
                categoryHint = if (query.contains("live", ignoreCase = true) ||
                    query.contains("concert", ignoreCase = true)) AiCoverCategory.LIVE
                    else AiCoverCategory.COVER,
            )
        }
    }
}
