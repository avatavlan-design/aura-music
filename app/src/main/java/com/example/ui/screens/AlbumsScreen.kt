package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Album
import com.example.model.Track
import com.example.ui.components.AlbumArtwork
import com.example.ui.components.SwipeToQueueTrackItem

@Composable
fun AlbumsScreen(
    albums: List<Album>,
    currentTrackId: String?,
    isPlaying: Boolean,
    onPlayAlbumGapless: (Album, Int) -> Unit,
    onQueueTrack: (Track) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedAlbum by remember { mutableStateOf<Album?>(null) }

    if (selectedAlbum != null) {
        val album = selectedAlbum!!
        AlbumDetailView(
            album = album,
            currentTrackId = currentTrackId,
            isPlaying = isPlaying,
            onBack = { selectedAlbum = null },
            onPlayAlbum = { index -> onPlayAlbumGapless(album, index) },
            onQueueTrack = onQueueTrack,
            onToggleFavorite = onToggleFavorite,
            onAddToPlaylist = onAddToPlaylist
        )
    } else {
        AlbumGridView(
            albums = albums,
            onAlbumClick = { selectedAlbum = it },
            modifier = modifier
        )
    }
}

@Composable
fun AlbumGridView(
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    if (albums.isEmpty()) {
        EmptyLibraryNotice(modifier)
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxSize()
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumCard(album = album, onClick = { onAlbumClick(album) })
        }
    }
}

@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("album_card_${album.id}")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            val sampleTrack = album.tracks.firstOrNull()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                AlbumArtwork(
                    track = sampleTrack,
                    size = 180.dp,
                    shapeRadius = 12.dp,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = album.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = "${album.artist} • ${album.trackCount} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun AlbumDetailView(
    album: Album,
    currentTrackId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayAlbum: (Int) -> Unit,
    onQueueTrack: (Track) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToPlaylist: (Track) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Back Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("album_back_button")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Album Detail",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val isSwipeEnabled = com.example.ui.components.rememberScrollCooldown(listState)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                // Album Banner Hero
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlbumArtwork(
                        track = album.tracks.firstOrNull(),
                        size = 120.dp,
                        shapeRadius = 16.dp
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(
                            text = album.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = album.artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${album.trackCount} songs • ${album.durationFormatted}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Seamless Gapless Album Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF10B981).copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "✓ 0-Delay Seamless Gapless",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }
                    }
                }

                // Play Gapless Album CTA Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { onPlayAlbum(0) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("play_album_gapless_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Play Gapless Album")
                    }

                    FilledTonalButton(
                        onClick = { onPlayAlbum((0 until album.tracks.size).random()) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = "Shuffle")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            itemsIndexed(album.tracks, key = { _, track -> track.id }) { index, track ->
                SwipeToQueueTrackItem(
                    track = track,
                    isPlaying = isPlaying,
                    isCurrentTrack = track.id == currentTrackId,
                    onTrackClick = { onPlayAlbum(index) },
                    onQueue = onQueueTrack,
                    onToggleFavorite = onToggleFavorite,
                    onAddToPlaylistClick = { onAddToPlaylist(track) },
                    isSwipeEnabled = isSwipeEnabled
                )
            }
        }
    }
}
