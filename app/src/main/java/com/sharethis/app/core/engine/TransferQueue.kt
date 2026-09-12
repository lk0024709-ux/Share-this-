package com.sharethis.app.core.engine

import com.sharethis.app.data.enums.QueueItemStatus
import com.sharethis.app.data.models.FileMetadata

/**
 * Multi-file transfer queue (pure JVM, unit-tested).
 *
 * Tracks per-file status across the batch and computes the aggregate
 * "Sending 3 of 17 · 1.8 GB / 5.2 GB · 34%" summary. The Android layer owns
 * persistence (URIs are Android objects); this class owns ordering, status
 * transitions and the retry/skip/cancel semantics:
 *
 *   WAITING → ACTIVE → { VERIFIED | FAILED | SKIPPED | CANCELLED }
 *   FAILED → WAITING (retry), CANCELLED → WAITING (re-enqueue)
 */
class TransferQueue(items: List<FileMetadata>) {

    class Item(val fileId: Int, val metadata: FileMetadata) {
        @Volatile var status: QueueItemStatus = QueueItemStatus.WAITING
        @Volatile var error: String = ""
        @Volatile var bytesDone: Long = 0L
    }

    private val queue: MutableList<Item> =
        items.mapIndexed { index, meta -> Item(index, meta) }.toMutableList()
    @Volatile var paused: Boolean = false
        private set

    val items: List<Item> get() = queue.toList()

    val totalBytes: Long get() = queue.sumOf { it.metadata.fileSize }

    val filesTotal: Int get() = queue.size

    fun item(fileId: Int): Item? = queue.firstOrNull { it.fileId == fileId }

    fun markActive(fileId: Int) {
        item(fileId)?.let { it.status = QueueItemStatus.ACTIVE; it.error = "" }
    }

    fun markVerified(fileId: Int) {
        item(fileId)?.let {
            it.status = QueueItemStatus.VERIFIED
            it.bytesDone = it.metadata.fileSize
        }
    }

    fun markFailed(fileId: Int, error: String) {
        item(fileId)?.let { it.status = QueueItemStatus.FAILED; it.error = error.take(200) }
    }

    fun markSkipped(fileId: Int) {
        item(fileId)?.let { it.status = QueueItemStatus.SKIPPED }
    }

    fun markCancelled(fileId: Int) {
        item(fileId)?.let { if (it.status == QueueItemStatus.WAITING || it.status == QueueItemStatus.ACTIVE) it.status = QueueItemStatus.CANCELLED }
    }

    fun cancelRemaining() {
        queue.forEach { if (it.status == QueueItemStatus.WAITING || it.status == QueueItemStatus.ACTIVE) it.status = QueueItemStatus.CANCELLED }
    }

    fun updateBytes(fileId: Int, bytes: Long) {
        item(fileId)?.let { it.bytesDone = bytes.coerceAtMost(it.metadata.fileSize) }
    }

    /** Re-enqueues every FAILED item for a retry pass. Returns their ids. */
    fun retryFailed(): List<Int> {
        val ids = queue.filter { it.status == QueueItemStatus.FAILED }.map { it.fileId }
        ids.forEach { id -> item(id)?.status = QueueItemStatus.WAITING }
        return ids
    }

    fun remove(fileId: Int) {
        queue.removeAll { it.fileId == fileId }
    }

    /** Moves an item one position up in the queue (only while WAITING). */
    fun moveUp(fileId: Int) {
        val index = queue.indexOfFirst { it.fileId == fileId }
        if (index > 0 && queue[index].status == QueueItemStatus.WAITING &&
            queue[index - 1].status == QueueItemStatus.WAITING
        ) {
            val item = queue.removeAt(index)
            queue.add(index - 1, item)
        }
    }

    fun pause() { paused = true }

    fun resume() { paused = false }

    // -------------------------------------------------------------- summary

    val bytesDone: Long get() = queue.sumOf { it.bytesDone }

    val filesDone: Int get() = queue.count {
        it.status == QueueItemStatus.VERIFIED || it.status == QueueItemStatus.SKIPPED
    }

    val filesVerified: Int get() = queue.count { it.status == QueueItemStatus.VERIFIED }

    val filesFailed: Int get() = queue.count { it.status == QueueItemStatus.FAILED }

    val hasPendingWork: Boolean
        get() = queue.any { it.status == QueueItemStatus.WAITING || it.status == QueueItemStatus.ACTIVE }

    val currentFileIndex: Int get() = queue.indexOfFirst { it.status == QueueItemStatus.ACTIVE }
}
