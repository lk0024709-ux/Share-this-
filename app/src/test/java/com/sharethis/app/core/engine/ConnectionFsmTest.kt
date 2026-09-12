package com.sharethis.app.core.engine

import com.sharethis.app.data.enums.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFsmTest {

    private class FakeClock(var now: Long = 0L) : ConnectionFsm.Clock {
        override fun now(): Long = now
    }

    @Test
    fun `happy path walks the full chain`() {
        val fsm = ConnectionFsm()
        assertTrue(fsm.transition(TransferState.DISCOVERING))
        assertTrue(fsm.transition(TransferState.PAIRING))
        assertTrue(fsm.transition(TransferState.CONNECTING))
        assertTrue(fsm.transition(TransferState.AUTHENTICATING))
        assertTrue(fsm.transition(TransferState.NEGOTIATING))
        assertTrue(fsm.transition(TransferState.CONNECTED))
        assertTrue(fsm.transition(TransferState.TRANSFERRING))
        assertTrue(fsm.transition(TransferState.VERIFYING))
        assertTrue(fsm.transition(TransferState.COMPLETED))
        assertEquals(TransferState.COMPLETED, fsm.state)
    }

    @Test
    fun `impossible transitions rejected`() {
        val fsm = ConnectionFsm()
        assertFalse(fsm.transition(TransferState.TRANSFERRING)) // IDLE → TRANSFERRING
        assertTrue(fsm.transition(TransferState.CONNECTING))
        assertFalse(fsm.transition(TransferState.COMPLETED)) // must authenticate first
        assertTrue(fsm.transition(TransferState.AUTHENTICATING))
        assertTrue(fsm.transition(TransferState.AUTHENTICATION_FAILED))
        assertFalse(fsm.transition(TransferState.TRANSFERRING)) // auth failure needs reconnect/reset
    }

    @Test
    fun `cancel allowed from any active state`() {
        val fsm = ConnectionFsm()
        fsm.transition(TransferState.PAIRING)
        assertTrue(fsm.transition(TransferState.CANCELLED))
        fsm.reset()
        fsm.transition(TransferState.TRANSFERRING)
        assertTrue(fsm.transition(TransferState.CANCELLED))
    }

    @Test
    fun `timeouts fire via clock`() {
        val clock = FakeClock()
        val timeouts = ConnectionFsm.Timeouts(connectingMs = 1000, transferringInactivityMs = 5000)
        val fsm = ConnectionFsm(timeouts, clock)
        fsm.transition(TransferState.CONNECTING)
        assertNull(fsm.checkTimeout())
        clock.now = 1500
        val event = fsm.checkTimeout()
        assertNotNull(event)
        assertEquals(TransferState.CONNECTING, event!!.state)
    }

    @Test
    fun `transferring watchdog uses last progress timestamp`() {
        val clock = FakeClock()
        val fsm = ConnectionFsm(
            ConnectionFsm.Timeouts(transferringInactivityMs = 5000), clock
        )
        fsm.transition(TransferState.CONNECTING)
        fsm.transition(TransferState.CONNECTED)
        fsm.transition(TransferState.TRANSFERRING)
        clock.now = 20_000
        assertNull(fsm.checkTimeout(activeSinceMs = 18_000)) // recent progress → no timeout
        assertNotNull(fsm.checkTimeout(activeSinceMs = 10_000)) // stalled 10s → timeout
        assertNotNull(fsm.checkTimeout()) // no progress ever reported
    }

    @Test
    fun `terminal states have no timeout`() {
        val clock = FakeClock()
        val fsm = ConnectionFsm(clock = clock)
        fsm.transition(TransferState.CONNECTING)
        fsm.transition(TransferState.CONNECTION_FAILED)
        clock.now = 1_000_000
        assertNull(fsm.checkTimeout())
    }

    @Test
    fun `retry budget counts reconnect loops`() {
        val fsm = ConnectionFsm(
            ConnectionFsm.Timeouts(connectRetries = 2)
        )
        fsm.transition(TransferState.CONNECTING)
        assertEquals(2, fsm.retriesLeft(TransferState.CONNECTING))
        fsm.transition(TransferState.RECONNECTING) // attempt 1
        assertEquals(1, fsm.retriesLeft(TransferState.CONNECTING))
        fsm.transition(TransferState.CONNECTING)
        fsm.transition(TransferState.RECONNECTING) // attempt 2
        assertEquals(0, fsm.retriesLeft(TransferState.CONNECTING))
    }

    @Test
    fun `reset returns to idle`() {
        val fsm = ConnectionFsm()
        fsm.transition(TransferState.TRANSFERRING)
        fsm.transition(TransferState.CANCELLED)
        fsm.reset()
        assertEquals(TransferState.IDLE, fsm.state)
        assertTrue(fsm.transition(TransferState.CONNECTING))
    }
}
