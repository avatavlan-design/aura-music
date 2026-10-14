package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.LibraryTab
import com.example.model.Track
import com.example.ui.components.CategoryTopBar
import com.example.ui.components.NowPlayingModalSheet
import com.example.ui.components.PersistentMiniPlayer
import com.example.ui.screens.AlbumsScreen
import com.example.ui.screens.ArtistsScreen
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.FoldersScreen
import com.example.ui.screens.PlaylistsScreen
import com.example.ui.screens.SongsScreen

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val isNowPlayingExpanded by viewModel.isNowPlayingExpanded.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()

    val playbackState by viewModel.playbackState.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()
    val filteredTracks by viewModel.filteredTracks.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val favoriteTracks by viewModel.favoriteTracks.collectAsState()
    val includedFolders by viewModel.includedFolders.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var trackForPlaylist by remember { mutableStateOf<Track?>(null) }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    // Predictive back / BackHandler handling
    BackHandler(enabled = isNowPlayingExpanded) {
        viewModel.setNowPlayingExpanded(false)
    }

    BackHandler(enabled = isSearchActive && !isNowPlayingExpanded) {
        viewModel.toggleSearch()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CategoryTopBar(
                selectedTab = selectedTab,
                onTabSelected = { viewModel.selectTab(it) },
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                isSearchActive = isSearchActive,
                onToggleSearch = { viewModel.toggleSearch() },
                isDarkMode = isDarkMode,
                onToggleTheme = { viewModel.toggleTheme() },
                isScanning = isScanning,
                onRescan = { viewModel.rescanLibrary() }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding()) {
                if (playbackState.currentTrack != null) {
                    PersistentMiniPlayer(
                        playbackState = playbackState,
                        onExpandClick = { viewModel.setNowPlayingExpanded(true) },
                        onPlayPauseClick = { viewModel.playbackManager.playPause() },
                        onNextClick = { viewModel.playbackManager.skipToNext() }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                LibraryTab.TRACKS -> {
                    SongsScreen(
                        tracks = if (isSearchActive && searchQuery.isNotEmpty()) filteredTracks else allTracks,
                        currentTrackId = playbackState.currentTrack?.id,
                        isPlaying = playbackState.isPlaying,
                        onTrackClick = { track, list, index -> viewModel.playTrack(track, list, index) },
                        onQueueTrack = { viewModel.queueTrack(it) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onAddToPlaylist = { trackForPlaylist = it },
                        onPlayAll = { list -> viewModel.playTrack(list.first(), list, 0) },
                        onShuffleAll = { list ->
                            val shuffled = list.shuffled()
                            viewModel.playTrack(shuffled.first(), shuffled, 0)
                        }
                    )
                }
                LibraryTab.ALBUMS -> {
                    val displayedAlbums = if (isSearchActive && searchQuery.isNotEmpty()) {
                        val q = searchQuery.trim().lowercase()
                        albums.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
                    } else albums

                    AlbumsScreen(
                        albums = displayedAlbums,
                        currentTrackId = playbackState.currentTrack?.id,
                        isPlaying = playbackState.isPlaying,
                        onPlayAlbumGapless = { album, index -> viewModel.playAlbumGapless(album, index) },
                        onQueueTrack = { viewModel.queueTrack(it) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onAddToPlaylist = { trackForPlaylist = it }
                    )
                }
                LibraryTab.ARTISTS -> {
                    val displayedArtists = if (isSearchActive && searchQuery.isNotEmpty()) {
                        val q = searchQuery.trim().lowercase()
                        artists.filter { it.name.lowercase().contains(q) }
                    } else artists

                    ArtistsScreen(
                        artists = displayedArtists,
                        currentTrackId = playbackState.currentTrack?.id,
                        isPlaying = playbackState.isPlaying,
                        onTrackClick = { track, list, index -> viewModel.playTrack(track, list, index) },
                        onQueueTrack = { viewModel.queueTrack(it) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onAddToPlaylist = { trackForPlaylist = it }
                    )
                }
                LibraryTab.PLAYLISTS -> {
                    PlaylistsScreen(
                        playlists = playlists,
                        allTracks = allTracks,
                        currentTrackId = playbackState.currentTrack?.id,
                        isPlaying = playbackState.isPlaying,
                        onCreatePlaylist = { viewModel.createPlaylist(it) },
                        onTrackClick = { track, list, index -> viewModel.playTrack(track, list, index) },
                        onQueueTrack = { viewModel.queueTrack(it) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onRemoveFromPlaylist = { plId, trId -> viewModel.removeTrackFromPlaylist(plId, trId) }
                    )
                }
                LibraryTab.FAVORITES -> {
                    val displayedFavs = if (isSearchActive && searchQuery.isNotEmpty()) {
                        val q = searchQuery.trim().lowercase()
                        favoriteTracks.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
                    } else favoriteTracks

                    FavoritesScreen(
                        favoriteTracks = displayedFavs,
                        currentTrackId = playbackState.currentTrack?.id,
                        isPlaying = playbackState.isPlaying,
                        onTrackClick = { track, list, index -> viewModel.playTrack(track, list, index) },
                        onQueueTrack = { viewModel.queueTrack(it) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onAddToPlaylist = { trackForPlaylist = it }
                    )
                }
                LibraryTab.FOLDERS -> {
                    FoldersScreen(
                        includedFolders = includedFolders,
                        allTracks = allTracks,
                        currentTrackId = playbackState.currentTrack?.id,
                        isPlaying = playbackState.isPlaying,
                        onAddFolder = { viewModel.addIncludedFolder(it) },
                        onRemoveFolder = { viewModel.removeIncludedFolder(it) },
                        onRescan = { viewModel.rescanLibrary() },
                        onTrackClick = { track, list, index -> viewModel.playTrack(track, list, index) },
                        onQueueTrack = { viewModel.queueTrack(it) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) }
                    )
                }
            }

            // Fullscreen Now Playing Modal Sheet
            NowPlayingModalSheet(
                playbackState = playbackState,
                visible = isNowPlayingExpanded,
                onDismiss = { viewModel.setNowPlayingExpanded(false) },
                onPlayPause = { viewModel.playbackManager.playPause() },
                onNext = { viewModel.playbackManager.skipToNext() },
                onPrevious = { viewModel.playbackManager.skipToPrevious() },
                onSeek = { viewModel.playbackManager.seekTo(it) },
                onToggleShuffle = { viewModel.playbackManager.toggleShuffle() },
                onCycleRepeat = { viewModel.playbackManager.cycleRepeatMode() },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                onJumpToQueueIndex = { viewModel.playbackManager.jumpToQueueIndex(it) },
                onRemoveFromQueue = { viewModel.playbackManager.removeFromQueue(it) }
            )

            // Add to Playlist Dialog
            if (trackForPlaylist != null) {
                val targetTrack = trackForPlaylist!!
                AlertDialog(
                    onDismissRequest = { trackForPlaylist = null },
                    title = { Text("Add '${targetTrack.title}' to Playlist") },
                    text = {
                        if (playlists.isEmpty()) {
                            Text("No playlists created yet. Create one in the Playlists tab!")
                        } else {
                            LazyColumn {
                                items(playlists) { pl ->
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        onClick = {
                                            viewModel.addTrackToPlaylist(pl.id, targetTrack.id)
                                            trackForPlaylist = null
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                                            Spacer(modifier = Modifier.size(8.dp))
                                            Text(pl.name, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { trackForPlaylist = null }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}
