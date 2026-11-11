package dev.timelapse.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.timelapse.data.RuntimeState
import dev.timelapse.data.Settings
import dev.timelapse.gif.GifState
import dev.timelapse.graph
import dev.timelapse.service.CaptureService
import dev.timelapse.work.SyncScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiState(
    val settings: Settings,
    val runtime: RuntimeState,
    val pendingUploads: Int,
    val totalPhotos: Int,
    val gif: GifState?,
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val g = app.graph

    val ui: StateFlow<UiState?> = combine(
        g.settings.settings,
        g.settings.state,
        g.photos.observePendingUploads(),
        g.photos.observeTotal(),
        g.gifStore.stateFlow,
    ) { settings, runtime, pending, total, gif -> UiState(settings, runtime, pending, total, gif) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun saveSettings(settings: Settings) = viewModelScope.launch {
        g.settings.saveSettings(settings)
        // Let the service re-plan the next shot with the new schedule.
        if (CaptureService.isRunning) CaptureService.send(app, CaptureService.ACTION_TICK)
    }

    fun onDriveAuthorized() = viewModelScope.launch {
        g.settings.setDriveConnected(true)
        g.settings.setLastError(null)
        SyncScheduler.enqueue(app)
    }

    fun syncNow() = SyncScheduler.enqueue(app)

    fun reportError(message: String) = viewModelScope.launch { g.settings.setLastError(message) }
}
