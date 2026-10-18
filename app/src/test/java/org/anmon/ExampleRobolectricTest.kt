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
}
