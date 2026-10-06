package com.hermes.client.data.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hermes.client.data.network.HermesRestApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Where phone uploads land on the gateway host. Inside the locked root. */
const val UPLOADS_DIR = "/home/spaulding/.hermes/uploads"

data class UploadResult(val name: String, val ok: Boolean, val error: String? = null)

/**
 * Uploads a content:// document to the gateway's managed-files area, streaming rather than
 * buffering (the server accepts up to 100 MB). Shared by the Files screen's upload button and the
 * "Share to Hermes Local" intent path so both behave identically.
 */
class FileUploader(
    private val context: Context,
    private val rest: HermesRestApi,
) {
    suspend fun upload(uri: Uri, destDir: String = UPLOADS_DIR): UploadResult {
        val resolver = context.contentResolver
        val (name, size) = queryNameAndSize(uri)
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return runCatching {
            rest.uploadFileStreaming(
                destPath = "${destDir.trimEnd('/')}/$name",
                fileName = name,
                mimeType = mime,
                contentLength = size,
                openStream = {
                    resolver.openInputStream(uri) ?: throw IOException("Could not read the selected file")
                },
            )
            UploadResult(name, ok = true)
        }.getOrElse { UploadResult(name, ok = false, error = it.message ?: "Upload failed") }
    }

    /** Display name + byte size from the content provider, measuring the stream when it reports neither. */
    private suspend fun queryNameAndSize(uri: Uri): Pair<String, Long> = withContext(Dispatchers.IO) {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }?.let { if (!c.isNull(it)) name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
                }
            }
        }
        if (size < 0) {
            // A -1 length makes OkHttp fall back to chunked encoding, which this endpoint rejects,
            // so measure the stream to set a real Content-Length.
            size = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    var total = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                    }
                    total
                } ?: 0L
            }.getOrDefault(0L)
        }
        name.replace('/', '_') to size
    }
}
