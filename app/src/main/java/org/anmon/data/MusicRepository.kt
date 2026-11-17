package org.anmon.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.anmon.model.Album
import org.anmon.model.Artist
import org.anmon.model.Playlist
import org.anmon.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MusicRepository(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val prefs = context.getSharedPreferences("aura_music_prefs", Context.MODE_PRIVATE)
    private val scanMutex = Mutex()
    private val pendingRescanRequest = AtomicBoolean(false)

    private data class CachedTagMetadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val genre: String?,
        val durationMs: Long?,
        val cdTrackTag: String?,
        val discTag: String?,
        val mimeType: String?
    )

    private val metadataCache = ConcurrentHashMap<String, CachedTagMetadata>()

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
        val usedIds = mutableSetOf<String>()
        trackList.groupBy { it.album.trim().ifEmpty { "Unknown Album" } }
            .map { (albumTitle, albumTracks) ->
                val sortedAlbumTracks = sortAlbumTracks(albumTracks)
                val primaryArtist = sortedAlbumTracks.firstOrNull()?.artist ?: "Unknown Artist"
                val baseId = albumTitle.lowercase().replace(" ", "_").ifEmpty { "unknown_album" }
                var uniqueId = baseId
                var counter = 2
                while (!usedIds.add(uniqueId)) {
                    uniqueId = "${baseId}_$counter"
                    counter++
                }
                Album(
                    id = uniqueId,
                    title = albumTitle,
                    artist = primaryArtist,
                    trackCount = sortedAlbumTracks.size,
                    totalDurationMs = sortedAlbumTracks.sumOf { it.durationMs },
                    tracks = sortedAlbumTracks
                )
            }
            .sortedBy { it.title.lowercase() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val artists: StateFlow<List<Artist>> = _tracks.map { trackList ->
        val usedIds = mutableSetOf<String>()
        trackList.groupBy { it.artist.trim().ifEmpty { "Unknown Artist" } }
            .map { (artistName, artistTracks) ->
                val albumCount = artistTracks.map { it.album }.distinct().size
                val baseId = artistName.lowercase().replace(" ", "_").ifEmpty { "unknown_artist" }
                var uniqueId = baseId
                var counter = 2
                while (!usedIds.add(uniqueId)) {
                    uniqueId = "${baseId}_$counter"
                    counter++
                }
                Artist(
                    id = uniqueId,
                    name = artistName,
                    trackCount = artistTracks.size,
                    albumCount = albumCount,
                    tracks = artistTracks.sortedBy { it.title.lowercase() }
                )
            }
            .sortedBy { it.name.lowercase() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            rescanLibrary()
        }
    }

    private fun publishTracks(rawTracks: List<Track>) {
        val favSet = _favorites.value
        val usedIds = mutableSetOf<String>()
        val mapped = rawTracks.map { track ->
            var safeId = track.id
            var suffix = 2
            while (!usedIds.add(safeId)) {
                safeId = "${track.id}_$suffix"
                suffix++
            }
            track.copy(
                id = safeId,
                isFavorite = favSet.contains(safeId) || favSet.contains(track.id)
            )
        }
        _tracks.value = mapped
    }

    suspend fun rescanLibrary() = withContext(Dispatchers.IO) {
        pendingRescanRequest.set(true)
        if (!scanMutex.tryLock()) {
            // Another scan is currently running; wait for the lock and run if still pending
            scanMutex.withLock {
                if (pendingRescanRequest.getAndSet(false)) {
                    try {
                        _isScanning.value = true
                        performRescanInternal()
                    } finally {
                        _isScanning.value = false
                    }
                }
            }
            return@withContext
        }

        try {
            _isScanning.value = true
            while (pendingRescanRequest.getAndSet(false)) {
                performRescanInternal()
            }
        } finally {
            _isScanning.value = false
            scanMutex.unlock()
        }
    }

    private suspend fun performRescanInternal() {
        var allFound = mutableListOf<Track>()

        // 1. Ensure sample tracks are always loaded for instant offline playback
        try {
            val sampleTracks = SampleAudioGenerator.ensureSampleTracks(context)
            allFound.addAll(sampleTracks)
            if (_tracks.value.isEmpty()) {
                publishTracks(allFound)
            }
        } catch (t: Throwable) {
            Log.e("MusicRepository", "Error loading sample tracks", t)
        }

        // 2. Query device MediaStore with resilient multi-stage fallback
        val mediaStoreTracks = try {
            queryMediaStore()
        } catch (t: Throwable) {
            Log.e("MusicRepository", "Error querying MediaStore", t)
            emptyList()
        }

        if (mediaStoreTracks.isNotEmpty()) {
            allFound = mergeTracksDeduplicated(
                baseTracks = allFound,
                incomingTracks = mediaStoreTracks,
                preferIncomingFolderPath = false
            ).toMutableList()
            publishTracks(allFound)
        }

        // 3. Scan user-included folders (Storage Access Framework & Local Directories)
        val foldersSnapshot = _includedFolders.value.toList()
        for (folderUriStr in foldersSnapshot) {
            try {
                val folderTracks = scanFolder(
                    folderUriStr = folderUriStr,
                    mediaStoreTracks = mediaStoreTracks,
                    onIncrementalBatch = { partialFolderTracks ->
                        val mergedPartial = mergeTracksDeduplicated(
                            baseTracks = allFound,
                            incomingTracks = partialFolderTracks,
                            preferIncomingFolderPath = true
                        )
                        publishTracks(mergedPartial)
                    }
                )
                if (folderTracks.isNotEmpty()) {
                    allFound = mergeTracksDeduplicated(
                        baseTracks = allFound,
                        incomingTracks = folderTracks,
                        preferIncomingFolderPath = true
                    ).toMutableList()
                    publishTracks(allFound)
                }
            } catch (t: Throwable) {
                Log.e("MusicRepository", "Error scanning folder: $folderUriStr", t)
            }
        }

        // Final publish with favorite status applied
        publishTracks(allFound)

        // 4. Discover and parse .m3u and .m3u8 playlists
        try {
            val m3uPlaylists = scanM3uPlaylists(_tracks.value)
            _m3uPlaylists.value = m3uPlaylists
        } catch (t: Throwable) {
            Log.e("MusicRepository", "Error discovering M3U playlists", t)
        }
    }

    private fun queryMediaStore(): List<Track> {
        val discovered = mutableListOf<Track>()
        val volumes = mutableSetOf<Uri>()
        volumes.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                for (volName in MediaStore.getExternalVolumeNames(context)) {
                    volumes.add(MediaStore.Audio.Media.getContentUri(volName))
                }
            } catch (t: Throwable) {
                Log.w("MusicRepository", "Could not enumerate external volume names", t)
            }
        }

        for (volumeUri in volumes) {
            // First query tracks marked IS_MUSIC != 0
            val musicFlagTracks = queryMediaStoreVolume(volumeUri, onlyIsMusic = true)
            for (t in musicFlagTracks) {
                if (discovered.none { isSameTrack(it, t) }) {
                    discovered.add(t)
                }
            }
            // Also query without IS_MUSIC filter to catch valid music files where OEM MediaScanner left IS_MUSIC == 0
            val allAudioTracks = queryMediaStoreVolume(volumeUri, onlyIsMusic = false)
            for (t in allAudioTracks) {
                if (discovered.none { isSameTrack(it, t) }) {
                    discovered.add(t)
                }
            }
        }

        return discovered
    }

    private fun queryMediaStoreVolume(volumeUri: Uri, onlyIsMusic: Boolean): List<Track> {
        val coreColumns = listOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DISPLAY_NAME
        )

        val candidateProjections = mutableListOf<Array<String>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            candidateProjections.add(
                (coreColumns + listOf(
                    MediaStore.Audio.Media.DISC_NUMBER,
                    MediaStore.Audio.Media.CD_TRACK_NUMBER,
                    MediaStore.Audio.Media.GENRE,
                    MediaStore.Audio.Media.RELATIVE_PATH
                )).toTypedArray()
            )
            candidateProjections.add(
                (coreColumns + listOf(
                    MediaStore.Audio.Media.DISC_NUMBER,
                    MediaStore.Audio.Media.CD_TRACK_NUMBER,
                    MediaStore.Audio.Media.RELATIVE_PATH
                )).toTypedArray()
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            candidateProjections.add(
                (coreColumns + listOf(MediaStore.Audio.Media.RELATIVE_PATH)).toTypedArray()
            )
        }
        candidateProjections.add(coreColumns.toTypedArray())
        candidateProjections.add(
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE
            )
        )

        val selection = if (onlyIsMusic) "${MediaStore.Audio.Media.IS_MUSIC} != 0" else null
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        for (projection in candidateProjections) {
            try {
                val tracks = mutableListOf<Track>()
                val cursor = context.contentResolver.query(volumeUri, projection, selection, null, sortOrder)
                    ?: continue
                cursor.use { c ->
                    val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
                    val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                    val durCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    val trackCol = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
                    val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                    val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                    val dateCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                    val displayNameCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    } else -1
                    val discCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        c.getColumnIndex(MediaStore.Audio.Media.DISC_NUMBER)
                    } else -1
                    val cdTrackCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        c.getColumnIndex(MediaStore.Audio.Media.CD_TRACK_NUMBER)
                    } else -1
                    val genreCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        c.getColumnIndex(MediaStore.Audio.Media.GENRE)
                    } else -1

                    while (c.moveToNext()) {
                        try {
                            val id = c.safeLong(idCol) ?: continue
                            val path = c.safeString(dataCol)?.takeIf { it.isNotBlank() }
                            val displayName = c.safeString(displayNameCol)?.takeIf { it.isNotBlank() }
                                ?: path?.substringAfterLast('/')
                            val mimeType = c.safeString(mimeCol)?.takeIf { it.isNotBlank() } ?: "audio/mpeg"

                            // Skip system internal sounds / ringtones / notifications when scanning broad audio table
                            if (!onlyIsMusic) {
                                if (!isAudioFile(mimeType, displayName ?: path)) continue
                                val lowerPath = (path ?: "").lowercase()
                                if (lowerPath.startsWith("/system/") ||
                                    lowerPath.startsWith("/product/") ||
                                    lowerPath.startsWith("/apex/") ||
                                    lowerPath.contains("/ringtones/") ||
                                    lowerPath.contains("/notifications/") ||
                                    lowerPath.contains("/alarms/") ||
                                    lowerPath.contains("/ui/")
                                ) {
                                    continue
                                }
                            }

                            val rawTitle = c.safeString(titleCol)?.takeIf { it.isNotBlank() }
                                ?: displayName?.substringBeforeLast('.')
                                ?: "Unknown Track"
                            val rawArtist = c.safeString(artistCol)?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                            val rawAlbum = c.safeString(albumCol)?.takeIf { it.isNotBlank() } ?: "Unknown Album"
                            val albumId = c.safeLong(albumIdCol) ?: 0L
                            val duration = c.safeLong(durCol) ?: 0L
                            val rawTrack = c.safeInt(trackCol) ?: 0
                            val discTag = c.safeString(discCol)
                            val cdTrackTag = c.safeString(cdTrackCol)
                            val rawGenre = c.safeString(genreCol)
                            val genre = if (rawGenre.isNullOrBlank() || rawGenre.contains("<unknown>", ignoreCase = true)) {
                                "General"
                            } else {
                                rawGenre.trim()
                            }
                            val dateAdded = (c.safeLong(dateCol) ?: 0L) * 1000L
                            val relativePath = c.safeString(relPathCol)

                            val contentUri = ContentUris.withAppendedId(volumeUri, id)

                            val folderName = when {
                                !path.isNullOrBlank() -> File(path).parentFile?.name?.takeIf { it.isNotBlank() } ?: "Device Music"
                                !relativePath.isNullOrBlank() -> relativePath.trimEnd('/', '\\').substringAfterLast('/').takeIf { it.isNotBlank() } ?: "Device Music"
                                else -> "Device Music"
                            }

                            val (discNum, trackNum) = parseDiscAndTrackNumbers(
                                rawTrackColumn = rawTrack,
                                cdTrackTag = cdTrackTag,
                                discNumberTag = discTag,
                                filePathOrName = path ?: displayName,
                                parentFolderName = folderName
                            )

                            val ext = (displayName ?: path)?.substringAfterLast('.', "")?.lowercase() ?: ""
                            val isWav = ext == "wav" || mimeType.contains("wav", ignoreCase = true)
                            val hasArtwork = !isWav && (albumId > 0 || ext in listOf("mp3", "flac", "m4a", "ogg", "opus"))

                            tracks.add(
                                Track(
                                    id = "media_$id",
                                    title = rawTitle,
                                    artist = if (rawArtist.contains("<unknown>", ignoreCase = true)) "Unknown Artist" else rawArtist,
                                    album = if (rawAlbum.contains("<unknown>", ignoreCase = true)) "Unknown Album" else rawAlbum,
                                    genre = genre,
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
                        } catch (rowErr: Throwable) {
                            Log.w("MusicRepository", "Skipping malformed MediaStore row", rowErr)
                        }
                    }
                }
                return tracks
            } catch (queryErr: Throwable) {
                Log.w("MusicRepository", "Projection query failed on $volumeUri, trying fallback projection", queryErr)
            }
        }
        return emptyList()
    }

    private data class DiscoveredAudioEntry(
        val fileUri: Uri,
        val displayName: String,
        val mimeType: String?,
        val parentFolderName: String,
        val grandParentFolderName: String?,
        val resolvedFilePath: String?
    )

    private suspend fun scanFolder(
        folderUriStr: String,
        mediaStoreTracks: List<Track> = emptyList(),
        onIncrementalBatch: ((List<Track>) -> Unit)? = null
    ): List<Track> {
        val uri = try {
            Uri.parse(folderUriStr)
        } catch (_: Throwable) {
            return emptyList()
        }

        val rootFolderName = resolveFolderDisplayName(context, folderUriStr)
        val discoveredEntries = mutableListOf<DiscoveredAudioEntry>()
        val seenUris = mutableSetOf<String>()

        // Strategy 1: Fast SAF traversal via DocumentsContract.buildChildDocumentsUriUsingTree
        try {
            if (DocumentsContract.isTreeUri(uri)) {
                val rootDocId = DocumentsContract.getTreeDocumentId(uri)
                val visitedDocs = mutableSetOf<String>()

                fun traverseDocumentsContract(
                    docId: String,
                    currentFolderName: String,
                    parentFolderName: String?,
                    depth: Int
                ) {
                    if (depth > 12 || !visitedDocs.add(docId)) return
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
                    val projection = arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    )
                    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                        val docIdCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                        while (c.moveToNext()) {
                            val childDocId = c.safeString(docIdCol) ?: continue
                            val childName = c.safeString(nameCol)?.takeIf { it.isNotBlank() }
                                ?: childDocId.substringAfterLast('/').substringAfterLast(':')
                            val childMime = c.safeString(mimeCol)

                            if (childMime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                traverseDocumentsContract(
                                    docId = childDocId,
                                    currentFolderName = childName.ifBlank { currentFolderName },
                                    parentFolderName = currentFolderName,
                                    depth = depth + 1
                                )
                            } else if (isAudioFile(childMime, childName)) {
                                val fileUri = DocumentsContract.buildDocumentUriUsingTree(uri, childDocId)
                                val uriKey = fileUri.toString()
                                if (seenUris.add(uriKey)) {
                                    discoveredEntries.add(
                                        DiscoveredAudioEntry(
                                            fileUri = fileUri,
                                            displayName = childName.ifBlank { "Unknown" },
                                            mimeType = childMime,
                                            parentFolderName = currentFolderName,
                                            grandParentFolderName = parentFolderName,
                                            resolvedFilePath = resolveFilePathFromDocId(childDocId)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                traverseDocumentsContract(
                    docId = rootDocId,
                    currentFolderName = rootFolderName,
                    parentFolderName = null,
                    depth = 0
                )
            }
        } catch (t: Throwable) {
            Log.w("MusicRepository", "DocumentsContract tree scan fallback for $folderUriStr", t)
        }

        // Strategy 2: Fallback to DocumentFile.fromTreeUri if Strategy 1 found nothing
        if (discoveredEntries.isEmpty()) {
            try {
                val rootDoc = DocumentFile.fromTreeUri(context, uri)
                if (rootDoc != null) {
                    val visitedDocUris = mutableSetOf<String>()
                    fun scanDocFolder(
                        currentFolder: DocumentFile,
                        currentFolderName: String,
                        parentFolderName: String?,
                        depth: Int
                    ) {
                        if (depth > 12) return
                        val folderKey = currentFolder.uri.toString()
                        if (!visitedDocUris.add(folderKey)) return

                        for (docFile in currentFolder.listFiles()) {
                            val name = docFile.name ?: docFile.uri.lastPathSegment?.substringAfterLast('/') ?: "Unknown"
                            val mime = docFile.type
                            if (docFile.isDirectory) {
                                scanDocFolder(
                                    currentFolder = docFile,
                                    currentFolderName = name,
                                    parentFolderName = currentFolderName,
                                    depth = depth + 1
                                )
                            } else if (isAudioFile(mime, name)) {
                                val fileUri = docFile.uri
                                val uriKey = fileUri.toString()
                                if (seenUris.add(uriKey)) {
                                    val docId = try {
                                        DocumentsContract.getDocumentId(fileUri)
                                    } catch (_: Throwable) {
                                        null
                                    }
                                    discoveredEntries.add(
                                        DiscoveredAudioEntry(
                                            fileUri = fileUri,
                                            displayName = name,
                                            mimeType = mime,
                                            parentFolderName = currentFolderName,
                                            grandParentFolderName = parentFolderName,
                                            resolvedFilePath = docId?.let { resolveFilePathFromDocId(it) }
                                        )
                                    )
                                }
                            }
                        }
                    }
                    scanDocFolder(rootDoc, rootFolderName, null, 0)
                }
            } catch (t: Throwable) {
                Log.w("MusicRepository", "DocumentFile fallback failed for $folderUriStr", t)
            }
        }

        // Strategy 3: Fallback to direct Java File traversal if mapped local folder is readable
        val localRootDir = resolveLocalDirectoryFromUri(uri)
        if (discoveredEntries.isEmpty() && localRootDir != null && localRootDir.exists() && localRootDir.canRead()) {
            try {
                fun scanJavaDir(dir: File, parentName: String?, depth: Int) {
                    if (depth > 12) return
                    val files = dir.listFiles() ?: return
                    for (f in files) {
                        if (f.isDirectory) {
                            scanJavaDir(f, dir.name, depth + 1)
                        } else if (f.isFile && isAudioFile(null, f.name)) {
                            val fUri = Uri.fromFile(f)
                            if (seenUris.add(fUri.toString())) {
                                discoveredEntries.add(
                                    DiscoveredAudioEntry(
                                        fileUri = fUri,
                                        displayName = f.name,
                                        mimeType = guessMimeTypeFromName(f.name),
                                        parentFolderName = dir.name.ifBlank { rootFolderName },
                                        grandParentFolderName = parentName,
                                        resolvedFilePath = f.absolutePath
                                    )
                                )
                            }
                        }
                    }
                }
                scanJavaDir(localRootDir, null, 0)
            } catch (t: Throwable) {
                Log.w("MusicRepository", "Java File fallback failed for $folderUriStr", t)
            }
        }

        // Also match any MediaStore tracks whose filePath resides inside localRootDir if still empty
        if (discoveredEntries.isEmpty() && localRootDir != null) {
            val prefix = localRootDir.absolutePath.trimEnd('/') + "/"
            val matchedFromMediaStore = mediaStoreTracks.filter { track ->
                val fp = track.filePath ?: return@filter false
                fp.startsWith(prefix, ignoreCase = true)
            }.map { it.copy(folderPath = rootFolderName) }
            if (matchedFromMediaStore.isNotEmpty()) {
                onIncrementalBatch?.invoke(matchedFromMediaStore)
                return matchedFromMediaStore
            }
        }

        if (discoveredEntries.isEmpty()) {
            return emptyList()
        }

        // Build fast lookup maps from MediaStore tracks so already-indexed files need 0 IPC calls
        val mediaByPath = HashMap<String, Track>(mediaStoreTracks.size * 2)
        val mediaByFileName = HashMap<String, Track>(mediaStoreTracks.size * 2)
        for (msTrack in mediaStoreTracks) {
            msTrack.filePath?.takeIf { it.isNotBlank() }?.let { fp ->
                mediaByPath[fp.lowercase()] = msTrack
                val fileName = fp.substringAfterLast('/').lowercase()
                if (fileName.isNotBlank()) {
                    mediaByFileName.putIfAbsent(fileName, msTrack)
                }
            }
        }

        val tracks = ArrayList<Track>(discoveredEntries.size)
        val indicesNeedingTagExtraction = mutableListOf<Int>()

        // Phase 1: Instant track construction (0ms IPC overhead)
        for ((index, entry) in discoveredEntries.withIndex()) {
            val uriStr = entry.fileUri.toString()
            val matchedMediaStore = entry.resolvedFilePath?.lowercase()?.let { mediaByPath[it] }
                ?: mediaByFileName[entry.displayName.lowercase()]

            if (matchedMediaStore != null) {
                tracks.add(
                    matchedMediaStore.copy(
                        folderPath = rootFolderName,
                        filePath = entry.resolvedFilePath ?: matchedMediaStore.filePath
                    )
                )
                continue
            }

            val cached = metadataCache[uriStr]
            val fastTrack = buildTrackFromEntryAndMetadata(
                entry = entry,
                rootFolderName = rootFolderName,
                cached = cached
            )
            tracks.add(fastTrack)
            if (cached == null) {
                indicesNeedingTagExtraction.add(index)
            }
        }

        // Emit Phase 1 tracks immediately so UI populates within ~100ms
        onIncrementalBatch?.invoke(tracks.toList())

        // Phase 2: Refine ID3/Vorbis metadata with bounded timeouts & safe retriever release
        for ((batchCount, idx) in indicesNeedingTagExtraction.withIndex()) {
            val entry = discoveredEntries[idx]
            val uriStr = entry.fileUri.toString()
            val extracted = extractMetadataSafely(entry.fileUri)
            if (extracted != null) {
                metadataCache[uriStr] = extracted
                tracks[idx] = buildTrackFromEntryAndMetadata(
                    entry = entry,
                    rootFolderName = rootFolderName,
                    cached = extracted
                )
            }
            if ((batchCount + 1) % 20 == 0) {
                onIncrementalBatch?.invoke(tracks.toList())
            }
        }

        return tracks
    }

    private fun buildTrackFromEntryAndMetadata(
        entry: DiscoveredAudioEntry,
        rootFolderName: String,
        cached: CachedTagMetadata?
    ): Track {
        val nameWithoutExt = entry.displayName.substringBeforeLast('.')
        val cleanedFileTitle = cleanTrackTitleFromFilename(nameWithoutExt)

        // Infer default album and artist from folder structure when ID3 tags are absent
        val parentIsDiscFolder = DISC_FOLDER_REGEX.containsMatchIn(entry.parentFolderName)
        val inferredAlbum = if (parentIsDiscFolder) {
            entry.grandParentFolderName?.takeIf { it.isNotBlank() } ?: rootFolderName
        } else {
            entry.parentFolderName.ifBlank { rootFolderName }
        }

        val title = cached?.title?.takeIf { it.isNotBlank() } ?: cleanedFileTitle.ifBlank { nameWithoutExt }
        val artist = cached?.artist?.takeIf { it.isNotBlank() && !it.contains("<unknown>", ignoreCase = true) }
            ?: "Unknown Artist"
        val album = cached?.album?.takeIf { it.isNotBlank() && !it.contains("<unknown>", ignoreCase = true) }
            ?: inferredAlbum
        val genre = cached?.genre?.takeIf { it.isNotBlank() && !it.contains("<unknown>", ignoreCase = true) }
            ?: "General"
        val durationMs = cached?.durationMs ?: 0L
        val mimeType = cached?.mimeType?.takeIf { it.isNotBlank() }
            ?: entry.mimeType?.takeIf { it.isNotBlank() }
            ?: guessMimeTypeFromName(entry.displayName)

        val (discNum, trackNum) = parseDiscAndTrackNumbers(
            rawTrackColumn = null,
            cdTrackTag = cached?.cdTrackTag,
            discNumberTag = cached?.discTag,
            filePathOrName = entry.resolvedFilePath ?: entry.displayName,
            parentFolderName = entry.parentFolderName
        )

        val ext = entry.displayName.substringAfterLast('.', "").lowercase()
        val isWav = ext == "wav" || mimeType.contains("wav", ignoreCase = true) || ext == "mid" || ext == "midi"
        val hasArtwork = !isWav && ext in listOf("mp3", "flac", "m4a", "ogg", "opus", "aac", "mp4")

        val uriStr = entry.fileUri.toString()
        return Track(
            id = "folder_${uriStr.hashCode()}",
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            trackNumber = trackNum,
            discNumber = discNum,
            durationMs = durationMs,
            uri = uriStr,
            filePath = entry.resolvedFilePath,
            mimeType = mimeType,
            folderPath = rootFolderName,
            hasEmbeddedArtwork = hasArtwork
        )
    }

    private suspend fun extractMetadataSafely(fileUri: Uri): CachedTagMetadata? {
        return withTimeoutOrNull(800L) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, fileUri)
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim()
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()
                val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.trim()
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val cdTrackTag = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.trim()
                val discTag = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.trim()
                val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.trim()
                CachedTagMetadata(
                    title = title,
                    artist = artist,
                    album = album,
                    genre = genre,
                    durationMs = durationMs,
                    cdTrackTag = cdTrackTag,
                    discTag = discTag,
                    mimeType = mimeType
                )
            } catch (t: Throwable) {
                Log.w("MusicRepository", "Could not extract ID3 tags from $fileUri", t)
                null
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {}
            }
        }
    }

    private fun isAudioFile(mimeType: String?, fileName: String?): Boolean {
        if (mimeType != null) {
            val lowerMime = mimeType.lowercase()
            if (lowerMime.startsWith("audio/") && !lowerMime.contains("mpegurl") && !lowerMime.contains("scpls")) {
                return true
            }
            if (lowerMime == "application/ogg" || lowerMime == "application/x-flac") {
                return true
            }
        }
        val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: ""
        return ext in SUPPORTED_AUDIO_EXTENSIONS
    }

    private fun guessMimeTypeFromName(fileName: String?): String {
        return when (fileName?.substringAfterLast('.', "")?.lowercase()) {
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            "m4a", "mp4", "alac" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wma" -> "audio/x-ms-wma"
            "aiff", "aif" -> "audio/x-aiff"
            else -> "audio/mpeg"
        }
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
        }
        // Always trigger a library rescan even if the user re-selected an already included folder
        scope.launch { rescanLibrary() }
    }

    suspend fun addIncludedFolderAndScan(folderUriStr: String): Int {
        val current = _includedFolders.value.toMutableList()
        if (!current.contains(folderUriStr)) {
            current.add(folderUriStr)
            _includedFolders.value = current
            saveIncludedFolders(current)
        }
        rescanLibrary()
        return _tracks.value.size
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
                        val path = cursor.safeString(dataCol)
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
            val publicMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
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
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) {
                        fileName = cursor.safeString(nameIndex) ?: "Imported Playlist"
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

    private fun Cursor.safeString(colIndex: Int): String? {
        if (colIndex < 0) return null
        return try {
            if (isNull(colIndex)) null else getString(colIndex)
        } catch (_: Throwable) {
            null
        }
    }

    private fun Cursor.safeLong(colIndex: Int): Long? {
        if (colIndex < 0) return null
        return try {
            if (isNull(colIndex)) null else getLong(colIndex)
        } catch (_: Throwable) {
            try {
                getString(colIndex)?.toLongOrNull()
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun Cursor.safeInt(colIndex: Int): Int? {
        if (colIndex < 0) return null
        return try {
            if (isNull(colIndex)) null else getInt(colIndex)
        } catch (_: Throwable) {
            try {
                getString(colIndex)?.toIntOrNull()
            } catch (_: Throwable) {
                null
            }
        }
    }

    companion object {
        private val SUPPORTED_AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "ogg", "oga", "m4a", "aac", "opus",
            "wma", "mid", "midi", "alac", "aiff", "aif", "mka", "webm", "ape", "wv", "mp4"
        )
        private val DISC_FOLDER_REGEX = Regex("""(?i)(?:^|[/\\_\-\s(\[])(?:disc|disk|cd)\s*0*(\d{1,2})(?:$|[/\\_\-\s)\]])""")
        private val DISC_TRACK_FILENAME_REGEX = Regex("""^\s*0*(\d{1,2})\s*[-._]\s*0*(\d{1,3})(?:\s*[-._\s]+(.*)|$)""")
        private val LEADING_TRACK_FILENAME_REGEX = Regex("""^\s*0*(\d{1,3})\s*[-._)\s]+\s*(.+)$""")
        private val FIRST_NUMBER_REGEX = Regex("""(\d+)""")

        /**
         * Determines whether two [Track] instances refer to the exact same underlying audio file.
         * Never matches `null == null` on optional `filePath`.
         */
        fun isSameTrack(a: Track, b: Track): Boolean {
            if (a.uri.isNotBlank() && b.uri.isNotBlank() && a.uri == b.uri) return true
            val pathA = a.filePath?.takeIf { it.isNotBlank() }
            val pathB = b.filePath?.takeIf { it.isNotBlank() }
            if (pathA != null && pathB != null && pathA.equals(pathB, ignoreCase = true)) return true
            return false
        }

        /**
         * Merges [incomingTracks] into [baseTracks] without dropping tracks that have `filePath == null`.
         * When a track from a user-selected folder matches an existing MediaStore track and
         * [preferIncomingFolderPath] is true, updates its `folderPath` to the selected folder.
         */
        fun mergeTracksDeduplicated(
            baseTracks: List<Track>,
            incomingTracks: List<Track>,
            preferIncomingFolderPath: Boolean = false
        ): List<Track> {
            val result = baseTracks.toMutableList()
            for (incoming in incomingTracks) {
                val existingIdx = result.indexOfFirst { isSameTrack(it, incoming) }
                if (existingIdx < 0) {
                    result.add(incoming)
                } else if (preferIncomingFolderPath && incoming.folderPath.isNotBlank()) {
                    val existing = result[existingIdx]
                    result[existingIdx] = existing.copy(
                        folderPath = incoming.folderPath,
                        filePath = existing.filePath ?: incoming.filePath
                    )
                }
            }
            return result
        }

        /**
         * Resolves a human-readable folder name for a SAF tree URI or file URI.
         */
        fun resolveFolderDisplayName(context: Context, folderUriStr: String): String {
            return try {
                val uri = Uri.parse(folderUriStr)
                if (uri.scheme == "file") {
                    uri.path?.let { File(it).name }?.takeIf { it.isNotBlank() } ?: "Selected Folder"
                } else {
                    val docName = try {
                        DocumentFile.fromTreeUri(context, uri)?.name
                    } catch (_: Throwable) {
                        null
                    }
                    if (!docName.isNullOrBlank()) {
                        docName
                    } else if (DocumentsContract.isTreeUri(uri)) {
                        val docId = DocumentsContract.getTreeDocumentId(uri)
                        docId.substringAfterLast(':').trimEnd('/', '\\').substringAfterLast('/').takeIf { it.isNotBlank() }
                            ?: docId.substringBefore(':').takeIf { it.isNotBlank() }
                            ?: "Selected Folder"
                    } else {
                        uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                            ?: "Selected Folder"
                    }
                }
            } catch (_: Throwable) {
                "Selected Folder"
            }
        }

        fun resolveFilePathFromDocId(docId: String): String? {
            return try {
                when {
                    docId.startsWith("raw:") -> docId.removePrefix("raw:")
                    docId.startsWith("primary:") -> {
                        val rel = docId.removePrefix("primary:").trimStart('/')
                        "${Environment.getExternalStorageDirectory().absolutePath}/$rel"
                    }
                    docId.contains(":") -> {
                        val parts = docId.split(":", limit = 2)
                        val vol = parts[0]
                        val rel = parts[1].trimStart('/')
                        val candidate = File("/storage/$vol/$rel")
                        if (candidate.exists()) candidate.absolutePath else null
                    }
                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
        }

        fun resolveLocalDirectoryFromUri(uri: Uri): File? {
            return try {
                when {
                    uri.scheme == "file" -> uri.path?.let { File(it) }
                    DocumentsContract.isTreeUri(uri) -> {
                        val docId = DocumentsContract.getTreeDocumentId(uri)
                        resolveFilePathFromDocId(docId)?.let { File(it) }
                    }
                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
        }

        fun cleanTrackTitleFromFilename(nameWithoutExt: String): String {
            val trimmed = nameWithoutExt.trim()
            val dtMatch = DISC_TRACK_FILENAME_REGEX.find(trimmed)
            if (dtMatch != null) {
                val remainder = dtMatch.groupValues.getOrNull(3)?.trim()
                if (!remainder.isNullOrBlank()) return remainder
            }
            val leadMatch = LEADING_TRACK_FILENAME_REGEX.find(trimmed)
            if (leadMatch != null) {
                val remainder = leadMatch.groupValues.getOrNull(2)?.trim()
                if (!remainder.isNullOrBlank()) return remainder
            }
            return trimmed
        }

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
