package dev.timelapse.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Live camera view, framed like the stored photos (full 4:3 sensor area, nothing cropped).
 * While the capture service takes a shot ([busy]) it owns the camera, so the preview is released and rebound after.
 */
@Composable
fun CameraPreview(front: Boolean, busy: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    Box(modifier.background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        if (busy) Overlay("Taking photo…")
    }
    DisposableEffect(front, busy, owner) {
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        if (!busy) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val p = future.get()
                    val useCase = Preview.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .build(),
                        )
                        .build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    p.unbindAll()
                    p.bindToLifecycle(owner, selector, useCase)
                    provider = p
                    preview = useCase
                } catch (e: Exception) {
                    Log.w("CameraPreview", "Preview failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose { preview?.let { provider?.unbind(it) } }
    }
}

/** Plays the preview GIF. Re-loads whenever [length] (the committed size) changes, i.e. after each new frame. */
@Composable
fun GifPlayer(file: File, length: Long, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black)) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Overlay("GIF playback needs Android 9+")
            return@Box
        }
        var drawable by remember { mutableStateOf<Drawable?>(null) }
        LaunchedEffect(file, length) {
            drawable = withContext(Dispatchers.IO) {
                try {
                    loadGif(file, length)
                } catch (e: Exception) {
                    Log.w("GifPlayer", "Cannot load GIF", e)
                    null
                }
            }
        }
        AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { view ->
                if (view.drawable !== drawable) {
                    (view.drawable as? AnimatedImageDrawable)?.stop()
                    view.setImageDrawable(drawable)
                    (drawable as? AnimatedImageDrawable)?.start()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (drawable == null) Overlay("Loading GIF…")
    }
}

/**
 * Maps only the committed bytes, so a frame being appended at the same time is never read.
 * The mapping stays valid after the file is closed and frames are decoded lazily, so large GIFs are fine.
 */
@RequiresApi(Build.VERSION_CODES.P)
private fun loadGif(file: File, length: Long): Drawable {
    val buffer = RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, length) }
    return ImageDecoder.decodeDrawable(ImageDecoder.createSource(buffer))
}

@Composable
private fun Overlay(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small).padding(8.dp),
        )
    }
}
