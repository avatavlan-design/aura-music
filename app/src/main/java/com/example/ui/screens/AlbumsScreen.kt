package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Album
import com.example.model.Track
import com.example.ui.components.AlbumArtwork
import com.example.ui.components.SwipeToQueueTrackItem
import kotlin.random.Random

@Composable
fun AlbumsScreen(
    albums: List<Album>,
    currentTrackId: String?,
    isPlaying: Boolean,
    onPlayAlbum: (Album, Int) -> Unit,
    onQueueTrack: (Track) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onSubScreenActiveChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedAlbum by remember { mutableStateOf<Album?>(null) }
    var showRandomDialog by remember { mutableStateOf(false) }
    var randomSelection by remember { mutableStateOf<List<Album>>(emptyList()) }

    val isSubActive = selectedAlbum != null || showRandomDialog
    LaunchedEffect(isSubActive) {
        onSubScreenActiveChanged(isSubActive)
    }

    BackHandler(enabled = showRandomDialog) {
        showRandomDialog = false
    }

    BackHandler(enabled = selectedAlbum != null) {
        selectedAlbum = null
    }

    fun refreshRandomSelection() {
        if (albums.isNotEmpty()) {
            val freshSeed = System.nanoTime() xor System.currentTimeMillis() xor Random.nextLong()
            val freshRng = Random(freshSeed)
            randomSelection = albums.shuffled(freshRng).take(6)
        } else {
            randomSelection = emptyList()
        }
    }

    if (selectedAlbum != null) {
        val album = selectedAlbum!!
        AlbumDetailView(
            album = album,
            currentTrackId = currentTrackId,
            isPlaying = isPlaying,
            onBack = { selectedAlbum = null },
            onPlayAlbum = { index -> onPlayAlbum(album, index) },
            onQueueTrack = onQueueTrack,
            onToggleFavorite = onToggleFavorite,
            onAddToPlaylist = onAddToPlaylist
        )
    } else {
        AlbumGridView(
            albums = albums,
            onAlbumClick = { selectedAlbum = it },
            onOpenRandomPicker = {
                refreshRandomSelection()
                showRandomDialog = true
            },
            modifier = modifier
        )
    }

    if (showRandomDialog) {
        RandomAlbumSelectionDialog(
            randomAlbums = randomSelection,
            onAlbumSelected = { album ->
                showRandomDialog = false
                selectedAlbum = album
            },
            onTryAgain = {
                refreshRandomSelection()
            },
            onDismiss = {
                showRandomDialog = false
            }
        )
    }
}

@Composable
fun AlbumGridView(
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit,
    onOpenRandomPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (albums.isEmpty()) {
        EmptyLibraryNotice(modifier)
        return
    }

    val gridState = rememberLazyGridState()
    var isButtonVisible by remember { mutableStateOf(true) }

    // Scroll listener: scroll down hides button, scroll up reveals it
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta < -8f) {
                    // Scrolling down -> scroll away
                    isButtonVisible = false
                } else if (delta > 8f) {
                    // Scrolling up -> reappear
                    isButtonVisible = true
                }
                return Offset.Zero
            }
        }
    }

    // When scrolled to the very top, always ensure the button is visible
    val isAtTop by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
        }
    }
    LaunchedEffect(isAtTop) {
        if (isAtTop) {
            isButtonVisible = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
    ) {
        // Random Album Selection Banner: scrolls away on scroll down, reappears on scroll up
        AnimatedVisibility(
            visible = isButtonVisible,
            enter = expandVertically(animationSpec = tween(durationMillis = 250)) + fadeIn(animationSpec = tween(durationMillis = 200)),
            exit = shrinkVertically(animationSpec = tween(durationMillis = 250)) + fadeOut(animationSpec = tween(durationMillis = 200))
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Random Album Picker",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Unbiased selection of 6 random albums",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onOpenRandomPicker,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("random_album_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Random Album")
                    }
                }
            }
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(albums, key = { it.id }) { album ->
                AlbumCard(album = album, onClick = { onAlbumClick(album) })
            }
        }
    }
}

@Composable
fun RandomAlbumSelectionDialog(
    randomAlbums: List<Album>,
    onAlbumSelected: (Album) -> Unit,
    onTryAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Shuffle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "Pick a Random Album",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Here are 6 random albums from your library. Select one to play or tap 'Try Again' for a new random set!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (randomAlbums.isEmpty()) {
                    Text(
                        text = "No albums found in your library.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        items(randomAlbums, key = { it.id }) { album ->
                            RandomAlbumPickCard(
                                album = album,
                                onClick = { onAlbumSelected(album) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onTryAgain,
                modifier = Modifier.testTag("random_album_try_again_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Try Again")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("random_album_close_button")
            ) {
                Text("Close")
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun RandomAlbumPickCard(
    album: Album,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("random_album_pick_${album.id}")
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                AlbumArtwork(
                    track = album.tracks.firstOrNull(),
                    size = 100.dp,
                    shapeRadius = 8.dp,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = album.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album.artist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
    BackHandler {
        onBack()
    }

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

                    }
                }

                // Play Album CTA Button
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
                            .testTag("play_album_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Play Album")
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
