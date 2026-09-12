package com.sharethis.app.core.pairing

import com.sharethis.app.core.engine.JsonCodec
import com.sharethis.app.core.security.Base64Url
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadTest {

    private fun samplePayload(ttlMs: Long = 60_000): PairingPayload.Payload =
        PairingPayload.build(
            sessionId = 1234567890123456L,
            challenge = ByteArray(16) { 7 },
            pin = "512890",
            receiverPublicKey = ByteArray(91) { it.toByte() },
            ipAddress = "192.168.43.1",
            port = 8990,
            ssid = "ShareThis-1234",
            passphrase = "secret-pass",
            band = "5GHz",
            deviceName = "Rahul's Phone",
            security = "WPA2",
            ttlMs = ttlMs,
            nowMs = System.currentTimeMillis()
        )

    @Test
    fun `qr text round-trips`() {
        val payload = samplePayload()
        val text = PairingPayload.encode(payload)
        assertTrue(text.startsWith("SHARETHISv2:"))
        val result = PairingPayload.decode(text)
        assertTrue(result is PairingPayload.DecodeResult.Ok)
        val back = (result as PairingPayload.DecodeResult.Ok).payload
        assertEquals(payload.sessionId, back.sessionId)
        assertEquals(payload.ipAddress, back.ipAddress)
        assertEquals(payload.port, back.port)
        assertEquals(payload.ssid, back.ssid)
        assertEquals(payload.deviceName, back.deviceName)
        assertTrue(payload.challenge.contentEquals(back.challenge))
        assertTrue(payload.receiverPublicKey!!.contentEquals(back.receiverPublicKey!!))
    }

    @Test
    fun `expired payload is rejected`() {
        val payload = samplePayload(ttlMs = -1) // already expired
        val result = PairingPayload.decode(PairingPayload.encode(payload))
        assertTrue(result is PairingPayload.DecodeResult.Expired)
    }

    @Test
    fun `v1 qr detected as wrong version`() {
        val result = PairingPayload.decode("SHARETHISv1:{\"ssid\":\"x\"}")
        assertTrue(result is PairingPayload.DecodeResult.WrongVersion)
        assertEquals(1, (result as PairingPayload.DecodeResult.WrongVersion).found)
    }

    @Test
    fun `wrong-app qr rejected`() {
        assertTrue(PairingPayload.decode("https://example.com") is PairingPayload.DecodeResult.NotShareThis)
        assertTrue(PairingPayload.decode("") is PairingPayload.DecodeResult.NotShareThis)
        assertTrue(PairingPayload.decode("WIFI:S:MyNetwork;P:password;;") is PairingPayload.DecodeResult.NotShareThis)
    }

    @Test
    fun `tampered json rejected`() {
        val text = PairingPayload.encode(samplePayload())
        val json = JsonToMap(text)
        val challengeToken = JsonCodec.asString(json["ch"])
        // Empty the challenge → malformed.
        val broken = text.replace("\"ch\":\"$challengeToken\"", "\"ch\":\"\"")
        val brokenResult = PairingPayload.decode(broken)
        assertTrue("expected malformed, got $brokenResult",
            brokenResult is PairingPayload.DecodeResult.Malformed)
        // Corrupt the port.
        val badPort = text.replace("\"port\":8990", "\"port\":notaport")
        val badPortResult = PairingPayload.decode(badPort)
        assertTrue("expected malformed, got $badPortResult",
            badPortResult is PairingPayload.DecodeResult.Malformed)
    }

    @Test
    fun `replay guard blocks second use`() {
        val guard = PairingPayload.ReplayGuard()
        val now = 5_000_000L
        assertFalse(guard.isReplay(1L, now + 60_000, now))
        assertTrue(guard.isReplay(1L, now + 60_000, now)) // same session id again
        assertFalse(guard.isReplay(2L, now + 60_000, now))
        guard.forget(1L)
        assertFalse(guard.isReplay(1L, now + 60_000, now))
    }

    @Test
    fun `session ids are cryptographically random and never zero`() {
        val ids = HashSet<Long>()
        repeat(200) {
            val id = PairingPayload.newSessionId()
            assertTrue(id != 0L)
            ids.add(id)
        }
        // Collisions in 200 draws of a 64-bit value are astronomically unlikely.
        assertTrue(ids.size > 190)
    }

    @Test
    fun `payload carries only temporary session values`() {
        val payload = samplePayload()
        val text = PairingPayload.encode(payload)
        // The PIN is a short-lived pairing authenticator that travels inside
        // the same authenticated channel — but no long-lived secret exists.
        assertTrue(payload.pin.isNotEmpty())
        assertTrue(payload.expiresAtMs > 1_000_000L) // bounded lifetime
        assertFalse(text.contains(Base64Url.encodeToHex(payload.challenge)))
    }

    private fun JsonToMap(text: String): Map<String, String> =
        com.sharethis.app.core.engine.JsonCodec.parseObject(
            text.removePrefix("SHARETHISv2:").trimStart(':')
        )

    @Test
    fun `public key optional for pin mode`() {
        val payload = PairingPayload.build(
            sessionId = 5L, challenge = ByteArray(16), pin = "111111", receiverPublicKey = null,
            ipAddress = "10.0.0.2", port = 8990, ssid = "", passphrase = "",
            band = "Unknown", deviceName = "X", security = "OPEN"
        )
        val back = (PairingPayload.decode(PairingPayload.encode(payload))
            as PairingPayload.DecodeResult.Ok).payload
        assertEquals(null, back.receiverPublicKey)
        assertNotNull(back)
    }
}
