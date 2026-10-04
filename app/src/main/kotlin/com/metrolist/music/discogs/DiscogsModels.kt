package com.metrolist.music.discogs

internal data class DiscogsReleaseSummary(
    val id: Int,
    val masterId: Int?,
    val title: String,
    val year: Int?,
    val releaseDate: String? = null,
    val country: String?,
    val formats: List<String>,
    val genres: List<String>,
    val styles: List<String>,
    val labels: List<String>,
    val catalogNumber: String?,
    val thumbnailUrl: String?,
    val coverUrl: String?,
)

internal data class DiscogsReleasePage(
    val items: List<DiscogsReleaseSummary>,
    val page: Int,
    val pages: Int,
    val perPage: Int,
    val totalItems: Int,
) {
    val hasNextPage: Boolean
        get() = page < pages
}

internal data class DiscogsCompilationSummary(
    val id: Int,
    val masterId: Int?,
    val title: String,
    val year: Int?,
    val country: String?,
    val formats: List<String>,
    val genres: List<String>,
    val styles: List<String>,
    val labels: List<String>,
    val thumbnailUrl: String?,
    val coverUrl: String?,
)

internal data class DiscogsCompilationPage(
    val items: List<DiscogsCompilationSummary>,
    val page: Int,
    val pages: Int,
    val perPage: Int,
    val totalItems: Int,
) {
    val hasNextPage: Boolean
        get() = page < pages
}

internal data class DiscogsCredit(
    val name: String,
    val role: String,
    val tracks: String? = null,
)

internal data class DiscogsTrack(
    val position: String,
    val title: String,
    val artists: List<String>,
    val durationText: String?,
    val durationSeconds: Int?,
    val credits: List<DiscogsCredit> = emptyList(),
)

internal data class DiscogsVideo(
    val title: String,
    val uri: String,
    val durationSeconds: Int?,
    val description: String?,
)

internal data class DiscogsCompilationDetail(
    val id: Int,
    val masterId: Int?,
    val title: String,
    val year: Int?,
    val releaseDate: String? = null,
    val country: String?,
    val artists: List<String>,
    val labels: List<String>,
    val genres: List<String>,
    val styles: List<String>,
    val formats: List<String> = emptyList(),
    val formatDescriptions: List<String> = emptyList(),
    val coverUrl: String?,
    val tracks: List<DiscogsTrack>,
    val videos: List<DiscogsVideo>,
    val credits: List<DiscogsCredit> = emptyList(),
) {
    val discogsUrl: String
        get() = "https://www.discogs.com/release/$id"
}
