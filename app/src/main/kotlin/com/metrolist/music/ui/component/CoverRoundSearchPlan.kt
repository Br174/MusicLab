package com.metrolist.music.ui.component

/** LAB66: resumable YouTube discovery; a cursor advances ONLY after each query. */
internal object CoverRoundSearchPlan {
    internal data class Query(val text: String, val categoryHint: AiCoverCategory)

    private val official = listOf(
        "official audio", "official music video", "original recording", "studio version",
        "official cover", "professional singer cover", "famous cover version",
        "studio cover by other singers", "cover interpretation", "duet official", "live official",
        "foreign language cover", "adaptation cover", "cover studio", "official performance",
    )
    private val talent = listOf(
        "X Factor cover live", "The Voice cover live", "live concert cover",
        "acoustic cover", "talent show performance", "unplugged cover",
        "festival live cover", "TV audition cover", "live studio session",
        "semi official cover", "TV show cover", "singer interpretation",
        // LAB76 additive Italian + international TV/talent and famous performers.
        "Festival di Sanremo cover", "Sanremo live cover", "Eurovision cover",
        "X Factor Italia cover", "The Voice Italy cover", "The Voice Global cover",
        "Amici cover", "famous singer covers", "cover by famous singer",
        "TV music performance", "stadio concerto cover",
        "stadium concert cover", "remix cover", "BBC Live Lounge cover",
        "mashup cover", "AI cover", "intelligenza artificiale cover", "videoclip cover",
    )
    private val amateur = listOf(
        "guitar cover", "piano cover", "home studio cover", "independent artist cover",
        "instrumental interpretation", "bedroom cover", "street performance cover",
        "acoustic vocal cover", "amateur cover", "local band cover",
        "independent vocalist cover", "solo voice cover",
        "cover band live", "tribute band concert", "guitar instrumental cover",
        "chitarra cover", "instrumental guitar version", "famous artists live",
        "X Factor performance", "The Voice performance", "Sanremo esibizione",
        "Eurovision live version", "stadio live", "stadium live",
        "remix live cover", "acoustic performance",
    )
    private val longTail = listOf(
        "rare cover version", "foreign language adaptation", "tribute band cover",
        "local singer cover", "jazz reinterpretation", "orchestra cover",
        "folk reinterpretation", "live radio session", "soul cover",
        "indie acoustic cover", "choir cover", "alternative rock cover",
        "classical guitar version", "piano solo performance", "unusual cover",
        "regional language version",
        "X Factor live cover", "The Voice singer cover", "Festival di Sanremo",
        "Eurovision Song Contest cover", "Amici talent cover",
        "stadio concerto", "stadium concert", "famous singer live cover",
        "cover band", "guitar cover", "chitarra instrumental",
        "instrumental version", "strumentale cover", "remix cover",
        "acoustic cover live", "foreign version singer", "cover internazionale",
        "studio live recording", "TV talent performance", "radio live cover",
    )
    const val finalRound = 6
    private val stage = (talent + amateur.filter {
        listOf("X Factor", "The Voice", "Sanremo", "Eurovision", "stadium", "stadio", "famous artists")
            .any { name -> it.contains(name, true) }
    } + longTail.filter {
        listOf("X Factor", "The Voice", "Sanremo", "Eurovision", "Amici", "stadium", "stadio", "famous singer", "TV talent")
            .any { name -> it.contains(name, true) }
    }).distinct()
    private val instruments = (amateur + longTail).filter {
        listOf("guitar", "chitarra", "piano", "instrumental", "strumentale", "orchestra", "jazz", "soul", "folk", "choir", "classical", "acoustic", "alternative rock")
            .any { name -> it.contains(name, true) }
    }.distinct()
    private val independent = (amateur.filterNot { it in stage || it in instruments } +
        listOf("rare cover version", "local singer cover", "tribute band cover", "cover band", "unusual cover")).distinct()
    private val mixed = (longTail + amateur + talent).distinct()
    private val finalDescriptors = listOf("cover", "official audio", "studio version", "live", "remix", "mashup", "AI cover", "foreign language cover")

    /** Six objectives; descriptors never constrain the autonomous final title query. */
    fun queries(title: String, round: Int, originalArtist: String = ""): List<Query> {
        if (title.isBlank()) return emptyList()
        val clean = title.trim()
        val artist = originalArtist.trim()
        val family = when {
            round <= 1 -> official
            round == 2 -> stage
            round == 3 -> independent
            round == 4 -> instruments
            round == 5 -> mixed
            else -> finalDescriptors
        }
        // Strongest quick discovery is always first, also in later rounds.
        val first = if (round == 1) {
            listOf(
                Query("$clean $artist", AiCoverCategory.COVER),
                Query("$clean $artist cover", AiCoverCategory.COVER),
                Query("$clean $artist official", AiCoverCategory.COVER),
                Query("$clean $artist cover studio", AiCoverCategory.COVER),
            )
        } else if (round >= finalRound) {
            listOf(
                Query(clean, AiCoverCategory.COVER),
                Query("$clean ${family.first()}", AiCoverCategory.COVER),
            )
        } else {
            listOf(Query("$clean cover", AiCoverCategory.COVER))
        }
        val offset = 0 // later-round families already rotated above without overlaps
        val targeted = family.indices.map { idx ->
            val keyword = family[(offset + idx) % family.size]
            Query(
                text = "$clean $keyword",
                categoryHint = when {
                    keyword.contains("remix", true) -> AiCoverCategory.REMIX
                    keyword.contains("foreign", true) || keyword.contains("language", true) ||
                        keyword.contains("adaptation", true) -> AiCoverCategory.FOREIGN
                    keyword.contains("live", true) || keyword.contains("concert", true) ||
                        keyword.contains("festival", true) || keyword.contains("stadio", true) ||
                        keyword.contains("stadium", true) || keyword.contains("sanremo", true) ||
                        keyword.contains("eurovision", true) -> AiCoverCategory.LIVE
                    else -> AiCoverCategory.COVER
                },
            )
        }
        return (first + targeted).filter { round >= finalRound || it.text.trim() != clean }
            .distinctBy { it.text.trim().lowercase() }
    }
}
