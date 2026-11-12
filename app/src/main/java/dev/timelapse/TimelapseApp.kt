package dev.timelapse

import android.app.Application
import android.content.Context
import dev.timelapse.data.PhotoDatabase
import dev.timelapse.data.SettingsRepository
import dev.timelapse.gif.GifAppender
import dev.timelapse.gif.GifStore
import dev.timelapse.service.Notifications
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class TimelapseApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifications.createChannels(this)
    }
}

/** Manual dependency container; everything is a process-wide singleton. */
class AppGraph(private val context: Context) {
    val settings by lazy { SettingsRepository(context) }
    val photos by lazy { PhotoDatabase.build(context).photoDao() }
    val gifStore by lazy { GifStore(File(context.filesDir, "gif")) }
    val gifAppender by lazy { GifAppender(photos, gifStore, settings) }
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
    /** Stored photos at the selected resolution; kept forever as the MP4's frames. */
    val photosDir: File get() = File(context.filesDir, "photos").apply { mkdirs() }
    val videoFile: File get() = File(File(context.filesDir, "video").apply { mkdirs() }, "timelapse.mp4")
}

val Context.graph: AppGraph get() = (applicationContext as TimelapseApp).graph
