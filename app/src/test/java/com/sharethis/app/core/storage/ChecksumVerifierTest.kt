package com.sharethis.app.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ChecksumVerifierTest {

    @Test
    fun `crc32 matches known vector`() {
        // Standard check value for CRC-32/ISO-HDLC over "123456789".
        assertEquals(0xCBF43926L, ChecksumVerifier.crc32Of("123456789".toByteArray()))
    }

    @Test
    fun `crc32 stream matches batch`() {
        val bytes = ByteArray(300_000) { (it * 31).toByte() }
        val fromStream = ChecksumVerifier.crc32Of(ByteArrayInputStream(bytes))
        assertEquals(ChecksumVerifier.crc32Of(bytes), fromStream)
    }

    @Test
    fun `streaming accumulator matches one-shot`() {
        val bytes = ByteArray(200_000) { (it * 7 + 3).toByte() }
        val streaming = ChecksumVerifier.StreamingCrc32()
        var offset = 0
        while (offset < bytes.size) {
            val chunk = minOf(8192, bytes.size - offset)
            streaming.update(bytes, offset, chunk)
            offset += chunk
        }
        assertEquals(ChecksumVerifier.crc32Of(bytes), streaming.value())
    }

    @Test
    fun `md5 matches known vector`() {
        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72",
            ChecksumVerifier.md5Hex("abc".toByteArray())
        )
    }

    @Test
    fun `verify helper compares values`() {
        assertTrue(ChecksumVerifier.verifyCrc32(123L, 123L))
        assertFalse(ChecksumVerifier.verifyCrc32(123L, 124L))
    }
}
