package dev.timelapse.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.timelapse.graph
import dev.timelapse.service.CaptureService
import dev.timelapse.service.Scheduler
import java.time.Instant
import java.time.ZoneId

private val RecordRed = Color(0xFFE53935)

@Composable
fun MainScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state = ui ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(state, onOpenSettings)
        MediaArea(state)
        Controls(state, vm)
        InfoCard(state, vm)
        SetupCard()
    }
}

@Composable
private fun Header(state: UiState, onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Timelapse", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        StatusChip(state)
        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
    }
}

@Composable
private fun StatusChip(state: UiState) {
    val night = Scheduler.isNight(Instant.now(), state.settings, ZoneId.systemDefault())
    val (label, color) = when {
        !state.runtime.running -> "Stopped" to MaterialTheme.colorScheme.outline
        state.cameraBusy -> "Shooting" to RecordRed
        night -> "Night pause" to Color(0xFF5C6BC0)
        else -> "Recording" to RecordRed
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.18f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

/** What the camera sees, plus the preview GIF next to it when enabled. */
@Composable
private fun MediaArea(state: UiState) {
    val context = LocalContext.current
    val portrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    val cameraAspect = if (portrait) 3f / 4f else 4f / 3f

    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val hasCamera = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    val camera = @Composable { modifier: Modifier ->
        Labeled("Camera", modifier) {
            if (hasCamera) {
                CameraPreview(
                    front = state.settings.useFrontCamera,
                    busy = state.cameraBusy,
                    modifier = Modifier.fillMaxWidth().aspectRatio(cameraAspect).clip(MaterialTheme.shapes.medium),
                )
            } else {
                Placeholder(Modifier.aspectRatio(cameraAspect)) {
                    Text("Camera access is needed for the preview", color = Color.White,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { askCamera.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                }
            }
        }
    }

    if (state.settings.gifEnabled) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            camera(Modifier.weight(1f))
            Labeled("Preview GIF", Modifier.weight(1f)) {
                val gif = state.gif
                val aspect = gif?.let { it.width.toFloat() / it.height } ?: cameraAspect
                if (gif == null) {
                    Placeholder(Modifier.aspectRatio(aspect)) {
                        Text("No frames yet", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    GifPlayer(
                        file = context.graph.gifStore.gifFile,
                        length = gif.length,
                        modifier = Modifier.fillMaxWidth().aspectRatio(aspect).clip(MaterialTheme.shapes.medium),
                    )
                }
            }
        }
    } else {
        // Alone, the camera gets the full width but is kept short enough to leave the controls on screen.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            camera(Modifier.heightIn(max = 440.dp).aspectRatio(cameraAspect, matchHeightConstraintsFirst = true))
        }
    }
}

@Composable
private fun Labeled(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun Placeholder(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Color(0xFF202020)).padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** Big record/stop button, plus a manual shot while recording. */
@Composable
private fun Controls(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    val running = state.runtime.running
    val permissions = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.CAMERA] == true) CaptureService.start(context)
        else vm.reportError("Camera permission is required")
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                if (running) {
                    CaptureService.send(context, CaptureService.ACTION_STOP)
                } else {
                    val missing = permissions.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) CaptureService.start(context) else permissionLauncher.launch(missing.toTypedArray())
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) MaterialTheme.colorScheme.surfaceVariant else RecordRed,
                contentColor = if (running) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
            ),
            modifier = Modifier.weight(1f).height(56.dp),
        ) {
            // ● record / ■ stop
            Box(
                Modifier.size(14.dp)
                    .clip(if (running) RoundedCornerShape(2.dp) else CircleShape)
                    .background(if (running) RecordRed else Color.White),
            )
            Spacer(Modifier.width(10.dp))
            Text(if (running) "Stop" else "Record", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (running) {
            OutlinedButton(
                onClick = { CaptureService.send(context, CaptureService.ACTION_CAPTURE_NOW) },
                enabled = !state.cameraBusy,
                modifier = Modifier.height(56.dp),
            ) { Text("Take photo now") }
        }
    }
}

@Composable
private fun InfoCard(state: UiState, vm: MainViewModel) {
    val s = state.settings
    val r = state.runtime
    Section("Info") {
        if (r.running) Row2("Next shot", formatTime(r.nextShotAt))
        Row2("Last shot", formatTime(r.lastShotAt))
        Row2("Schedule", buildString {
            append("every ${s.intervalMinutes} min")
            if (s.nightHours > 0) append(" · paused ${formatMinutes(s.nightStartMinutes)}–${formatMinutes((s.nightStartMinutes + s.nightHours * 60) % (24 * 60))}")
        })
        Row2("Resolution", s.resolution.label)
        Row2("Photos", "${state.photosToday} today · ${state.totalPhotos} " +
            (r.statsResetAt?.let { "since ${formatTime(it)}" } ?: "total"))
        Row2("Video", when {
            state.videoRendering -> "rendering…"
            r.video != null -> "${r.video.frames} frames · ${r.video.width}×${r.video.height} · ${formatBytes(r.video.bytes)}"
            else -> "not rendered yet"
        })
        r.video?.let { if (!state.videoRendering) Row2("Video rendered", formatTime(it.renderedAt)) }
        if (s.gifEnabled) {
            Row2("Preview GIF", state.gif?.let { "${it.frames} frames · ${it.width}×${it.height} · ${formatBytes(it.length)}" } ?: "—")
        }
        Row2("Google Drive", if (r.driveConnected) "connected" else "not connected")
        Row2("Waiting for upload", state.pendingUploads.toString())
        Row2("Last upload", formatTime(r.lastUploadAt))
        r.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.totalPhotos > 0) {
                TextButton(onClick = vm::renderVideoNow, enabled = !state.videoRendering) { Text("Render video now") }
            }
            if (r.driveConnected) TextButton(onClick = vm::syncNow) { Text("Sync now") }
        }
        var confirm by remember { mutableStateOf(false) }
        TextButton(onClick = { confirm = true }) { Text("Clear statistics") }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Clear statistics?") },
                text = {
                    Text("Photo counts restart from zero, and last shot, last upload and the last error are cleared. " +
                        "Photos, the video, the GIF and your settings are kept.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.clearStats()
                        confirm = false
                    }) { Text("Clear") }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            )
        }
    }
}
