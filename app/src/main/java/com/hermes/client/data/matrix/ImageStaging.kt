package com.hermes.client.data.matrix

/**
 * Result of staging a picked image into a real file for the SDK to upload.
 * [mimetype] is null when nothing reliable was found; the SDK then guesses from the file.
 */
data class StagedImage(
    val path: String,
    val filename: String,
    val mimetype: String?,
)

/**
 * A display name safe to send as an image filename: separators and control characters removed,
 * whitespace trimmed, capped in length. Null / blank input falls back to [FALLBACK_FILENAME].
 * Pure so the sender and tests need no Android classes.
 */
fun sanitizedImageFilename(raw: String?): String {
    if (raw.isNullOrBlank()) return FALLBACK_FILENAME
    val cut = raw.lastIndexOfAny(charArrayOf('/', '\\')).let { if (it >= 0) it + 1 else 0 }
    val cleaned = raw
        .substring(cut)
        .filter { it.isLetterOrDigit() || it in KEEP_NAME_CHARS }
        .trim()
        .take(FILENAME_MAX_LENGTH)
        .takeIf { name ->
            // The part before the last dot needs actual content, so ".jpg" alone is not a name.
            name.substringBeforeLast('.').any { it.isLetterOrDigit() }
        }
        ?: FALLBACK_FILENAME
    return cleaned
}

/** Characters kept when sanitizing an attachment filename; everything else is dropped. */
private val KEEP_NAME_CHARS = setOf('-', '.', '_', '(', ')', ' ')

/**
 * The mimetype to claim for an image: the resolver's answer wins when it looks like an image,
 * otherwise the filename extension decides, otherwise nothing (the SDK inspects the file).
 * Pure.
 */
fun imageMimetype(filename: String, resolverMimetype: String?): String? {
    resolverMimetype?.takeIf { it.startsWith("image/") }?.let { return it }
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        else -> null
    }
}

/** Filename extension chosen from a known mimetype; images only. Pure. */
fun imageExtensionFor(mimetype: String?): String? = when (mimetype) {
    "image/png" -> ".png"
    "image/jpeg" -> ".jpg"
    "image/webp" -> ".webp"
    "image/gif" -> ".gif"
    "image/bmp" -> ".bmp"
    "image/heic" -> ".heic"
    "image/heif" -> ".heif"
    "image/avif" -> ".avif"
    else -> null
}

const val FALLBACK_FILENAME = "image.jpg"
const val FILENAME_MAX_LENGTH = 80
