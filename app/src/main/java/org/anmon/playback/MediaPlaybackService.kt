package org.anmon.playback

import android.app.Notification
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
import androidx.media.session.MediaButtonReceiver
import org.anmon.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaPlaybackService : Service() {
    private val TAG = "MediaPlaybackService"
    private var mediaSession: MediaSessionCompat? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isForegroundService: Boolean = false

    companion object {
        @Volatile
        var currentSessionToken: MediaSessionCompat.Token? = null
        @Volatile
        var isServiceRunning: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        MediaNotificationManager.createNotificationChannel(this)

        val playbackManager = PlaybackManagerProvider.get(this)

        val session = MediaSessionCompat(this, "AuraMusicMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    playbackManager.resumePlayback()
                }

                override fun onPause() {
                    playbackManager.onExternalPauseRequest()
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
                    playbackManager.onExternalPauseRequest()
                }
            })
            isActive = true
        }
        mediaSession = session
        currentSessionToken = session.sessionToken

        // Observe playback state sequentially on Main thread; collectLatest cancels any stale artwork loads
        serviceScope.launch {
            playbackManager.playbackState.collectLatest { state ->
                updateMediaSessionState(state)
                updateForegroundStateSuspend(state)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceRunning = true
        if (intent != null) {
            try {
                MediaButtonReceiver.handleIntent(mediaSession, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to handle media button intent", e)
            }
        }

        val playbackManager = PlaybackManagerProvider.get(this)
        val state = playbackManager.playbackState.value
        updateMediaSessionState(state)

        // Ensure Android 12+ strict foreground start timing is immediately satisfied on onStartCommand
        val track = state.currentTrack
        if (track != null && (state.isPlaying || isForegroundService || playbackManager.isWaitingToAutoResume)) {
            val fastBitmap = MediaNotificationManager.getCachedArtworkBitmap(track)
                ?: MediaNotificationManager.createProceduralBitmap(track)
            val initialNotification = MediaNotificationManager.buildNotification(
                this,
                track,
                state.isPlaying,
                fastBitmap
            )
            promoteToForeground(initialNotification)
        }

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

    private suspend fun updateForegroundStateSuspend(state: PlaybackState) {
        val track = state.currentTrack
        val playbackManager = PlaybackManagerProvider.get(this)

        if (track == null) {
            if (isForegroundService) {
                try {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {}
                isForegroundService = false
            }
            MediaNotificationManager.cancelNotification(this)
            WidgetUpdater.updateAllWidgets(this, null, false)
            return
        }

        // Sync home screen widgets immediately
        WidgetUpdater.updateAllWidgets(this, track, state.isPlaying)

        val shouldKeepInForeground = state.isPlaying || isForegroundService || playbackManager.isWaitingToAutoResume

        // Fast path: if artwork for this track is already cached in memory, update immediately without IO
        val cachedBitmap = MediaNotificationManager.getCachedArtworkBitmap(track)
        if (cachedBitmap != null) {
            val notification = MediaNotificationManager.buildNotification(
                this,
                track,
                state.isPlaying,
                cachedBitmap
            )
            postOrPromoteNotification(notification, shouldKeepInForeground)
            return
        }

        // Slow path (new track): satisfy foreground requirement immediately with procedural art first
        if (shouldKeepInForeground && !isForegroundService) {
            val quickBitmap = MediaNotificationManager.createProceduralBitmap(track)
            val quickNotification = MediaNotificationManager.buildNotification(
                this,
                track,
                state.isPlaying,
                quickBitmap
            )
            promoteToForeground(quickNotification)
        }

        // Decode embedded ID3 artwork on IO; cancelled automatically by collectLatest if state changes
        val resolvedBitmap = withContext(Dispatchers.IO) {
            MediaNotificationManager.getArtworkBitmap(this@MediaPlaybackService, track)
        }

        val finalNotification = MediaNotificationManager.buildNotification(
            this,
            track,
            state.isPlaying,
            resolvedBitmap
        )
        postOrPromoteNotification(finalNotification, shouldKeepInForeground)
    }

    private fun postOrPromoteNotification(notification: Notification, keepInForeground: Boolean) {
        if (keepInForeground) {
            promoteToForeground(notification)
        } else {
            try {
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(MediaNotificationManager.NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.e(TAG, "Error posting media notification", e)
            }
        }
    }

    private fun promoteToForeground(notification: Notification) {
        try {
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
            isForegroundService = true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to promote service to foreground; falling back to notification manager", e)
            try {
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(MediaNotificationManager.NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val manager = PlaybackManagerProvider.get(this)
        manager.savePlaybackSession()
        if (!manager.playbackState.value.isPlaying && !manager.isWaitingToAutoResume) {
            if (isForegroundService) {
                try {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {}
                isForegroundService = false
            }
            MediaNotificationManager.cancelNotification(this)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isServiceRunning = false
        isForegroundService = false
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
