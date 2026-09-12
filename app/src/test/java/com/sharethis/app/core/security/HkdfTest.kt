package com.sharethis.app.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HkdfTest {

    /** RFC 5869 Appendix A, Test Case 1 (SHA-256). */
    @Test
    fun `rfc5869 test case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c)
        val info = byteArrayOf(0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte())

        val prk = Hkdf.extract(salt, ikm)
        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            Base64Url.encodeToHex(prk)
        )

        val okm = Hkdf.expand(prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            Base64Url.encodeToHex(okm)
        )
    }

    @Test
    fun `deterministic and length-correct`() {
        val ikm = "sharethis session secret".toByteArray()
        val a = Hkdf.derive(ikm, "salt".toByteArray(), "info".toByteArray(), 64)
        val b = Hkdf.derive(ikm, "salt".toByteArray(), "info".toByteArray(), 64)
        assertEquals(64, a.size)
        assertArrayEquals(a, b)
    }

    @Test
    fun `different info produces different keys`() {
        val ikm = ByteArray(32) { it.toByte() }
        val a = Hkdf.derive(ikm, ByteArray(0), "send".toByteArray(), 32)
        val b = Hkdf.derive(ikm, ByteArray(0), "receive".toByteArray(), 32)
        assertTrue(!a.contentEquals(b))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid output length`() {
        Hkdf.derive(ByteArray(16), ByteArray(0), ByteArray(0), 0)
    }
}
