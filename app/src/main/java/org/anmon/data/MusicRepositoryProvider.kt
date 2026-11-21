package org.anmon.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-level singleton provider for [MusicRepository], matching [org.anmon.playback.PlaybackManagerProvider].
 * Keeps library state and ongoing background scans alive across Activity/ViewModel recreations.
 */
object MusicRepositoryProvider {
    @Volatile
    private var instance: MusicRepository? = null

    fun get(context: Context): MusicRepository {
        val existing = instance
        if (existing != null) return existing
        return synchronized(this) {
            val current = instance
            if (current != null) {
                current
            } else {
                val appContext = context.applicationContext
                val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                MusicRepository(appContext, repoScope).also { instance = it }
            }
        }
    }

    fun reset() {
        synchronized(this) {
            instance = null
        }
    }
}
