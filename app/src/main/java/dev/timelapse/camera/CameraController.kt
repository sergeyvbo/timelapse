package dev.timelapse.camera

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dev.timelapse.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    suspend fun capture(file: File, settings: Settings) = withContext(Dispatchers.Main) {
        val provider = cameraProvider(context)
        val selector = if (settings.useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(settings.jpegQuality)
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

    private companion object {
        const val WARM_UP_MS = 1500L
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
