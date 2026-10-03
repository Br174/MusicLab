package com.metrolist.music.ui.component

/**
 * LAB26 normalization for provider-controlled UI labels.
 *
 * Artist, album and song titles are never translated here. This helper is only for
 * generic section/navigation labels coming from YouTube Music or Spotify.
 */
internal fun italianizeUiLabel(raw: String): String {
    val text = raw.trim()
    if (text.isBlank()) return text

    val lower = text.lowercase()
    STATIC[lower]?.let { return it }

    val patterns = listOf(
        Regex("^because you like (.+)$", RegexOption.IGNORE_CASE) to { m: MatchResult -> "Perché ti piace ${m.groupValues[1]}" },
        Regex("^because you listened to (.+)$", RegexOption.IGNORE_CASE) to { m: MatchResult -> "Perché hai ascoltato ${m.groupValues[1]}" },
        Regex("^similar to (.+)$", RegexOption.IGNORE_CASE) to { m: MatchResult -> "Simili a ${m.groupValues[1]}" },
        Regex("^more like (.+)$", RegexOption.IGNORE_CASE) to { m: MatchResult -> "Altri come ${m.groupValues[1]}" },
        Regex("^more from (.+)$", RegexOption.IGNORE_CASE) to { m: MatchResult -> "Altro da ${m.groupValues[1]}" },
        Regex("^from (.+)$", RegexOption.IGNORE_CASE) to { m: MatchResult -> "Da ${m.groupValues[1]}" },
        Regex("^recommended (.+)$", RegexOption.IGNORE_CASE) to { m: MatchResult -> "Consigliati: ${m.groupValues[1]}" },
        Regex("^best of (.+)$", RegexOption.IGNORE_CASE) to { m: MatchResult -> "Il meglio di ${m.groupValues[1]}" },
    )
    patterns.forEach { (regex, transform) ->
        regex.matchEntire(text)?.let { return transform(it) }
    }
    return text
}

private val STATIC = mapOf(
    "quick picks" to "Scelte rapide",
    "listen again" to "Ascolta di nuovo",
    "recently played" to "Riprodotti di recente",
    "recommended for you" to "Consigliati per te",
    "recommended music videos" to "Video musicali consigliati",
    "recommended albums" to "Album consigliati",
    "recommended playlists" to "Playlist consigliate",
    "songs of the week" to "Brani della settimana",
    "song of the week" to "Brano della settimana",
    "this week's songs" to "Brani della settimana",
    "trending" to "Di tendenza",
    "trending songs" to "Brani di tendenza",
    "trending music" to "Musica di tendenza",
    "new releases" to "Nuove uscite",
    "new release" to "Nuova uscita",
    "new albums & singles" to "Nuovi album e singoli",
    "new albums and singles" to "Nuovi album e singoli",
    "mixed for you" to "Mix per te",
    "mixes for you" to "Mix per te",
    "your mixes" to "I tuoi mix",
    "your top mixes" to "I tuoi mix migliori",
    "your top songs" to "I tuoi brani più ascoltati",
    "your top tracks" to "I tuoi brani più ascoltati",
    "your top artists" to "I tuoi artisti più ascoltati",
    "your playlists" to "Le tue playlist",
    "your favorites" to "I tuoi preferiti",
    "favorites" to "Preferiti",
    "all-time favorites" to "Preferiti di sempre",
    "discover" to "Scopri",
    "discover picks" to "Scelte da scoprire",
    "made for you" to "Creati per te",
    "for you" to "Per te",
    "albums for you" to "Album per te",
    "artists for you" to "Artisti per te",
    "playlists for you" to "Playlist per te",
    "today's biggest hits" to "I successi di oggi",
    "popular" to "Popolari",
    "popular songs" to "Brani popolari",
    "popular albums" to "Album popolari",
    "popular releases" to "Uscite popolari",
    "fans might also like" to "Potrebbero piacerti anche",
    "music videos" to "Video musicali",
    "videos" to "Video",
    "albums" to "Album",
    "singles" to "Singoli",
    "songs" to "Brani",
    "artists" to "Artisti",
    "playlists" to "Playlist",
    "episodes" to "Episodi",
    "podcasts" to "Podcast",
    "live performances" to "Esibizioni dal vivo",
    "live" to "Dal vivo",
    "community playlists" to "Playlist della comunità",
    "moods & genres" to "Atmosfere e generi",
    "moods and genres" to "Atmosfere e generi",
)
