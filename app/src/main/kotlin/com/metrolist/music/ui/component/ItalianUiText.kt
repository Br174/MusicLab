package com.metrolist.music.ui.component

import java.util.Locale

/**
 * LAB26 display-only normalizer for dynamic provider section titles.
 * It is intentionally applied only to UI headings/chips, never to song/album/artist names.
 */
internal fun italianizeDynamicUiText(raw: String?): String {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return value

    val lower = value.lowercase(Locale.ROOT)
    exactItalianTitles[lower]?.let { return it }

    val becauseYouLike = Regex("^because you like\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(value)
    if (becauseYouLike != null) return "Perché ti piace ${becauseYouLike.groupValues[1]}"

    val becauseYouListen = Regex("^because you (?:listen|listened) to\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(value)
    if (becauseYouListen != null) return "Perché ascolti ${becauseYouListen.groupValues[1]}"

    val similarTo = Regex("^(?:similar to|more like)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(value)
    if (similarTo != null) return "Simili a ${similarTo.groupValues[1]}"

    val fansAlsoLike = Regex("^fans also like\\s*(.*)$", RegexOption.IGNORE_CASE).matchEntire(value)
    if (fansAlsoLike != null) {
        val tail = fansAlsoLike.groupValues[1].trim()
        return if (tail.isBlank()) "Piace anche ai fan" else "Piace anche ai fan di $tail"
    }

    val mixesFor = Regex("^mixes? for you$", RegexOption.IGNORE_CASE).matchEntire(value)
    if (mixesFor != null) return "Mix per te"

    return value
}

private val exactItalianTitles = mapOf(
    "songs of the week" to "Brani della settimana",
    "song of the week" to "Brano della settimana",
    "this week's songs" to "Brani della settimana",
    "weekly songs" to "Brani della settimana",
    "discover weekly" to "Scoperte della settimana",
    "release radar" to "Radar nuove uscite",
    "your top tracks" to "I tuoi brani più ascoltati",
    "your top songs" to "I tuoi brani più ascoltati",
    "your top artists" to "I tuoi artisti più ascoltati",
    "all-time favorites" to "Preferiti di sempre",
    "your favorites" to "I tuoi preferiti",
    "more artists you love" to "Altri artisti che ami",
    "your playlists" to "Le tue playlist",
    "new releases" to "Nuove uscite",
    "new releases for you" to "Nuove uscite per te",
    "new albums & singles" to "Nuovi album e singoli",
    "made for you" to "Creato per te",
    "recommended for you" to "Consigliati per te",
    "recommendations for you" to "Consigliati per te",
    "recommended" to "Consigliati",
    "recently played" to "Ascoltati di recente",
    "listen again" to "Ascolta di nuovo",
    "quick picks" to "Scelte rapide",
    "trending" to "Di tendenza",
    "trending songs" to "Brani di tendenza",
    "charts" to "Classifiche",
    "popular" to "Popolari",
    "popular releases" to "Uscite popolari",
    "music to get you started" to "Musica per iniziare",
    "music videos" to "Video musicali",
    "recommended music videos" to "Video musicali consigliati",
    "forgotten favorites" to "Preferiti dimenticati",
    "keep listening" to "Continua ad ascoltare",
    "from the community" to "Dalla community",
    "moods & genres" to "Generi e atmosfere",
    "moods and genres" to "Generi e atmosfere",
    "podcasts" to "Podcast",
    "your shows" to "I tuoi programmi",
    "episodes for later" to "Episodi da ascoltare",
    "new episodes" to "Nuovi episodi",
    "top picks" to "Scelte migliori",
    "for you" to "Per te",
    "following" to "Seguiti",
    "discover" to "Scopri",
)
