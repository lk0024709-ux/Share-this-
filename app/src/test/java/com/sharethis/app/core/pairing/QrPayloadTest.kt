package com.sharethis.app.core.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadTest {

    @Test
    fun `payload round-trips through json`() {
        val payload = QrCodePayloadHandler.QrPayload(
            ssid = "AndroidShare_\"5G\"",
            passphrase = "p@ss|w:rd\\x",
            ip = "192.168.43.1",
            port = 8990,
            band = "5GHz",
            deviceName = "Pixel \"8\"",
            security = "WPA2"
        )
        val restored = QrCodePayloadHandler.QrPayload.fromJson(payload.toJson())

        assertNotNull(restored)
        assertEquals(payload, restored)
    }

    @Test
    fun `magic-wrapped text encodes and decodes`() {
        val payload = QrCodePayloadHandler.QrPayload(
            ssid = "ShareThis-1234",
            passphrase = "87654321",
            ip = "192.168.43.1",
            port = 8990
        )
        val text = QrCodePayloadHandler.encodePayload(payload)
        assert(text.startsWith(QrCodePayloadHandler.QR_MAGIC))

        val decoded = QrCodePayloadHandler.decodePayload("  $text\n")
        assertEquals(payload.ssid, decoded?.ssid)
        assertEquals(payload.ip, decoded?.ip)
        assertEquals(8990, decoded?.port)
    }

    @Test
    fun `foreign qr text is rejected`() {
        assertNull(QrCodePayloadHandler.decodePayload("https://example.com"))
        assertNull(QrCodePayloadHandler.decodePayload(""))
        assertNull(QrCodePayloadHandler.decodePayload("SHARETHISv1:"))
        assertNull(QrCodePayloadHandler.decodePayload("SHARETHISv1:{not json}"))
    }

    @Test
    fun `payload with bad port is rejected`() {
        val bad = QrCodePayloadHandler.QrPayload(
            ssid = "x", passphrase = "y", ip = "1.2.3.4", port = 99999
        )
        assertNull(QrCodePayloadHandler.QrPayload.fromJson(bad.toJson()))
    }

    @Test
    fun `network config converts both ways`() {
        val payload = QrCodePayloadHandler.QrPayload(
            ssid = "S", passphrase = "P", ip = "10.0.0.1", port = 9000
        )
        val config = payload.toNetworkConfig()
        val back = QrCodePayloadHandler.QrPayload.fromNetworkConfig(config)
        assertEquals(payload, back)
    }
}
