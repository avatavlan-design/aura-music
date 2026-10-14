package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Playlist
import com.example.model.Track
import com.example.ui.components.AlbumArtwork
import com.example.ui.components.SwipeToQueueTrackItem

@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    allTracks: List<Track>,
    currentTrackId: String?,
    isPlaying: Boolean,
    onCreatePlaylist: (String) -> Unit,
    onTrackClick: (Track, List<Track>, Int) -> Unit,
    onQueueTrack: (Track) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistTitle by remember { mutableStateOf("") }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistTitle,
                    onValueChange = { newPlaylistTitle = it },
                    placeholder = { Text("Playlist Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("playlist_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistTitle.isNotBlank()) {
                            onCreatePlaylist(newPlaylistTitle.trim())
                            newPlaylistTitle = ""
                            showCreateDialog = false
                        }
                    },
                    modifier = Modifier.testTag("confirm_create_playlist")
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (selectedPlaylist != null) {
        val playlist = playlists.find { it.id == selectedPlaylist?.id } ?: selectedPlaylist!!
        val playlistTracks = remember(playlist.trackIds, allTracks) {
            val trackMap = allTracks.associateBy { it.id }
            playlist.trackIds.mapNotNull { trackMap[it] }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedPlaylist = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = playlist.name,
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
                            track = playlistTracks.firstOrNull(),
                            size = 80.dp,
                            shapeRadius = 16.dp
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${playlistTracks.size} songs",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    if (playlistTracks.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { onTrackClick(playlistTracks.first(), playlistTracks, 0) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Play")
                            }

                            FilledTonalButton(
                                onClick = {
                                    val shuffled = playlistTracks.shuffled()
                                    onTrackClick(shuffled.first(), shuffled, 0)
                                }
                            ) {
                                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                itemsIndexed(playlistTracks, key = { _, track -> track.id }) { index, track ->
                    SwipeToQueueTrackItem(
                        track = track,
                        isPlaying = isPlaying,
                        isCurrentTrack = track.id == currentTrackId,
                        onTrackClick = { onTrackClick(track, playlistTracks, index) },
                        onQueue = onQueueTrack,
                        onToggleFavorite = onToggleFavorite,
                        onAddToPlaylistClick = null
                    )
                }
            }
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Playlists (${playlists.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Button(
                            onClick = { showCreateDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("create_playlist_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New Playlist", fontSize = 13.sp)
                        }
                    }
                }

                items(playlists, key = { it.id }) { pl ->
                    val plTracks = allTracks.filter { pl.trackIds.contains(it.id) }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedPlaylist = pl }
                            .testTag("playlist_item_${pl.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AlbumArtwork(
                                track = plTracks.firstOrNull(),
                                size = 56.dp,
                                shapeRadius = 12.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pl.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${pl.trackIds.size} songs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}
