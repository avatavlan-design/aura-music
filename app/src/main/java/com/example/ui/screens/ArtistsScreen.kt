package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.Artist
import com.example.model.Track
import com.example.ui.components.AlbumArtwork
import com.example.ui.components.SwipeToQueueTrackItem

@Composable
fun ArtistsScreen(
    artists: List<Artist>,
    currentTrackId: String?,
    isPlaying: Boolean,
    onTrackClick: (Track, List<Track>, Int) -> Unit,
    onQueueTrack: (Track) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedArtist by remember { mutableStateOf<Artist?>(null) }

    if (selectedArtist != null) {
        val artist = selectedArtist!!
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedArtist = null }, modifier = Modifier.testTag("artist_back_button")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArtwork(
                            track = artist.tracks.firstOrNull(),
                            size = 90.dp,
                            shapeRadius = 45.dp // round avatar
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = artist.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${artist.trackCount} tracks • ${artist.albumCount} albums",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (artist.tracks.isNotEmpty()) {
                                    onTrackClick(artist.tracks.first(), artist.tracks, 0)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Play Artist")
                        }

                        FilledTonalButton(
                            onClick = {
                                if (artist.tracks.isNotEmpty()) {
                                    val shuffled = artist.tracks.shuffled()
                                    onTrackClick(shuffled.first(), shuffled, 0)
                                }
                            }
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = "Shuffle")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                itemsIndexed(artist.tracks, key = { _, track -> track.id }) { index, track ->
                    SwipeToQueueTrackItem(
                        track = track,
                        isPlaying = isPlaying,
                        isCurrentTrack = track.id == currentTrackId,
                        onTrackClick = { onTrackClick(track, artist.tracks, index) },
                        onQueue = onQueueTrack,
                        onToggleFavorite = onToggleFavorite,
                        onAddToPlaylistClick = { onAddToPlaylist(track) }
                    )
                }
            }
        }
    } else {
        if (artists.isEmpty()) {
            EmptyLibraryNotice(modifier)
            return
        }

        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(artists, key = { it.id }) { artist ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedArtist = artist }
                        .testTag("artist_item_${artist.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArtwork(
                            track = artist.tracks.firstOrNull(),
                            size = 54.dp,
                            shapeRadius = 27.dp
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = artist.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${artist.trackCount} songs • ${artist.albumCount} albums",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}
