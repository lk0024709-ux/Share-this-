package com.sharethis.app.core.engine

import com.sharethis.app.data.models.FileMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class FrameProtocolTest {

    @Test
    fun `manifest frame round-trips with file list`() {
        val header = FrameProtocol.FrameHeader(
            type = FrameProtocol.TYPE_MANIFEST,
            filesTotal = 2,
            manifest = listOf(
                FileMetadata("movie night (2024).mp4", 1_234_567_890L, "video/mp4"),
                FileMetadata("weird \"name\" \\ slash.txt", 42L, "text/plain")
            )
        )
        val out = ByteArrayOutputStream()
        FrameProtocol.writeFrame(out, header)

        val back = FrameProtocol.readFrame(ByteArrayInputStream(out.toByteArray()))

        assertEquals(FrameProtocol.TYPE_MANIFEST, back.type)
        assertEquals(2, back.filesTotal)
        assertEquals(2, back.manifest.size)
        assertEquals("movie night (2024).mp4", back.manifest[0].fileName)
        assertEquals(1_234_567_890L, back.manifest[0].fileSize)
        assertEquals("weird \"name\" \\ slash.txt", back.manifest[1].fileName)
    }

    @Test
    fun `file header round-trips metadata`() {
        val header = FrameProtocol.FrameHeader(
            type = FrameProtocol.TYPE_FILE,
            fileName = "photo.jpg",
            fileSize = 987_654L,
            mimeType = "image/jpeg",
            fileIndex = 1,
            filesTotal = 3
        )
        val out = ByteArrayOutputStream()
        FrameProtocol.writeFrame(out, header)

        val back = FrameProtocol.readFrame(ByteArrayInputStream(out.toByteArray()))

        assertEquals(FrameProtocol.TYPE_FILE, back.type)
        assertEquals("photo.jpg", back.fileName)
        assertEquals(987_654L, back.fileSize)
        assertEquals("image/jpeg", back.mimeType)
        assertEquals(1, back.fileIndex)
        assertEquals(3, back.filesTotal)
    }

    @Test
    fun `checksum frame carries trailing crc`() {
        val header = FrameProtocol.FrameHeader(
            type = FrameProtocol.TYPE_CHECKSUM,
            fileName = "a.bin",
            crc32 = 0xCBF43926L,
            fileIndex = 0,
            filesTotal = 1
        )
        val out = ByteArrayOutputStream()
        FrameProtocol.writeFrame(out, header)

        val back = FrameProtocol.readFrame(ByteArrayInputStream(out.toByteArray()))

        assertEquals(FrameProtocol.TYPE_CHECKSUM, back.type)
        assertEquals(0xCBF43926L, back.crc32)
    }

    @Test(expected = IOException::class)
    fun `bad magic is rejected`() {
        val garbage = ByteArray(64) { (it % 251).toByte() }
        // Ensure the first 4 bytes are NOT "SHT1".
        garbage[0] = 'N'.code.toByte()
        garbage[1] = 'O'.code.toByte()
        garbage[2] = 'P'.code.toByte()
        garbage[3] = 'E'.code.toByte()
        FrameProtocol.readFrame(ByteArrayInputStream(garbage))
    }

    @Test(expected = IOException::class)
    fun `truncated stream throws`() {
        val header = FrameProtocol.FrameHeader(type = FrameProtocol.TYPE_END)
        val out = ByteArrayOutputStream()
        FrameProtocol.writeFrame(out, header)
        val truncated = out.toByteArray().copyOf(5)
        FrameProtocol.readFrame(ByteArrayInputStream(truncated))
    }

    @Test
    fun `successive frames read back in order`() {
        val out = ByteArrayOutputStream()
        FrameProtocol.writeFrame(out, FrameProtocol.FrameHeader(type = FrameProtocol.TYPE_MANIFEST))
        FrameProtocol.writeFrame(
            out,
            FrameProtocol.FrameHeader(type = FrameProtocol.TYPE_FILE, fileName = "x", fileSize = 1)
        )
        FrameProtocol.writeFrame(out, FrameProtocol.FrameHeader(type = FrameProtocol.TYPE_END))

        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(FrameProtocol.TYPE_MANIFEST, FrameProtocol.readFrame(input).type)
        assertEquals(FrameProtocol.TYPE_FILE, FrameProtocol.readFrame(input).type)
        assertEquals(FrameProtocol.TYPE_END, FrameProtocol.readFrame(input).type)
        assertTrue(input.available() == 0)
    }
}
