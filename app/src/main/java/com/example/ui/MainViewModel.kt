package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.MusicRepository
import com.example.model.Album
import com.example.model.LibraryTab
import com.example.model.Track
import com.example.playback.PlaybackManager
import com.example.playback.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val repository = MusicRepository(application, viewModelScope)
    val playbackManager: com.example.playback.PlaybackManager
        get() = com.example.playback.PlaybackManagerProvider.get(getApplication())

    val playbackState: StateFlow<PlaybackState> = com.example.playback.PlaybackManagerProvider.playbackState

    private val _selectedTab = MutableStateFlow(LibraryTab.TRACKS)
    val selectedTab: StateFlow<LibraryTab> = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    private val _isDarkMode = MutableStateFlow(true) // Default to sleek obsidian dark mode
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _isNowPlayingExpanded = MutableStateFlow(false)
    val isNowPlayingExpanded: StateFlow<Boolean> = _isNowPlayingExpanded.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    val allTracks: StateFlow<List<Track>> = repository.tracks
    val albums: StateFlow<List<Album>> = repository.albums
    val artists = repository.artists
    val playlists = repository.playlists
    val favoriteTracks = repository.favoriteTracks
    val includedFolders = repository.includedFolders
    val isScanning = repository.isScanning

    // Filtered tracks when search query is entered
    val filteredTracks: StateFlow<List<Track>> = combine(allTracks, _searchQuery) { tracks, query ->
        if (query.isBlank()) {
            tracks
        } else {
            val q = query.trim().lowercase()
            tracks.filter {
                it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q) ||
                it.genre.lowercase().contains(q)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun selectTab(tab: LibraryTab) {
        _selectedTab.value = tab
    }

    fun toggleSearch() {
        _isSearchActive.value = !_isSearchActive.value
        if (!_isSearchActive.value) {
            _searchQuery.value = ""
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleTheme() {
        _isDarkMode.value = !_isDarkMode.value
    }

    fun setNowPlayingExpanded(expanded: Boolean) {
        _isNowPlayingExpanded.value = expanded
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun playTrack(track: Track, contextList: List<Track>, index: Int) {
        playbackManager.playQueue(contextList, index)
    }

    fun playAlbum(album: Album, startIndex: Int = 0) {
        playbackManager.playQueue(album.tracks, startIndex)
        _snackbarMessage.value = "Playing ${album.title}"
    }

    fun queueTrack(track: Track) {
        playbackManager.addToQueue(track, playNext = false)
        _snackbarMessage.value = "Queued: ${track.title}"
    }

    fun toggleFavorite(trackId: String) {
        repository.toggleFavorite(trackId)
    }

    fun createPlaylist(name: String) {
        repository.createPlaylist(name)
        _snackbarMessage.value = "Created playlist '$name'"
    }

    fun addTrackToPlaylist(playlistId: String, trackId: String) {
        repository.addTrackToPlaylist(playlistId, trackId)
        _snackbarMessage.value = "Added to playlist"
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        repository.removeTrackFromPlaylist(playlistId, trackId)
    }

    fun addIncludedFolder(folderUri: String) {
        repository.addIncludedFolder(folderUri)
        _snackbarMessage.value = "Folder added. Scanning audio files..."
    }

    fun removeIncludedFolder(folderUri: String) {
        repository.removeIncludedFolder(folderUri)
        _snackbarMessage.value = "Folder removed"
    }

    fun rescanLibrary() {
        viewModelScope.launch {
            repository.rescanLibrary()
            _snackbarMessage.value = "Library refreshed"
        }
    }

    fun importM3uPlaylist(uri: Uri) {
        viewModelScope.launch {
            val pl = repository.importM3uPlaylist(uri)
            if (pl != null) {
                _snackbarMessage.value = "Imported '${pl.name}' (${pl.trackIds.size} tracks)"
            } else {
                _snackbarMessage.value = "Could not import M3U playlist"
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        repository.deletePlaylist(playlistId)
        _snackbarMessage.value = "Playlist removed"
    }
}
