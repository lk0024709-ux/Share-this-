package com.sharethis.app.core.engine

/**
 * Smoothed throughput meter (pure JVM).
 *
 * Raw instantaneous socket rates jitter wildly on Wi-Fi (bursty TCP,
 * buffering, radio scheduling). This class aggregates bytes into ~250 ms
 * buckets and reports an exponentially-weighted moving average, so the UI
 * shows a realistic steady speed instead of 0 → 90 MB/s → 3 MB/s flicker.
 */
class SpeedWindow(
    private val bucketMs: Long = 250L,
    private val emaAlpha: Double = 0.35,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    @Volatile var currentBps: Long = 0L
        private set

    @Volatile var peakBps: Long = 0L
        private set

    private var bucketStartMs = clock()
    private var bucketBytes = 0L
    private var warmupBps = 0L

    @Synchronized
    fun add(bytes: Int) {
        add(bytes.toLong())
    }

    @Synchronized
    fun add(bytes: Long) {
        if (bytes < 0) return
        bucketBytes += bytes
        val now = clock()
        val elapsed = now - bucketStartMs
        if (elapsed >= bucketMs) {
            val bucketBps = TransferProgress.speedBytesPerSec(bucketBytes, elapsed)
            currentBps = if (currentBps == 0L) {
                bucketBps
            } else {
                (emaAlpha * bucketBps + (1 - emaAlpha) * currentBps).toLong()
            }
            if (currentBps > peakBps) peakBps = currentBps
            bucketStartMs = now
            bucketBytes = 0L
        } else if (currentBps == 0L) {
            // Early estimate so the UI shows speed within the first bucket.
            warmupBps = TransferProgress.speedBytesPerSec(bucketBytes, elapsed)
        }
    }

    /** Smoothed bytes/sec (falls back to the warm-up estimate until the first bucket closes). */
    @Synchronized
    fun bps(): Long = if (currentBps > 0L) currentBps else warmupBps

    @Synchronized
    fun reset() {
        bucketStartMs = clock()
        bucketBytes = 0L
        currentBps = 0L
        peakBps = 0L
        warmupBps = 0L
    }
}
