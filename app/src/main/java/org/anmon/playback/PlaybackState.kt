package org.anmon.playback

import org.anmon.model.RepeatMode
import org.anmon.model.Track

data class PlaybackState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.ALL,
    val isPreloaded: Boolean = false
) {
    val progress: Float
        get() = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val nextTrack: Track?
        get() = if (queue.isNotEmpty() && currentIndex in 0 until queue.size - 1) {
            queue[currentIndex + 1]
        } else if (queue.isNotEmpty() && repeatMode == RepeatMode.ALL) {
            queue.firstOrNull()
        } else null

    val previousTrack: Track?
        get() = if (queue.isNotEmpty() && currentIndex > 0) {
            queue[currentIndex - 1]
        } else null
}
