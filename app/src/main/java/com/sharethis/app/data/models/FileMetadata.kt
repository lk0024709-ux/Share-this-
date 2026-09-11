package com.sharethis.app.data.models

import java.io.Serializable

/**
 * Payload metadata for one file in a transfer batch.
 *
 * [crc32] is filled in by the sender *after* streaming (trailing checksum
 * frame) so large files never need a second pre-read pass.
 */
data class FileMetadata(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val crc32: Long = 0L,
    val lastModified: Long = System.currentTimeMillis()
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L
        const val MIME_DEFAULT = "application/octet-stream"
    }

    val isEmpty: Boolean get() = fileSize <= 0L

    fun humanSize(): String {
        if (fileSize <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = fileSize.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "${value.toLong()} B" else "%.1f %s".format(value, units[unit])
    }
}
