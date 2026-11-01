package com.metrolist.music.ui.screens.search

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.spotify.Spotify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight, empty-query-only Explore landing screen.
 * Fixed color tiles are original, locally rendered designs, not mislabelled
 * Spotify cover art. Canonical Spotify URLs/identities are resolved on tap.
 * No network calls run when entering this screen or when scrolling the grid.
 */
@Composable
internal fun MusicLabSearchExploreHome(
    navController: NavController,
    pureBlack: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var openingChart by remember { mutableStateOf<String?>(null) }

    // R29: All charts navigate internally, including previously unsupported
    // country cards. No automatic Spotify intent, guessed ID or silent redirect.
    fun openChart(chart: ExploreChart) {
        navController.navigate("musiclab_chart/" + Uri.encode(chart.title))
    }

    val miniPlayerBottomInset = LocalPlayerAwareWindowInsets.current
        .only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(
            if (pureBlack) Color.Black else MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = miniPlayerBottomInset + 16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item(key = "explore_tabs") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExploreTabChip("Classifiche", selectedTab == 0,
                    { selectedTab = 0 }, Modifier.weight(1f))
                ExploreTabChip("Generi musicali", selectedTab == 1,
                    { selectedTab = 1 }, Modifier.weight(1f))
                ExploreTabChip("Hit Parade", false,
                    { navController.navigate("musiclab_hit_parade") }, Modifier.weight(1f))
            }
        }
        if (selectedTab == 0) {
            item(key = "explore_featured_heading") { ExploreHeading("In primo piano") }
            items(MusicLabSearchExploreCatalog.featured.chunked(2), key = { "featured_" + it.first().title }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { chart ->
                        ExploreChartTile(chart, Modifier.weight(1f),
                            isLoading = openingChart == chart.title,
                            onClick = { openChart(chart) })
                    }
                }
            }
            item(key = "explore_all_countries") {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    ExploreHeading("Classifiche per Paese")
                    Text("Fonte verificata per ogni Paese: Last.fm o YouTube Music",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            items(MusicLabSearchExploreCatalog.countries.chunked(3),
                key = { "countries_" + it.first().shortLabel }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    row.forEach { chart ->
                        ExploreChartTile(chart, Modifier.weight(1f),
                            isLoading = openingChart == chart.title,
                            onClick = { openChart(chart) }, compact = true)
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            item(key = "explore_genres_heading") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ExploreHeading("Generi musicali")
                    Text("Scegli un genere per cercare brani e artisti in MusicLab",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(MusicLabSearchExploreCatalog.genres.chunked(2),
                key = { "genre_" + it.first().name }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { genre ->
                        ExploreGenreTile(genre, Modifier.weight(1f)) {
                            navController.navigate(SearchRoutes.resultRoute(genre.search))
                        }
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ExploreHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold)
}

@Composable
private fun ExploreTabChip(label: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(40.dp))
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer)
        .clickable(onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp)
    }
}

@Composable
private fun ExploreChartTile(chart: ExploreChart, modifier: Modifier,
    isLoading: Boolean, onClick: () -> Unit, compact: Boolean = false) {
    val accent = Color(chart.accent)
    Column(modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.verticalGradient(listOf(
                accent.copy(alpha = 0.92f), accent, Color(0xFF22375E))))
            .padding(if (compact) 5.dp else 12.dp),
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(if (chart.spotifySearch.startsWith("Top Songs")) "Brani" else "Top 50",
                    color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 18.sp else 27.sp,
                    textAlign = TextAlign.Center, maxLines = 2)
                Box(Modifier.fillMaxWidth(0.55f).height(1.dp)
                    .background(Color.White.copy(alpha = 0.46f)))
                Text(chart.shortLabel, color = Color.White.copy(alpha = 0.92f),
                    fontSize = if (compact) 8.sp else 10.sp,
                    maxLines = 2, textAlign = TextAlign.Center,
                    lineHeight = if (compact) 10.sp else 12.sp,
                    fontWeight = FontWeight.Bold)
            }
        }
        Text(chart.title, style = if (compact) MaterialTheme.typography.bodySmall
            else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        if (!compact || isLoading) {
            Text(if (isLoading) "Apertura..." else "Classifiche",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ExploreGenreTile(genre: ExploreGenre, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().aspectRatio(1.7f)
        .clip(RoundedCornerShape(10.dp))
        .background(Brush.horizontalGradient(listOf(
            Color(genre.accent), Color(genre.accent).copy(alpha = 0.66f))))
        .clickable(onClick = onClick)
        .padding(14.dp), contentAlignment = Alignment.CenterStart) {
        Text(genre.name, color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, maxLines = 2)
    }
}
