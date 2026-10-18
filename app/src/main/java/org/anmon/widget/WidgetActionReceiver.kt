package com.example.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.playback.PlaybackManagerProvider

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val manager = PlaybackManagerProvider.get(context)

        when (intent?.action) {
            WidgetUpdater.ACTION_PLAY_PAUSE -> {
                manager.playPause()
            }
            WidgetUpdater.ACTION_PREV -> {
                manager.skipToPrevious()
            }
            WidgetUpdater.ACTION_NEXT -> {
                manager.skipToNext()
            }
        }

        val state = manager.playbackState.value
        WidgetUpdater.updateAllWidgets(context, state.currentTrack, state.isPlaying)
    }
}
