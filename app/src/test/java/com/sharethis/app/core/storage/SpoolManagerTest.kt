package com.sharethis.app.core.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SpoolManagerTest {

    private fun tempRoot(): File =
        Files.createTempDirectory("sharethis-spool").toFile()

    private fun randomBytes(size: Int, seed: Int): ByteArray =
        ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    @Test
    fun `chunk write and read back`() {
        val root = tempRoot()
        val spool = SpoolManager(root)
        val state = spool.create(7, 99L)
        spool.openFileState(state, 0, "video.mp4", 1000, "video/mp4", 250, 4)
        val chunk = randomBytes(250, 1)
        spool.writeChunk(state, 0, 0, 250, chunk, 0, 250)
        val back = spool.readChunk(state, 0, 0, 250, 250)!!
        assertArrayEquals(chunk, back)
        root.deleteRecursively()
    }

    @Test
    fun `resume point skips valid contiguous prefix and catches corruption`() {
        val root = tempRoot()
        val spool = SpoolManager(root)
        val state = spool.create(7, 99L)
        val fs = spool.openFileState(state, 0, "a.bin", 1000, "application/octet-stream", 250, 4)
        // Write chunks 0 and 1, mark them.
        for (index in 0..1) {
            val chunk = randomBytes(250, index)
            spool.writeChunk(state, 0, index, 250, chunk, 0, 250)
            fs.markChunk(index, ChecksumVerifier.crc32Of(chunk))
        }
        assertEquals(2, spool.firstInvalidChunk(state, 0))

        // Corrupt chunk 0 on disk — resume must fall back to chunk 0.
        val spoolFile = spool.spoolFile(state, 0)
        spoolFile.writeBytes(ByteArray(250) { 9 })
        assertEquals(0, spool.firstInvalidChunk(state, 0))
        root.deleteRecursively()
    }

    @Test
    fun `persistence survives reload`() {
        val root = tempRoot()
        val spool = SpoolManager(root)
        val state = spool.create(42, 1234L)
        val fs = spool.openFileState(state, 3, "फ़ाइल 🎬.mp4", 500, "video/mp4", 250, 2)
        val chunk = randomBytes(250, 5)
        spool.writeChunk(state, 3, 0, 250, chunk, 0, 250)
        fs.markChunk(0, ChecksumVerifier.crc32Of(chunk))
        fs.complete = true
        spool.persist(state)

        val reloaded = SpoolManager(root).load(42)
        assertEquals(1234L, reloaded!!.sessionId)
        val file = reloaded.files[3]!!
        assertEquals("फ़ाइल 🎬.mp4", file.fileName)
        assertEquals(250, file.chunkSize)
        assertTrue(file.isChunkReceived(0))
        assertTrue(file.complete)
        assertEquals(1, SpoolManager(root).firstInvalidChunk(reloaded, 3))
        root.deleteRecursively()
    }

    @Test
    fun `load returns null for unknown transfer`() {
        val root = tempRoot()
        assertEquals(null, SpoolManager(root).load(999))
        root.deleteRecursively()
    }

    @Test
    fun `delete removes directory`() {
        val root = tempRoot()
        val spool = SpoolManager(root)
        val state = spool.create(5, 1L)
        spool.openFileState(state, 0, "x", 10, "text/plain", 10, 1)
        spool.writeChunk(state, 0, 0, 10, ByteArray(10), 0, 10)
        assertTrue(spool.spoolFile(state, 0).exists())
        spool.delete(5)
        assertTrue(!spool.spoolFile(state, 0).exists())
        assertEquals(null, spool.load(5))
        root.deleteRecursively()
    }

    @Test
    fun `cleanup removes only old transfers`() {
        val root = tempRoot()
        val spool = SpoolManager(root)
        val old = spool.create(1, 1L)
        spool.openFileState(old, 0, "a", 10, "text/plain", 10, 1)
        val fresh = spool.create(2, 1L)
        spool.openFileState(fresh, 0, "b", 10, "text/plain", 10, 1)
        val dirOld = File(root, "1")
        val dirFresh = File(root, "2")
        dirOld.setLastModified(System.currentTimeMillis() - 8 * 24 * 3600 * 1000L)
        spool.cleanupOlderThan(7 * 24 * 3600 * 1000L)
        assertTrue(!dirOld.exists())
        assertTrue(dirFresh.exists())
        root.deleteRecursively()
    }

    @Test
    fun `deterministic chunk sizes are stable and bounded`() {
        assertEquals(64 * 1024, SpoolManager.deterministicChunkSize(0))
        assertEquals(64 * 1024, SpoolManager.deterministicChunkSize(500 * 1024))
        assertEquals(512 * 1024, SpoolManager.deterministicChunkSize(30L * 1024 * 1024))
        assertEquals(1 shl 20, SpoolManager.deterministicChunkSize(200L * 1024 * 1024))
        assertEquals(4 shl 20, SpoolManager.deterministicChunkSize(6L * 1024 * 1024 * 1024))
        // Determinism: same input, same output (resume correctness depends on it).
        repeat(10) {
            assertEquals(
                SpoolManager.deterministicChunkSize(123456789L),
                SpoolManager.deterministicChunkSize(123456789L)
            )
        }
    }
}
