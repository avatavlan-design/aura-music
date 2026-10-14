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

    // Tuned LoadControl for aggressive seamless preloading and 0-delay gapless playback
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

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var activeQueue: List<Track> = emptyList()
    private var progressJob: Job? = null

    init {
        exoPlayer.repeatMode = Player.REPEAT_MODE_ALL

        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCurrentTrackFromPlayer()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
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
                        _playbackState.value = _playbackState.value.copy(
                            durationMs = exoPlayer.duration.coerceAtLeast(0L),
                            currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                            isGaplessPreloaded = exoPlayer.hasNextMediaItem()
                        )
                    }
                    Player.STATE_ENDED -> {
                        if (exoPlayer.repeatMode == Player.REPEAT_MODE_OFF && !exoPlayer.hasNextMediaItem()) {
                            _playbackState.value = _playbackState.value.copy(isPlaying = false)
                            stopProgressTicker()
                        }
                    }
                    else -> Unit
                }
            }
        })
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                if (exoPlayer.isPlaying) {
                    _playbackState.value = _playbackState.value.copy(
                        currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                        durationMs = exoPlayer.duration.coerceAtLeast(0L),
                        isGaplessPreloaded = exoPlayer.hasNextMediaItem()
                    )
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
     * All tracks are queued directly into ExoPlayer so the next track is pre-buffered
     * and transitions with 0 delay (gapless).
     */
    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return

        activeQueue = tracks
        val mediaItems = tracks.map { it.toMediaItem() }

        exoPlayer.setMediaItems(mediaItems, startIndex, 0L)
        exoPlayer.prepare()
        exoPlayer.play()

        val currentTrack = tracks.getOrNull(startIndex)
        _playbackState.value = _playbackState.value.copy(
            queue = tracks,
            currentIndex = startIndex,
            currentTrack = currentTrack,
            durationMs = currentTrack?.durationMs ?: 0L,
            currentPositionMs = 0L,
            isPlaying = true,
            isGaplessPreloaded = tracks.size > 1
        )
        syncWidgetsAndNotification()
    }

    /**
     * Swipe-to-Queue feature:
     * Appends track to the current queue and ExoPlayer playlist.
     * If nothing is playing, starts playing immediately.
     */
    fun addToQueue(track: Track, playNext: Boolean = false) {
        if (activeQueue.isEmpty()) {
            playQueue(listOf(track), 0)
            return
        }

        val currentIndex = exoPlayer.currentMediaItemIndex
        val insertIndex = if (playNext && currentIndex in activeQueue.indices) {
            currentIndex + 1
        } else {
            activeQueue.size
        }

        val newQueue = activeQueue.toMutableList()
        newQueue.add(insertIndex, track)
        activeQueue = newQueue

        // Preload track into ExoPlayer's playback pipeline
        exoPlayer.addMediaItem(insertIndex, track.toMediaItem())

        _playbackState.value = _playbackState.value.copy(
            queue = newQueue,
            isGaplessPreloaded = exoPlayer.hasNextMediaItem()
        )
    }

    fun playPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.playbackState == Player.STATE_IDLE && activeQueue.isNotEmpty()) {
                exoPlayer.prepare()
            }
            exoPlayer.play()
        }
    }

    fun skipToNext() {
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
            exoPlayer.play()
        } else if (exoPlayer.repeatMode == Player.REPEAT_MODE_ALL && activeQueue.isNotEmpty()) {
            exoPlayer.seekTo(0, 0L)
            exoPlayer.play()
        }
    }

    fun skipToPrevious() {
        if (exoPlayer.currentPosition > 3000) {
            exoPlayer.seekTo(0L)
        } else if (exoPlayer.hasPreviousMediaItem()) {
            exoPlayer.seekToPreviousMediaItem()
            exoPlayer.play()
        } else {
            exoPlayer.seekTo(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        _playbackState.value = _playbackState.value.copy(
            currentPositionMs = positionMs
        )
    }

    fun toggleShuffle() {
        val newShuffle = !_playbackState.value.isShuffleEnabled
        exoPlayer.shuffleModeEnabled = newShuffle
        _playbackState.value = _playbackState.value.copy(isShuffleEnabled = newShuffle)
    }

    fun cycleRepeatMode() {
        val nextMode = when (_playbackState.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }

        exoPlayer.repeatMode = when (nextMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }

        _playbackState.value = _playbackState.value.copy(repeatMode = nextMode)
    }

    fun jumpToQueueIndex(index: Int) {
        if (index in activeQueue.indices) {
            exoPlayer.seekTo(index, 0L)
            exoPlayer.play()
        }
    }

    fun removeFromQueue(index: Int) {
        if (index !in activeQueue.indices) return
        val currentIndex = exoPlayer.currentMediaItemIndex

        val newQueue = activeQueue.toMutableList()
        newQueue.removeAt(index)
        activeQueue = newQueue

        exoPlayer.removeMediaItem(index)

        val updatedIndex = exoPlayer.currentMediaItemIndex
        val updatedTrack = activeQueue.getOrNull(updatedIndex)

        _playbackState.value = _playbackState.value.copy(
            queue = newQueue,
            currentIndex = updatedIndex,
            currentTrack = updatedTrack,
            isGaplessPreloaded = exoPlayer.hasNextMediaItem()
        )
    }

    private fun updateCurrentTrackFromPlayer() {
        val index = exoPlayer.currentMediaItemIndex
        val currentTrack = activeQueue.getOrNull(index)

        _playbackState.value = _playbackState.value.copy(
            currentIndex = index,
            currentTrack = currentTrack,
            durationMs = exoPlayer.duration.coerceAtLeast(0L),
            isGaplessPreloaded = exoPlayer.hasNextMediaItem()
        )
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
        stopProgressTicker()
        MediaNotificationManager.cancelNotification(context)
        exoPlayer.release()
    }
}

object PlaybackManagerProvider {
    @Volatile
    private var instance: PlaybackManager? = null

    fun get(context: Context): PlaybackManager {
        return instance ?: synchronized(this) {
            instance ?: PlaybackManager(
                context.applicationContext,
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
            ).also { instance = it }
        }
    }
}

