package com.sharethis.app.core.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class ChecksumVerifierTest {

    @Test
    fun `crc32 matches known vector`() {
        // Standard check value for CRC-32/ISO-HDLC over "123456789".
        assertEquals(0xCBF43926L, ChecksumVerifier.crc32Of("123456789".toByteArray()))
    }

    @Test
    fun `crc32 region matches batch`() {
        val bytes = ByteArray(1000) { (it * 31).toByte() }
        assertEquals(ChecksumVerifier.crc32Of(bytes), ChecksumVerifier.crc32Of(bytes, 0, bytes.size))
        assertEquals(
            ChecksumVerifier.crc32Of(bytes.copyOfRange(100, 500)),
            ChecksumVerifier.crc32Of(bytes, 100, 400)
        )
    }

    @Test
    fun `sha256 matches known vectors`() {
        // FIPS 180-2 vectors.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ChecksumVerifier.sha256Hex("abc".toByteArray())
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ChecksumVerifier.sha256Hex(ByteArray(0))
        )
        val million = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            ChecksumVerifier.sha256Hex(million)
        )
    }

    @Test
    fun `sha256 streaming equals one-shot`() {
        val bytes = ByteArray(300_000) { (it * 7 + 3).toByte() }
        assertEquals(
            ChecksumVerifier.sha256Hex(bytes),
            ChecksumVerifier.sha256Hex(ByteArrayInputStream(bytes))
        )
        val streaming = ChecksumVerifier.StreamingSha256()
        var offset = 0
        while (offset < bytes.size) {
            val chunk = minOf(8192, bytes.size - offset)
            streaming.update(bytes, offset, chunk)
            offset += chunk
        }
        assertEquals(ChecksumVerifier.sha256Hex(bytes), streaming.digestHex())
    }

    @Test
    fun `streaming crc accumulator matches one-shot`() {
        val bytes = ByteArray(200_000) { (it * 13).toByte() }
        val streaming = ChecksumVerifier.StreamingCrc32()
        var offset = 0
        while (offset < bytes.size) {
            val chunk = minOf(4096, bytes.size - offset)
            streaming.update(bytes, offset, chunk)
            offset += chunk
        }
        assertEquals(ChecksumVerifier.crc32Of(bytes), streaming.value)
    }

    @Test
    fun `sha256 matches platform message digest on random data`() {
        val bytes = ByteArray(70_000) { (it * 37 + 11).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
        assertArrayEquals(expected, ChecksumVerifier.sha256(bytes))
        assertTrue(ChecksumVerifier.toHex(expected).length == 64)
    }
}
