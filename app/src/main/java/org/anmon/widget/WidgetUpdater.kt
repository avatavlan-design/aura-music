package org.anmon.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import org.anmon.MainActivity
import org.anmon.R
import org.anmon.data.ArtworkCache
import org.anmon.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

object WidgetUpdater {
    const val ACTION_PLAY_PAUSE = "org.anmon.auramusic.ACTION_PLAY_PAUSE"
    const val ACTION_PREV = "org.anmon.auramusic.ACTION_PREV"
    const val ACTION_NEXT = "org.anmon.auramusic.ACTION_NEXT"

    fun updateAllWidgets(context: Context, track: Track?, isPlaying: Boolean) {
        val appWidgetManager = AppWidgetManager.getInstance(context)

        CoroutineScope(Dispatchers.IO).launch {
            val artBitmap = getArtworkBitmap(context, track)

            // Update Large Widget (4x2)
            val largeComponent = ComponentName(context, AuraMusicLargeWidget::class.java)
            val largeIds = appWidgetManager.getAppWidgetIds(largeComponent)
            if (largeIds.isNotEmpty()) {
                val views = buildLargeViews(context, track, isPlaying, artBitmap)
                appWidgetManager.updateAppWidget(largeIds, views)
            }

            // Update Medium Widget (4x1 and 3x1)
            val mediumComponent = ComponentName(context, AuraMusicMediumWidget::class.java)
            val mediumIds = appWidgetManager.getAppWidgetIds(mediumComponent)
            if (mediumIds.isNotEmpty()) {
                val views = buildMediumViews(context, track, isPlaying, artBitmap)
                appWidgetManager.updateAppWidget(mediumIds, views)
            }

            // Update Small Widget (2x1)
            val smallComponent = ComponentName(context, AuraMusicSmallWidget::class.java)
            val smallIds = appWidgetManager.getAppWidgetIds(smallComponent)
            if (smallIds.isNotEmpty()) {
                val views = buildSmallViews(context, isPlaying, artBitmap)
                appWidgetManager.updateAppWidget(smallIds, views)
            }

            // Update Tiny Widget (1x1)
            val tinyComponent = ComponentName(context, AuraMusicTinyWidget::class.java)
            val tinyIds = appWidgetManager.getAppWidgetIds(tinyComponent)
            if (tinyIds.isNotEmpty()) {
                val views = buildTinyViews(context, isPlaying)
                appWidgetManager.updateAppWidget(tinyIds, views)
            }
        }
    }

    private fun buildLargeViews(
        context: Context,
        track: Track?,
        isPlaying: Boolean,
        artBitmap: Bitmap?
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_large)

        views.setTextViewText(R.id.widget_title, track?.title ?: "No Track Playing")
        views.setTextViewText(R.id.widget_artist, track?.artist ?: "Aura Music")
        views.setTextViewText(R.id.widget_genre, track?.genre ?: "Offline Hi-Fi")

        if (artBitmap != null) {
            views.setImageViewBitmap(R.id.widget_album_art, artBitmap)
        } else {
            views.setImageViewResource(R.id.widget_album_art, R.drawable.ic_widget_placeholder)
        }

        val playPauseRes = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        views.setImageViewResource(R.id.widget_btn_play_pause, playPauseRes)

        // Set PendingIntents
        views.setOnClickPendingIntent(R.id.widget_btn_play_pause, getActionPendingIntent(context, ACTION_PLAY_PAUSE, 101))
        views.setOnClickPendingIntent(R.id.widget_btn_prev, getActionPendingIntent(context, ACTION_PREV, 102))
        views.setOnClickPendingIntent(R.id.widget_btn_next, getActionPendingIntent(context, ACTION_NEXT, 103))
        views.setOnClickPendingIntent(R.id.widget_large_root, getLaunchAppPendingIntent(context))

        return views
    }

    private fun buildMediumViews(
        context: Context,
        track: Track?,
        isPlaying: Boolean,
        artBitmap: Bitmap?
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_medium)

        views.setTextViewText(R.id.widget_title, track?.title ?: "No Track Playing")
        views.setTextViewText(R.id.widget_artist, track?.artist ?: "Aura Music")

        if (artBitmap != null) {
            views.setImageViewBitmap(R.id.widget_album_art, artBitmap)
        } else {
            views.setImageViewResource(R.id.widget_album_art, R.drawable.ic_widget_placeholder)
        }

        val playPauseRes = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        views.setImageViewResource(R.id.widget_btn_play_pause, playPauseRes)

        views.setOnClickPendingIntent(R.id.widget_btn_play_pause, getActionPendingIntent(context, ACTION_PLAY_PAUSE, 201))
        views.setOnClickPendingIntent(R.id.widget_btn_prev, getActionPendingIntent(context, ACTION_PREV, 202))
        views.setOnClickPendingIntent(R.id.widget_btn_next, getActionPendingIntent(context, ACTION_NEXT, 203))
        views.setOnClickPendingIntent(R.id.widget_medium_root, getLaunchAppPendingIntent(context))

        return views
    }

    private fun buildSmallViews(
        context: Context,
        isPlaying: Boolean,
        artBitmap: Bitmap?
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_small)

        if (artBitmap != null) {
            views.setImageViewBitmap(R.id.widget_album_art, artBitmap)
        } else {
            views.setImageViewResource(R.id.widget_album_art, R.drawable.ic_widget_placeholder)
        }

        val playPauseRes = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        views.setImageViewResource(R.id.widget_btn_play_pause, playPauseRes)

        views.setOnClickPendingIntent(R.id.widget_btn_play_pause, getActionPendingIntent(context, ACTION_PLAY_PAUSE, 301))
        views.setOnClickPendingIntent(R.id.widget_small_root, getLaunchAppPendingIntent(context))

        return views
    }

    private fun buildTinyViews(
        context: Context,
        isPlaying: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_tiny)

        val playPauseRes = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        views.setImageViewResource(R.id.widget_btn_play_pause, playPauseRes)

        views.setOnClickPendingIntent(R.id.widget_btn_play_pause, getActionPendingIntent(context, ACTION_PLAY_PAUSE, 401))

        return views
    }

    private fun getActionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getLaunchAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private suspend fun getArtworkBitmap(context: Context, track: Track?): Bitmap? {
        if (track == null) return null
        val bytes = ArtworkCache.getArtworkBytes(context, track)
        return if (bytes != null && bytes.isNotEmpty()) {
            try {
                val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                getRoundedCornerBitmap(raw, 24f)
            } catch (_: Exception) {
                null
            }
        } else {
            createProceduralBitmap(track)
        }
    }

    private fun createProceduralBitmap(track: Track): Bitmap {
        val size = 160
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val seed = (track.album.hashCode() + track.title.hashCode()).absoluteValue
        val colors = intArrayOf(
            android.graphics.Color.rgb(139, 92, 246), // Violet
            android.graphics.Color.rgb(99, 102, 241), // Indigo
            android.graphics.Color.rgb(6, 182, 212)   // Cyan
        )
        val baseColor = colors[seed % colors.size]

        val paint = Paint().apply {
            isAntiAlias = true
            color = baseColor
        }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 24f, 24f, paint)

        // Draw initial letter
        val textPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 64f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val letter = track.title.take(1).uppercase()
        val yPos = (canvas.height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(letter, canvas.width / 2f, yPos, textPaint)

        return bitmap
    }

    private fun getRoundedCornerBitmap(bitmap: Bitmap, cornerRadius: Float): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            isAntiAlias = true
            color = -0x1
        }
        val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }
}
