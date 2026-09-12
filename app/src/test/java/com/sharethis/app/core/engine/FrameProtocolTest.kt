package com.sharethis.app.core.engine

import com.sharethis.app.core.security.SessionCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom

class FrameProtocolTest {

    private val random = SecureRandom()

    private fun plainFrame(type: Int, meta: String) = FrameProtocol.FrameHeader(
        type, 0, 77L, 5, 2, 9, 3, meta.length, 0
    )

    @Test
    fun `plaintext frame round-trips with all header fields`() {
        val out = ByteArrayOutputStream()
        val meta = JsonCodec.obj("name" to "video file.mp4", "size" to 123456L)
            .toByteArray(Charsets.UTF_8)
        FrameProtocol.writeFrame(out, FrameProtocol.TYPE_HELLO, 0, 77L, 5, 2, 9, 3, meta, ByteArray(0))

        val back = FrameProtocol.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertEquals(FrameProtocol.TYPE_HELLO, back.type)
        assertEquals(77L, back.sessionId)
        assertEquals(5, back.transferId)
        assertEquals(2, back.fileId)
        assertEquals(9, back.chunkIndex)
        assertEquals(3L, back.sequence)
        assertEquals("video file.mp4", JsonCodec.parseObject(String(back.meta))["name"]?.let { JsonCodec.asString(it) })
        assertTrue(back.payload.isEmpty())
    }

    @Test
    fun `sealed frame round-trips with payload view`() {
        val aead = SessionCrypto.newAead(ByteArray(32) { 1 }, byteArrayOf(1, 2, 3, 4))
        val meta = JsonCodec.obj("name" to "a.bin", "cs" to 512).toByteArray(Charsets.UTF_8)
        val payload = ByteArray(700) { (it % 256).toByte() }
        val blob = FrameProtocol.seal(aead, 11, meta, payload)
        assertTrue(blob.size == 2 + meta.size + payload.size + 16) // + GCM tag

        val opened = FrameProtocol.openSealed(aead, blob, 11)
        assertEquals(512, JsonCodec.asInt(JsonCodec.parseObject(String(opened.meta))["cs"]))
        assertEquals(payload.size, opened.payloadLength)
        assertArrayEquals(payload, opened.payload.copyOfRange(opened.payloadOffset,
            opened.payloadOffset + opened.payloadLength))
    }

    @Test
    fun `successive frames read back in order`() {
        val out = ByteArrayOutputStream()
        FrameProtocol.writeFrame(out, FrameProtocol.TYPE_MANIFEST, 0, 1L, 1, 0, 0, 0,
            "{}".toByteArray(), ByteArray(0))
        FrameProtocol.writeFrame(out, FrameProtocol.TYPE_FILE_META, 0, 1L, 1, 0, 0, 1,
            "{}".toByteArray(), ByteArray(0))
        FrameProtocol.writeFrame(out, FrameProtocol.TYPE_END, 0, 1L, 1, 0, 0, 2,
            "{}".toByteArray(), ByteArray(0))

        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(FrameProtocol.TYPE_MANIFEST, FrameProtocol.readFrame(input).type)
        assertEquals(FrameProtocol.TYPE_FILE_META, FrameProtocol.readFrame(input).type)
        assertEquals(FrameProtocol.TYPE_END, FrameProtocol.readFrame(input).type)
        assertTrue(input.available() == 0)
    }

    @Test(expected = FrameProtocol.UnsupportedProtocolException::class)
    fun `v1 magic reports incompatible version`() {
        // A v1 peer still speaks "SHT1" — the header reader consumes 48 bytes
        // before validating, so pad like a live peer would.
        val bytes = ByteArray(FrameProtocol.HEADER_BYTES)
        bytes[0] = 0x53; bytes[1] = 0x48; bytes[2] = 0x54; bytes[3] = 0x31 // SHT1
        bytes[4] = 0; bytes[5] = 0; bytes[6] = 0; bytes[7] = 1             // version 1
        FrameProtocol.readFrame(ByteArrayInputStream(bytes))
    }

    @Test(expected = IOException::class)
    fun `bad magic is rejected`() {
        val garbage = ByteArray(64) { (it % 251).toByte() }
        garbage[0] = 'N'.code.toByte()
        garbage[1] = 'O'.code.toByte()
        garbage[2] = 'P'.code.toByte()
        garbage[3] = 'E'.code.toByte()
        FrameProtocol.readFrame(ByteArrayInputStream(garbage))
    }

    @Test(expected = IOException::class)
    fun `wrong version is rejected`() {
        val out = ByteArrayOutputStream()
        FrameProtocol.writeFrame(out, FrameProtocol.TYPE_HELLO, 0, 1L, 1, 0, 0, 0,
            "{}".toByteArray(), ByteArray(0))
        val bytes = out.toByteArray()
        bytes[4] = 9 // version 9
        FrameProtocol.readFrame(ByteArrayInputStream(bytes))
    }

    @Test(expected = IOException::class)
    fun `oversized payload length is rejected before allocation`() {
        val out = ByteArrayOutputStream()
        FrameProtocol.writeFrame(out, FrameProtocol.TYPE_CHUNK, 0, 1L, 1, 0, 0, 0,
            ByteArray(0), ByteArray(0))
        val bytes = out.toByteArray()
        // Overwrite payloadLength field (offset 40, big-endian) with 0x00810000
        // (= 8,454,144 > MAX_PAYLOAD_BYTES) — must be rejected before any
        // allocation is attempted.
        bytes[40] = 0x00
        bytes[41] = 0x81.toByte()
        bytes[42] = 0x00
        bytes[43] = 0x00
        FrameProtocol.readFrame(ByteArrayInputStream(bytes))
    }

    @Test(expected = IOException::class)
    fun `header corruption is caught by crc`() {
        val out = ByteArrayOutputStream()
        FrameProtocol.writeFrame(out, FrameProtocol.TYPE_MANIFEST, 0, 1L, 1, 0, 0, 0,
            "{}".toByteArray(), ByteArray(0))
        val bytes = out.toByteArray()
        bytes[20] = (bytes[20] + 1).toByte() // flip a fileId byte -> CRC mismatch
        FrameProtocol.readFrame(ByteArrayInputStream(bytes))
    }

    @Test(expected = IOException::class)
    fun `truncated stream throws`() {
        val out = ByteArrayOutputStream()
        FrameProtocol.writeFrame(out, FrameProtocol.TYPE_END, 0, 1L, 1, 0, 0, 0,
            "{}".toByteArray(), ByteArray(0))
        FrameProtocol.readFrame(ByteArrayInputStream(out.toByteArray().copyOf(10)))
    }

    @Test
    fun `readOrEof returns full length or -1`() {
        val stream = ByteArrayInputStream("hello world".toByteArray())
        val buffer = ByteArray(32)
        assertEquals(5, FrameProtocol.readOrEof(stream, buffer, 0, 5))
        // Remaining " world" is 6 bytes — partial read, not -1.
        assertEquals(6, FrameProtocol.readOrEof(stream, buffer, 0, 20))
        assertEquals(-1, FrameProtocol.readOrEof(stream, buffer, 0, 20))
        // Partial then EOF returns the partial count.
        val partial = ByteArrayInputStream("ab".toByteArray())
        assertEquals(2, FrameProtocol.readOrEof(partial, buffer, 0, 8))
        assertEquals(-1, FrameProtocol.readOrEof(partial, buffer, 0, 8))
    }

    @Test
    fun `unknown frame type is rejected`() {
        val out = ByteArrayOutputStream()
        FrameProtocol.writeFrame(out, FrameProtocol.TYPE_HELLO, 0, 1L, 1, 0, 0, 0,
            "{}".toByteArray(), ByteArray(0))
        val bytes = out.toByteArray()
        bytes[5] = 99.toByte() // unknown type
        // Rebuild CRC so only the type is invalid.
        val crc = java.util.zip.CRC32().apply { update(bytes, 0, FrameProtocol.PREFIX_BYTES) }
        val bb = java.nio.ByteBuffer.wrap(bytes)
        bb.position(FrameProtocol.PREFIX_BYTES)
        bb.putInt(crc.value.toInt())
        try {
            FrameProtocol.readFrame(ByteArrayInputStream(bytes))
            throw AssertionError("unknown type must be rejected")
        } catch (_: IOException) { }
    }
}
