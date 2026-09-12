package com.sharethis.app.core.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.sharethis.app.core.engine.FastTransferEngine
import com.sharethis.app.data.models.DuplicatePolicy
import com.sharethis.app.data.models.FileMetadata
import java.io.File
import java.io.IOException

/**
 * Android adapter for the pure-JVM engine's [FastTransferEngine.ReceiveSink].
 *
 * Responsibilities (v2 receive pipeline):
 *  - **Space pre-check** — [onManifest] sums the announced manifest sizes and
 *    compares against allocatable bytes on the destination volume *before a
 *    single payload chunk is sent*, throwing
 *    [FastTransferEngine.StorageSpaceException] so the sender aborts with
 *    `INSUFFICIENT_STORAGE` instead of filling the disk mid-transfer.
 *  - **Duplicate policy** — when the destination already contains a file with
 *    the same (sanitized) name, applies [DuplicatePolicy]. The default
 *    ([DuplicatePolicy.KEEP_BOTH]) auto-renames to "name (1).ext" — a silent
 *    overwrite never happens. [DuplicatePolicy.ASK] routes through an
 *    optional [DuplicateResolver]; UI implementers may block on a dialog,
 *    but must answer — unresolved asks fall back to KEEP_BOTH.
 *  - **Finalize** — [onFileVerified] streams the verified spool file into
 *    user-visible storage via [StorageBridge] (MediaStore on API 29+,
 *    classic paths on 21–28). Only SHA-256-verified bytes ever land here;
 *    a failed copy cleans up its partial target.
 */
class ReceiveSinkAdapter(
    context: Context,
    private val policy: DuplicatePolicy = DuplicatePolicy.KEEP_BOTH,
    private val resolver: DuplicateResolver? = null,
    private val events: Events? = null
) : FastTransferEngine.ReceiveSink {

    /** Optional UI hooks; all called on the transfer thread. */
    interface Events {
        fun onSaved(displayPath: String, metadata: FileMetadata) {}
        fun onSkipped(metadata: FileMetadata) {}
    }

    /**
     * Synchronous duplicate-resolution hook for [DuplicatePolicy.ASK].
     * Called on the transfer thread — a dialog implementation must block
     * this thread (it is a dedicated engine thread, not the main thread).
     */
    fun interface DuplicateResolver {
        fun resolve(metadata: FileMetadata, existingPath: String): DuplicatePolicy
    }

    private val appContext = context.applicationContext
    private val bridge = StorageBridge(appContext)

    // ------------------------------------------------------------- manifest

    @Throws(FastTransferEngine.StorageSpaceException::class)
    override fun onManifest(files: List<FileMetadata>) {
        if (files.isEmpty()) return
        val needed = files.sumOf { it.fileSize.coerceAtLeast(0L) }
        // Empty/zero-byte files and rounding: add a small margin.
        val margin = (needed / 20L).coerceAtLeast(16L * 1024L * 1024L) // max(5%, 16 MB)
        val available = availableBytesOnDestination()
        if (available in 0..(needed + margin)) {
            throw FastTransferEngine.StorageSpaceException(
                "Need ${humanBytes(needed + margin)}, only ${humanBytes(available)} available"
            )
        }
    }

    // ------------------------------------------------------------ finalize

    @Throws(IOException::class)
    override fun onFileVerified(metadata: FileMetadata, spoolFile: File): String? {
        val sanitized = bridge.sanitizeFileName(metadata.fileName)
        val existing = findExisting(sanitized)
        if (existing != null) {
            val decision = when (policy) {
                DuplicatePolicy.ASK ->
                    resolver?.resolve(metadata, existing) ?: DuplicatePolicy.KEEP_BOTH
                else -> policy
            }
            when (decision) {
                DuplicatePolicy.SKIP -> {
                    events?.onSkipped(metadata)
                    return null
                }
                DuplicatePolicy.REPLACE -> deleteExisting(sanitized)
                DuplicatePolicy.KEEP_BOTH, DuplicatePolicy.ASK -> Unit // unique naming below
            }
        }

        val target = try {
            bridge.createOutput(sanitized, metadata.mimeType)
        } catch (e: IOException) {
            throw e
        }
        try {
            spoolFile.inputStream().use { input ->
                target.stream.use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            // Never leave a partial (pending) row/file behind.
            bridge.deleteTarget(target)
            throw IOException("Could not save ${metadata.fileName}: ${e.message}", e)
        }
        events?.onSaved(target.displayPath, metadata)
        return target.displayPath
    }

    override fun onFileFailed(metadata: FileMetadata, error: Throwable) {
        // Spool cleanup is owned by the engine; nothing user-visible was
        // created (finalization only happens after verification).
    }

    // -------------------------------------------------------------- helpers

    /**
     * Available bytes on the volume that will hold received files.
     * - API 29+: the external-primary volume that serves MediaStore.Downloads.
     * - API 21–28: the classic public Downloads directory.
     * Returns -1 when the volume cannot be probed (never blocks a transfer
     * on a read-only system quirk).
     */
    private fun availableBytesOnDestination(): Long {
        val probeDir: File? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // App-external dir lives on the same external-primary volume as
            // MediaStore.Downloads.
            appContext.getExternalFilesDir(null)
                ?: Environment.getExternalStorageDirectory()
        } else {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }
        if (probeDir == null) return -1L
        // The directory may not exist yet on a fresh device — stat its parent.
        val statDir = if (probeDir.exists()) probeDir else probeDir.parentFile ?: probeDir
        return try {
            StatFs(statDir.absolutePath).availableBytes
        } catch (_: Exception) {
            -1L
        }
    }

    /** Full path of an existing file with this name in the receive dir, or null. */
    private fun findExisting(sanitizedName: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return findExistingMediaStore(sanitizedName)
        }
        val dir = File(
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            StorageBridge.RECEIVE_DIR
        )
        val candidate = File(dir, sanitizedName)
        return if (candidate.exists()) candidate.absolutePath else null
    }

    private fun findExistingMediaStore(sanitizedName: String): String? = try {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/${StorageBridge.RECEIVE_DIR}"
        appContext.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(sanitizedName, relativePath),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(1)
                "$relativePath/$name"
            } else null
        }
    } catch (_: Exception) {
        null
    }

    private fun deleteExisting(sanitizedName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val collection =
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val relativePath =
                    "${Environment.DIRECTORY_DOWNLOADS}/${StorageBridge.RECEIVE_DIR}"
                appContext.contentResolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(sanitizedName, relativePath)
                )
            } catch (_: Exception) { /* best effort — KEEP_BOTH name still works */ }
        } else {
            val dir = File(
                @Suppress("DEPRECATION")
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                StorageBridge.RECEIVE_DIR
            )
            try { File(dir, sanitizedName).delete() } catch (_: Exception) { }
        }
    }

    private fun humanBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) "%.1f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
    }
}
