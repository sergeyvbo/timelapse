package dev.timelapse.ui

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.timelapse.data.RuntimeState
import dev.timelapse.data.Settings
import dev.timelapse.gif.GifState
import dev.timelapse.graph
import dev.timelapse.service.CaptureService
import dev.timelapse.work.SyncScheduler
import dev.timelapse.work.VideoScheduler
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.timelapse.camera.CameraController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
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
    val photosToday: Int = 0,
    val cameraBusy: Boolean = false,
    val videoRendering: Boolean = false,
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val g = app.graph

    // Must be declared before [ui], which uses it while initializing.
    private val statsSince = g.settings.state.map { it.statsResetAt ?: 0L }.distinctUntilChanged()

    val ui: StateFlow<UiState?> = combine(
        g.settings.settings,
        g.settings.state,
        g.photos.observePendingUploads(),
        totalCount(),
        g.gifStore.stateFlow,
    ) { settings, runtime, pending, total, gif -> UiState(settings, runtime, pending, total, gif) }
        .combine(CameraController.busy) { ui, busy -> ui.copy(cameraBusy = busy) }
        .combine(
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(VideoScheduler.WORK_NAME)
                .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } },
        ) { ui, rendering -> ui.copy(videoRendering = rendering) }
        .combine(todayCount()) { ui, today -> ui.copy(photosToday = today) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Photos since statistics were last cleared. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun totalCount() = statsSince.flatMapLatest { g.photos.observeCountSince(it) }

    /** Photos since local midnight (or the last reset, if later); re-queried when the date changes (checked once a minute). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun todayCount() = flow {
        while (true) {
            emit(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            delay(60_000)
        }
    }.distinctUntilChanged()
        .combine(statsSince) { midnight, reset -> maxOf(midnight, reset) }
        .distinctUntilChanged()
        .flatMapLatest { g.photos.observeCountSince(it) }

    fun clearStats() = viewModelScope.launch { g.settings.resetStats(System.currentTimeMillis()) }

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

    fun renderVideoNow() = VideoScheduler.enqueue(app, force = true)

    /** Deletes every local photo, GIF and the video and resets the counters. Only while stopped. */
    fun startOver() = viewModelScope.launch {
        if (g.settings.state().running) return@launch
        WorkManager.getInstance(app).cancelUniqueWork(VideoScheduler.WORK_NAME)
        withContext(Dispatchers.IO) {
            g.gifAppender.exclusive {
                g.photos.deleteAll()
                g.photosDir.listFiles()?.forEach { it.delete() }
                g.gifStore.reset()
                g.videoFile.parentFile?.listFiles()?.forEach { it.delete() }
                g.settings.startOver()
            }
        }
    }

    fun reportError(message: String) = viewModelScope.launch { g.settings.setLastError(message) }
}
