package dev.timelapse.drive

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * OAuth via Google Identity Services. Scope `drive.file` only grants access to files this app created.
 * Once the user has granted it, [accessToken] returns a token silently, also from background workers.
 */
object DriveAuth {
    private val SCOPE = Scope("https://www.googleapis.com/auth/drive.file")

    suspend fun authorize(context: Context): AuthorizationResult {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()
        return Identity.getAuthorizationClient(context).authorize(request).await()
    }

    /** Null when user interaction is required. */
    suspend fun accessToken(context: Context): String? {
        val result = authorize(context)
        return if (result.hasResolution()) null else result.accessToken
    }

    suspend fun invalidate(context: Context, token: String) = withContext(Dispatchers.IO) {
        try {
            GoogleAuthUtil.clearToken(context, token)
        } catch (_: Exception) {
        }
    }
}

/** Minimal Drive v3 REST client (blocking; call from a background thread). */
class DriveClient(private val http: OkHttpClient, private val token: String) {

    class HttpException(val code: Int, body: String) : IOException("HTTP $code: $body")

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        val JSON = "application/json; charset=UTF-8".toMediaType()
    }

    fun findFolder(name: String): String? {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "name = '$escaped' and mimeType = '$FOLDER_MIME' and trashed = false")
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("spaces", "drive")
            .build()
        val files = json(Request.Builder().url(url).get()).getJSONArray("files")
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    fun createFolder(name: String): String {
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
        return json(Request.Builder().url("$API/files?fields=id").post(meta.toString().toRequestBody(JSON))).getString("id")
    }

    /** True if the file exists and is not in the trash. */
    fun isAlive(fileId: String): Boolean = try {
        !json(Request.Builder().url("$API/files/$fileId?fields=id,trashed").get()).optBoolean("trashed")
    } catch (e: HttpException) {
        if (e.code == 404) false else throw e
    }

    fun createFile(name: String, parentId: String, body: RequestBody): String {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parentId))
        val session = startResumable("POST", "$UPLOAD/files?uploadType=resumable&fields=id", meta, body)
        return json(Request.Builder().url(session).put(body)).getString("id")
    }

    fun replaceContent(fileId: String, body: RequestBody): String {
        val session = startResumable("PATCH", "$UPLOAD/files/$fileId?uploadType=resumable&fields=id", JSONObject(), body)
        return json(Request.Builder().url(session).put(body)).getString("id")
    }

    /** Resumable upload works for any size (multipart is limited to 5 MB, and the GIF keeps growing). */
    private fun startResumable(method: String, url: String, meta: JSONObject, body: RequestBody): String {
        val request = Request.Builder().url(url)
            .header("X-Upload-Content-Type", body.contentType().toString())
            .header("X-Upload-Content-Length", body.contentLength().toString())
            .method(method, meta.toString().toRequestBody(JSON))
        return execute(request).use { it.header("Location") ?: throw IOException("Drive returned no upload session") }
    }

    private fun json(request: Request.Builder): JSONObject = execute(request).use { JSONObject(it.body!!.string()) }

    private fun execute(request: Request.Builder): Response {
        val response = http.newCall(request.header("Authorization", "Bearer $token").build()).execute()
        if (!response.isSuccessful) {
            val text = response.body?.string().orEmpty().take(300)
            response.close()
            throw HttpException(response.code, text)
        }
        return response
    }
}

/**
 * Streams the committed part of the local GIF: bytes `[0, length - 1)` never change after commit,
 * and the final byte is always the 0x3B trailer, so concurrent appends cannot corrupt the upload.
 */
class GifSnapshotBody(private val file: File, private val length: Long) : RequestBody() {
    override fun contentType(): MediaType = "image/gif".toMediaType()
    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        file.source().buffer().use { src ->
            var remaining = length - 1
            while (remaining > 0) {
                val n = src.read(sink.buffer, minOf(remaining, 64 * 1024L))
                if (n == -1L) throw IOException("GIF shorter than expected")
                remaining -= n
                sink.emitCompleteSegments()
            }
        }
        sink.writeByte(0x3B)
    }
}
