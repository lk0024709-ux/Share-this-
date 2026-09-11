package com.sharethis.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinProtocolTest {

    @Test
    fun `generated pin is 6 digits`() {
        repeat(50) {
            val pin = PinPairingEngine.generatePin()
            assertTrue(PinPairingEngine.isValidPin(pin))
        }
        assertTrue(PinPairingEngine.isValidPin("512890"))
        assertTrue(!PinPairingEngine.isValidPin("51289"))
        assertTrue(!PinPairingEngine.isValidPin("51289a"))
        assertTrue(!PinPairingEngine.isValidPin(""))
    }

    @Test
    fun `discover packet round-trips`() {
        val bytes = PinPairingEngine.buildDiscover("512890", "Pixel 8")
        val parsed = PinPairingEngine.parseDiscover(String(bytes, Charsets.UTF_8))
        assertNotNull(parsed)
        assertEquals("512890", parsed!!.pin)
        assertEquals("Pixel 8", parsed.deviceName)
    }

    @Test
    fun `offer packet round-trips`() {
        val bytes = PinPairingEngine.buildOffer("512890", 8990, "Galaxy S24", "5GHz")
        val parsed = PinPairingEngine.parseOffer(String(bytes, Charsets.UTF_8))
        assertNotNull(parsed)
        assertEquals("512890", parsed!!.pin)
        assertEquals(8990, parsed.tcpPort)
        assertEquals("Galaxy S24", parsed.deviceName)
        assertEquals("5GHz", parsed.band)
    }

    @Test
    fun `malformed packets are rejected`() {
        assertNull(PinPairingEngine.parseDiscover("NOPE|512890|X"))
        assertNull(PinPairingEngine.parseDiscover("SHARETHIS_PIN|12|X"))
        assertNull(PinPairingEngine.parseOffer("SHARETHIS_OFFER|512890|notaport|X|Y"))
        assertNull(PinPairingEngine.parseOffer("SHARETHIS_OFFER|512890|99999|X|Y"))
        assertNull(PinPairingEngine.parseOffer("garbage"))
    }

    @Test
    fun `delimiters in device name are sanitized`() {
        val bytes = PinPairingEngine.buildDiscover("111111", "a|b\nc")
        val parsed = PinPairingEngine.parseDiscover(String(bytes, Charsets.UTF_8))
        assertNotNull(parsed)
        assertEquals("a b c", parsed!!.deviceName)
    }
}
