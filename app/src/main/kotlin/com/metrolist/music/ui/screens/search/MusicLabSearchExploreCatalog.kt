package com.metrolist.music.ui.screens.search

import com.metrolist.spotify.models.SpotifyPlaylist

/**
 * R28: lightweight directory of search targets. A tile is never treated as a
 * verified Spotify playlist until a canonical ID is known or an exact result
 * with Spotify's real account ID is returned. No invented playlist IDs.
 */
internal data class ExploreChart(
    val title: String,
    val spotifySearch: String,
    val shortLabel: String,
    val accent: Int,
    val confirmedSpotifyId: String? = null,
)

internal data class ExploreGenre(val name: String, val search: String, val accent: Int)

internal object MusicLabSearchExploreCatalog {
    val featured = listOf(
        ExploreChart("Brani preferiti Italia", "Top Songs - Italy", "ITALIA", 0xFF8EAFBB.toInt()),
        ExploreChart("Top 50 Italia", "Top 50 - Italy", "ITALY", 0xFF234D8D.toInt(),
            "37i9dQZEVXbIQnj7RRhdSX"),
        ExploreChart("Top 50 Globale", "Top 50 - Global", "GLOBAL", 0xFF137B7A.toInt(),
            "37i9dQZEVXbMDoHDwVN2tF"),
        ExploreChart("Brani preferiti globali", "Top Songs - Global", "GLOBAL", 0xFFB87850.toInt()),
    )

    private data class Country(val italian: String, val english: String, val accent: Int)

    // All countries and territories shown in the user-supplied Spotify chart
    // screenshots, sorted in Italian without changing any published playlists.
    private val countryDefinitions = listOf(
        Country("Arabia Saudita", "Saudi Arabia", 0xFF138375.toInt()),
        Country("Argentina", "Argentina", 0xFF6EA6E7.toInt()),
        Country("Australia", "Australia", 0xFFF2B542.toInt()),
        Country("Austria", "Austria", 0xFFEB6761.toInt()),
        Country("Belgio", "Belgium", 0xFFFB663F.toInt()),
        Country("Bielorussia", "Belarus", 0xFFBE3041.toInt()),
        Country("Bolivia", "Bolivia", 0xFF078472.toInt()),
        Country("Brasile", "Brazil", 0xFFE7A72A.toInt()),
        Country("Bulgaria", "Bulgaria", 0xFF078C76.toInt()),
        Country("Canada", "Canada", 0xFFB12648.toInt()),
        Country("Cile", "Chile", 0xFFB83F3B.toInt()),
        Country("Colombia", "Colombia", 0xFFF1B329.toInt()),
        Country("Corea del Sud", "South Korea", 0xFFC32B4C.toInt()),
        Country("Costa Rica", "Costa Rica", 0xFFD63C56.toInt()),
        Country("Danimarca", "Denmark", 0xFFF77655.toInt()),
        Country("Ecuador", "Ecuador", 0xFFEAB845.toInt()),
        Country("Egitto", "Egypt", 0xFF865363.toInt()),
        Country("El Salvador", "El Salvador", 0xFF4580D6.toInt()),
        Country("Emirati Arabi Uniti", "United Arab Emirates", 0xFF0A776F.toInt()),
        Country("Estonia", "Estonia", 0xFF27345A.toInt()),
        Country("Filippine", "Philippines", 0xFF325BB6.toInt()),
        Country("Finlandia", "Finland", 0xFF334D80.toInt()),
        Country("Francia", "France", 0xFF294D87.toInt()),
        Country("Germania", "Germany", 0xFFDB3D4B.toInt()),
        Country("Giappone", "Japan", 0xFFF66A58.toInt()),
        Country("Grecia", "Greece", 0xFF405F8C.toInt()),
        Country("Guatemala", "Guatemala", 0xFF8CB5EC.toInt()),
        Country("Honduras", "Honduras", 0xFF4268C2.toInt()),
        Country("Hong Kong", "Hong Kong", 0xFFF37745.toInt()),
        Country("India", "India", 0xFF598D71.toInt()),
        Country("Indonesia", "Indonesia", 0xFFEA4861.toInt()),
        Country("Irlanda", "Ireland", 0xFF117B60.toInt()),
        Country("Islanda", "Iceland", 0xFF335482.toInt()),
        Country("Israele", "Israel", 0xFF394D83.toInt()),
        Country("Italia", "Italy", 0xFF234D8D.toInt()),
        Country("Kazakistan", "Kazakhstan", 0xFF70B1F6.toInt()),
        Country("Lettonia", "Latvia", 0xFFAC2847.toInt()),
        Country("Lituania", "Lithuania", 0xFFB64940.toInt()),
        Country("Lussemburgo", "Luxembourg", 0xFF9B3360.toInt()),
        Country("Malesia", "Malaysia", 0xFF7239F3.toInt()),
        Country("Marocco", "Morocco", 0xFF8D304A.toInt()),
        Country("Messico", "Mexico", 0xFF087E6B.toInt()),
        Country("Nicaragua", "Nicaragua", 0xFF305894.toInt()),
        Country("Nigeria", "Nigeria", 0xFF5B448C.toInt()),
        Country("Norvegia", "Norway", 0xFF276B9A.toInt()),
        Country("Nuova Zelanda", "New Zealand", 0xFF344C81.toInt()),
        Country("Paesi Bassi", "Netherlands", 0xFFF87342.toInt()),
        Country("Pakistan", "Pakistan", 0xFFD6CF51.toInt()),
        Country("Panama", "Panama", 0xFFD34B63.toInt()),
        Country("Paraguay", "Paraguay", 0xFFF56773.toInt()),
        Country("Perù", "Peru", 0xFFF9744F.toInt()),
        Country("Polonia", "Poland", 0xFFEE5049.toInt()),
        Country("Portogallo", "Portugal", 0xFF7A384B.toInt()),
        Country("Regno Unito", "United Kingdom", 0xFFBD3050.toInt()),
        Country("Repubblica Ceca", "Czech Republic", 0xFFF06B65.toInt()),
        Country("Repubblica Dominicana", "Dominican Republic", 0xFF344F88.toInt()),
        Country("Romania", "Romania", 0xFF45698B.toInt()),
        Country("Singapore", "Singapore", 0xFFB42B48.toInt()),
        Country("Slovacchia", "Slovakia", 0xFF315AC0.toInt()),
        Country("Spagna", "Spain", 0xFFF18A39.toInt()),
        Country("Stati Uniti", "USA", 0xFFDC304E.toInt()),
        Country("Sudafrica", "South Africa", 0xFF0F796A.toInt()),
        Country("Svezia", "Sweden", 0xFF254179.toInt()),
        Country("Svizzera", "Switzerland", 0xFFEF4657.toInt()),
        Country("Taiwan", "Taiwan", 0xFFCE3A54.toInt()),
        Country("Thailandia", "Thailand", 0xFFEF3C56.toInt()),
        Country("Turchia", "Turkey", 0xFFF45248.toInt()),
        Country("Ucraina", "Ukraine", 0xFF4369C9.toInt()),
        Country("Ungheria", "Hungary", 0xFFB43D58.toInt()),
        Country("Uruguay", "Uruguay", 0xFF3761AE.toInt()),
        Country("Venezuela", "Venezuela", 0xFFED543C.toInt()),
        Country("Vietnam", "Vietnam", 0xFFEE4854.toInt()),
    )

    val countries: List<ExploreChart> = countryDefinitions.map {
        ExploreChart("Top 50 " + it.italian, "Top 50 - " + it.english,
            it.english.uppercase(java.util.Locale.ROOT), it.accent,
            if (it.english == "Italy") "37i9dQZEVXbIQnj7RRhdSX" else null)
    }

    val genres = listOf(
        ExploreGenre("Pop", "musica pop", 0xFFBA4772.toInt()),
        ExploreGenre("Rock", "musica rock", 0xFFBA6844.toInt()),
        ExploreGenre("Dance e EDM", "musica dance EDM", 0xFF526BC2.toInt()),
        ExploreGenre("Jazz", "jazz", 0xFF8260B0.toInt()),
        ExploreGenre("Classica", "musica classica", 0xFF577C70.toInt()),
        ExploreGenre("Rap e Hip-Hop", "hip hop rap", 0xFF976143.toInt()),
        ExploreGenre("Anni 80", "musica anni 80", 0xFF9859A4.toInt()),
        ExploreGenre("Musica italiana", "musica italiana", 0xFF379281.toInt()),
        ExploreGenre("Blues", "blues", 0xFF5A719C.toInt()),
        ExploreGenre("Soul e R&B", "soul r&b", 0xFFBD586B.toInt()),
        ExploreGenre("Reggae", "reggae", 0xFF3F9570.toInt()),
        ExploreGenre("Metal", "heavy metal", 0xFF4D4F66.toInt()),
        ExploreGenre("Indie", "indie", 0xFF6C6DAD.toInt()),
        ExploreGenre("Latina", "musica latina", 0xFFE48656.toInt()),
        ExploreGenre("Elettronica", "musica elettronica", 0xFF3977A4.toInt()),
        ExploreGenre("Folk", "musica folk", 0xFF9D8866.toInt()),
    )


    /**
     * R29: ISO country mapping is independent of colored display cards.
     * A 72-card catalog is navigable, but a country never inherits another
     * country's chart when its own source is unavailable.
     */
    fun countryCode(chart: ExploreChart): String? {
        if (chart.title == "Top 50 Globale" || chart.title == "Brani preferiti globali") return null
        val name = if (chart.title == "Brani preferiti Italia") "Italy" else
            chart.spotifySearch.removePrefix("Top 50 - ").trim()
        val aliases = mapOf(
            "USA" to "US", "United Kingdom" to "GB",
            "South Korea" to "KR", "Czech Republic" to "CZ",
            "United Arab Emirates" to "AE", "Vietnam" to "VN",
            "Hong Kong" to "HK", "Taiwan" to "TW"
        )
        aliases[name]?.let { return it }
        return java.util.Locale.getISOCountries().firstOrNull { code ->
            java.util.Locale("", code).getDisplayCountry(java.util.Locale.ENGLISH)
                .equals(name, ignoreCase = true)
        }
    }

    fun lastFmCountry(chart: ExploreChart): String? =
        if (chart.title.contains("Globale", ignoreCase = true)) null
        else if (chart.title == "Brani preferiti Italia") "Italy"
        else chart.spotifySearch.removePrefix("Top 50 - ").trim()

    /** Do not mistake user-made lookalike charts for official Spotify data. */
    fun officialMatch(candidates: List<SpotifyPlaylist>, chart: ExploreChart): SpotifyPlaylist? =
        candidates.firstOrNull { playlist ->
            playlist.owner?.id?.equals("spotify", ignoreCase = true) == true &&
                playlist.name.trim().equals(chart.spotifySearch, ignoreCase = true) &&
                playlist.id.matches(Regex("[A-Za-z0-9]{22}"))
        }
}
