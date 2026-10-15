package com.example.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

class PlaybackManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "PlaybackManager"

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

    init {
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
                updateState { it.copy(isPlaying = isPlaying) }
                if (isPlaying) {
                    startProgressTicker()
                } else {
                    stopProgressTicker()
                }
                syncWidgetsAndNotification()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        updateState {
                            it.copy(
                                durationMs = player.duration.coerceAtLeast(0L),
                                currentPositionMs = player.currentPosition.coerceAtLeast(0L),
                                isPreloaded = player.hasNextMediaItem()
                            )
                        }
                    }
                    Player.STATE_ENDED -> {
                        if (player.repeatMode == Player.REPEAT_MODE_OFF && !player.hasNextMediaItem()) {
                            updateState { it.copy(isPlaying = false) }
                            stopProgressTicker()
                        }
                    }
                    else -> Unit
                }
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
                    updateState {
                        it.copy(
                            currentPositionMs = player.currentPosition.coerceAtLeast(0L),
                            durationMs = player.duration.coerceAtLeast(0L),
                            isPreloaded = player.hasNextMediaItem()
                        )
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

        val player = ensurePlayerActive()
        activeQueue = tracks
        val mediaItems = tracks.map { it.toMediaItem() }

        player.setMediaItems(mediaItems, startIndex, 0L)
        player.prepare()
        player.play()

        val currentTrack = tracks.getOrNull(startIndex)
        updateState {
            it.copy(
                queue = tracks,
                currentIndex = startIndex,
                currentTrack = currentTrack,
                durationMs = currentTrack?.durationMs ?: 0L,
                currentPositionMs = 0L,
                isPlaying = true,
                isPreloaded = tracks.size > 1
            )
        }
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

        val currentIndex = player.currentMediaItemIndex
        val insertIndex = if (playNext && currentIndex in activeQueue.indices) {
            currentIndex + 1
        } else {
            activeQueue.size
        }

        val newQueue = activeQueue.toMutableList()
        newQueue.add(insertIndex, track)
        activeQueue = newQueue

        player.addMediaItem(insertIndex, track.toMediaItem())

        updateState {
            it.copy(
                queue = newQueue,
                isPreloaded = player.hasNextMediaItem()
            )
        }
    }

    fun playPause() {
        val player = ensurePlayerActive()
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE && activeQueue.isNotEmpty()) {
                val mediaItems = activeQueue.map { it.toMediaItem() }
                val startIdx = _playbackState.value.currentIndex.coerceIn(0, activeQueue.size - 1)
                player.setMediaItems(mediaItems, startIdx, _playbackState.value.currentPositionMs)
                player.prepare()
            }
            player.play()
        }
    }

    fun skipToNext() {
        val player = ensurePlayerActive()
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.play()
        } else if (player.repeatMode == Player.REPEAT_MODE_ALL && activeQueue.isNotEmpty()) {
            player.seekTo(0, 0L)
            player.play()
        }
    }

    fun skipToPrevious() {
        val player = ensurePlayerActive()
        if (player.currentPosition > 3000) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
            player.play()
        } else {
            player.seekTo(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        val player = ensurePlayerActive()
        player.seekTo(positionMs)
        updateState { it.copy(currentPositionMs = positionMs) }
    }

    fun toggleShuffle() {
        val player = ensurePlayerActive()
        val newShuffle = !_playbackState.value.isShuffleEnabled
        player.shuffleModeEnabled = newShuffle
        updateState { it.copy(isShuffleEnabled = newShuffle) }
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
    }

    fun jumpToQueueIndex(index: Int) {
        val player = ensurePlayerActive()
        if (index in activeQueue.indices) {
            player.seekTo(index, 0L)
            player.play()
        }
    }

    fun removeFromQueue(index: Int) {
        val player = ensurePlayerActive()
        if (index !in activeQueue.indices) return

        val newQueue = activeQueue.toMutableList()
        newQueue.removeAt(index)
        activeQueue = newQueue

        player.removeMediaItem(index)

        val updatedIndex = player.currentMediaItemIndex
        val updatedTrack = activeQueue.getOrNull(updatedIndex)

        updateState {
            it.copy(
                queue = newQueue,
                currentIndex = updatedIndex,
                currentTrack = updatedTrack,
                isPreloaded = player.hasNextMediaItem()
            )
        }
    }

    private fun updateCurrentTrackFromPlayer() {
        val player = _exoPlayer ?: return
        val index = player.currentMediaItemIndex
        val currentTrack = activeQueue.getOrNull(index)

        updateState {
            it.copy(
                currentIndex = index,
                currentTrack = currentTrack,
                durationMs = player.duration.coerceAtLeast(0L),
                isPreloaded = player.hasNextMediaItem()
            )
        }
        syncWidgetsAndNotification()
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
        isReleased = true
        stopProgressTicker()
        MediaNotificationManager.cancelNotification(context)
        try {
            _exoPlayer?.release()
        } catch (_: Exception) {}
        _exoPlayer = null
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
