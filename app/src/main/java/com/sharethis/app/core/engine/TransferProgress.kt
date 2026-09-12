package com.sharethis.app.core.engine

/**
 * Real-time transfer metrics snapshot. Emitted by [FastTransferEngine] at a
 * throttled cadence (every 200 ms) so the UI never gets flooded on fast links.
 *
 * Pure Kotlin — safe to unit test on the JVM.
 */
data class TransferProgress(
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val fileName: String = "",
    val filesCompleted: Int = 0,
    val filesTotal: Int = 0,
    val filesVerified: Int = 0,
    val filesFailed: Int = 0,
    val verifying: Boolean = false,
    val transportLabel: String = ""
) {
    val mbPerSecond: Double
        get() = bytesPerSecond / (1024.0 * 1024.0)

    val percent: Int
        get() {
            if (totalBytes <= 0L) return 0
            return ((bytesTransferred * 100) / totalBytes).toInt().coerceIn(0, 100)
        }

    /** Seconds remaining, or -1 when speed is unknown. */
    val etaSeconds: Long
        get() {
            if (bytesPerSecond <= 0L) return -1L
            val remaining = (totalBytes - bytesTransferred).coerceAtLeast(0L)
            return remaining / bytesPerSecond
        }

    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f)

    fun speedLabel(): String = formatSpeed(bytesPerSecond)

    fun etaLabel(): String = formatEta(etaSeconds)

    companion object {
        const val UNKNOWN_ETA = -1L

        fun speedBytesPerSec(bytesInWindow: Long, windowMs: Long): Long {
            if (bytesInWindow <= 0L || windowMs <= 0L) return 0L
            return bytesInWindow * 1000L / windowMs
        }

        fun formatSpeed(bytesPerSecond: Long): String {
            if (bytesPerSecond <= 0L) return "—"
            val mbps = bytesPerSecond / (1024.0 * 1024.0)
            return when {
                mbps >= 100.0 -> "%d MB/s".format(mbps.toLong())
                mbps >= 1.0 -> "%.1f MB/s".format(mbps)
                else -> "%d KB/s".format(bytesPerSecond / 1024L)
            }
        }

        fun formatEta(etaSeconds: Long): String {
            if (etaSeconds < 0L) return "calculating…"
            if (etaSeconds < 60L) return "${etaSeconds}s left"
            val minutes = etaSeconds / 60L
            if (minutes < 60L) return "${minutes}m ${etaSeconds % 60L}s left"
            return "${minutes / 60L}h ${minutes % 60L}m left"
        }
    }
}
