package org.anmon.data

import android.content.Context
import android.util.Log
import org.anmon.model.Playlist
import org.anmon.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal data class CachedTagMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val durationMs: Long?,
    val cdTrackTag: String?,
    val discTag: String?,
    val mimeType: String?
)

internal data class PersistedLibrarySnapshot(
    val fingerprint: String,
    val tagsComplete: Boolean,
    val includedFolders: List<String>,
    val tracks: List<Track>,
    val m3uPlaylists: List<Playlist>
)

internal class LibraryCacheStore(context: Context) {
    private val filesDir: File = context.filesDir
    private val libraryCacheFile = File(filesDir, "aura_library_cache_v1.json")
    private val tagCacheFile = File(filesDir, "aura_tag_cache_v1.json")
    private val cacheLock = Any()

    fun loadLibraryCache(favorites: Set<String>): PersistedLibrarySnapshot? = synchronized(cacheLock) {
        try {
            if (!libraryCacheFile.exists()) return null
            val rawJson = libraryCacheFile.readText()
            if (rawJson.isBlank()) return null
            val root = JSONObject(rawJson)
            val fingerprint = root.optString("fingerprint", "")
            val tagsComplete = root.optBoolean("tagsComplete", true)

            val foldersArray = root.optJSONArray("includedFolders") ?: JSONArray()
            val includedFolders = ArrayList<String>(foldersArray.length())
            for (i in 0 until foldersArray.length()) {
                val uri = foldersArray.optString(i, "")
                if (uri.isNotBlank()) includedFolders.add(uri)
            }

            val tracksArray = root.optJSONArray("tracks") ?: JSONArray()
            val tracks = ArrayList<Track>(tracksArray.length())
            for (i in 0 until tracksArray.length()) {
                val obj = tracksArray.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                val uri = obj.optString("uri", "")
                if (id.isBlank() || uri.isBlank()) continue
                val artist = obj.optString("artist", "Unknown Artist")
                val albumIdRaw = obj.optLong("albumId", -1L)
                val filePathRaw = if (obj.isNull("filePath")) null else obj.optString("filePath", "").takeIf { it.isNotBlank() }

                tracks.add(
                    Track(
                        id = id,
                        title = obj.optString("title", "Unknown Track"),
                        artist = artist,
                        album = obj.optString("album", "Unknown Album"),
                        albumArtist = obj.optString("albumArtist", artist),
                        genre = obj.optString("genre", "General"),
                        trackNumber = obj.optInt("trackNumber", 1),
                        discNumber = obj.optInt("discNumber", 1),
                        durationMs = obj.optLong("durationMs", 0L),
                        uri = uri,
                        filePath = filePathRaw,
                        mimeType = obj.optString("mimeType", "audio/mpeg"),
                        bitrateKbps = obj.optInt("bitrateKbps", 320),
                        sampleRateHz = obj.optInt("sampleRateHz", 44100),
                        dateAdded = obj.optLong("dateAdded", 0L),
                        folderPath = obj.optString("folderPath", "Default"),
                        hasEmbeddedArtwork = obj.optBoolean("hasEmbeddedArtwork", false),
                        albumId = if (albumIdRaw >= 0L) albumIdRaw else null,
                        isFavorite = favorites.contains(id)
                    )
                )
            }

            val m3uArray = root.optJSONArray("m3uPlaylists") ?: JSONArray()
            val m3uPlaylists = ArrayList<Playlist>(m3uArray.length())
            for (i in 0 until m3uArray.length()) {
                val obj = m3uArray.optJSONObject(i) ?: continue
                val trackIdsJson = obj.optJSONArray("trackIds") ?: JSONArray()
                val trackIds = ArrayList<String>(trackIdsJson.length())
                for (j in 0 until trackIdsJson.length()) {
                    trackIds.add(trackIdsJson.getString(j))
                }
                m3uPlaylists.add(
                    Playlist(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        trackIds = trackIds,
                        createdDate = obj.optLong("createdDate", System.currentTimeMillis()),
                        isSystemFavorites = false,
                        isM3u = true,
                        filePath = if (obj.isNull("filePath")) null else obj.optString("filePath", "").takeIf { it.isNotBlank() }
                    )
                )
            }

            PersistedLibrarySnapshot(
                fingerprint = fingerprint,
                tagsComplete = tagsComplete,
                includedFolders = includedFolders,
                tracks = tracks,
                m3uPlaylists = m3uPlaylists
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read persisted library cache", t)
            null
        }
    }

    fun saveLibraryCache(
        fingerprint: String,
        tagsComplete: Boolean,
        includedFolders: List<String>,
        tracks: List<Track>,
        m3uPlaylists: List<Playlist>
    ): Unit = synchronized(cacheLock) {
        try {
            val root = JSONObject()
            root.put("version", 2)
            root.put("fingerprint", fingerprint)
            root.put("tagsComplete", tagsComplete)

            val foldersArray = JSONArray()
            for (uri in includedFolders) {
                foldersArray.put(uri)
            }
            root.put("includedFolders", foldersArray)

            val tracksArray = JSONArray()
            for (t in tracks) {
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("album", t.album)
                    put("albumArtist", t.albumArtist)
                    put("genre", t.genre)
                    put("trackNumber", t.trackNumber)
                    put("discNumber", t.discNumber)
                    put("durationMs", t.durationMs)
                    put("uri", t.uri)
                    if (t.filePath != null) put("filePath", t.filePath) else put("filePath", JSONObject.NULL)
                    put("mimeType", t.mimeType)
                    put("bitrateKbps", t.bitrateKbps)
                    put("sampleRateHz", t.sampleRateHz)
                    put("dateAdded", t.dateAdded)
                    put("folderPath", t.folderPath)
                    put("hasEmbeddedArtwork", t.hasEmbeddedArtwork)
                    put("albumId", t.albumId ?: -1L)
                }
                tracksArray.put(obj)
            }
            root.put("tracks", tracksArray)

            val m3uArray = JSONArray()
            for (pl in m3uPlaylists) {
                val obj = JSONObject().apply {
                    put("id", pl.id)
                    put("name", pl.name)
                    put("createdDate", pl.createdDate)
                    if (pl.filePath != null) put("filePath", pl.filePath) else put("filePath", JSONObject.NULL)
                    val ids = JSONArray()
                    pl.trackIds.forEach { ids.put(it) }
                    put("trackIds", ids)
                }
                m3uArray.put(obj)
            }
            root.put("m3uPlaylists", m3uArray)

            writeAtomically(libraryCacheFile, root.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed writing library cache file", t)
        }
    }

    fun loadTagCache(targetMap: ConcurrentHashMap<String, CachedTagMetadata>): Unit = synchronized(cacheLock) {
        try {
            if (!tagCacheFile.exists()) return
            val raw = tagCacheFile.readText()
            if (raw.isBlank()) return
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val uriKey = keys.next()
                val obj = root.optJSONObject(uriKey) ?: continue
                targetMap[uriKey] = CachedTagMetadata(
                    title = obj.optNullableString("title"),
                    artist = obj.optNullableString("artist"),
                    album = obj.optNullableString("album"),
                    genre = obj.optNullableString("genre"),
                    durationMs = if (obj.has("durationMs") && !obj.isNull("durationMs")) obj.optLong("durationMs") else null,
                    cdTrackTag = obj.optNullableString("cdTrackTag"),
                    discTag = obj.optNullableString("discTag"),
                    mimeType = obj.optNullableString("mimeType")
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not load persisted tag cache", t)
        }
    }

    fun saveTagCache(sourceMap: Map<String, CachedTagMetadata>): Unit = synchronized(cacheLock) {
        try {
            val root = JSONObject()
            for ((uriKey, meta) in sourceMap) {
                val obj = JSONObject().apply {
                    if (meta.title != null) put("title", meta.title)
                    if (meta.artist != null) put("artist", meta.artist)
                    if (meta.album != null) put("album", meta.album)
                    if (meta.genre != null) put("genre", meta.genre)
                    if (meta.durationMs != null) put("durationMs", meta.durationMs)
                    if (meta.cdTrackTag != null) put("cdTrackTag", meta.cdTrackTag)
                    if (meta.discTag != null) put("discTag", meta.discTag)
                    if (meta.mimeType != null) put("mimeType", meta.mimeType)
                }
                root.put(uriKey, obj)
            }
            writeAtomically(tagCacheFile, root.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "Could not save persisted tag cache", t)
        }
    }

    private fun writeAtomically(target: File, content: String) {
        val tmpFile = File(filesDir, "${target.name}.tmp")
        tmpFile.writeText(content)
        if (!tmpFile.renameTo(target)) {
            target.writeText(content)
            tmpFile.delete()
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").takeIf { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "LibraryCacheStore"

        /**
         * SplitMix64 hash mixer used with commutative XOR + SUM aggregation so storage fingerprints
         * are 100% deterministic regardless of filesystem or SQLite cursor ordering.
         */
        fun mix64(input: Long): Long {
            var z = input + -7046029254386353131L // 0x9e3779b97f4a7c15
            z = (z xor (z ushr 30)) * -4658895280553007687L // 0xbf58476d1ce4e5b9
            z = (z xor (z ushr 27)) * -7723592293110705685L // 0x94d049bb133111eb
            return z xor (z ushr 31)
        }
    }
}
