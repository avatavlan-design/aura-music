package com.example.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.example.model.RepeatMode
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class PlaybackManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "PlaybackManager"
    private val sessionPrefs = context.getSharedPreferences(SESSION_PREFS_NAME, Context.MODE_PRIVATE)

    // Tuned LoadControl for aggressive seamless preloading
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 15_000,
            /* maxBufferMs = */ 60_000,
            /* bufferForPlaybackMs = */ 500,
            /* bufferForPlaybackAfterRebufferMs = */ 1_000
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(10_000, false)
        .build()

    var isReleased: Boolean = false
        private set

    private var _exoPlayer: ExoPlayer? = null

    val exoPlayer: ExoPlayer
        get() = ensurePlayerActive()

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var activeQueue: List<Track> = emptyList()
    private var progressJob: Job? = null
    private var lastPersistedTickMs: Long = 0L

    init {
        restorePlaybackSession()
        ensurePlayerActive()
    }

    fun ensurePlayerActive(): ExoPlayer {
        val existing = _exoPlayer
        if (existing != null && !isReleased) {
            return existing
        }
        isReleased = false
        val newPlayer = createExoPlayer()
        _exoPlayer = newPlayer
        attachListeners(newPlayer)

        // If we already have an active or restored queue, prime the player with the queue items
        if (activeQueue.isNotEmpty() && newPlayer.mediaItemCount == 0) {
            try {
                val mediaItems = activeQueue.map { it.toMediaItem() }
                val startIdx = _playbackState.value.currentIndex.coerceIn(0, activeQueue.size - 1)
                val startPos = _playbackState.value.currentPositionMs.coerceAtLeast(0L)
                newPlayer.setMediaItems(mediaItems, startIdx, startPos)
                newPlayer.prepare()
            } catch (e: Exception) {
                Log.w(TAG, "Unable to pre-populate restored queue into ExoPlayer", e)
            }
        }
        return newPlayer
    }

    private fun createExoPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().apply {
                repeatMode = when (_playbackState.value.repeatMode) {
                    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                }
                shuffleModeEnabled = _playbackState.value.isShuffleEnabled
            }
    }

    private fun attachListeners(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCurrentTrackFromPlayer()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val currentPos = player.currentPosition.coerceAtLeast(0L)
                val resolvedPos = if (currentPos > 0L) currentPos else _playbackState.value.currentPositionMs
                val resolvedDur = if (player.duration > 0L) player.duration else _playbackState.value.durationMs
                updateState {
                    it.copy(
                        isPlaying = isPlaying,
                        currentPositionMs = resolvedPos,
                        durationMs = resolvedDur
                    )
                }
                if (isPlaying) {
                    startProgressTicker()
                } else {
                    stopProgressTicker()
                    savePlaybackSession()
                }
                syncWidgetsAndNotification()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) {
                    val pos = player.currentPosition.coerceAtLeast(_playbackState.value.currentPositionMs)
                    updateState { it.copy(currentPositionMs = pos) }
                    savePlaybackSession()
                }
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                    // External audio focus loss (e.g. Instagram video started playing):
                    // Capture and persist the exact playback position immediately.
                    val pos = player.currentPosition.coerceAtLeast(_playbackState.value.currentPositionMs)
                    updateState { it.copy(currentPositionMs = pos, isPlaying = player.isPlaying) }
                    savePlaybackSession()
                    syncWidgetsAndNotification()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        val resolvedPos = player.currentPosition.coerceAtLeast(_playbackState.value.currentPositionMs)
                        val resolvedDur = if (player.duration > 0L) player.duration else _playbackState.value.durationMs
                        updateState {
                            it.copy(
                                durationMs = resolvedDur,
                                currentPositionMs = resolvedPos,
                                isPreloaded = player.hasNextMediaItem()
                            )
                        }
                        savePlaybackSession()
                    }
                    Player.STATE_ENDED -> {
                        if (player.repeatMode == Player.REPEAT_MODE_OFF && !player.hasNextMediaItem()) {
                            updateState { it.copy(isPlaying = false, currentPositionMs = 0L) }
                            stopProgressTicker()
                            savePlaybackSession()
                        }
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "ExoPlayer error encountered; preserving session for clean recovery", error)
                val pos = player.currentPosition.coerceAtLeast(_playbackState.value.currentPositionMs)
                updateState { it.copy(isPlaying = false, currentPositionMs = pos) }
                savePlaybackSession()
                syncWidgetsAndNotification()
            }
        })
    }

    private fun updateState(transform: (PlaybackState) -> PlaybackState) {
        val updated = transform(_playbackState.value)
        _playbackState.value = updated
        PlaybackManagerProvider.updateSharedState(updated)
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val player = _exoPlayer
                if (player != null && player.isPlaying) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    val dur = if (player.duration > 0L) player.duration else _playbackState.value.durationMs
                    updateState {
                        it.copy(
                            currentPositionMs = pos,
                            durationMs = dur,
                            isPreloaded = player.hasNextMediaItem()
                        )
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastPersistedTickMs >= 2000L) {
                        lastPersistedTickMs = now
                        savePlaybackSession()
                    }
                }
                delay(250)
            }
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
    }

    /**
     * Plays a list of tracks (e.g. an album or playlist) starting at startIndex.
     * All tracks are queued directly into ExoPlayer so the next track is pre-buffered.
     */
    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return

        val safeIndex = startIndex.coerceIn(0, tracks.size - 1)
        val player = ensurePlayerActive()
        activeQueue = tracks
        val mediaItems = tracks.map { it.toMediaItem() }

        player.setMediaItems(mediaItems, safeIndex, 0L)
        player.prepare()
        if (player.playWhenReady) {
            player.playWhenReady = false
        }
        player.playWhenReady = true
        player.play()

        val currentTrack = tracks.getOrNull(safeIndex)
        updateState {
            it.copy(
                queue = tracks,
                currentIndex = safeIndex,
                currentTrack = currentTrack,
                durationMs = currentTrack?.durationMs ?: 0L,
                currentPositionMs = 0L,
                isPlaying = true,
                isPreloaded = tracks.size > 1
            )
        }
        savePlaybackSession()
        syncWidgetsAndNotification()
    }

    /**
     * Swipe-to-Queue feature:
     * Appends track to the current queue and ExoPlayer playlist.
     * If nothing is playing, starts playing immediately.
     */
    fun addToQueue(track: Track, playNext: Boolean = false) {
        val player = ensurePlayerActive()
        if (activeQueue.isEmpty()) {
            playQueue(listOf(track), 0)
            return
        }

        val currentIndex = player.currentMediaItemIndex.takeIf { it in activeQueue.indices }
            ?: _playbackState.value.currentIndex.coerceIn(0, activeQueue.size - 1)
        val insertIndex = if (playNext && currentIndex in activeQueue.indices) {
            currentIndex + 1
        } else {
            activeQueue.size
        }

        val newQueue = activeQueue.toMutableList()
        newQueue.add(insertIndex, track)
        activeQueue = newQueue

        if (player.mediaItemCount > 0) {
            player.addMediaItem(insertIndex.coerceAtMost(player.mediaItemCount), track.toMediaItem())
        } else {
            val mediaItems = activeQueue.map { it.toMediaItem() }
            player.setMediaItems(mediaItems, currentIndex, _playbackState.value.currentPositionMs)
            player.prepare()
        }

        updateState {
            it.copy(
                queue = newQueue,
                isPreloaded = player.hasNextMediaItem()
            )
        }
        savePlaybackSession()
    }

    fun playPause() {
        val player = ensurePlayerActive()
        if (player.isPlaying) {
            val pos = player.currentPosition.coerceAtLeast(_playbackState.value.currentPositionMs)
            player.pause()
            updateState { it.copy(isPlaying = false, currentPositionMs = pos) }
            savePlaybackSession()
            syncWidgetsAndNotification()
        } else {
            resumePlayback()
        }
    }

    /**
     * Resumes playback reliably even after external audio focus loss (e.g. Instagram video),
     * decoder release, or background service/process recreation.
     */
    fun resumePlayback() {
        val player = ensurePlayerActive()
        if (activeQueue.isEmpty()) {
            restorePlaybackSession()
        }
        if (activeQueue.isEmpty()) return

        val targetIndex = _playbackState.value.currentIndex.coerceIn(0, activeQueue.size - 1)
        val playerPos = if (player.currentMediaItemIndex == targetIndex) player.currentPosition else 0L
        val targetPosition = maxOf(playerPos, _playbackState.value.currentPositionMs, 0L)

        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE || player.playerError != null) {
            val mediaItems = activeQueue.map { it.toMediaItem() }
            player.setMediaItems(mediaItems, targetIndex, targetPosition)
            player.prepare()
        } else if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(targetIndex, 0L)
        } else if (player.currentMediaItemIndex != targetIndex) {
            player.seekTo(targetIndex, targetPosition)
        }

        // If playWhenReady stayed true during transient audio focus suppression,
        // toggling false -> true forces ExoPlayer's AudioFocusManager to re-request audio focus.
        if (player.playWhenReady) {
            player.playWhenReady = false
        }
        player.playWhenReady = true
        player.play()

        val currentTrack = activeQueue.getOrNull(targetIndex)
        updateState {
            it.copy(
                queue = activeQueue,
                currentIndex = targetIndex,
                currentTrack = currentTrack,
                durationMs = if (player.duration > 0L) player.duration else (currentTrack?.durationMs ?: it.durationMs),
                currentPositionMs = if (player.playbackState == Player.STATE_ENDED) 0L else targetPosition,
                isPlaying = true,
                isPreloaded = activeQueue.size > 1
            )
        }
        savePlaybackSession()
        syncWidgetsAndNotification()
    }

    fun skipToNext() {
        val player = ensurePlayerActive()
        if (activeQueue.isEmpty()) {
            restorePlaybackSession()
        }
        if (activeQueue.isEmpty()) return

        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE || player.playerError != null) {
            val nextIdx = ((_playbackState.value.currentIndex + 1) % activeQueue.size).coerceIn(0, activeQueue.size - 1)
            val mediaItems = activeQueue.map { it.toMediaItem() }
            player.setMediaItems(mediaItems, nextIdx, 0L)
            player.prepare()
        } else if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else if (player.repeatMode == Player.REPEAT_MODE_ALL && activeQueue.isNotEmpty()) {
            player.seekTo(0, 0L)
        }

        if (player.playWhenReady) {
            player.playWhenReady = false
        }
        player.playWhenReady = true
        player.play()
        updateCurrentTrackFromPlayer()
    }

    fun skipToPrevious() {
        val player = ensurePlayerActive()
        if (activeQueue.isEmpty()) {
            restorePlaybackSession()
        }
        if (activeQueue.isEmpty()) return

        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE || player.playerError != null) {
            val prevIdx = (_playbackState.value.currentIndex - 1).coerceAtLeast(0)
            val mediaItems = activeQueue.map { it.toMediaItem() }
            player.setMediaItems(mediaItems, prevIdx, 0L)
            player.prepare()
        } else if (player.currentPosition > 3000) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(0L)
        }

        if (player.playWhenReady) {
            player.playWhenReady = false
        }
        player.playWhenReady = true
        player.play()
        updateCurrentTrackFromPlayer()
    }

    fun seekTo(positionMs: Long) {
        val safePos = positionMs.coerceAtLeast(0L)
        val player = ensurePlayerActive()
        player.seekTo(safePos)
        updateState { it.copy(currentPositionMs = safePos) }
        savePlaybackSession()
    }

    fun toggleShuffle() {
        val player = ensurePlayerActive()
        val newShuffle = !_playbackState.value.isShuffleEnabled
        player.shuffleModeEnabled = newShuffle
        updateState { it.copy(isShuffleEnabled = newShuffle) }
        savePlaybackSession()
    }

    fun cycleRepeatMode() {
        val player = ensurePlayerActive()
        val nextMode = when (_playbackState.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }

        player.repeatMode = when (nextMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }

        updateState { it.copy(repeatMode = nextMode) }
        savePlaybackSession()
    }

    fun jumpToQueueIndex(index: Int) {
        val player = ensurePlayerActive()
        if (index in activeQueue.indices) {
            if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE || player.playerError != null) {
                val mediaItems = activeQueue.map { it.toMediaItem() }
                player.setMediaItems(mediaItems, index, 0L)
                player.prepare()
            } else {
                player.seekTo(index, 0L)
            }
            if (player.playWhenReady) {
                player.playWhenReady = false
            }
            player.playWhenReady = true
            player.play()
            updateCurrentTrackFromPlayer()
        }
    }

    fun removeFromQueue(index: Int) {
        val player = ensurePlayerActive()
        if (index !in activeQueue.indices) return

        val newQueue = activeQueue.toMutableList()
        newQueue.removeAt(index)
        activeQueue = newQueue

        if (index < player.mediaItemCount) {
            player.removeMediaItem(index)
        }

        val updatedIndex = if (newQueue.isEmpty()) -1 else player.currentMediaItemIndex.coerceIn(0, newQueue.size - 1)
        val updatedTrack = newQueue.getOrNull(updatedIndex)

        updateState {
            it.copy(
                queue = newQueue,
                currentIndex = updatedIndex,
                currentTrack = updatedTrack,
                isPreloaded = player.hasNextMediaItem()
            )
        }
        savePlaybackSession()
    }

    private fun updateCurrentTrackFromPlayer() {
        val player = _exoPlayer ?: return
        if (activeQueue.isEmpty()) return
        val index = player.currentMediaItemIndex.coerceIn(0, activeQueue.size - 1)
        val currentTrack = activeQueue.getOrNull(index)
        val dur = if (player.duration > 0L) player.duration else (currentTrack?.durationMs ?: 0L)
        val pos = player.currentPosition.coerceAtLeast(0L)

        updateState {
            it.copy(
                currentIndex = index,
                currentTrack = currentTrack,
                durationMs = dur,
                currentPositionMs = pos,
                isPreloaded = player.hasNextMediaItem()
            )
        }
        savePlaybackSession()
        syncWidgetsAndNotification()
    }

    fun savePlaybackSession() {
        try {
            val state = _playbackState.value
            val queueToSave = activeQueue.ifEmpty { state.queue }
            if (queueToSave.isEmpty() || state.currentTrack == null) {
                return
            }

            val jsonArray = JSONArray()
            for (track in queueToSave) {
                val obj = JSONObject().apply {
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("albumArtist", track.albumArtist)
                    put("genre", track.genre)
                    put("trackNumber", track.trackNumber)
                    put("discNumber", track.discNumber)
                    put("durationMs", track.durationMs)
                    put("uri", track.uri)
                    put("filePath", track.filePath ?: "")
                    put("mimeType", track.mimeType)
                    put("bitrateKbps", track.bitrateKbps)
                    put("sampleRateHz", track.sampleRateHz)
                    put("dateAdded", track.dateAdded)
                    put("folderPath", track.folderPath)
                    put("hasEmbeddedArtwork", track.hasEmbeddedArtwork)
                    if (track.albumId != null) put("albumId", track.albumId)
                    put("isFavorite", track.isFavorite)
                }
                jsonArray.put(obj)
            }

            val playerPos = _exoPlayer?.currentPosition ?: 0L
            val posToSave = if (playerPos > 0L) playerPos else state.currentPositionMs.coerceAtLeast(0L)

            sessionPrefs.edit()
                .putString(KEY_QUEUE_JSON, jsonArray.toString())
                .putInt(KEY_CURRENT_INDEX, state.currentIndex.coerceIn(0, queueToSave.size - 1))
                .putLong(KEY_CURRENT_POSITION_MS, posToSave)
                .putBoolean(KEY_SHUFFLE_ENABLED, state.isShuffleEnabled)
                .putString(KEY_REPEAT_MODE, state.repeatMode.name)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist playback session", e)
        }
    }

    fun restorePlaybackSession() {
        try {
            val queueJsonStr = sessionPrefs.getString(KEY_QUEUE_JSON, null) ?: return
            val jsonArray = JSONArray(queueJsonStr)
            if (jsonArray.length() == 0) return

            val restoredTracks = mutableListOf<Track>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val filePathRaw = obj.optString("filePath", "")
                val hasAlbumId = obj.has("albumId") && !obj.isNull("albumId")
                restoredTracks.add(
                    Track(
                        id = obj.optString("id", ""),
                        title = obj.optString("title", "Unknown Title"),
                        artist = obj.optString("artist", "Unknown Artist"),
                        album = obj.optString("album", "Unknown Album"),
                        albumArtist = obj.optString("albumArtist", "Unknown Artist"),
                        genre = obj.optString("genre", "General"),
                        trackNumber = obj.optInt("trackNumber", 1),
                        discNumber = obj.optInt("discNumber", 1),
                        durationMs = obj.optLong("durationMs", 0L),
                        uri = obj.optString("uri", ""),
                        filePath = filePathRaw.ifEmpty { null },
                        mimeType = obj.optString("mimeType", "audio/mpeg"),
                        bitrateKbps = obj.optInt("bitrateKbps", 320),
                        sampleRateHz = obj.optInt("sampleRateHz", 44100),
                        dateAdded = obj.optLong("dateAdded", System.currentTimeMillis()),
                        folderPath = obj.optString("folderPath", "Default"),
                        hasEmbeddedArtwork = obj.optBoolean("hasEmbeddedArtwork", false),
                        albumId = if (hasAlbumId) obj.optLong("albumId") else null,
                        isFavorite = obj.optBoolean("isFavorite", false)
                    )
                )
            }

            if (restoredTracks.isEmpty()) return

            val savedIndex = sessionPrefs.getInt(KEY_CURRENT_INDEX, 0).coerceIn(0, restoredTracks.size - 1)
            val savedPositionMs = sessionPrefs.getLong(KEY_CURRENT_POSITION_MS, 0L).coerceAtLeast(0L)
            val savedShuffle = sessionPrefs.getBoolean(KEY_SHUFFLE_ENABLED, false)
            val savedRepeatStr = sessionPrefs.getString(KEY_REPEAT_MODE, RepeatMode.ALL.name) ?: RepeatMode.ALL.name
            val savedRepeat = try {
                RepeatMode.valueOf(savedRepeatStr)
            } catch (_: Exception) {
                RepeatMode.ALL
            }

            activeQueue = restoredTracks
            val currentTrack = restoredTracks[savedIndex]

            updateState {
                it.copy(
                    queue = restoredTracks,
                    currentIndex = savedIndex,
                    currentTrack = currentTrack,
                    durationMs = currentTrack.durationMs,
                    currentPositionMs = savedPositionMs.coerceAtMost(
                        if (currentTrack.durationMs > 0L) currentTrack.durationMs else Long.MAX_VALUE
                    ),
                    isPlaying = false,
                    isShuffleEnabled = savedShuffle,
                    repeatMode = savedRepeat,
                    isPreloaded = restoredTracks.size > 1
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore playback session", e)
        }
    }

    private fun syncWidgetsAndNotification() {
        val state = _playbackState.value
        com.example.widget.WidgetUpdater.updateAllWidgets(context, state.currentTrack, state.isPlaying)
        MediaNotificationManager.updateNotification(context, state.currentTrack, state.isPlaying)

        try {
            val serviceIntent = android.content.Intent(context, MediaPlaybackService::class.java)
            if (state.isPlaying) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else if (state.currentTrack != null) {
                // Best-effort service update when paused while app is in foreground
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {}
    }

    private fun Track.toMediaItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.parse(uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .build()
            )
            .build()
    }

    fun release() {
        if (isReleased) return
        savePlaybackSession()
        isReleased = true
        stopProgressTicker()
        try {
            _exoPlayer?.release()
        } catch (_: Exception) {}
        _exoPlayer = null
    }

    companion object {
        const val SESSION_PREFS_NAME = "aura_playback_session_prefs"
        private const val KEY_QUEUE_JSON = "queue_json"
        private const val KEY_CURRENT_INDEX = "current_index"
        private const val KEY_CURRENT_POSITION_MS = "current_position_ms"
        private const val KEY_SHUFFLE_ENABLED = "is_shuffle_enabled"
        private const val KEY_REPEAT_MODE = "repeat_mode"
    }
}

object PlaybackManagerProvider {
    @Volatile
    private var instance: PlaybackManager? = null

    private val _sharedPlaybackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _sharedPlaybackState.asStateFlow()

    fun get(context: Context): PlaybackManager {
        val current = instance
        if (current != null && !current.isReleased) {
            return current
        }
        return synchronized(this) {
            val second = instance
            if (second != null && !second.isReleased) {
                second
            } else {
                PlaybackManager(
                    context.applicationContext,
                    CoroutineScope(Dispatchers.Main + SupervisorJob())
                ).also {
                    instance = it
                    _sharedPlaybackState.value = it.playbackState.value
                }
            }
        }
    }

    fun updateSharedState(state: PlaybackState) {
        _sharedPlaybackState.value = state
    }

    fun reset() {
        synchronized(this) {
            try {
                instance?.release()
            } catch (_: Exception) {}
            instance = null
        }
    }
}
