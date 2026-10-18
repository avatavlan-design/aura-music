package org.anmon.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import org.anmon.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

object ArtworkCache {
    // Sentinel object to cache negative results (files with no artwork)
    private val NO_ART = ByteArray(0)
    private val memoryCache = LruCache<String, ByteArray>(50) // Cache up to 50 album arts

    suspend fun getArtworkBytes(context: Context, track: Track): ByteArray? = withContext(Dispatchers.IO) {
        val cacheKey = track.id

        // 1. Check in-memory cache
        val cached = memoryCache.get(cacheKey)
        if (cached === NO_ART) return@withContext null
        if (cached != null) return@withContext cached

        // 2. Early return for formats or sources that never have embedded APIC tags
        if (!track.hasEmbeddedArtwork ||
            track.id.startsWith("sample_") ||
            track.uri.endsWith(".wav", ignoreCase = true) ||
            track.mimeType.contains("wav", ignoreCase = true) ||
            track.uri.endsWith(".mid", ignoreCase = true)
        ) {
            memoryCache.put(cacheKey, NO_ART)
            return@withContext null
        }

        // 3. If MediaStore track with an albumId, load via ContentResolver to avoid JNI retriever failures
        if (track.albumId != null && track.albumId > 0) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentUri = Uri.parse(track.uri)
                    val bitmap = context.contentResolver.loadThumbnail(contentUri, Size(256, 256), null)
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                    val bytes = stream.toByteArray()
                    memoryCache.put(cacheKey, bytes)
                    return@withContext bytes
                } else {
                    val albumArtUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        track.albumId
                    )
                    context.contentResolver.openInputStream(albumArtUri)?.use { input ->
                        val bytes = input.readBytes()
                        if (bytes.isNotEmpty()) {
                            memoryCache.put(cacheKey, bytes)
                            return@withContext bytes
                        }
                    }
                }
            } catch (_: Exception) {
                // Silently fall through to file retriever if needed
            }
        }

        // 4. For file-based or SAF tracks with supported formats (MP3, FLAC, M4A, OGG)
        val ext = track.uri.substringAfterLast('.', "").lowercase()
        val isSupportedFormat = ext in listOf("mp3", "flac", "m4a", "mp4", "ogg", "opus") ||
                track.mimeType.contains("mpeg") || track.mimeType.contains("flac") || track.mimeType.contains("mp4")

        if (!isSupportedFormat) {
            memoryCache.put(cacheKey, NO_ART)
            return@withContext null
        }

        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            if (track.uri.startsWith("file://") || track.uri.startsWith("/")) {
                val path = if (track.uri.startsWith("file://")) Uri.parse(track.uri).path ?: track.uri else track.uri
                retriever.setDataSource(path)
            } else {
                retriever.setDataSource(context, Uri.parse(track.uri))
            }
            val art = retriever.embeddedPicture
            if (art != null && art.isNotEmpty()) {
                memoryCache.put(cacheKey, art)
                art
            } else {
                memoryCache.put(cacheKey, NO_ART)
                null
            }
        } catch (_: Throwable) {
            memoryCache.put(cacheKey, NO_ART)
            null
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {}
        }
    }
}
