package com.metrolist.music.utils

import java.util.Locale

/**
 * LAB26 display-only normalization for provider-owned section headings.
 *
 * Song, album, playlist and artist proper names never pass through this helper.
 * YouTube is also requested with an Italian locale; this map covers provider
 * headings that can still arrive in English because of account/server caches.
 */
fun italianizeProviderUiText(raw: String): String {
    val text = raw.trim()
    if (text.isBlank()) return text

    val key = text
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
        .trim()

    exactProviderItalian[key]?.let { return it }

    val patterns = listOf(
        "because you like " to "Perché ti piace ",
        "because you listened to " to "Perché hai ascoltato ",
        "because you listen to " to "Perché ascolti ",
        "similar to " to "Simili a ",
        "more like " to "Altri come ",
        "inspired by " to "Ispirati a ",
        "fans also like " to "Ai fan piacciono anche ",
        "made for " to "Creato per ",
        "mixes for " to "Mix per ",
        "recommended based on " to "Consigliati in base a ",
    )
    patterns.firstOrNull { key.startsWith(it.first) }?.let { (english, italian) ->
        return italian + text.drop(english.length)
    }

    // Conservative phrase replacements for section headings only. This keeps
    // unknown proper-name fragments intact while translating the UI wording.
    var translated = text
    val replacements = listOf(
        Regex("(?i)\\bsongs of the week\\b") to "Brani della settimana",
        Regex("(?i)\\bthis week in music\\b") to "Questa settimana in musica",
        Regex("(?i)\\byour week in music\\b") to "La tua settimana in musica",
        Regex("(?i)\\bnew releases\\b") to "Nuove uscite",
        Regex("(?i)\\brecently played\\b") to "Ascoltati di recente",
        Regex("(?i)\\bquick picks\\b") to "Selezioni rapide",
        Regex("(?i)\\brecommended for you\\b") to "Consigliati per te",
        Regex("(?i)\\bmade for you\\b") to "Creati per te",
        Regex("(?i)\\btrending songs\\b") to "Brani di tendenza",
        Regex("(?i)\\btrending\\b") to "Di tendenza",
        Regex("(?i)\\blisten again\\b") to "Ascolta di nuovo",
        Regex("(?i)\\byour mixes\\b") to "I tuoi mix",
        Regex("(?i)\\bmixes for you\\b") to "Mix per te",
        Regex("(?i)\\bdiscover weekly\\b") to "Scoperte della settimana",
        Regex("(?i)\\brelease radar\\b") to "Radar delle uscite",
        Regex("(?i)\\bpopular albums and singles\\b") to "Album e singoli popolari",
        Regex("(?i)\\bpopular\\b") to "Popolari",
        Regex("(?i)\\bnew music\\b") to "Nuova musica",
        Regex("(?i)\\bcharts\\b") to "Classifiche",
        Regex("(?i)\\bmoods & genres\\b") to "Atmosfere e generi",
        Regex("(?i)\\bmoods and genres\\b") to "Atmosfere e generi",
    )
    replacements.forEach { (regex, value) ->
        translated = translated.replace(regex, value)
    }
    return translated
}

private val exactProviderItalian = mapOf(
    "your top tracks" to "I tuoi brani più ascoltati",
    "your top songs" to "I tuoi brani più ascoltati",
    "your top artists" to "I tuoi artisti più ascoltati",
    "all-time favorites" to "Preferiti di sempre",
    "more artists you love" to "Altri artisti che potrebbero piacerti",
    "your playlists" to "Le tue playlist",
    "new releases" to "Nuove uscite",
    "for you" to "Per te",
    "following" to "Seguiti",
    "discover" to "Scopri",
    "recently played" to "Ascoltati di recente",
    "quick picks" to "Selezioni rapide",
    "songs of the week" to "Brani della settimana",
    "songs this week" to "Brani di questa settimana",
    "this week" to "Questa settimana",
    "trending songs" to "Brani di tendenza",
    "trending" to "Di tendenza",
    "listen again" to "Ascolta di nuovo",
    "recommended for you" to "Consigliati per te",
    "made for you" to "Creati per te",
    "your mixes" to "I tuoi mix",
    "mixes for you" to "Mix per te",
    "discover weekly" to "Scoperte della settimana",
    "release radar" to "Radar delle uscite",
    "popular albums and singles" to "Album e singoli popolari",
    "popular" to "Popolari",
    "new music" to "Nuova musica",
    "charts" to "Classifiche",
    "moods & genres" to "Atmosfere e generi",
    "moods and genres" to "Atmosfere e generi",
    "recommended music" to "Musica consigliata",
    "recommended albums" to "Album consigliati",
    "recommended playlists" to "Playlist consigliate",
    "your favorite music" to "La tua musica preferita",
    "music to get you started" to "Musica per iniziare",
)
