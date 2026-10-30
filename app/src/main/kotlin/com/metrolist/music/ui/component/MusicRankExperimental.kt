package com.metrolist.music.ui.component

/** Experimental priority model; scoring is never evidence of song identity. */
internal object MusicRankExperimental {
    const val APP_ID = "it.verlezza.musiclab.musicrank"

    fun enabled(applicationId: String): Boolean = applicationId == APP_ID

    fun score(
        verifiedRelease: Boolean,
        documentedWork: Boolean,
        documentedPerformer: Boolean,
        videoAvailable: Boolean,
        videoTitleMatched: Boolean,
        originalPublication: Boolean,
        credited: Boolean,
    ): Int {
        val values = listOf(
            if (verifiedRelease) 30 else 0,
            if (documentedPerformer) 20 else 0,
            if (documentedWork) 15 else 0,
            if (credited) 10 else 0,
            if (videoAvailable) 8 else 0,
            if (videoTitleMatched) 7 else 0,
            if (originalPublication) 10 else 0,
        )
        return values.sum().coerceIn(0, 100)
    }
}
