package com.example.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.ArtworkCache
import com.example.model.Track
import com.example.widget.WidgetActionReceiver
import com.example.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

object MediaNotificationManager {
    const val CHANNEL_ID = "aura_music_playback_channel"
    const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aura Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows offline music playback controls in notification center and lock screen"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun updateNotification(context: Context, track: Track?, isPlaying: Boolean) {
        if (track == null) {
            cancelNotification(context)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val artBitmap = getArtworkBitmap(context, track)
            val notification = buildNotification(context, track, isPlaying, artBitmap)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    fun buildNotification(
        context: Context,
        track: Track,
        isPlaying: Boolean,
        artBitmap: Bitmap?
    ): Notification {
        createNotificationChannel(context)

        val prevIntent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetUpdater.ACTION_PREV
        }
        val prevPendingIntent = PendingIntent.getBroadcast(
            context,
            501,
            prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetUpdater.ACTION_PLAY_PAUSE
        }
        val playPausePendingIntent = PendingIntent.getBroadcast(
            context,
            502,
            playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = WidgetUpdater.ACTION_NEXT
        }
        val nextPendingIntent = PendingIntent.getBroadcast(
            context,
            503,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            context,
            500,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_play)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setSubText(track.album)
            .setContentIntent(appPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .addAction(R.drawable.ic_widget_prev, "Previous", prevPendingIntent)
            .addAction(playPauseIcon, playPauseTitle, playPausePendingIntent)
            .addAction(R.drawable.ic_widget_next, "Next", nextPendingIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )

        if (artBitmap != null) {
            builder.setLargeIcon(artBitmap)
        }

        return builder.build()
    }

    fun cancelNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    private suspend fun getArtworkBitmap(context: Context, track: Track): Bitmap? {
        val bytes = ArtworkCache.getArtworkBytes(context, track)
        return if (bytes != null && bytes.isNotEmpty()) {
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
                null
            }
        } else {
            createProceduralBitmap(track)
        }
    }

    private fun createProceduralBitmap(track: Track): Bitmap {
        val size = 180
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val seed = (track.album.hashCode() + track.title.hashCode()).absoluteValue
        val colors = intArrayOf(
            android.graphics.Color.rgb(139, 92, 246),
            android.graphics.Color.rgb(99, 102, 241),
            android.graphics.Color.rgb(6, 182, 212)
        )
        val paint = Paint().apply {
            isAntiAlias = true
            color = colors[seed % colors.size]
        }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 24f, 24f, paint)

        val textPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 72f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val letter = track.title.take(1).uppercase()
        val yPos = (canvas.height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(letter, canvas.width / 2f, yPos, textPaint)

        return bitmap
    }
}
