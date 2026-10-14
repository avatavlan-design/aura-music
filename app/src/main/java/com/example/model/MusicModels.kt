package com.example.model

data class Track(
    val id: String,
    val title: String,
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
    val albumArtist: String = artist,
    val genre: String = "General",
    val trackNumber: Int = 1,
    val discNumber: Int = 1,
    val durationMs: Long = 0L,
    val uri: String,
    val filePath: String? = null,
    val mimeType: String = "audio/mpeg",
    val bitrateKbps: Int = 320,
    val sampleRateHz: Int = 44100,
    val dateAdded: Long = System.currentTimeMillis(),
    val folderPath: String = "Default",
    val hasEmbeddedArtwork: Boolean = false,
    val albumId: Long? = null,
    val isFavorite: Boolean = false
) {
    val durationFormatted: String
        get() {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }

    val formatLabel: String
        get() {
            val extension = uri.substringAfterLast('.', "").uppercase()
            return when {
                extension.isNotEmpty() && extension.length <= 4 -> extension
                mimeType.contains("flac") -> "FLAC"
                mimeType.contains("wav") -> "WAV"
                mimeType.contains("ogg") || mimeType.contains("opus") -> "OGG"
                mimeType.contains("aac") || mimeType.contains("mp4") || mimeType.contains("m4a") -> "AAC"
                else -> "MP3"
            }
        }
}

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val trackCount: Int,
    val totalDurationMs: Long,
    val year: String? = null,
    val tracks: List<Track> = emptyList()
) {
    val durationFormatted: String
        get() {
            val totalMinutes = (totalDurationMs / 60000).coerceAtLeast(0)
            return "$totalMinutes mins"
        }
}

data class Artist(
    val id: String,
    val name: String,
    val trackCount: Int,
    val albumCount: Int,
    val tracks: List<Track> = emptyList()
)

data class Playlist(
    val id: String,
    val name: String,
    val trackIds: List<String> = emptyList(),
    val createdDate: Long = System.currentTimeMillis(),
    val isSystemFavorites: Boolean = false
)

enum class SortOption(val displayName: String) {
    TITLE("Song Title"),
    ARTIST("Artist Name"),
    ALBUM("Album Title"),
    DURATION("Duration"),
    DATE_ADDED("Recently Added")
}

enum class LibraryTab(val displayName: String) {
    TRACKS("Songs"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    PLAYLISTS("Playlists"),
    FAVORITES("Favorites"),
    FOLDERS("Folders")
}

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}
