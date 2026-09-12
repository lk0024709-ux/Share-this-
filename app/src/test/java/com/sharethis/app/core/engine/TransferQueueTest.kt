package com.sharethis.app.core.engine

import com.sharethis.app.data.enums.QueueItemStatus
import com.sharethis.app.data.models.FileMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferQueueTest {

    private fun queue(): TransferQueue = TransferQueue(
        listOf(
            FileMetadata("a.jpg", 100, "image/jpeg"),
            FileMetadata("b.mp4", 1000, "video/mp4"),
            FileMetadata("c.txt", 10, "text/plain")
        )
    )

    @Test
    fun `aggregate math`() {
        val q = queue()
        assertEquals(3, q.filesTotal)
        assertEquals(1110L, q.totalBytes)
        q.markVerified(0)
        q.updateBytes(1, 500)
        assertEquals(600L, q.bytesDone)
        assertEquals(1, q.filesDone)
        assertEquals(1, q.filesVerified)
    }

    @Test
    fun `retry failed re-enqueues only failures`() {
        val q = queue()
        q.markVerified(0)
        q.markFailed(1, "sha mismatch")
        q.markFailed(2, "io")
        assertEquals(listOf(1, 2), q.retryFailed())
        assertEquals(QueueItemStatus.WAITING, q.item(1)!!.status)
        assertEquals(QueueItemStatus.VERIFIED, q.item(0)!!.status)
        assertTrue(q.hasPendingWork)
    }

    @Test
    fun `cancel remaining leaves finished items`() {
        val q = queue()
        q.markVerified(0)
        q.markActive(1)
        q.cancelRemaining()
        assertEquals(QueueItemStatus.VERIFIED, q.item(0)!!.status)
        assertEquals(QueueItemStatus.CANCELLED, q.item(1)!!.status)
        assertEquals(QueueItemStatus.CANCELLED, q.item(2)!!.status)
        assertFalse(q.hasPendingWork)
    }

    @Test
    fun `skip and remove`() {
        val q = queue()
        q.markSkipped(2)
        assertEquals(QueueItemStatus.SKIPPED, q.item(2)!!.status)
        q.remove(2)
        assertEquals(2, q.filesTotal)
    }

    @Test
    fun `move up only while waiting`() {
        val q = queue()
        q.moveUp(1)
        assertEquals("b.mp4", q.items[0].metadata.fileName)
        q.markActive(0)
        q.moveUp(1) // item 0 is ACTIVE — no move
        assertEquals("b.mp4", q.items[0].metadata.fileName)
    }

    @Test
    fun `pause and resume flags`() {
        val q = queue()
        assertFalse(q.paused)
        q.pause()
        assertTrue(q.paused)
        q.resume()
        assertFalse(q.paused)
    }

    @Test
    fun `current file index tracks active`() {
        val q = queue()
        assertEquals(-1, q.currentFileIndex)
        q.markActive(1)
        assertEquals(1, q.currentFileIndex)
    }
}
