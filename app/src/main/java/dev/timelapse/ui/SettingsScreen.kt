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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import dev.timelapse.data.Resolution
import dev.timelapse.data.Settings
import dev.timelapse.drive.DriveAuth
import dev.timelapse.service.CaptureService
import dev.timelapse.service.Scheduler
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state = ui ?: return
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        SetupCard()
        DriveCard(state, vm)
        SettingsCard(state.settings, gifStarted = state.gif != null, onSave = {
            vm.saveSettings(it)
            onBack()
        })
    }
}

@Composable
fun SetupCard() {
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
        // Play services often reports misconfiguration (wrong SHA-1/package, API disabled, not a test user)
        // as RESULT_CANCELED, with the real status in the intent, so always try to parse it.
        try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data)
            if (result.resultCode == Activity.RESULT_OK) vm.onDriveAuthorized()
            else vm.reportError("Drive authorization cancelled (resultCode=${result.resultCode}, no status)")
        } catch (e: com.google.android.gms.common.api.ApiException) {
            val code = e.statusCode
            val name = com.google.android.gms.common.api.CommonStatusCodes.getStatusCodeString(code)
            val hint = when (code) {
                10 -> " - OAuth client mismatch: check package dev.timelapse + SHA-1 in Cloud Console"
                16 -> " - cancelled by user or consent screen blocked (are you a test user?)"
                else -> ""
            }
            android.util.Log.w("DriveAuth", "authorization failed", e)
            vm.reportError("Drive authorization failed: $code $name$hint")
        } catch (e: Exception) {
            android.util.Log.w("DriveAuth", "authorization failed", e)
            vm.reportError("Drive authorization failed (resultCode=${result.resultCode}): ${e.message}")
        }
    }

    Section("Google Drive") {
        Row2("Status", if (state.runtime.driveConnected) "Connected" else "Not connected")
        Row2("Folder", state.settings.driveFolderName)
        Text("Uploads run on Wi-Fi only: each photo after it is taken, the video after its daily rebuild. " +
            "Photos stay on the phone as the video's frames.",
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
private fun SettingsCard(settings: Settings, gifStarted: Boolean, onSave: (Settings) -> Unit) {
    var interval by remember(settings) { mutableStateOf(settings.intervalMinutes.toString()) }
    var nightStart by remember(settings) { mutableStateOf(formatMinutes(settings.nightStartMinutes)) }
    var nightHours by remember(settings) { mutableStateOf(settings.nightHours.toString()) }
    var front by remember(settings) { mutableStateOf(settings.useFrontCamera) }
    var quality by remember(settings) { mutableStateOf(settings.jpegQuality.toString()) }
    var resolution by remember(settings) { mutableStateOf(settings.resolution) }
    var fps by remember(settings) { mutableStateOf(settings.videoFps.toString()) }
    var gifEnabled by remember(settings) { mutableStateOf(settings.gifEnabled) }
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
        ResolutionSelect(resolution) { resolution = it }
        Text("Photos are stored at this size, and the video is rendered at it too (or at the largest size the phone's " +
            "encoder supports). A change applies to new photos; the whole video is re-rendered at the new size.",
            style = MaterialTheme.typography.bodySmall)
        NumberField("JPEG quality (1-100)", quality) { quality = it }
        NumberField("Video frames per second", fps) { fps = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = gifEnabled, onCheckedChange = { gifEnabled = it })
            Text("Also create a preview GIF", Modifier.weight(1f))
        }
        if (gifEnabled) {
            NumberField("GIF frame width (px)", gifWidth, enabled = !gifStarted) { gifWidth = it }
            if (gifStarted) Text("Frame width is fixed once the GIF has started.", style = MaterialTheme.typography.bodySmall)
            NumberField("GIF frame delay (ms)", gifDelay) { gifDelay = it }
        }
        OutlinedTextField(folder, { folder = it }, label = { Text("Drive folder") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val parsed = parseSettings(interval, nightStart, nightHours, front, quality, gifWidth, gifDelay, folder)
                ?.let { p -> fps.trim().toIntOrNull()?.let { p.copy(resolution = resolution, videoFps = it, gifEnabled = gifEnabled) } }
                ?.sanitized()
            if (parsed == null) {
                error = "Please check the values (time as HH:mm, numbers only)."
            } else {
                error = null
                onSave(parsed)
            }
        }) { Text("Save") }
    }
}

@Composable
private fun ResolutionSelect(value: Resolution, onChange: (Resolution) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Resolution", Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(value.label + "  ▾") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Resolution.entries.forEach { r ->
                    DropdownMenuItem(text = { Text(r.label) }, onClick = {
                        onChange(r)
                        open = false
                    })
                }
            }
        }
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

