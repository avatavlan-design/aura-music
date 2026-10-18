package org.anmon

import org.anmon.model.Album
import org.anmon.model.RepeatMode
import org.anmon.model.Track
import org.anmon.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun track_formatLabel_identifiesFileExtensionsCorrectly() {
        val flacTrack = Track(
            id = "1",
            title = "Test FLAC",
            uri = "file:///storage/music/song.flac",
            mimeType = "audio/flac"
        )
        assertEquals("FLAC", flacTrack.formatLabel)

        val mp3Track = Track(
            id = "2",
            title = "Test MP3",
            uri = "file:///storage/music/song.mp3",
            mimeType = "audio/mpeg"
        )
        assertEquals("MP3", mp3Track.formatLabel)

        val wavTrack = Track(
            id = "3",
            title = "Test WAV",
            uri = "content://media/audio/123",
            mimeType = "audio/wav"
        )
        assertEquals("WAV", wavTrack.formatLabel)
    }

    @Test
    fun track_durationFormatted_formatsMinutesAndSeconds() {
        val track1 = Track(
            id = "1",
            title = "Short",
            uri = "file:///a.mp3",
            durationMs = 65000L // 1:05
        )
        assertEquals("1:05", track1.durationFormatted)

        val track2 = Track(
            id = "2",
            title = "Zero",
            uri = "file:///b.mp3",
            durationMs = 0L
        )
        assertEquals("0:00", track2.durationFormatted)

        val track3 = Track(
            id = "3",
            title = "Long",
            uri = "file:///c.mp3",
            durationMs = 215000L // 3:35
        )
        assertEquals("3:35", track3.durationFormatted)
    }

    @Test
    fun playbackState_gaplessNextTrack_computesPreloadedItem() {
        val track1 = Track(id = "1", title = "Track 1", album = "Album 1", uri = "file:///1.wav")
        val track2 = Track(id = "2", title = "Track 2", album = "Album 1", uri = "file:///2.wav")
        val track3 = Track(id = "3", title = "Track 3", album = "Album 1", uri = "file:///3.wav")

        val state = PlaybackState(
            currentTrack = track1,
            currentIndex = 0,
            queue = listOf(track1, track2, track3),
            repeatMode = RepeatMode.ALL
        )

        assertEquals("Track 2", state.nextTrack?.title)
        assertNull(state.previousTrack)

        val stateAtEnd = PlaybackState(
            currentTrack = track3,
            currentIndex = 2,
            queue = listOf(track1, track2, track3),
            repeatMode = RepeatMode.OFF
        )
        assertNull(stateAtEnd.nextTrack)
        assertEquals("Track 2", stateAtEnd.previousTrack?.title)

        val stateAtEndRepeating = PlaybackState(
            currentTrack = track3,
            currentIndex = 2,
            queue = listOf(track1, track2, track3),
            repeatMode = RepeatMode.ALL
        )
        assertEquals("Track 1", stateAtEndRepeating.nextTrack?.title)
    }

    @Test
    fun album_durationFormatted_calculatesTotalMinutes() {
        val album = Album(
            id = "test_album",
            title = "Starlight Horizons",
            artist = "Aura Collective",
            trackCount = 2,
            totalDurationMs = 185000L // 3 minutes 5 seconds
        )
        assertEquals("3 mins", album.durationFormatted)
    }
}
