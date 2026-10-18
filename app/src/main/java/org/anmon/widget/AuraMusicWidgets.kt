package org.anmon.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import org.anmon.playback.PlaybackManagerProvider

class AuraMusicLargeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val player = PlaybackManagerProvider.get(context)
        val state = player.playbackState.value
        WidgetUpdater.updateAllWidgets(context, state.currentTrack, state.isPlaying)
    }
}

class AuraMusicMediumWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val player = PlaybackManagerProvider.get(context)
        val state = player.playbackState.value
        WidgetUpdater.updateAllWidgets(context, state.currentTrack, state.isPlaying)
    }
}

class AuraMusicSmallWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val player = PlaybackManagerProvider.get(context)
        val state = player.playbackState.value
        WidgetUpdater.updateAllWidgets(context, state.currentTrack, state.isPlaying)
    }
}

class AuraMusicTinyWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val player = PlaybackManagerProvider.get(context)
        val state = player.playbackState.value
        WidgetUpdater.updateAllWidgets(context, state.currentTrack, state.isPlaying)
    }
}
