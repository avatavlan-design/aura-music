package org.anmon

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import org.anmon.ui.MainScreen
import org.anmon.ui.MainViewModel
import org.anmon.ui.theme.AuraMusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mainViewModel: MainViewModel = viewModel()
            val currentTheme by mainViewModel.currentTheme.collectAsState()

            // Request runtime permissions gracefully for audio reading
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { /* Permissions handled gracefully; library will rescan */ }

            LaunchedEffect(Unit) {
                val permissionsToRequest = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                permissionLauncher.launch(permissionsToRequest.toTypedArray())

                try {
                    val serviceIntent = android.content.Intent(this@MainActivity, org.anmon.playback.MediaPlaybackService::class.java)
                    startService(serviceIntent)
                } catch (_: Exception) {}
            }

            AuraMusicTheme(theme = currentTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(viewModel = mainViewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        org.anmon.playback.PlaybackManagerProvider.get(this)
    }

    override fun onStop() {
        try {
            org.anmon.playback.PlaybackManagerProvider.get(this).savePlaybackSession()
        } catch (_: Exception) {}
        super.onStop()
    }
}
