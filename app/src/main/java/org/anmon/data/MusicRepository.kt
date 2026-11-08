package org.anmon.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.anmon.model.Album
import org.anmon.model.Artist
import org.anmon.model.Playlist
import org.anmon.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MusicRepository(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val prefs = context.getSharedPreferences("aura_music_prefs", Context.MODE_PRIVATE)

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _includedFolders = MutableStateFlow<List<String>>(loadIncludedFolders())
    val includedFolders: StateFlow<List<String>> = _includedFolders.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(loadFavorites())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _userPlaylists = MutableStateFlow<List<Playlist>>(loadPlaylists())
    private val _m3uPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = combine(_userPlaylists, _m3uPlaylists) { userPls, m3uPls ->
        val existingIds = userPls.map { it.id }.toSet()
        val nonDuplicateM3us = m3uPls.filterNot { existingIds.contains(it.id) }
        userPls + nonDuplicateM3us
    }.stateIn(scope, SharingStarted.Eagerly, _userPlaylists.value)

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    val favoriteTracks: StateFlow<List<Track>> = _tracks.map { allTracks ->
        val favSet = _favorites.value
        allTracks.filter { favSet.contains(it.id) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val albums: StateFlow<List<Album>> = _tracks.map { trackList ->
        trackList.groupBy { it.album.ifEmpty { "Unknown Album" } }
            .map { (albumTitle, albumTracks) ->
                val sortedAlbumTracks = sortAlbumTracks(albumTracks)
                val primaryArtist = sortedAlbumTracks.firstOrNull()?.artist ?: "Unknown Artist"
                Album(
                    id = albumTitle.lowercase().replace(" ", "_"),
                    title = albumTitle,
                    artist = primaryArtist,
                    trackCount = sortedAlbumTracks.size,
                    totalDurationMs = sortedAlbumTracks.sumOf { it.durationMs },
                    tracks = sortedAlbumTracks
                )
            }
            .sortedBy { it.title }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val artists: StateFlow<List<Artist>> = _tracks.map { trackList ->
        trackList.groupBy { it.artist.ifEmpty { "Unknown Artist" } }
            .map { (artistName, artistTracks) ->
                val albumCount = artistTracks.map { it.album }.distinct().size
                Artist(
                    id = artistName.lowercase().replace(" ", "_"),
                    name = artistName,
                    trackCount = artistTracks.size,
                    albumCount = albumCount,
                    tracks = artistTracks.sortedBy { it.title }
                )
            }
            .sortedBy { it.name }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            rescanLibrary()
        }
    }

    suspend fun rescanLibrary() = withContext(Dispatchers.IO) {
        _isScanning.value = true
        val allFound = mutableListOf<Track>()

        // 1. Ensure sample tracks are always loaded for instant offline playback
        try {
            val sampleTracks = SampleAudioGenerator.ensureSampleTracks(context)
            allFound.addAll(sampleTracks)
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error loading sample tracks", e)
        }

        // 2. Query device MediaStore
        try {
            val mediaStoreTracks = queryMediaStore()
            allFound.addAll(mediaStoreTracks)
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error querying MediaStore", e)
        }

        // 3. Scan user-included folders (Storage Access Framework)
        for (folderUriStr in _includedFolders.value) {
            try {
                val folderTracks = scanFolder(folderUriStr)
                for (ft in folderTracks) {
                    if (allFound.none { it.uri == ft.uri || it.filePath == ft.filePath }) {
                        allFound.add(ft)
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicRepository", "Error scanning folder: $folderUriStr", e)
            }
        }

        // Apply favorite status
        val favSet = _favorites.value
        val mappedTracks = allFound.map { track ->
            track.copy(isFavorite = favSet.contains(track.id))
        }

        _tracks.value = mappedTracks

        // 4. Discover and parse .m3u and .m3u8 playlists
        try {
            val m3uPlaylists = scanM3uPlaylists(mappedTracks)
            _m3uPlaylists.value = m3uPlaylists
        } catch (e: Exception) {
            Log.e("MusicRepository", "Error discovering M3U playlists", e)
        }

        _isScanning.value = false
    }

    private fun queryMediaStore(): List<Track> {
        val tracks = mutableListOf<Track>()
        val baseProjection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            baseProjection.add(MediaStore.Audio.Media.DISC_NUMBER)
            baseProjection.add(MediaStore.Audio.Media.CD_TRACK_NUMBER)
        }
        val projection = baseProjection.toTypedArray()

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val discCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cursor.getColumnIndex(MediaStore.Audio.Media.DISC_NUMBER)
            } else -1
            val cdTrackCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cursor.getColumnIndex(MediaStore.Audio.Media.CD_TRACK_NUMBER)
            } else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown Track"
                val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                val album = cursor.getString(albumCol) ?: "Unknown Album"
                val albumId = cursor.getLong(albumIdCol)
                val duration = cursor.getLong(durCol)
                val rawTrack = cursor.getInt(trackCol)
                val discTag = if (discCol >= 0) cursor.getString(discCol) else null
                val cdTrackTag = if (cdTrackCol >= 0) cursor.getString(cdTrackCol) else null
                val mimeType = cursor.getString(mimeCol) ?: "audio/mpeg"
                val path = cursor.getString(dataCol)
                val dateAdded = cursor.getLong(dateCol) * 1000

                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                // Derive folder name from path
                val folderName = if (path != null) {
                    File(path).parentFile?.name ?: "Device Music"
                } else "Device Music"

                val (discNum, trackNum) = parseDiscAndTrackNumbers(
                    rawTrackColumn = rawTrack,
                    cdTrackTag = cdTrackTag,
                    discNumberTag = discTag,
                    filePathOrName = path,
                    parentFolderName = folderName
                )

                val ext = path?.substringAfterLast('.', "")?.lowercase() ?: ""
                val isWav = ext == "wav" || mimeType.contains("wav")
                val hasArtwork = !isWav && (albumId > 0 || ext in listOf("mp3", "flac", "m4a", "ogg", "opus"))

                tracks.add(
                    Track(
                        id = "media_$id",
                        title = title,
                        artist = if (artist.contains("<unknown>")) "Unknown Artist" else artist,
                        album = if (album.contains("<unknown>")) "Unknown Album" else album,
                        trackNumber = trackNum,
                        discNumber = discNum,
                        durationMs = duration,
                        uri = contentUri.toString(),
                        filePath = path,
                        mimeType = mimeType,
                        dateAdded = dateAdded,
                        folderPath = folderName,
                        hasEmbeddedArtwork = hasArtwork,
                        albumId = albumId
                    )
                )
            }
        }

        return tracks
    }

    private fun scanFolder(folderUriStr: String): List<Track> {
        val tracks = mutableListOf<Track>()
        val uri = Uri.parse(folderUriStr)
        val rootFolder = DocumentFile.fromTreeUri(context, uri) ?: return tracks
        val rootFolderName = rootFolder.name ?: "Selected Folder"

        fun scanDocFolder(currentFolder: DocumentFile, depth: Int) {
            if (depth > 3) return
            val parentName = currentFolder.name ?: rootFolderName
            for (docFile in currentFolder.listFiles()) {
                if (docFile.isDirectory) {
                    scanDocFolder(docFile, depth + 1)
                } else if (docFile.isFile && isAudioFile(docFile.type, docFile.name)) {
                    val fileUri = docFile.uri
                    val name = docFile.name ?: "Unknown"

                    // Extract metadata using retriever
                    var title = name.substringBeforeLast('.')
                    var artist = "Unknown Artist"
                    var album = rootFolderName
                    var durationMs = 0L
                    var cdTrackTag: String? = null
                    var discTag: String? = null
                    var mimeType = docFile.type ?: "audio/mpeg"

                    try {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(context, fileUri)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { durationMs = it }
                        cdTrackTag = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                        discTag = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.takeIf { it.isNotBlank() }?.let { mimeType = it }
                        retriever.release()
                    } catch (e: Exception) {
                        Log.w("MusicRepository", "Could not extract full ID3 tags from $fileUri", e)
                    }

                    val (discNum, trackNum) = parseDiscAndTrackNumbers(
                        rawTrackColumn = null,
                        cdTrackTag = cdTrackTag,
                        discNumberTag = discTag,
                        filePathOrName = name,
                        parentFolderName = parentName
                    )

                    val ext = name.substringAfterLast('.', "").lowercase()
                    val isWav = ext == "wav" || mimeType.contains("wav") || ext == "mid"
                    val hasArtwork = !isWav && ext in listOf("mp3", "flac", "m4a", "ogg", "opus")

                    tracks.add(
                        Track(
                            id = "folder_${fileUri.hashCode()}",
                            title = title,
                            artist = artist,
                            album = album,
                            trackNumber = trackNum,
                            discNumber = discNum,
                            durationMs = durationMs,
                            uri = fileUri.toString(),
                            mimeType = mimeType,
                            folderPath = rootFolderName,
                            hasEmbeddedArtwork = hasArtwork
                        )
                    )
                }
            }
        }

        scanDocFolder(rootFolder, 0)
        return tracks
    }

    private fun isAudioFile(mimeType: String?, fileName: String?): Boolean {
        if (mimeType?.startsWith("audio/") == true) return true
        val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: ""
        return ext in listOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma", "mid")
    }

    fun toggleFavorite(trackId: String) {
        val current = _favorites.value.toMutableSet()
        if (current.contains(trackId)) {
            current.remove(trackId)
        } else {
            current.add(trackId)
        }
        _favorites.value = current
        saveFavorites(current)

        // update in-memory tracks
        _tracks.value = _tracks.value.map { track ->
            if (track.id == trackId) {
                track.copy(isFavorite = current.contains(trackId))
            } else track
        }
    }

    fun addIncludedFolder(folderUriStr: String) {
        val current = _includedFolders.value.toMutableList()
        if (!current.contains(folderUriStr)) {
            current.add(folderUriStr)
            _includedFolders.value = current
            saveIncludedFolders(current)
            scope.launch { rescanLibrary() }
        }
    }

    fun removeIncludedFolder(folderUriStr: String) {
        val current = _includedFolders.value.toMutableList()
        if (current.remove(folderUriStr)) {
            _includedFolders.value = current
            saveIncludedFolders(current)
            scope.launch { rescanLibrary() }
        }
    }

    fun createPlaylist(name: String, trackIds: List<String> = emptyList()) {
        val newPlaylist = Playlist(
            id = "pl_${System.currentTimeMillis()}",
            name = name,
            trackIds = trackIds
        )
        val current = _userPlaylists.value.toMutableList()
        current.add(newPlaylist)
        _userPlaylists.value = current
        savePlaylists(current)
    }

    fun addTrackToPlaylist(playlistId: String, trackId: String) {
        val currentUsers = _userPlaylists.value.map { pl ->
            if (pl.id == playlistId && !pl.trackIds.contains(trackId)) {
                pl.copy(trackIds = pl.trackIds + trackId)
            } else pl
        }
        _userPlaylists.value = currentUsers
        savePlaylists(currentUsers)

        val currentM3us = _m3uPlaylists.value.map { pl ->
            if (pl.id == playlistId && !pl.trackIds.contains(trackId)) {
                pl.copy(trackIds = pl.trackIds + trackId)
            } else pl
        }
        _m3uPlaylists.value = currentM3us
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        val currentUsers = _userPlaylists.value.map { pl ->
            if (pl.id == playlistId) {
                pl.copy(trackIds = pl.trackIds.filter { it != trackId })
            } else pl
        }
        _userPlaylists.value = currentUsers
        savePlaylists(currentUsers)

        val currentM3us = _m3uPlaylists.value.map { pl ->
            if (pl.id == playlistId) {
                pl.copy(trackIds = pl.trackIds.filter { it != trackId })
            } else pl
        }
        _m3uPlaylists.value = currentM3us
    }

    fun deletePlaylist(playlistId: String) {
        val currentUsers = _userPlaylists.value.toMutableList()
        val removedFromUser = currentUsers.removeAll { it.id == playlistId }
        if (removedFromUser) {
            _userPlaylists.value = currentUsers
            savePlaylists(currentUsers)
            return
        }

        val currentM3us = _m3uPlaylists.value.toMutableList()
        val targetM3u = currentM3us.find { it.id == playlistId }
        if (targetM3u != null) {
            if (targetM3u.filePath != null && !targetM3u.filePath.startsWith("content:")) {
                try {
                    File(targetM3u.filePath).delete()
                } catch (e: Exception) {
                    Log.w("MusicRepository", "Could not delete playlist file", e)
                }
            }
            currentM3us.remove(targetM3u)
            _m3uPlaylists.value = currentM3us
        }
    }

    private fun scanM3uPlaylists(allFoundTracks: List<Track>): List<Playlist> {
        val m3uList = mutableListOf<Playlist>()
        val visitedPaths = mutableSetOf<String>()

        fun processM3uFile(file: File) {
            if (!file.exists() || !file.isFile) return
            val canonical = try { file.canonicalPath } catch (e: Exception) { file.absolutePath }
            if (visitedPaths.contains(canonical)) return
            visitedPaths.add(canonical)

            try {
                val content = file.readText()
                val entries = M3uParser.parse(content)
                val trackIds = M3uParser.resolveTracks(entries, file.parentFile, allFoundTracks)
                val plName = file.nameWithoutExtension.replace('_', ' ')
                m3uList.add(
                    Playlist(
                        id = "m3u_${canonical.hashCode()}",
                        name = plName,
                        trackIds = trackIds,
                        createdDate = file.lastModified(),
                        isM3u = true,
                        filePath = canonical
                    )
                )
            } catch (e: Exception) {
                Log.e("MusicRepository", "Error reading M3U file: ${file.absolutePath}", e)
            }
        }

        // 1. App internal files (sample_music and imported_m3u)
        val sampleDir = File(context.filesDir, "sample_music")
        if (sampleDir.exists()) {
            sampleDir.listFiles()?.filter {
                it.name.endsWith(".m3u", ignoreCase = true) || it.name.endsWith(".m3u8", ignoreCase = true)
            }?.forEach { processM3uFile(it) }
        }
        val importedDir = File(context.filesDir, "imported_m3u")
        if (importedDir.exists()) {
            importedDir.listFiles()?.filter {
                it.name.endsWith(".m3u", ignoreCase = true) || it.name.endsWith(".m3u8", ignoreCase = true)
            }?.forEach { processM3uFile(it) }
        }

        // 2. Query MediaStore for .m3u / .m3u8 files
        try {
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA
            )
            val selection = "${MediaStore.Files.FileColumns.DATA} LIKE '%.m3u' OR ${MediaStore.Files.FileColumns.DATA} LIKE '%.m3u8' OR ${MediaStore.Files.FileColumns.MIME_TYPE} = 'audio/x-mpegurl'"
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }
            context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                if (dataCol >= 0) {
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(dataCol)
                        if (path != null) {
                            processM3uFile(File(path))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("MusicRepository", "MediaStore M3U query warning", e)
        }

        // 3. Scan user-included folders
        for (folderUriStr in _includedFolders.value) {
            try {
                val uri = Uri.parse(folderUriStr)
                val docFolder = DocumentFile.fromTreeUri(context, uri) ?: continue
                for (docFile in docFolder.listFiles()) {
                    val name = docFile.name ?: ""
                    if (docFile.isFile && (name.endsWith(".m3u", ignoreCase = true) || name.endsWith(".m3u8", ignoreCase = true))) {
                        try {
                            context.contentResolver.openInputStream(docFile.uri)?.use { stream ->
                                val content = stream.bufferedReader().readText()
                                val entries = M3uParser.parse(content)
                                val trackIds = M3uParser.resolveTracks(entries, null, allFoundTracks)
                                val plName = name.substringBeforeLast('.').replace('_', ' ')
                                m3uList.add(
                                    Playlist(
                                        id = "m3u_tree_${docFile.uri.hashCode()}",
                                        name = plName,
                                        trackIds = trackIds,
                                        createdDate = docFile.lastModified(),
                                        isM3u = true,
                                        filePath = docFile.uri.toString()
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("MusicRepository", "Error parsing M3U docFile: ${docFile.uri}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicRepository", "Error scanning included folder for M3Us", e)
            }
        }

        // 4. External public Music folder (if accessible)
        try {
            val publicMusic = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
            if (publicMusic?.exists() == true) {
                publicMusic.listFiles()?.filter {
                    it.name.endsWith(".m3u", ignoreCase = true) || it.name.endsWith(".m3u8", ignoreCase = true)
                }?.forEach { processM3uFile(it) }
            }
            val externalFiles = context.getExternalFilesDir(null)
            if (externalFiles?.exists() == true) {
                externalFiles.listFiles()?.filter {
                    it.name.endsWith(".m3u", ignoreCase = true) || it.name.endsWith(".m3u8", ignoreCase = true)
                }?.forEach { processM3uFile(it) }
            }
        } catch (e: Exception) {
            Log.w("MusicRepository", "External storage M3U check error", e)
        }

        return m3uList
    }

    suspend fun importM3uPlaylist(uri: Uri, playlistName: String? = null): Playlist? = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val content = stream.bufferedReader().use { it.readText() }

            val name = playlistName ?: run {
                var fileName = "Imported Playlist"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex) ?: "Imported Playlist"
                    }
                }
                fileName.substringBeforeLast('.').replace('_', ' ')
            }

            val importedDir = File(context.filesDir, "imported_m3u").apply { mkdirs() }
            val localFile = File(importedDir, "${name.replace(' ', '_')}_${System.currentTimeMillis()}.m3u")
            localFile.writeText(content)

            val entries = M3uParser.parse(content)
            val resolvedIds = M3uParser.resolveTracks(entries, localFile.parentFile, _tracks.value)

            val newPl = Playlist(
                id = "m3u_${localFile.name.hashCode()}",
                name = name,
                trackIds = resolvedIds,
                createdDate = System.currentTimeMillis(),
                isM3u = true,
                filePath = localFile.absolutePath
            )

            val current = _m3uPlaylists.value.toMutableList()
            current.removeAll { it.id == newPl.id }
            current.add(0, newPl)
            _m3uPlaylists.value = current
            newPl
        } catch (e: Exception) {
            Log.e("MusicRepository", "Failed to import M3U playlist", e)
            null
        }
    }

    private fun loadFavorites(): Set<String> {
        return prefs.getStringSet("favorite_ids", emptySet()) ?: emptySet()
    }

    private fun saveFavorites(favs: Set<String>) {
        prefs.edit().putStringSet("favorite_ids", favs).apply()
    }

    private fun loadIncludedFolders(): List<String> {
        val json = prefs.getString("included_folders", "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            list.add(array.getString(i))
        }
        return list
    }

    private fun saveIncludedFolders(folders: List<String>) {
        val array = JSONArray()
        folders.forEach { array.put(it) }
        prefs.edit().putString("included_folders", array.toString()).apply()
    }

    private fun loadPlaylists(): List<Playlist> {
        val json = prefs.getString("user_playlists", null)
        if (json == null) {
            // Provide a starter playlist
            return listOf(
                Playlist(
                    id = "pl_chill",
                    name = "Chill Vibes",
                    trackIds = listOf("sample_01_starlight_reverie.wav", "sample_01_moonlight_rain.wav")
                )
            )
        }
        val array = JSONArray(json)
        val list = mutableListOf<Playlist>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val trackIdsArr = obj.getJSONArray("trackIds")
            val trackIds = mutableListOf<String>()
            for (j in 0 until trackIdsArr.length()) {
                trackIds.add(trackIdsArr.getString(j))
            }
            list.add(
                Playlist(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    trackIds = trackIds,
                    createdDate = obj.optLong("createdDate", System.currentTimeMillis()),
                    isSystemFavorites = obj.optBoolean("isSystemFavorites", false)
                )
            )
        }
        return list
    }

    private fun savePlaylists(playlists: List<Playlist>) {
        val array = JSONArray()
        for (pl in playlists) {
            val obj = JSONObject().apply {
                put("id", pl.id)
                put("name", pl.name)
                put("createdDate", pl.createdDate)
                put("isSystemFavorites", pl.isSystemFavorites)
                val trackIdsArr = JSONArray()
                pl.trackIds.forEach { trackIdsArr.put(it) }
                put("trackIds", trackIdsArr)
            }
            array.put(obj)
        }
        prefs.edit().putString("user_playlists", array.toString()).apply()
    }

    companion object {
        private val DISC_FOLDER_REGEX = Regex("""(?i)(?:^|[/\\_\-\s(\[])(?:disc|disk|cd)\s*0*(\d{1,2})(?:$|[/\\_\-\s)\]])""")
        private val DISC_TRACK_FILENAME_REGEX = Regex("""^\s*0*(\d{1,2})\s*[-._]\s*0*(\d{1,3})(?:\s*[-._\s].*|$)""")
        private val LEADING_TRACK_FILENAME_REGEX = Regex("""^\s*0*(\d{1,3})(?:\s*[-._\s].*)""")
        private val FIRST_NUMBER_REGEX = Regex("""(\d+)""")

        /**
         * Sorts album tracks first by discNumber ascending, then by trackNumber ascending,
         * and finally by title ascending so multi-disc albums play Disc 1 (Track 1..N)
         * followed by Disc 2 (Track 1..N).
         */
        fun sortAlbumTracks(tracks: List<Track>): List<Track> {
            return tracks.sortedWith(
                compareBy<Track> { it.discNumber }
                    .thenBy { it.trackNumber }
                    .thenBy { it.title.lowercase() }
            )
        }

        private fun parseTagPositiveInt(raw: String?): Int? {
            if (raw.isNullOrBlank()) return null
            val beforeSlash = raw.substringBefore('/').trim()
            beforeSlash.toIntOrNull()?.let { if (it > 0) return it }
            val match = FIRST_NUMBER_REGEX.find(beforeSlash) ?: return null
            return match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
        }

        /**
         * Extracts `(discNumber, trackNumber)` from MediaStore columns, ID3/Vorbis tags,
         * or folder/filename conventions.
         */
        fun parseDiscAndTrackNumbers(
            rawTrackColumn: Int? = null,
            cdTrackTag: String? = null,
            discNumberTag: String? = null,
            filePathOrName: String? = null,
            parentFolderName: String? = null
        ): Pair<Int, Int> {
            var discNum: Int? = null
            var trackNum: Int? = null

            // 1. Parse track number from MediaStore TRACK column or CD_TRACK_NUMBER tag
            if (rawTrackColumn != null && rawTrackColumn > 0) {
                val t = if (rawTrackColumn >= 1000) rawTrackColumn % 1000 else rawTrackColumn
                if (t > 0) {
                    trackNum = t
                }
            }
            val parsedCdTrack = parseTagPositiveInt(cdTrackTag)
            if (trackNum == null && parsedCdTrack != null && parsedCdTrack > 0) {
                val t = if (parsedCdTrack >= 1000) parsedCdTrack % 1000 else parsedCdTrack
                if (t > 0) {
                    trackNum = t
                }
            }

            // 2. Parse disc number from explicit DISC_NUMBER tag or 1000-encoded TRACK column (e.g. 2001 -> disc 2, track 1)
            val parsedDiscTag = parseTagPositiveInt(discNumberTag)
            if (parsedDiscTag != null && parsedDiscTag > 0) {
                discNum = parsedDiscTag
            } else if (rawTrackColumn != null && rawTrackColumn >= 1000) {
                val encodedDisc = rawTrackColumn / 1000
                if (encodedDisc > 0) {
                    discNum = encodedDisc
                }
            } else if (parsedCdTrack != null && parsedCdTrack >= 1000) {
                val encodedDisc = parsedCdTrack / 1000
                if (encodedDisc > 0) {
                    discNum = encodedDisc
                }
            }

            // 3. Fallback: check parent folder name or file path for "Disc 2", "CD2", "Disk 2", etc.
            if (discNum == null) {
                val folderMatch = parentFolderName?.let { DISC_FOLDER_REGEX.find(it) }
                    ?: filePathOrName?.let { DISC_FOLDER_REGEX.find(it) }
                val folderDisc = folderMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (folderDisc != null && folderDisc > 0) {
                    discNum = folderDisc
                }
            }

            // 4. Fallback: check base filename for "1-01 Song.mp3" / "2-01 Song.mp3" or leading track number
            val baseFileName = filePathOrName
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.substringBeforeLast('.')
            if (!baseFileName.isNullOrBlank()) {
                val dtMatch = DISC_TRACK_FILENAME_REGEX.find(baseFileName)
                if (dtMatch != null) {
                    val fileDisc = dtMatch.groupValues[1].toIntOrNull()
                    val fileTrack = dtMatch.groupValues[2].toIntOrNull()
                    if (discNum == null && fileDisc != null && fileDisc > 0) {
                        discNum = fileDisc
                    }
                    if (trackNum == null && fileTrack != null && fileTrack > 0) {
                        trackNum = fileTrack
                    }
                } else if (trackNum == null) {
                    val leadMatch = LEADING_TRACK_FILENAME_REGEX.find(baseFileName)
                    val rawLead = leadMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (rawLead != null && rawLead > 0) {
                        if (rawLead >= 100 && discNum == null) {
                            // e.g. "101 - Track" or "201 - Track"
                            discNum = rawLead / 100
                            val t = rawLead % 100
                            if (t > 0) trackNum = t
                        } else {
                            trackNum = rawLead
                        }
                    }
                }
            }

            return (discNum ?: 1).coerceAtLeast(1) to (trackNum ?: 1).coerceAtLeast(1)
        }
    }
}
