package dev.timelapse.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.Identity
import dev.timelapse.data.Settings
import dev.timelapse.drive.DriveAuth
import dev.timelapse.service.CaptureService
import dev.timelapse.service.Scheduler
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MainScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state = ui ?: run {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
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
        Text("Timelapse", style = MaterialTheme.typography.headlineMedium)
        StatusCard(state, vm)
        SetupCard()
        DriveCard(state, vm)
        if (!state.runtime.running) PreviewCard(state.settings.useFrontCamera)
        SettingsCard(state.settings, gifStarted = state.gif != null, onSave = vm::saveSettings)
    }
}

@Composable
private fun StatusCard(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    val running = state.runtime.running
    val permissions = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            CaptureService.start(context)
        } else {
            vm.reportError("Camera permission is required")
        }
    }

    Section("Status") {
        val night = Scheduler.isNight(Instant.now(), state.settings, ZoneId.systemDefault())
        Row2("State", if (!running) "Stopped" else if (night) "Running (night pause)" else "Running")
        if (running) Row2("Next shot", formatTime(state.runtime.nextShotAt))
        Row2("Last shot", formatTime(state.runtime.lastShotAt))
        Row2("Photos taken", state.totalPhotos.toString())
        Row2("Waiting for upload", state.pendingUploads.toString())
        Row2("GIF", state.gif?.let { "${it.frames} frames · ${it.width}×${it.height} · ${formatBytes(it.length)}" } ?: "not started")
        Row2("Last upload", formatTime(state.runtime.lastUploadAt))
        state.runtime.lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                Button(onClick = { CaptureService.send(context, CaptureService.ACTION_STOP) }) { Text("Stop") }
                OutlinedButton(onClick = { CaptureService.send(context, CaptureService.ACTION_CAPTURE_NOW) }) { Text("Take photo now") }
            } else {
                Button(onClick = {
                    val missing = permissions.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) CaptureService.start(context) else permissionLauncher.launch(missing.toTypedArray())
                }) { Text("Start") }
            }
        }
    }
}

@Composable
private fun SetupCard() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    // Re-checked every time the user comes back from the system settings screens.
    val batteryOk = remember(refresh) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val alarmsOk = remember(refresh) {
        Build.VERSION.SDK_INT !in 31..32 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
    if (batteryOk && alarmsOk) return

    Section("Setup") {
        if (!batteryOk) {
            Text("Battery optimization can delay or skip shots.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                )
            }) { Text("Disable battery optimization") }
        }
        if (!alarmsOk) {
            Text("Exact alarms are needed to shoot on time.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                )
            }) { Text("Allow exact alarms") }
        }
    }
}

@Composable
private fun DriveCard(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data)
                vm.onDriveAuthorized()
            } catch (e: Exception) {
                vm.reportError("Drive authorization failed: ${e.message}")
            }
        } else {
            vm.reportError("Drive authorization cancelled")
        }
    }

    Section("Google Drive") {
        Row2("Status", if (state.runtime.driveConnected) "Connected" else "Not connected")
        Row2("Folder", state.settings.driveFolderName)
        Text("Uploads run on Wi-Fi only. Originals are deleted from the phone after a successful upload.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    try {
                        val r = DriveAuth.authorize(context)
                        val pending = r.pendingIntent
                        if (r.hasResolution() && pending != null) {
                            authLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                        } else {
                            vm.onDriveAuthorized()
                        }
                    } catch (e: Exception) {
                        vm.reportError("Drive authorization failed: ${e.message}")
                    }
                }
            }) { Text(if (state.runtime.driveConnected) "Reconnect" else "Connect Google Drive") }
            if (state.runtime.driveConnected) OutlinedButton(onClick = vm::syncNow) { Text("Sync now") }
        }
    }
}

@Composable
private fun PreviewCard(front: Boolean) {
    var show by remember { mutableStateOf(false) }
    Section("Framing") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Camera preview", Modifier.weight(1f))
            Switch(checked = show, onCheckedChange = { show = it })
        }
        if (show) CameraPreview(front)
    }
}

@Composable
private fun CameraPreview(front: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f))
    DisposableEffect(front, owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider = future.get().also { p ->
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                p.unbindAll()
                p.bindToLifecycle(owner, selector, preview)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }
}

@Composable
private fun SettingsCard(settings: Settings, gifStarted: Boolean, onSave: (Settings) -> Unit) {
    var interval by remember(settings) { mutableStateOf(settings.intervalMinutes.toString()) }
    var nightStart by remember(settings) { mutableStateOf(formatMinutes(settings.nightStartMinutes)) }
    var nightHours by remember(settings) { mutableStateOf(settings.nightHours.toString()) }
    var front by remember(settings) { mutableStateOf(settings.useFrontCamera) }
    var quality by remember(settings) { mutableStateOf(settings.jpegQuality.toString()) }
    var gifWidth by remember(settings) { mutableStateOf(settings.gifWidth.toString()) }
    var gifDelay by remember(settings) { mutableStateOf(settings.gifFrameDelayMs.toString()) }
    var folder by remember(settings) { mutableStateOf(settings.driveFolderName) }
    var error by remember { mutableStateOf<String?>(null) }

    Section("Settings") {
        NumberField("Interval during the day (minutes)", interval) { interval = it }
        OutlinedTextField(nightStart, { nightStart = it }, label = { Text("Night starts at (HH:mm)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        NumberField("Night length (hours)", nightHours) { nightHours = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use front camera", Modifier.weight(1f))
            Switch(checked = front, onCheckedChange = { front = it })
        }
        NumberField("JPEG quality (1-100)", quality) { quality = it }
        NumberField("GIF frame width (px)", gifWidth, enabled = !gifStarted) { gifWidth = it }
        if (gifStarted) Text("Frame width is fixed once the GIF has started.", style = MaterialTheme.typography.bodySmall)
        NumberField("GIF frame delay (ms)", gifDelay) { gifDelay = it }
        OutlinedTextField(folder, { folder = it }, label = { Text("Drive folder") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val parsed = parseSettings(interval, nightStart, nightHours, front, quality, gifWidth, gifDelay, folder)
            if (parsed == null) {
                error = "Please check the values (time as HH:mm, numbers only)."
            } else {
                error = null
                onSave(parsed)
            }
        }) { Text("Save") }
    }
}

private fun parseSettings(
    interval: String, nightStart: String, nightHours: String, front: Boolean,
    quality: String, gifWidth: String, gifDelay: String, folder: String,
): Settings? {
    val start = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(nightStart.trim())?.destructured?.let { (h, m) ->
        val hh = h.toInt()
        val mm = m.toInt()
        if (hh in 0..23 && mm in 0..59) hh * 60 + mm else null
    } ?: return null
    return Settings(
        intervalMinutes = interval.trim().toIntOrNull()?.takeIf { it >= 1 } ?: return null,
        nightStartMinutes = start,
        nightHours = nightHours.trim().toIntOrNull()?.takeIf { it in 0..23 } ?: return null,
        useFrontCamera = front,
        jpegQuality = quality.trim().toIntOrNull() ?: return null,
        gifWidth = gifWidth.trim().toIntOrNull() ?: return null,
        gifFrameDelayMs = gifDelay.trim().toIntOrNull() ?: return null,
        driveFolderName = folder,
    ).sanitized()
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NumberField(label: String, value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private val timeFormat = DateTimeFormatter.ofPattern("EEE HH:mm")

private fun formatTime(epochMillis: Long?): String =
    epochMillis?.let { timeFormat.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "—"

private fun formatMinutes(m: Int) = "%02d:%02d".format(m / 60, m % 60)

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}
