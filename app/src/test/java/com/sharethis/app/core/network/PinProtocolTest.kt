package com.sharethis.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinProtocolTest {

    @Test
    fun `generated pin is 6 digits and unpredictable-ish`() {
        repeat(50) {
            val pin = PinProtocol.generatePin()
            assertTrue(PinProtocol.isValidPin(pin))
        }
        assertTrue(PinProtocol.isValidPin("512890"))
        assertTrue(!PinProtocol.isValidPin("51289"))
        assertTrue(!PinProtocol.isValidPin("51289a"))
        assertTrue(!PinProtocol.isValidPin(""))
    }

    @Test
    fun `discover packet round-trips`() {
        val bytes = PinProtocol.buildDiscover("512890", "Pixel 8")
        val parsed = PinProtocol.parseDiscover(String(bytes, Charsets.UTF_8))
        assertNotNull(parsed)
        assertEquals("512890", parsed!!.pin)
        assertEquals("Pixel 8", parsed.deviceName)
    }

    @Test
    fun `offer packet round-trips with session material`() {
        val challenge = ByteArray(16) { (it + 1).toByte() }
        val bytes = PinProtocol.buildOffer("512890", 8990, "Galaxy S24", "5GHz", 0x1122334455667788L, challenge)
        val parsed = PinProtocol.parseOffer(String(bytes, Charsets.UTF_8))
        assertNotNull(parsed)
        assertEquals("512890", parsed!!.pin)
        assertEquals(8990, parsed.tcpPort)
        assertEquals("Galaxy S24", parsed.deviceName)
        assertEquals("5GHz", parsed.band)
        assertEquals(0x1122334455667788L, parsed.sessionId)
        assertTrue(challenge.contentEquals(parsed.challenge))
    }

    @Test
    fun `malformed packets are rejected`() {
        assertNull(PinProtocol.parseDiscover("NOPE|512890|X"))
        assertNull(PinProtocol.parseDiscover("SHARETHIS_PIN2|12|X"))
        assertNull(PinProtocol.parseOffer("SHARETHIS_OFFER2|512890|notaport|X|Y|0011223344556677|AAAAAAAAAAAAAAAAAAAAAA"))
        assertNull(PinProtocol.parseOffer("SHARETHIS_OFFER2|512890|99999|X|Y|0011223344556677|AAAAAAAAAAAAAAAAAAAAAA"))
        assertNull(PinProtocol.parseOffer("garbage"))
        // v1 packets from an old app are cleanly rejected, not crashed on.
        assertNull(PinProtocol.parseOffer("SHARETHIS_OFFER|512890|8990|X|Y"))
    }

    @Test
    fun `bad session id or challenge rejected`() {
        // challenge below is the b64url of 16 zero bytes
        assertNull(PinProtocol.parseOffer("SHARETHIS_OFFER2|512890|8990|X|Y|nothex|AAAAAAAAAAAAAAAAAAAAAA"))
        assertNull(PinProtocol.parseOffer("SHARETHIS_OFFER2|512890|8990|X|Y|0011223344556677|tooshort"))
    }

    @Test
    fun `delimiters in device name are sanitized`() {
        val bytes = PinProtocol.buildDiscover("111111", "a|b\nc")
        val parsed = PinProtocol.parseDiscover(String(bytes, Charsets.UTF_8))
        assertNotNull(parsed)
        assertEquals("a b c", parsed!!.deviceName)
    }
}
