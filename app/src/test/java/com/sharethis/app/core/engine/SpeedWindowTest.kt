package com.sharethis.app.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedWindowTest {

    private class FakeClock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun `buckets average and ema smooths`() {
        val clock = FakeClock()
        val window = SpeedWindow(bucketMs = 250, emaAlpha = 0.5, clock = clock)
        // Bucket 1: 1000 bytes in 250ms → 4000 B/s.
        window.add(1000)
        clock.now = 250
        window.add(0) // force bucket close
        assertEquals(4000L, window.currentBps)

        // Bucket 2: 3000 bytes in 250ms → 12000 B/s bucket.
        window.add(3000)
        clock.now = 500
        window.add(0)
        // EMA(0.5): 0.5*12000 + 0.5*4000 = 8000
        assertEquals(8000L, window.currentBps)
    }

    @Test
    fun `warmup estimate before first bucket closes`() {
        val clock = FakeClock()
        val window = SpeedWindow(bucketMs = 1000, clock = clock)
        window.add(1000)
        assertEquals(0L, window.bps()) // elapsed 0 → no estimate yet
        clock.now = 500
        window.add(1000) // 2000 bytes over 500ms
        assertEquals(4000L, window.bps())
    }

    @Test
    fun `peak tracked`() {
        val clock = FakeClock()
        val window = SpeedWindow(bucketMs = 100, clock = clock)
        window.add(10_000)
        clock.now = 100
        window.add(0)
        val peak = window.currentBps
        window.add(1) // slow bucket
        clock.now = 400
        window.add(0)
        assertTrue(window.currentBps < peak)
        assertEquals(peak, window.peakBps)
    }

    @Test
    fun `reset clears everything`() {
        val clock = FakeClock()
        val window = SpeedWindow(bucketMs = 100, clock = clock)
        window.add(5000)
        clock.now = 100
        window.add(0)
        assertTrue(window.currentBps > 0)
        window.reset()
        assertEquals(0L, window.currentBps)
        assertEquals(0L, window.bps())
    }
}
