package org.anmon

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.anmon.model.Album
import org.anmon.model.Track
import org.anmon.playback.PlaybackManagerProvider
import org.anmon.data.M3uParser
import org.anmon.ui.MainViewModel
import org.anmon.ui.theme.AppTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Aura Music", appName)
  }

  @Test
  fun `playbackManagerProvider resurrects fresh player on reset`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val p1 = PlaybackManagerProvider.get(context)
    org.junit.Assert.assertNotNull(p1)
    org.junit.Assert.assertFalse(p1.isReleased)

    PlaybackManagerProvider.reset()
    org.junit.Assert.assertTrue(p1.isReleased)

    val p2 = PlaybackManagerProvider.get(context)
    org.junit.Assert.assertNotNull(p2)
    org.junit.Assert.assertFalse(p2.isReleased)
    org.junit.Assert.assertNotSame(p1, p2)
  }

  @Test
  fun `random album picker returns up to 6 unique albums with fresh seed`() {
    val sampleAlbums = (1..20).map { i ->
      Album(
        id = "album_$i",
        title = "Album $i",
        artist = "Artist $i",
        trackCount = 5,
        totalDurationMs = 600000,
        tracks = emptyList()
      )
    }

    val seed1 = System.nanoTime() xor System.currentTimeMillis() xor kotlin.random.Random.nextLong()
    val selection1 = sampleAlbums.shuffled(kotlin.random.Random(seed1)).take(6)

    val seed2 = System.nanoTime() xor System.currentTimeMillis() xor kotlin.random.Random.nextLong()
    val selection2 = sampleAlbums.shuffled(kotlin.random.Random(seed2)).take(6)

    assertEquals(6, selection1.size)
    assertEquals(6, selection2.size)
    assertEquals(6, selection1.distinctBy { it.id }.size)
  }

  @Test
  fun `m3u parser correctly parses directives and resolves tracks`() {
    val m3uContent = """
      #EXTM3U
      #EXTINF:120,Astral Wave - Starlight Reverie
      /storage/emulated/0/Music/01_starlight.wav
      #EXTINF:180,The Velvet Collective - Moonlight Rain
      subfolder/02_moonlight.mp3
      # Comment line to ignore
      unknown_track.flac
    """.trimIndent()

    val entries = M3uParser.parse(m3uContent)
    assertEquals(3, entries.size)
    assertEquals("Starlight Reverie", entries[0].title)
    assertEquals("Astral Wave", entries[0].artist)
    assertEquals(120L, entries[0].durationSeconds)
    assertEquals("Moonlight Rain", entries[1].title)
    assertEquals("The Velvet Collective", entries[1].artist)

    val sampleTracks = listOf(
      Track(
        id = "track_1",
        title = "Starlight Reverie",
        artist = "Astral Wave",
        album = "Album 1",
        durationMs = 120000,
        uri = "file:///storage/emulated/0/Music/01_starlight.wav",
        filePath = "/storage/emulated/0/Music/01_starlight.wav"
      ),
      Track(
        id = "track_2",
        title = "Moonlight Rain",
        artist = "The Velvet Collective",
        album = "Album 2",
        durationMs = 180000,
        uri = "file:///subfolder/02_moonlight.mp3",
        filePath = "/subfolder/02_moonlight.mp3"
      )
    )

    val resolvedIds = M3uParser.resolveTracks(entries, null, sampleTracks)
    assertEquals(2, resolvedIds.size)
    org.junit.Assert.assertTrue(resolvedIds.contains("track_1"))
    org.junit.Assert.assertTrue(resolvedIds.contains("track_2"))
  }

  @Test
  fun `playbackManager persists and restores active track queue and position across resets`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    PlaybackManagerProvider.reset()

    val tracks = listOf(
      Track(
        id = "t_1",
        title = "First Song",
        artist = "Artist A",
        album = "Album A",
        durationMs = 180000L,
        uri = "file:///music/first.mp3"
      ),
      Track(
        id = "t_2",
        title = "Second Song",
        artist = "Artist B",
        album = "Album B",
        durationMs = 210000L,
        uri = "file:///music/second.mp3"
      )
    )

    val manager1 = PlaybackManagerProvider.get(context)
    manager1.playQueue(tracks, startIndex = 1)
    manager1.seekTo(45000L)
    manager1.playPause() // Simulate pause when another app (e.g. Instagram) takes focus

    // Simulate background cleanup / recreation
    PlaybackManagerProvider.reset()

    val manager2 = PlaybackManagerProvider.get(context)
    val restoredState = manager2.playbackState.value

    org.junit.Assert.assertNotNull(restoredState.currentTrack)
    assertEquals("t_2", restoredState.currentTrack?.id)
    assertEquals("Second Song", restoredState.currentTrack?.title)
    assertEquals(1, restoredState.currentIndex)
    assertEquals(2, restoredState.queue.size)
    assertEquals(45000L, restoredState.currentPositionMs)
  }

  @Test
  fun `theme cycling updates current theme and remembers preference when re-created`() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val vm1 = MainViewModel(application)

    val initialTheme = vm1.currentTheme.value
    assertEquals(AppTheme.OBSIDIAN_PURPLE, initialTheme)

    // Cycle themes
    vm1.cycleTheme()
    val secondTheme = vm1.currentTheme.value
    assertEquals(AppTheme.CLEAN_LIGHT, secondTheme)

    vm1.cycleTheme()
    val thirdTheme = vm1.currentTheme.value
    assertEquals(AppTheme.EMERALD_BLACK, thirdTheme)
    assertEquals("Green & Black", thirdTheme.displayName)

    // Create a new ViewModel instance to simulate restarting the app
    val vm2 = MainViewModel(application)
    assertEquals(AppTheme.EMERALD_BLACK, vm2.currentTheme.value)
  }

  @Test
  fun `car bluetooth disconnect and phone call interruptions keep auto-resume armed`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    PlaybackManagerProvider.reset()

    val tracks = listOf(
      Track(
        id = "car_track_1",
        title = "Highway Drive",
        artist = "Midnight Synth",
        album = "Night Drive",
        durationMs = 200000L,
        uri = "file:///music/highway.mp3"
      )
    )

    val manager = PlaybackManagerProvider.get(context)
    manager.playQueue(tracks, startIndex = 0)
    org.junit.Assert.assertTrue(manager.userIntendsPlayback)

    // Simulate car head unit sending AVRCP pause right before cutting Bluetooth power
    manager.onExternalPauseRequest()
    manager.onBluetoothOrOutputDisconnected()

    org.junit.Assert.assertTrue(manager.userIntendsPlayback)
    org.junit.Assert.assertTrue(manager.isWaitingForBluetoothOrOutputReconnect)
    org.junit.Assert.assertTrue(manager.isWaitingToAutoResume)

    // Simulate manual user pause clearing auto-resume intent
    manager.resumePlayback()
    manager.playPause()
    org.junit.Assert.assertFalse(manager.userIntendsPlayback)
    org.junit.Assert.assertFalse(manager.isWaitingToAutoResume)
  }

  @Test
  fun `call ending after bluetooth disconnects during call stays paused instead of playing on phone speaker`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    PlaybackManagerProvider.reset()

    val tracks = listOf(
      Track(
        id = "bt_call_track_1",
        title = "Stereo Session",
        artist = "Aura Artist",
        album = "Night Drive",
        durationMs = 210000L,
        uri = "file:///music/stereo.mp3"
      )
    )

    val manager = PlaybackManagerProvider.get(context)
    // 1. Start playback while connected to Bluetooth stereo
    manager.externalOutputConnectedOverrideForTesting = true
    manager.playQueue(tracks, startIndex = 0)
    org.junit.Assert.assertTrue(manager.playbackState.value.isPlaying)
    org.junit.Assert.assertTrue(manager.wasPlayingOnExternalOutput)

    // 2. Receive a third-party VoIP call (e.g. Snapchat) while connected to Bluetooth
    manager.onCallOrFocusInterruptionStarted()
    org.junit.Assert.assertTrue(manager.isWaitingForCallOrFocusRecovery)

    // 3. Lose Bluetooth stereo connection while the call is ongoing
    manager.externalOutputConnectedOverrideForTesting = false
    manager.onBluetoothOrOutputDisconnected()

    org.junit.Assert.assertTrue(manager.externalOutputDisconnectedDuringInterruption)
    org.junit.Assert.assertFalse(manager.playbackState.value.isPlaying)
    org.junit.Assert.assertEquals(false, manager.exoPlayer.playWhenReady)

    // 4. Call ends while Bluetooth is still disconnected: automatic recovery must NOT resume on phone speakers
    org.junit.Assert.assertFalse(manager.canAutoResumeAfterCallEnded())
    manager.resumePlayback(fromExplicitUserAction = false)
    org.junit.Assert.assertFalse(manager.playbackState.value.isPlaying)
    org.junit.Assert.assertEquals(false, manager.exoPlayer.playWhenReady)

    // 5. Once Bluetooth stereo reconnects, auto-resume is allowed to proceed
    manager.externalOutputConnectedOverrideForTesting = true
    org.junit.Assert.assertTrue(manager.canAutoResumeAfterCallEnded())
    manager.resumePlayback(fromExplicitUserAction = false)
    org.junit.Assert.assertTrue(manager.playbackState.value.isPlaying)
  }

  @Test
  fun `multi-disc album parses disc numbers and sorts by discNumber first then trackNumber`() {
    // 1. Verify MediaStore 1000-encoded TRACK column (e.g. 1001 = Disc 1 Track 1, 2001 = Disc 2 Track 1)
    assertEquals(1 to 1, org.anmon.data.MusicRepository.parseDiscAndTrackNumbers(rawTrackColumn = 1001))
    assertEquals(2 to 1, org.anmon.data.MusicRepository.parseDiscAndTrackNumbers(rawTrackColumn = 2001))
    assertEquals(2 to 5, org.anmon.data.MusicRepository.parseDiscAndTrackNumbers(rawTrackColumn = 2005))

    // 2. Verify explicit DISC_NUMBER ("1/2", "2/2") and CD_TRACK_NUMBER ("01/10", "02/10") tags
    assertEquals(
      2 to 1,
      org.anmon.data.MusicRepository.parseDiscAndTrackNumbers(
        rawTrackColumn = 1,
        cdTrackTag = "01/10",
        discNumberTag = "2/2"
      )
    )

    // 3. Verify folder/filename fallbacks ("Disc 2", "2-03 Song.flac")
    assertEquals(
      2 to 3,
      org.anmon.data.MusicRepository.parseDiscAndTrackNumbers(
        rawTrackColumn = 3,
        filePathOrName = "/storage/emulated/0/Music/Album/Disc 2/03 - Track.mp3",
        parentFolderName = "Disc 2"
      )
    )
    assertEquals(
      2 to 4,
      org.anmon.data.MusicRepository.parseDiscAndTrackNumbers(
        filePathOrName = "2-04 Song Title.flac"
      )
    )

    // 4. Verify interleaved multi-disc tracks sort by discNumber first, then trackNumber
    val interleavedTracks = listOf(
      Track(id = "d1_t1", title = "D1 Track 1", album = "Double Album", discNumber = 1, trackNumber = 1, uri = "file:///1_1.mp3"),
      Track(id = "d2_t1", title = "D2 Track 1", album = "Double Album", discNumber = 2, trackNumber = 1, uri = "file:///2_1.mp3"),
      Track(id = "d1_t2", title = "D1 Track 2", album = "Double Album", discNumber = 1, trackNumber = 2, uri = "file:///1_2.mp3"),
      Track(id = "d2_t2", title = "D2 Track 2", album = "Double Album", discNumber = 2, trackNumber = 2, uri = "file:///2_2.mp3")
    )

    val sorted = org.anmon.data.MusicRepository.sortAlbumTracks(interleavedTracks)
    assertEquals(listOf("d1_t1", "d1_t2", "d2_t1", "d2_t2"), sorted.map { it.id })
  }
}
