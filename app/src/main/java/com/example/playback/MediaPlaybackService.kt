package com.example.playback

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.model.Track
import com.example.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MediaPlaybackService : MediaSessionService() {
    private val TAG = "MediaPlaybackService"
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        MediaNotificationManager.createNotificationChannel(this)

        val playbackManager = PlaybackManagerProvider.get(this)
        val player = playbackManager.exoPlayer
        mediaSession = MediaSession.Builder(this, player).build()

        // Observe playback state to manage foreground status and lockscreen media updates
        serviceScope.launch {
            playbackManager.playbackState.collectLatest { state ->
                updateForegroundState(state)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val playbackManager = PlaybackManagerProvider.get(this)
        updateForegroundState(playbackManager.playbackState.value)
        return START_STICKY
    }

    private fun updateForegroundState(state: PlaybackState) {
        val track = state.currentTrack ?: Track(
            id = "aura_idle",
            title = "Aura Music",
            artist = "Offline Player",
            uri = ""
        )

        val notification = MediaNotificationManager.buildNotification(
            this,
            track,
            state.isPlaying,
            null
        )

        try {
            if (state.isPlaying) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this,
                        MediaNotificationManager.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(MediaNotificationManager.NOTIFICATION_ID, notification)
                }
            } else {
                // When paused, keep notification in notification tray with play button, but allow system to pause foreground
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(false)
                }
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.notify(MediaNotificationManager.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating foreground notification", e)
        }

        // Also ensure home screen widgets are synced
        WidgetUpdater.updateAllWidgets(this, state.currentTrack, state.isPlaying)
    }

    override fun onDestroy() {
        serviceScope.launch {
            mediaSession?.run {
                player.release()
                release()
                mediaSession = null
            }
        }
        super.onDestroy()
    }
}
