package com.example.playback

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.ServiceCompat
import com.example.model.Track
import com.example.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MediaPlaybackService : Service() {
    private val TAG = "MediaPlaybackService"
    private var mediaSession: MediaSessionCompat? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        var currentSessionToken: MediaSessionCompat.Token? = null
    }

    override fun onCreate() {
        super.onCreate()
        MediaNotificationManager.createNotificationChannel(this)

        val playbackManager = PlaybackManagerProvider.get(this)

        val session = MediaSessionCompat(this, "AuraMusicMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    playbackManager.resumePlayback()
                }

                override fun onPause() {
                    if (playbackManager.playbackState.value.isPlaying) {
                        playbackManager.playPause()
                    }
                }

                override fun onSkipToNext() {
                    playbackManager.skipToNext()
                }

                override fun onSkipToPrevious() {
                    playbackManager.skipToPrevious()
                }

                override fun onSeekTo(pos: Long) {
                    playbackManager.seekTo(pos)
                }

                override fun onStop() {
                    if (playbackManager.playbackState.value.isPlaying) {
                        playbackManager.playPause()
                    }
                }
            })
            isActive = true
        }
        mediaSession = session
        currentSessionToken = session.sessionToken

        // Observe playback state to manage foreground status and lockscreen media updates
        serviceScope.launch {
            playbackManager.playbackState.collectLatest { state ->
                updateMediaSessionState(state)
                updateForegroundState(state)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val playbackManager = PlaybackManagerProvider.get(this)
        val state = playbackManager.playbackState.value
        updateMediaSessionState(state)
        updateForegroundState(state)
        return START_STICKY
    }

    private fun updateMediaSessionState(state: PlaybackState) {
        val session = mediaSession ?: return
        val pbState = if (state.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else if (state.currentTrack != null) {
            PlaybackStateCompat.STATE_PAUSED
        } else {
            PlaybackStateCompat.STATE_NONE
        }

        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO

        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(pbState, state.currentPositionMs, if (state.isPlaying) 1f else 0f)
                .setActions(actions)
                .build()
        )

        val track = state.currentTrack
        if (track != null) {
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs.coerceAtLeast(track.durationMs))
                    .build()
            )
        }
    }

    private fun updateForegroundState(state: PlaybackState) {
        val track = state.currentTrack
        if (track == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            WidgetUpdater.updateAllWidgets(this, null, false)
            return
        }

        // Start foreground synchronously with procedural placeholder first if playing,
        // so Android 12+ strict foreground start timing is always satisfied.
        if (state.isPlaying) {
            try {
                val quickBitmap = MediaNotificationManager.createProceduralBitmap(track)
                val initialNotification = MediaNotificationManager.buildNotification(
                    this,
                    track,
                    true,
                    quickBitmap
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this,
                        MediaNotificationManager.NOTIFICATION_ID,
                        initialNotification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(MediaNotificationManager.NOTIFICATION_ID, initialNotification)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting initial foreground notification", e)
            }
        }

        serviceScope.launch(Dispatchers.IO) {
            val artBitmap = MediaNotificationManager.getArtworkBitmap(this@MediaPlaybackService, track)
            val notification = MediaNotificationManager.buildNotification(
                this@MediaPlaybackService,
                track,
                state.isPlaying,
                artBitmap
            )

            try {
                if (state.isPlaying) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceCompat.startForeground(
                            this@MediaPlaybackService,
                            MediaNotificationManager.NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        startForeground(MediaNotificationManager.NOTIFICATION_ID, notification)
                    }
                } else {
                    // When paused (e.g. due to external video/audio focus loss), detach foreground
                    // without removing the notification so the user can resume playback anytime.
                    ServiceCompat.stopForeground(
                        this@MediaPlaybackService,
                        ServiceCompat.STOP_FOREGROUND_DETACH
                    )
                    val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(MediaNotificationManager.NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating foreground notification", e)
            }
        }

        // Also ensure home screen widgets are synced
        WidgetUpdater.updateAllWidgets(this, state.currentTrack, state.isPlaying)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val manager = PlaybackManagerProvider.get(this)
        manager.savePlaybackSession()
        if (!manager.playbackState.value.isPlaying) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            PlaybackManagerProvider.get(this).savePlaybackSession()
        } catch (_: Exception) {}
        currentSessionToken = null
        try {
            mediaSession?.run {
                isActive = false
                release()
            }
            mediaSession = null
        } catch (_: Exception) {}
        serviceScope.cancel()
        super.onDestroy()
    }
}
