package dev.timelapse.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.timelapse.graph
import dev.timelapse.service.CaptureService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface {
                    MainScreen(viewModel())
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The app is visible, so this is the moment Android allows (re)starting the camera service.
        lifecycleScope.launch {
            val running = graph.settings.state().running
            val hasCamera = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (running && hasCamera && !CaptureService.isRunning) CaptureService.start(this@MainActivity)
        }
    }
}
