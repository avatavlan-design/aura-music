package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
    val p1 = com.example.playback.PlaybackManagerProvider.get(context)
    org.junit.Assert.assertNotNull(p1)
    org.junit.Assert.assertFalse(p1.isReleased)

    com.example.playback.PlaybackManagerProvider.reset()
    org.junit.Assert.assertTrue(p1.isReleased)

    val p2 = com.example.playback.PlaybackManagerProvider.get(context)
    org.junit.Assert.assertNotNull(p2)
    org.junit.Assert.assertFalse(p2.isReleased)
    org.junit.Assert.assertNotSame(p1, p2)
  }
}
