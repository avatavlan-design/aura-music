package com.example.data

import com.example.model.Track
import java.io.File
import java.io.InputStream

data class M3uEntry(
    val pathOrUri: String,
    val title: String? = null,
    val artist: String? = null,
    val durationSeconds: Long? = null
)

object M3uParser {

    /**
     * Parses the string content of an .m3u or .m3u8 playlist file into [M3uEntry] items.
     */
    fun parse(content: String): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        val lines = content.lines()
        var currentTitle: String? = null
        var currentArtist: String? = null
        var currentDuration: Long? = null

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                // Format: #EXTINF:<duration>,[artist - ]<title>
                val info = line.substringAfter("#EXTINF:", "").trim()
                val durationPart = info.substringBefore(',').trim()
                currentDuration = durationPart.toLongOrNull()

                val titlePart = info.substringAfter(',', "").trim()
                if (titlePart.contains(" - ")) {
                    currentArtist = titlePart.substringBefore(" - ").trim()
                    currentTitle = titlePart.substringAfter(" - ").trim()
                } else if (titlePart.isNotEmpty()) {
                    currentTitle = titlePart
                }
            } else if (line.startsWith("#")) {
                // Ignore other directives / comments (#EXTM3U, #PLAYLIST, etc.)
                continue
            } else {
                entries.add(
                    M3uEntry(
                        pathOrUri = line,
                        title = currentTitle,
                        artist = currentArtist,
                        durationSeconds = currentDuration
                    )
                )
                currentTitle = null
                currentArtist = null
                currentDuration = null
            }
        }
        return entries
    }

    /**
     * Parses an input stream of an .m3u or .m3u8 file.
     */
    fun parseStream(inputStream: InputStream): List<M3uEntry> {
        return parse(inputStream.bufferedReader().use { it.readText() })
    }

    /**
     * Resolves M3U entries into known track IDs from the library.
     */
    fun resolveTracks(
        entries: List<M3uEntry>,
        m3uParentDir: File?,
        allTracks: List<Track>
    ): List<String> {
        val trackIds = mutableListOf<String>()

        for (entry in entries) {
            val normalizedPath = entry.pathOrUri.replace('\\', '/')
            val filename = normalizedPath.substringAfterLast('/')

            var matchedTrack: Track? = null

            // 1. Check absolute file path directly or relative to m3u folder
            if (m3uParentDir != null) {
                val candidateFile = if (normalizedPath.startsWith("/")) {
                    File(normalizedPath)
                } else {
                    File(m3uParentDir, normalizedPath)
                }
                val candidateCanonical = try { candidateFile.canonicalPath } catch (e: Exception) { candidateFile.absolutePath }
                matchedTrack = allTracks.firstOrNull { it.filePath != null && it.filePath.equals(candidateCanonical, ignoreCase = true) }
            }

            // 2. Check filePath matches normalizedPath directly
            if (matchedTrack == null) {
                matchedTrack = allTracks.firstOrNull { it.filePath != null && it.filePath.equals(normalizedPath, ignoreCase = true) }
            }

            // 3. Check filename match (e.g. "01_starlight_reverie.wav")
            if (matchedTrack == null) {
                matchedTrack = allTracks.firstOrNull { track ->
                    val tf = track.filePath?.substringAfterLast('/') ?: track.uri.substringAfterLast('/')
                    tf.equals(filename, ignoreCase = true)
                }
            }

            // 4. Check EXTINF title and artist
            if (matchedTrack == null && !entry.title.isNullOrBlank()) {
                matchedTrack = allTracks.firstOrNull { track ->
                    val titleMatch = track.title.equals(entry.title, ignoreCase = true)
                    val artistMatch = entry.artist.isNullOrBlank() || track.artist.equals(entry.artist, ignoreCase = true)
                    titleMatch && artistMatch
                }
            }

            // 5. Check song title matches filename without extension
            if (matchedTrack == null) {
                val titleFromFilename = filename.substringBeforeLast('.')
                matchedTrack = allTracks.firstOrNull { track ->
                    track.title.equals(titleFromFilename, ignoreCase = true)
                }
            }

            // 6. Check URI contains filename
            if (matchedTrack == null) {
                matchedTrack = allTracks.firstOrNull { track ->
                    track.uri.contains(filename, ignoreCase = true)
                }
            }

            if (matchedTrack != null && !trackIds.contains(matchedTrack.id)) {
                trackIds.add(matchedTrack.id)
            }
        }

        return trackIds
    }
}
