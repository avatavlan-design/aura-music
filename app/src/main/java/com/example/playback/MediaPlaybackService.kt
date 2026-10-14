package com.example.playback

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.ServiceCompat
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

    companion object {
        var currentSessionToken: MediaSessionCompat.Token? = null
    }

    override fun onCreate() {
        super.onCreate()
        MediaNotificationManager.createNotificationChannel(this)

        val playbackManager = PlaybackManagerProvider.get(this)
        val player = playbackManager.exoPlayer
        val session = MediaSession.Builder(this, player).build()
        mediaSession = session
        currentSessionToken = session.sessionCompatToken

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
        }

        // Also ensure home screen widgets are synced
        WidgetUpdater.updateAllWidgets(this, state.currentTrack, state.isPlaying)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val manager = PlaybackManagerProvider.get(this)
        if (!manager.playbackState.value.isPlaying) {
            MediaNotificationManager.cancelNotification(this)
            PlaybackManagerProvider.reset()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        currentSessionToken = null
        try {
            mediaSession?.run {
                release()
                mediaSession = null
            }
        } catch (_: Exception) {}
        PlaybackManagerProvider.reset()
        super.onDestroy()
    }
}
