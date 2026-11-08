package org.anmon.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.anmon.data.ListeningStatsSnapshot
import org.anmon.data.StatItemEntry
import org.anmon.model.Track
import org.anmon.ui.components.AlbumArtwork

enum class StatsCategory(
    val label: String,
    val icon: ImageVector,
    val tag: String
) {
    SONGS("Songs", Icons.Default.MusicNote, "stats_category_songs"),
    ARTISTS("Artists", Icons.Default.Person, "stats_category_artists"),
    ALBUMS("Albums", Icons.Default.Album, "stats_category_albums"),
    GENRES("Genres", Icons.Default.Category, "stats_category_genres")
}

@Composable
fun StatsScreen(
    stats: ListeningStatsSnapshot,
    allTracks: List<Track>,
    onPlayTrackContext: (Track, List<Track>, Int) -> Unit,
    onResetStats: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(StatsCategory.SONGS) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val trackById = remember(allTracks) {
        allTracks.associateBy { it.id }
    }

    val activeEntries = when (selectedCategory) {
        StatsCategory.SONGS -> stats.songs
        StatsCategory.ARTISTS -> stats.artists
        StatsCategory.ALBUMS -> stats.albums
        StatsCategory.GENRES -> stats.genres
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("stats_screen_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Live Listening Time Hero Card
        item {
            ListeningTimeHeroCard(
                stats = stats,
                onRequestReset = { showResetConfirm = true }
            )
        }

        // 2. Top #1 Spotlights across Songs, Artists, Albums, and Genres
        item {
            TopSpotlightsGrid(
                stats = stats,
                onSelectCategory = { selectedCategory = it }
            )
        }

        // 3. Genre Sonic Spectrum Breakdown Bar (shown when at least one genre is recorded)
        if (stats.genres.isNotEmpty()) {
            item {
                GenreSpectrumCard(
                    genres = stats.genres.take(5),
                    palette = listOf(
                        primaryColor,
                        secondaryColor,
                        tertiaryColor,
                        primaryColor.copy(alpha = 0.65f),
                        secondaryColor.copy(alpha = 0.65f)
                    )
                )
            }
        }

        // 4. Category Selector Chips
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Listening Breakdown",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${activeEntries.size} tracked",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatsCategory.entries.forEach { category ->
                        val isSelected = selectedCategory == category
                        val count = when (category) {
                            StatsCategory.SONGS -> stats.songs.size
                            StatsCategory.ARTISTS -> stats.artists.size
                            StatsCategory.ALBUMS -> stats.albums.size
                            StatsCategory.GENRES -> stats.genres.size
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedCategory = category },
                            label = {
                                Text(
                                    text = "${category.label} ($count)",
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = category.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag(category.tag)
                        )
                    }
                }
            }
        }

        // 5. Ranked Leaderboard Entries or Empty State
        if (activeEntries.isEmpty()) {
            item {
                EmptyStatsPlaceholder(category = selectedCategory)
            }
        } else {
            val maxPlays = activeEntries.maxOfOrNull { it.playCount }?.coerceAtLeast(1) ?: 1
            val maxTimeMs = activeEntries.maxOfOrNull { it.listeningTimeMs }?.coerceAtLeast(1L) ?: 1L

            itemsIndexed(activeEntries, key = { _, entry -> "${selectedCategory.name}_${entry.key}" }) { index, entry ->
                val sampleTrack = entry.trackId?.let { trackById[it] }
                StatLeaderboardItemCard(
                    rank = index + 1,
                    entry = entry,
                    category = selectedCategory,
                    sampleTrack = sampleTrack,
                    maxPlays = maxPlays,
                    maxTimeMs = maxTimeMs,
                    onClick = {
                        when (selectedCategory) {
                            StatsCategory.SONGS -> {
                                val target = sampleTrack ?: allTracks.find {
                                    it.title.equals(entry.title, ignoreCase = true)
                                }
                                if (target != null) {
                                    val idx = allTracks.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
                                    onPlayTrackContext(target, allTracks, idx)
                                }
                            }
                            StatsCategory.ARTISTS -> {
                                val artistTracks = allTracks.filter {
                                    it.artist.equals(entry.title, ignoreCase = true)
                                }
                                if (artistTracks.isNotEmpty()) {
                                    onPlayTrackContext(artistTracks.first(), artistTracks, 0)
                                }
                            }
                            StatsCategory.ALBUMS -> {
                                val albumTracks = org.anmon.data.MusicRepository.sortAlbumTracks(
                                    allTracks.filter { it.album.equals(entry.title, ignoreCase = true) }
                                )
                                if (albumTracks.isNotEmpty()) {
                                    onPlayTrackContext(albumTracks.first(), albumTracks, 0)
                                }
                            }
                            StatsCategory.GENRES -> {
                                val genreTracks = allTracks.filter {
                                    it.genre.equals(entry.title, ignoreCase = true)
                                }
                                if (genreTracks.isNotEmpty()) {
                                    onPlayTrackContext(genreTracks.first(), genreTracks, 0)
                                }
                            }
                        }
                    }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = "Reset Listening Stats?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will clear all recorded play counts and listening time for songs, artists, albums, and genres.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        onResetStats()
                    },
                    modifier = Modifier.testTag("confirm_reset_stats_button")
                ) {
                    Text(
                        text = "Reset",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ListeningTimeHeroCard(
    stats: ListeningStatsSnapshot,
    onRequestReset: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 6.dp,
        border = BorderStroke(1.dp, primary.copy(alpha = 0.28f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stats_hero_card")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            primaryContainer.copy(alpha = 0.65f),
                            surfaceVariant.copy(alpha = 0.55f),
                            MaterialTheme.colorScheme.surface
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(900f, 600f)
                    )
                )
                .drawBehind {
                    // Subtle theme-colored sonic rings in the top-right corner
                    val center = Offset(size.width * 0.88f, size.height * 0.22f)
                    drawCircle(
                        color = primary.copy(alpha = 0.10f),
                        radius = size.minDimension * 0.48f,
                        center = center,
                        style = Stroke(width = 18f)
                    )
                    drawCircle(
                        color = secondary.copy(alpha = 0.12f),
                        radius = size.minDimension * 0.30f,
                        center = center,
                        style = Stroke(width = 10f)
                    )
                }
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(primary.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Total Listening Time",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Live session & lifetime counter",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (stats.totalTrackPlays > 0 || stats.totalListeningTimeMs > 0L) {
                        OutlinedButton(
                            onClick = onRequestReset,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.testTag("reset_stats_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Reset listening stats",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reset", fontSize = 12.sp)
                        }
                    }
                }

                // Hours / Minutes / Seconds Blocks
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TimeUnitPill(
                        value = stats.hoursPart,
                        unitLabel = "HOURS",
                        accentColor = primary,
                        testTag = "stats_hours_value",
                        modifier = Modifier.weight(1f)
                    )
                    TimeUnitPill(
                        value = stats.minutesPart,
                        unitLabel = "MINUTES",
                        accentColor = secondary,
                        testTag = "stats_minutes_value",
                        modifier = Modifier.weight(1f)
                    )
                    TimeUnitPill(
                        value = stats.secondsPart,
                        unitLabel = "SECONDS",
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        testTag = "stats_seconds_value",
                        modifier = Modifier.weight(1f)
                    )
                }

                // Quick Counter Summary Row
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiniCounterStat(label = "Plays", value = "${stats.totalTrackPlays}")
                        MiniCounterStat(label = "Songs", value = "${stats.songs.size}")
                        MiniCounterStat(label = "Artists", value = "${stats.artists.size}")
                        MiniCounterStat(label = "Albums", value = "${stats.albums.size}")
                        MiniCounterStat(label = "Genres", value = "${stats.genres.size}")
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeUnitPill(
    value: Long,
    unitLabel: String,
    accentColor: Color,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "%02d".format(value),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = accentColor,
                modifier = Modifier.testTag(testTag)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = unitLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun MiniCounterStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun TopSpotlightsGrid(
    stats: ListeningStatsSnapshot,
    onSelectCategory: (StatsCategory) -> Unit
) {
    val topSong = stats.songs.firstOrNull()
    val topArtist = stats.artists.firstOrNull()
    val topAlbum = stats.albums.firstOrNull()
    val topGenre = stats.genres.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SpotlightMiniCard(
                label = "Top Song",
                entry = topSong,
                icon = Icons.Default.MusicNote,
                accentColor = MaterialTheme.colorScheme.primary,
                onClick = { onSelectCategory(StatsCategory.SONGS) },
                modifier = Modifier.weight(1f)
            )
            SpotlightMiniCard(
                label = "Top Artist",
                entry = topArtist,
                icon = Icons.Default.Person,
                accentColor = MaterialTheme.colorScheme.secondary,
                onClick = { onSelectCategory(StatsCategory.ARTISTS) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SpotlightMiniCard(
                label = "Top Album",
                entry = topAlbum,
                icon = Icons.Default.Album,
                accentColor = MaterialTheme.colorScheme.tertiary,
                onClick = { onSelectCategory(StatsCategory.ALBUMS) },
                modifier = Modifier.weight(1f)
            )
            SpotlightMiniCard(
                label = "Top Genre",
                entry = topGenre,
                icon = Icons.Default.AutoAwesome,
                accentColor = MaterialTheme.colorScheme.primary,
                onClick = { onSelectCategory(StatsCategory.GENRES) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SpotlightMiniCard(
    label: String,
    entry: StatItemEntry?,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.22f)),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
            }

            Text(
                text = entry?.title ?: "No plays yet",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = if (entry != null) {
                    "${entry.playCount} plays • ${entry.formattedListeningTime}"
                } else {
                    "Play music to track"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GenreSpectrumCard(
    genres: List<StatItemEntry>,
    palette: List<Color>
) {
    val totalPlays = genres.sumOf { it.playCount }.coerceAtLeast(1)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Genre Distribution",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Top ${genres.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // Proportional Segmented Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp)),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                genres.forEachIndexed { idx, genre ->
                    val weight = (genre.playCount.toFloat() / totalPlays.toFloat()).coerceAtLeast(0.06f)
                    val color = palette[idx % palette.size]
                    Box(
                        modifier = Modifier
                            .weight(weight)
                            .fillMaxHeight()
                            .background(color, RoundedCornerShape(4.dp))
                    )
                }
            }

            // Legend Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                genres.forEachIndexed { idx, genre ->
                    val pct = ((genre.playCount * 100f) / totalPlays).toInt().coerceIn(1, 100)
                    val color = palette[idx % palette.size]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                        Text(
                            text = "${genre.title} ($pct%)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatLeaderboardItemCard(
    rank: Int,
    entry: StatItemEntry,
    category: StatsCategory,
    sampleTrack: Track?,
    maxPlays: Int,
    maxTimeMs: Long,
    onClick: () -> Unit
) {
    val playFraction = (entry.playCount.toFloat() / maxPlays.toFloat()).coerceIn(0.08f, 1f)
    val timeFraction = (entry.listeningTimeMs.toFloat() / maxTimeMs.toFloat()).coerceIn(0.08f, 1f)
    val targetProgress = maxOf(playFraction, timeFraction)

    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "stat_bar_progress"
    )

    val rankAccent = when (rank) {
        1 -> MaterialTheme.colorScheme.primary
        2 -> MaterialTheme.colorScheme.secondary
        3 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        color = if (rank <= 3) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
        },
        shape = RoundedCornerShape(16.dp),
        border = if (rank == 1) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
        } else null,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("stat_item_${category.name.lowercase()}_$rank")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rank Badge
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (rank <= 3) rankAccent.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surface
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "#$rank",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (rank <= 3) rankAccent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Artwork or Category Icon
                if (sampleTrack != null) {
                    AlbumArtwork(
                        track = sampleTrack,
                        size = 44.dp,
                        shapeRadius = 10.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = category.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }

                // Title & Subtitle
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (entry.subtitle.isNotBlank()) {
                        Text(
                            text = entry.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Play Count & Listening Time Column
                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (entry.playCount == 1) "1 play" else "${entry.playCount} plays",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = entry.formattedListeningTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Theme-colored proportional bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun EmptyStatsPlaceholder(category: StatsCategory) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Equalizer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(
                text = "No ${category.label} Counted Yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Start playing any track in your library to see live play counts and listening duration in hours, minutes, and seconds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
