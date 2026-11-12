package dev.timelapse.camera

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dev.timelapse.data.Settings
import dev.timelapse.media.Frames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Headless still capture. The camera is bound only for the duration of one shot, so it stays
 * free (and cool) between shots.
 */
class CameraController(private val context: Context, private val owner: LifecycleOwner) {
    private val ioExecutor = Executors.newSingleThreadExecutor()

    /** Takes a photo and stores it at exactly the selected resolution (see [Frames.normalize]). */
    suspend fun capture(file: File, settings: Settings) {
        val raw = File(file.parentFile, file.name + ".raw")
        try {
            busy.value = true
            try {
                captureRaw(raw, settings)
            } finally {
                busy.value = false
            }
            withContext(Dispatchers.Default) { Frames.normalize(raw, file, settings.resolution, settings.jpegQuality) }
        } finally {
            raw.delete()
        }
    }

    private suspend fun captureRaw(file: File, settings: Settings) = withContext(Dispatchers.Main) {
        val provider = cameraProvider(context)
        val selector = if (settings.useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        // Closest 4:3 size at or above the target; normalize() then crops/scales to the exact size.
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(settings.resolution.long, settings.resolution.short),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()
        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(100) // temporary file; the stored JPEG uses the user's quality
            .setResolutionSelector(resolution)
            .setTargetRotation(displayRotation())
            .build()

        provider.unbindAll()
        provider.bindToLifecycle(owner, selector, imageCapture)
        try {
            // Let auto-exposure / white balance settle after the camera opens.
            delay(WARM_UP_MS)
            imageCapture.takePictureTo(file)
        } finally {
            provider.unbind(imageCapture)
        }
    }

    private fun displayRotation(): Int =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0

    private suspend fun ImageCapture.takePictureTo(file: File) = suspendCancellableCoroutine { cont ->
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        takePicture(options, ioExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = cont.resume(Unit)
            override fun onError(exception: ImageCaptureException) = cont.resumeWithException(exception)
        })
    }

    companion object {
        private const val WARM_UP_MS = 1500L

        /**
         * True while a shot is being taken. Capturing takes the camera away from the in-app preview
         * (it unbinds everything), so the preview watches this and binds again afterwards.
         */
        val busy = MutableStateFlow(false)
    }
}

suspend fun cameraProvider(context: Context): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        try {
            cont.resume(future.get())
        } catch (e: Exception) {
            cont.resumeWithException(e.cause ?: e)
        }
    }, ContextCompat.getMainExecutor(context))
}
