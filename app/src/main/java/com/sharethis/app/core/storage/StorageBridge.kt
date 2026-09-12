package com.sharethis.app.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.sharethis.app.data.models.FileMetadata
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Version-adaptive storage bridge.
 *
 *  - SEND side: reads anything the Storage Access Framework picker returns
 *    (`content://`, API 21+) plus legacy `file://` Uris — no storage
 *    permission needed for picker Uris.
 *  - RECEIVE side: writes to `Download/ShareThis/` via MediaStore on API 29+
 *    (scoped storage, IS_PENDING dance included) and via classic external
 *    storage paths on API 21-28.
 */
class StorageBridge(private val context: Context) {

    data class OutputTarget(
        val uri: Uri?,
        val file: File?,
        val stream: OutputStream,
        val displayPath: String
    )

    companion object {
        const val RECEIVE_DIR = "ShareThis"
        const val MAX_NAME_LENGTH = 120
    }

    // ------------------------------------------------------------------ read

    @Throws(IOException::class)
    fun openInput(uri: Uri): InputStream {
        if (uri.scheme == "file") {
            val path = uri.path ?: throw IOException("Bad file Uri")
            return File(path).inputStream()
        }
        return context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open ${uri.scheme} stream")
    }

    /**
     * Probes display name / size / MIME without reading file bytes.
     * CRC is intentionally left 0 — it is computed while streaming.
     */
    fun probeMetadata(uri: Uri): FileMetadata {
        var name: String? = null
        var size = -1L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.let { name = it }
                    if (sizeIndex >= 0) {
                        val probed = cursor.getLong(sizeIndex)
                        if (probed >= 0) size = probed
                    }
                }
            }
        } catch (_: Exception) { /* fall through to fallbacks */
        }
        if (uri.scheme == "file") {
            try {
                val file = File(uri.path!!)
                if (name.isNullOrBlank()) name = file.name
                if (size < 0 && file.exists()) size = file.length()
            } catch (_: Exception) { /* ignore */
            }
        }
        if (size < 0) {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                    if (fd.length >= 0) size = fd.length
                }
            } catch (_: Exception) { /* size stays unknown */
            }
        }
        var mime = try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        if (mime.isNullOrBlank()) {
            mime = guessMimeType(name) ?: FileMetadata.MIME_DEFAULT
        }
        val safeName = sanitizeFileName(name ?: "sharethis-${System.currentTimeMillis()}")
        return FileMetadata(
            fileName = safeName,
            fileSize = size.coerceAtLeast(0L),
            mimeType = mime
        )
    }

    /**
     * Exact byte length of [uri], or -1 when the provider cannot report one
     * (some streaming/cloud providers). 0 means a genuinely empty file.
     */
    fun exactSizeBytes(uri: Uri): Long {
        if (uri.scheme == "file") {
            return try {
                val f = File(uri.path!!)
                if (f.exists()) f.length() else -1L
            } catch (_: Exception) {
                -1L
            }
        }
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                if (fd.length >= 0) fd.length else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    // ----------------------------------------------------------------- write

    /**
     * Creates a receive-side output target. On API 29+ the returned stream
     * clears MediaStore IS_PENDING automatically on close.
     */
    @Throws(IOException::class)
    fun createOutput(displayName: String, mimeType: String): OutputTarget {
        val safe = sanitizeFileName(displayName).ifBlank {
            "sharethis-${System.currentTimeMillis()}"
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createMediaStoreOutput(safe, mimeType)
        } else {
            createLegacyOutput(safe)
        }
    }

    private fun createMediaStoreOutput(displayName: String, mimeType: String): OutputTarget {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, uniqueMediaStoreName(displayName))
            put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { FileMetadata.MIME_DEFAULT })
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$RECEIVE_DIR")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IOException("MediaStore insert failed")
        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Cannot open MediaStore output")
        return OutputTarget(
            uri = uri,
            file = null,
            stream = PendingClearingStream(stream, uri),
            displayPath = "${Environment.DIRECTORY_DOWNLOADS}/$RECEIVE_DIR/$displayName"
        )
    }

    @Suppress("DEPRECATION")
    private fun createLegacyOutput(displayName: String): OutputTarget {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            RECEIVE_DIR
        )
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val file = uniqueFile(dir, displayName)
        return OutputTarget(
            uri = null,
            file = file,
            stream = FileOutputStream(file),
            displayPath = file.absolutePath
        )
    }

    fun deleteTarget(target: OutputTarget) {
        try {
            target.uri?.let { context.contentResolver.delete(it, null, null) }
        } catch (_: Exception) { /* ignore */
        }
        try {
            target.file?.delete()
        } catch (_: Exception) { /* ignore */
        }
    }

    // ---------------------------------------------------------------- helpers

    fun sanitizeFileName(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (name.isEmpty()) name = "file"
        // Strip characters illegal on FAT/exFAT/Windows.
        name = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        if (name.length > MAX_NAME_LENGTH) {
            val dot = name.lastIndexOf('.')
            name = if (dot > 0) {
                name.take(MAX_NAME_LENGTH - (name.length - dot)) + name.substring(dot)
            } else {
                name.take(MAX_NAME_LENGTH)
            }
        }
        return name.trim().ifEmpty { "file" }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (candidate.exists() && index < 10000) {
            candidate = File(dir, "$base ($index)$ext")
            index++
        }
        return candidate
    }

    private fun uniqueMediaStoreName(displayName: String): String {
        // MediaStore auto-dedupes DISPLAY_NAME collisions; keep the name, but
        // avoid absurd duplicates piling up by tagging with time on retry.
        return displayName
    }

    private fun guessMimeType(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    /** Clears IS_PENDING the moment the transfer stream closes. */
    private inner class PendingClearingStream(
        out: OutputStream,
        private val uri: Uri
    ) : FilterOutputStream(out) {
        private var finalized = false

        override fun close() {
            try {
                super.close()
            } finally {
                finalizePending()
            }
        }

        private fun finalizePending() {
            if (finalized) return
            finalized = true
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, values, null, null)
            } catch (_: Exception) { /* best effort */
            }
        }
    }
}
