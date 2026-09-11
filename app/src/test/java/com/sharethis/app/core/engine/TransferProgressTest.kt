package com.sharethis.app.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferProgressTest {

    @Test
    fun `percent and fraction scale with bytes`() {
        val progress = TransferProgress(bytesTransferred = 50, totalBytes = 200)
        assertEquals(25, progress.percent)
        assertEquals(0.25f, progress.fraction, 0.0001f)
    }

    @Test
    fun `zero total stays at zero`() {
        val progress = TransferProgress(bytesTransferred = 0, totalBytes = 0)
        assertEquals(0, progress.percent)
        assertEquals(0f, progress.fraction, 0f)
    }

    @Test
    fun `eta divides remaining by speed`() {
        val progress = TransferProgress(
            bytesTransferred = 100L * 1024 * 1024,
            totalBytes = 300L * 1024 * 1024,
            bytesPerSecond = 50L * 1024 * 1024
        )
        assertEquals(4L, progress.etaSeconds)
        assertEquals(50.0, progress.mbPerSecond, 0.001)
    }

    @Test
    fun `unknown speed yields unknown eta`() {
        val progress = TransferProgress(bytesTransferred = 10, totalBytes = 100, bytesPerSecond = 0)
        assertEquals(-1L, progress.etaSeconds)
        assertEquals("calculating…", progress.etaLabel())
    }

    @Test
    fun `speed window math`() {
        assertEquals(1024L, TransferProgress.speedBytesPerSec(1024L, 1000L))
        assertEquals(0L, TransferProgress.speedBytesPerSec(0L, 1000L))
        assertEquals(0L, TransferProgress.speedBytesPerSec(100L, 0L))
    }

    @Test
    fun `labels format nicely`() {
        assertEquals("82.5 MB/s", TransferProgress.formatSpeed((82.5 * 1024 * 1024).toLong()))
        assertEquals("512 KB/s", TransferProgress.formatSpeed(512L * 1024))
        assertEquals("—", TransferProgress.formatSpeed(0))
        assertEquals("45s left", TransferProgress.formatEta(45))
        assertEquals("2m 5s left", TransferProgress.formatEta(125))
    }
}
