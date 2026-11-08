package org.anmon.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.anmon.model.Track
import org.json.JSONArray
import org.json.JSONObject

data class StatItemEntry(
    val key: String,
    val title: String,
    val subtitle: String = "",
    val trackId: String? = null,
    val playCount: Int = 0,
    val listeningTimeMs: Long = 0L,
    val lastPlayedMs: Long = 0L
) {
    val formattedListeningTime: String
        get() = ListeningStatsManager.formatDurationHms(listeningTimeMs)
}

data class ListeningStatsSnapshot(
    val totalListeningTimeMs: Long = 0L,
    val totalTrackPlays: Int = 0,
    val songs: List<StatItemEntry> = emptyList(),
    val artists: List<StatItemEntry> = emptyList(),
    val albums: List<StatItemEntry> = emptyList(),
    val genres: List<StatItemEntry> = emptyList()
) {
    val hoursPart: Long
        get() = (totalListeningTimeMs / 1000L) / 3600L

    val minutesPart: Long
        get() = ((totalListeningTimeMs / 1000L) % 3600L) / 60L

    val secondsPart: Long
        get() = (totalListeningTimeMs / 1000L) % 60L

    val formattedTotalTime: String
        get() = ListeningStatsManager.formatDurationHms(totalListeningTimeMs)
}

class ListeningStatsManager private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var totalListeningTimeMs: Long = 0L
    private var totalTrackPlays: Int = 0

    private val songMap = LinkedHashMap<String, StatItemEntry>()
    private val artistMap = LinkedHashMap<String, StatItemEntry>()
    private val albumMap = LinkedHashMap<String, StatItemEntry>()
    private val genreMap = LinkedHashMap<String, StatItemEntry>()

    private var lastPersistedAtMs: Long = 0L
    private var lastRecordedPlayKey: String? = null
    private var lastRecordedPlayTimestampMs: Long = 0L

    private val _statsState = MutableStateFlow(ListeningStatsSnapshot())
    val statsState: StateFlow<ListeningStatsSnapshot> = _statsState.asStateFlow()

    init {
        loadFromPrefs()
        publishSnapshot()
    }

    @Synchronized
    fun recordTrackPlay(track: Track, forceCount: Boolean = false) {
        val now = System.currentTimeMillis()
        val dedupeKey = "${track.id}|${track.title}"
        if (!forceCount && dedupeKey == lastRecordedPlayKey && (now - lastRecordedPlayTimestampMs) < 500L) {
            return
        }
        lastRecordedPlayKey = dedupeKey
        lastRecordedPlayTimestampMs = now

        totalTrackPlays += 1

        val songKey = track.id.ifBlank { "${track.title.lowercase()}|${track.artist.lowercase()}" }
        val artistName = track.artist.trim().ifEmpty { "Unknown Artist" }
        val artistKey = artistName.lowercase()
        val albumName = track.album.trim().ifEmpty { "Unknown Album" }
        val albumKey = "${albumName.lowercase()}|$artistKey"
        val genreName = normalizeGenre(track.genre)
        val genreKey = genreName.lowercase()

        val existingSong = songMap[songKey]
        songMap[songKey] = StatItemEntry(
            key = songKey,
            title = track.title.ifEmpty { "Unknown Track" },
            subtitle = "$artistName • $albumName",
            trackId = track.id,
            playCount = (existingSong?.playCount ?: 0) + 1,
            listeningTimeMs = existingSong?.listeningTimeMs ?: 0L,
            lastPlayedMs = now
        )

        val existingArtist = artistMap[artistKey]
        artistMap[artistKey] = StatItemEntry(
            key = artistKey,
            title = artistName,
            subtitle = albumName,
            trackId = track.id,
            playCount = (existingArtist?.playCount ?: 0) + 1,
            listeningTimeMs = existingArtist?.listeningTimeMs ?: 0L,
            lastPlayedMs = now
        )

        val existingAlbum = albumMap[albumKey]
        albumMap[albumKey] = StatItemEntry(
            key = albumKey,
            title = albumName,
            subtitle = artistName,
            trackId = track.id,
            playCount = (existingAlbum?.playCount ?: 0) + 1,
            listeningTimeMs = existingAlbum?.listeningTimeMs ?: 0L,
            lastPlayedMs = now
        )

        val existingGenre = genreMap[genreKey]
        genreMap[genreKey] = StatItemEntry(
            key = genreKey,
            title = genreName,
            subtitle = artistName,
            trackId = track.id,
            playCount = (existingGenre?.playCount ?: 0) + 1,
            listeningTimeMs = existingGenre?.listeningTimeMs ?: 0L,
            lastPlayedMs = now
        )

        publishSnapshot()
        saveToPrefs()
    }

    @Synchronized
    fun recordListeningDuration(track: Track, deltaMs: Long, persistImmediately: Boolean = false) {
        if (deltaMs <= 0L) return
        val now = System.currentTimeMillis()
        totalListeningTimeMs += deltaMs

        val songKey = track.id.ifBlank { "${track.title.lowercase()}|${track.artist.lowercase()}" }
        val artistName = track.artist.trim().ifEmpty { "Unknown Artist" }
        val artistKey = artistName.lowercase()
        val albumName = track.album.trim().ifEmpty { "Unknown Album" }
        val albumKey = "${albumName.lowercase()}|$artistKey"
        val genreName = normalizeGenre(track.genre)
        val genreKey = genreName.lowercase()

        val existingSong = songMap[songKey]
        songMap[songKey] = StatItemEntry(
            key = songKey,
            title = track.title.ifEmpty { "Unknown Track" },
            subtitle = "$artistName • $albumName",
            trackId = track.id,
            playCount = (existingSong?.playCount ?: 1).coerceAtLeast(1),
            listeningTimeMs = (existingSong?.listeningTimeMs ?: 0L) + deltaMs,
            lastPlayedMs = now
        )

        val existingArtist = artistMap[artistKey]
        artistMap[artistKey] = StatItemEntry(
            key = artistKey,
            title = artistName,
            subtitle = albumName,
            trackId = track.id,
            playCount = (existingArtist?.playCount ?: 1).coerceAtLeast(1),
            listeningTimeMs = (existingArtist?.listeningTimeMs ?: 0L) + deltaMs,
            lastPlayedMs = now
        )

        val existingAlbum = albumMap[albumKey]
        albumMap[albumKey] = StatItemEntry(
            key = albumKey,
            title = albumName,
            subtitle = artistName,
            trackId = track.id,
            playCount = (existingAlbum?.playCount ?: 1).coerceAtLeast(1),
            listeningTimeMs = (existingAlbum?.listeningTimeMs ?: 0L) + deltaMs,
            lastPlayedMs = now
        )

        val existingGenre = genreMap[genreKey]
        genreMap[genreKey] = StatItemEntry(
            key = genreKey,
            title = genreName,
            subtitle = artistName,
            trackId = track.id,
            playCount = (existingGenre?.playCount ?: 1).coerceAtLeast(1),
            listeningTimeMs = (existingGenre?.listeningTimeMs ?: 0L) + deltaMs,
            lastPlayedMs = now
        )

        publishSnapshot()

        if (persistImmediately || (now - lastPersistedAtMs) >= 2_000L) {
            lastPersistedAtMs = now
            saveToPrefs()
        }
    }

    @Synchronized
    fun flushToDisk() {
        saveToPrefs()
    }

    @Synchronized
    fun resetStats() {
        totalListeningTimeMs = 0L
        totalTrackPlays = 0
        songMap.clear()
        artistMap.clear()
        albumMap.clear()
        genreMap.clear()
        lastRecordedPlayKey = null
        lastRecordedPlayTimestampMs = 0L
        prefs.edit().clear().apply()
        publishSnapshot()
    }

    private fun normalizeGenre(rawGenre: String?): String {
        val cleaned = rawGenre?.trim().orEmpty()
        return if (cleaned.isEmpty() || cleaned.contains("<unknown>", ignoreCase = true)) {
            "General"
        } else {
            cleaned
        }
    }

    private fun sortEntries(entries: Collection<StatItemEntry>): List<StatItemEntry> {
        return entries.sortedWith(
            compareByDescending<StatItemEntry> { it.playCount }
                .thenByDescending { it.listeningTimeMs }
                .thenByDescending { it.lastPlayedMs }
                .thenBy { it.title.lowercase() }
        )
    }

    private fun publishSnapshot() {
        _statsState.value = ListeningStatsSnapshot(
            totalListeningTimeMs = totalListeningTimeMs,
            totalTrackPlays = totalTrackPlays,
            songs = sortEntries(songMap.values),
            artists = sortEntries(artistMap.values),
            albums = sortEntries(albumMap.values),
            genres = sortEntries(genreMap.values)
        )
    }

    private fun saveToPrefs() {
        try {
            prefs.edit()
                .putLong(KEY_TOTAL_TIME_MS, totalListeningTimeMs)
                .putInt(KEY_TOTAL_PLAYS, totalTrackPlays)
                .putString(KEY_SONGS_JSON, encodeEntries(songMap.values))
                .putString(KEY_ARTISTS_JSON, encodeEntries(artistMap.values))
                .putString(KEY_ALBUMS_JSON, encodeEntries(albumMap.values))
                .putString(KEY_GENRES_JSON, encodeEntries(genreMap.values))
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun loadFromPrefs() {
        try {
            totalListeningTimeMs = prefs.getLong(KEY_TOTAL_TIME_MS, 0L).coerceAtLeast(0L)
            totalTrackPlays = prefs.getInt(KEY_TOTAL_PLAYS, 0).coerceAtLeast(0)
            songMap.clear()
            artistMap.clear()
            albumMap.clear()
            genreMap.clear()

            decodeEntries(prefs.getString(KEY_SONGS_JSON, null)).forEach { songMap[it.key] = it }
            decodeEntries(prefs.getString(KEY_ARTISTS_JSON, null)).forEach { artistMap[it.key] = it }
            decodeEntries(prefs.getString(KEY_ALBUMS_JSON, null)).forEach { albumMap[it.key] = it }
            decodeEntries(prefs.getString(KEY_GENRES_JSON, null)).forEach { genreMap[it.key] = it }
        } catch (_: Exception) {
        }
    }

    private fun encodeEntries(entries: Collection<StatItemEntry>): String {
        val arr = JSONArray()
        for (entry in entries) {
            val obj = JSONObject().apply {
                put("key", entry.key)
                put("title", entry.title)
                put("subtitle", entry.subtitle)
                put("trackId", entry.trackId ?: "")
                put("playCount", entry.playCount)
                put("listeningTimeMs", entry.listeningTimeMs)
                put("lastPlayedMs", entry.lastPlayedMs)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun decodeEntries(jsonStr: String?): List<StatItemEntry> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        val arr = JSONArray(jsonStr)
        val result = mutableListOf<StatItemEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val key = obj.optString("key", "")
            if (key.isEmpty()) continue
            val trackIdRaw = obj.optString("trackId", "")
            result.add(
                StatItemEntry(
                    key = key,
                    title = obj.optString("title", "Unknown"),
                    subtitle = obj.optString("subtitle", ""),
                    trackId = trackIdRaw.ifEmpty { null },
                    playCount = obj.optInt("playCount", 0).coerceAtLeast(0),
                    listeningTimeMs = obj.optLong("listeningTimeMs", 0L).coerceAtLeast(0L),
                    lastPlayedMs = obj.optLong("lastPlayedMs", 0L).coerceAtLeast(0L)
                )
            )
        }
        return result
    }

    companion object {
        private const val PREFS_NAME = "aura_listening_stats_prefs"
        private const val KEY_TOTAL_TIME_MS = "total_listening_time_ms"
        private const val KEY_TOTAL_PLAYS = "total_track_plays"
        private const val KEY_SONGS_JSON = "songs_stats_json"
        private const val KEY_ARTISTS_JSON = "artists_stats_json"
        private const val KEY_ALBUMS_JSON = "albums_stats_json"
        private const val KEY_GENRES_JSON = "genres_stats_json"

        @Volatile
        private var instance: ListeningStatsManager? = null

        fun get(context: Context): ListeningStatsManager {
            return instance ?: synchronized(this) {
                instance ?: ListeningStatsManager(context.applicationContext).also { instance = it }
            }
        }

        fun resetForTesting(context: Context) {
            synchronized(this) {
                val mgr = get(context)
                mgr.resetStats()
            }
        }

        fun formatDurationHms(durationMs: Long): String {
            val totalSeconds = (durationMs.coerceAtLeast(0L)) / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return when {
                hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
                minutes > 0L -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        }
    }
}
