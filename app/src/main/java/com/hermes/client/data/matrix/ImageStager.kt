package com.hermes.client.data.matrix

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies a picked image's content URI into a cache file the SDK (native code) can read by path.
 * The staged file is disposable: the caller deletes it after the send attempt, whatever it
 * returned. Never throws; a failure is [stage] returning null.
 */
@Singleton
class ImageStager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Returns the staged copy of [uri], or null when it cannot be read. */
    suspend fun stage(uri: String): StagedImage? = withContext(Dispatchers.IO) {
        try {
            val parsed = Uri.parse(uri)
            if (parsed == null || parsed.scheme.isNullOrBlank()) return@withContext null
            val resolver = context.contentResolver
            val rawName = queryDisplayName(parsed)
            val resolverMimetype = runCatching { resolver.getType(parsed) }.getOrNull()
            val mimetype = imageMimetype(sanitizedImageFilename(rawName), resolverMimetype)
            val filename = filenameFor(rawName, mimetype)

            val dir = File(context.cacheDir, OUTBOX_DIR).apply { mkdirs() }
            val out = File(dir, "stage-${System.nanoTime()}")
            try {
                resolver.openInputStream(parsed)?.use { input ->
                    out.outputStream().use { output ->
                        input.copyTo(output, BUFFER_BYTES)
                    }
                } ?: return@withContext null
                if (out.length() == 0L) return@withContext null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                out.delete()
                return@withContext null
            }
            StagedImage(out.path, filename, mimetype)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Deletes [staged]'s backing file. Best effort. */
    suspend fun delete(staged: StagedImage): Unit = withContext(Dispatchers.IO) {
        runCatching { File(staged.path).delete() }
        Unit
    }

    /** Display name of the document, without committing to any column existing. Never throws. */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()

    companion object {
        const val OUTBOX_DIR = "matrix-outbox"
        const val BUFFER_BYTES = 64 * 1024
    }
}

/** The filename sent alongside the image: the sanitized display name, made consistent with [mimetype]. */
internal fun filenameFor(rawName: String?, mimetype: String?): String {
    val base = sanitizedImageFilename(rawName)
    val claimedMime = imageMimetype(base, null)
    val ext = imageExtensionFor(mimetype)
    return when {
        // The display name already carries an extension that matches the claimed mimetype.
        ext != null && base.endsWith(ext, ignoreCase = true) -> base
        // No mimetype is known: keep whatever extension the name claims (or the fallback).
        ext == null -> base
        // Name is there but has a wrong or missing extension: give it the right one.
        claimedMime == null -> base + ext
        else -> base.substringBeforeLast('.') + ext
    }
}
