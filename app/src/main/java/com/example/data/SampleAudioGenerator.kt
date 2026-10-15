package com.example.data

import android.content.Context
import android.util.Log
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

object SampleAudioGenerator {
    private const val TAG = "SampleAudioGenerator"
    private const val SAMPLE_RATE = 44100
    private const val CHANNELS = 2
    private const val BITS_PER_SAMPLE = 16

    suspend fun ensureSampleTracks(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val sampleDir = File(context.filesDir, "sample_music").apply { mkdirs() }

        val trackConfigs = listOf(
            SampleTrackDef(
                fileName = "01_starlight_reverie.wav",
                title = "Starlight Reverie",
                artist = "Astral Wave",
                album = "Aura Odyssey",
                genre = "Ambient Synth",
                trackNumber = 1,
                bpm = 110,
                baseFreq = 261.63, // C4
                durationSeconds = 12
            ),
            SampleTrackDef(
                fileName = "02_neon_horizon.wav",
                title = "Neon Horizon",
                artist = "Astral Wave",
                album = "Aura Odyssey",
                genre = "Ambient Synth",
                trackNumber = 2,
                bpm = 110,
                baseFreq = 329.63, // E4
                durationSeconds = 12
            ),
            SampleTrackDef(
                fileName = "01_moonlight_rain.wav",
                title = "Moonlight Rain",
                artist = "The Velvet Collective",
                album = "Midnight Lounge",
                genre = "Lo-Fi Beats",
                trackNumber = 1,
                bpm = 90,
                baseFreq = 220.0, // A3
                durationSeconds = 14
            ),
            SampleTrackDef(
                fileName = "02_urban_glow.wav",
                title = "Urban Glow",
                artist = "The Velvet Collective",
                album = "Midnight Lounge",
                genre = "Lo-Fi Beats",
                trackNumber = 2,
                bpm = 90,
                baseFreq = 293.66, // D4
                durationSeconds = 14
            )
        )

        val tracks = mutableListOf<Track>()

        for (def in trackConfigs) {
            val file = File(sampleDir, def.fileName)
            if (!file.exists() || file.length() < 1000) {
                generateWavFile(file, def)
            }

            val durationMs = (def.durationSeconds * 1000).toLong()
            tracks.add(
                Track(
                    id = "sample_${def.fileName}",
                    title = def.title,
                    artist = def.artist,
                    album = def.album,
                    genre = def.genre,
                    trackNumber = def.trackNumber,
                    durationMs = durationMs,
                    uri = file.toURI().toString(),
                    filePath = file.absolutePath,
                    mimeType = "audio/wav",
                    bitrateKbps = 1411,
                    sampleRateHz = SAMPLE_RATE,
                    folderPath = "Aura Built-in Samples",
                    hasEmbeddedArtwork = false,
                    isFavorite = false
                )
            )
        }

        // Ensure a built-in sample .m3u playlist exists for offline testing and immediate discovery
        try {
            val sampleM3u = File(sampleDir, "Aura_Chill_Odyssey.m3u")
            if (!sampleM3u.exists()) {
                val m3uContent = buildString {
                    appendLine("#EXTM3U")
                    appendLine("#EXTINF:12,Astral Wave - Starlight Reverie")
                    appendLine("01_starlight_reverie.wav")
                    appendLine("#EXTINF:14,The Velvet Collective - Moonlight Rain")
                    appendLine("01_moonlight_rain.wav")
                    appendLine("#EXTINF:12,Astral Wave - Neon Horizon")
                    appendLine("02_neon_horizon.wav")
                }
                sampleM3u.writeText(m3uContent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing sample .m3u playlist", e)
        }

        tracks
    }

    private data class SampleTrackDef(
        val fileName: String,
        val title: String,
        val artist: String,
        val album: String,
        val genre: String,
        val trackNumber: Int,
        val bpm: Int,
        val baseFreq: Double,
        val durationSeconds: Int
    )

    private fun generateWavFile(file: File, def: SampleTrackDef) {
        try {
            val totalSamples = SAMPLE_RATE * def.durationSeconds
            val audioDataSize = totalSamples * CHANNELS * (BITS_PER_SAMPLE / 8)
            val buffer = ByteArray(audioDataSize)
            val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

            val chordIntervals = listOf(1.0, 1.25, 1.5, 1.875) // Major 7th harmony
            val beatDuration = 60.0 / def.bpm

            for (i in 0 until totalSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val beatIndex = ((t / beatDuration).toInt()) % 4

                // Progressive melody & lush synth chord
                var leftSample = 0.0
                var rightSample = 0.0

                val melodyFreq = def.baseFreq * chordIntervals[beatIndex]
                val subBass = sin(2.0 * Math.PI * (def.baseFreq / 2.0) * t) * 0.28
                val lead = sin(2.0 * Math.PI * melodyFreq * t) * 0.22
                val harmonic = sin(2.0 * Math.PI * (melodyFreq * 2.0) * t) * 0.08
                val warmth = sin(2.0 * Math.PI * (def.baseFreq * 1.5) * t) * 0.15

                // Gentle stereo panning oscillation
                val pan = sin(2.0 * Math.PI * 0.2 * t) * 0.2

                val sampleVal = subBass + lead + harmonic + warmth
                leftSample += sampleVal * (0.5 - pan)
                rightSample += sampleVal * (0.5 + pan)

                // Smooth envelope fade in / fade out at track boundaries
                val fadeDuration = 0.4
                val envelope = when {
                    t < fadeDuration -> t / fadeDuration
                    t > def.durationSeconds - fadeDuration -> (def.durationSeconds - t) / fadeDuration
                    else -> 1.0
                }

                val leftInt16 = (leftSample * envelope * 24000.0).toInt().coerceIn(-32767, 32767).toShort()
                val rightInt16 = (rightSample * envelope * 24000.0).toInt().coerceIn(-32767, 32767).toShort()

                byteBuffer.putShort(leftInt16)
                byteBuffer.putShort(rightInt16)
            }

            FileOutputStream(file).use { fos ->
                writeWavHeader(fos, audioDataSize)
                fos.write(buffer)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed generating WAV for ${def.title}", e)
        }
    }

    private fun writeWavHeader(fos: FileOutputStream, audioDataSize: Int) {
        val totalDataLen = audioDataSize + 36
        val byteRate = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8)

        val header = ByteArray(44)
        // RIFF/WAVE header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // fmt chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // Subchunk1Size for PCM
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // AudioFormat: 1 = PCM
        header[21] = 0
        header[22] = CHANNELS.toByte()
        header[23] = 0
        header[24] = (SAMPLE_RATE and 0xff).toByte()
        header[25] = ((SAMPLE_RATE shr 8) and 0xff).toByte()
        header[26] = ((SAMPLE_RATE shr 16) and 0xff).toByte()
        header[27] = ((SAMPLE_RATE shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (CHANNELS * BITS_PER_SAMPLE / 8).toByte() // BlockAlign
        header[33] = 0
        header[34] = BITS_PER_SAMPLE.toByte() // BitsPerSample
        header[35] = 0
        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (audioDataSize and 0xff).toByte()
        header[41] = ((audioDataSize shr 8) and 0xff).toByte()
        header[42] = ((audioDataSize shr 16) and 0xff).toByte()
        header[43] = ((audioDataSize shr 24) and 0xff).toByte()

        fos.write(header, 0, 44)
    }
}
