package com.metrolist.music.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** R49: permanently visible and readable chart context, separated from its date. */
internal object MusicLabHistoricalHeaderPolicy {
    fun category(mode: MusicLabHistoricalChartMode): String =
        if (mode.isAlbum) "Hit Parade album" else "Hit Parade singoli"

    fun date(mode: MusicLabHistoricalChartMode, millis: Long): String =
        if (mode.annual) Calendar.getInstance().apply {
            timeInMillis = millis
        }.get(Calendar.YEAR).toString()
        else SimpleDateFormat("d MMMM yyyy", Locale.ITALIAN).format(Date(millis))
}

/**
 * Two visually separate lines avoid running title and date together, especially
 * on narrow Android screens. TopAppBar stays fixed during scroll and keeps the
 * chart's real loaded date when available.
 */
@Composable
internal fun MusicLabHistoricalHeaderTitle(
    mode: MusicLabHistoricalChartMode,
    dateMillis: Long,
) {
    val dateLabel = remember(mode, dateMillis) {
        MusicLabHistoricalHeaderPolicy.date(mode, dateMillis)
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = MusicLabHistoricalHeaderPolicy.category(mode),
            fontSize = 17.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = dateLabel,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
