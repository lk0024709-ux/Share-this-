package com.sharethis.app.core.storage

import com.sharethis.app.core.engine.JsonCodec
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Receiver-side resume persistence (pure JVM — java.io only).
 *
 * Incoming files are spooled into a private directory one chunk at a time
 * (`<root>/<transferId>/<fileId>.part`) so an interrupted transfer can be
 * resumed at chunk granularity after a disconnect, or even after receiver
 * process death (state is persisted atomically to `state.json`).
 *
 * Per-file persisted state: metadata, chunk size, chunk count, received-chunk
 * bitmap and per-chunk CRC32s. CRCs let us re-validate spooled bytes after a
 * restart before trusting them (fast, non-cryptographic check — the final
 * SHA-256 comparison remains the authoritative verdict).
 */
class SpoolManager(private val root: File) {

    class FileState(
        val fileId: Int,
        var fileName: String,
        var size: Long,
        var mimeType: String,
        var chunkSize: Int,
        var chunkCount: Int,
        var receivedMask: LongArray, // one bit per chunk
        var chunkCrcs: LongArray,
        var complete: Boolean = false,   // all chunks + SHA-256 verified
        var finalized: Boolean = false   // moved into user storage
    ) {
        // receivedMask is a bit-per-chunk bitmap stored as long words.
        fun isChunkReceived(index: Int): Boolean =
            index >= 0 && index / 64 < receivedMask.size &&
                (receivedMask[index / 64] and (1L shl (index % 64))) != 0L

        fun markChunk(index: Int, crc: Long) {
            if (index < 0 || index / 64 >= receivedMask.size) return
            receivedMask[index / 64] = receivedMask[index / 64] or (1L shl (index % 64))
            if (index < chunkCrcs.size) chunkCrcs[index] = crc
        }
    }

    class TransferState(
        val transferId: Int,
        val sessionId: Long,
        val files: MutableMap<Int, FileState> = LinkedHashMap()
    ) {
        fun totalReceivedBytes(): Long = files.values.sumOf { it.size * receivedCount(it) / maxOf(1, it.chunkCount) }
        private fun receivedCount(f: FileState): Int =
            (0 until f.chunkCount).count { f.isChunkReceived(it) }
    }

    private val transfers = HashMap<Int, TransferState>()

    companion object {
        private const val STATE_FILE = "state.json"
        private const val RETRY_IO_TIMES = 3

        fun deterministicChunkSize(fileSize: Long): Int = when {
            fileSize <= 0L -> 64 * 1024
            fileSize <= 1L shl 20 -> 64 * 1024            // ≤ 1 MiB
            fileSize <= 64L shl 20 -> 512 * 1024          // ≤ 64 MiB
            fileSize <= 512L shl 20 -> 1 shl 20           // ≤ 512 MiB
            else -> 4 shl 20                              // > 512 MiB → 4 MiB chunks
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Synchronized
    fun load(transferId: Int): TransferState? {
        transfers[transferId]?.let { return it }
        val dir = File(root, transferId.toString())
        val stateFile = File(dir, STATE_FILE)
        if (!stateFile.exists()) return null
        return try {
            val text = stateFile.readText(Charsets.UTF_8)
            val state = decodeState(text, transferId) ?: return null
            transfers[transferId] = state
            state
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun create(transferId: Int, sessionId: Long): TransferState {
        val existing = transfers[transferId]
        if (existing != null) return existing
        val dir = File(root, transferId.toString())
        dir.mkdirs()
        val state = TransferState(transferId, sessionId)
        transfers[transferId] = state
        return state
    }

    @Synchronized
    fun delete(transferId: Int) {
        transfers.remove(transferId)
        val dir = File(root, transferId.toString())
        deleteRecursively(dir)
    }

    @Synchronized
    fun listTransfers(): List<Int> =
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { it.name.toIntOrNull() } ?: emptyList()

    /** Removes spool directories older than [maxAgeMs] (called at app start). */
    @Synchronized
    fun cleanupOlderThan(maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()) {
        root.listFiles()?.forEach { dir ->
            val age = nowMs - (dir.lastModified())
            if (dir.isDirectory && age > maxAgeMs) {
                deleteRecursively(dir)
            }
        }
    }

    // ----------------------------------------------------------- file state

    fun openFileState(
        state: TransferState,
        fileId: Int,
        fileName: String,
        size: Long,
        mimeType: String,
        chunkSize: Int,
        chunkCount: Int
    ): FileState {
        val existing = state.files[fileId]
        if (existing != null) return existing
        val created = FileState(
            fileId, fileName, size, mimeType, chunkSize, chunkCount,
            LongArray((chunkCount + 63) / 64), LongArray(maxOf(chunkCount, 1))
        )
        state.files[fileId] = created
        return created
    }

    fun spoolFile(state: TransferState, fileId: Int): File =
        File(File(root, state.transferId.toString()), "$fileId.part")

    /** Writes one chunk at its fixed offset. */
    @Throws(IOException::class)
    fun writeChunk(
        state: TransferState,
        fileId: Int,
        chunkIndex: Int,
        chunkSize: Int,
        bytes: ByteArray,
        offset: Int,
        length: Int
    ) {
        val file = spoolFile(state, fileId)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(chunkIndex.toLong() * chunkSize)
            raf.write(bytes, offset, length)
        }
    }

    /** Reads back a spooled chunk (for CRC re-validation on resume). */
    @Throws(IOException::class)
    fun readChunk(state: TransferState, fileId: Int, chunkIndex: Int, chunkSize: Int, length: Int): ByteArray? {
        val file = spoolFile(state, fileId)
        if (!file.exists()) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val expected = chunkIndex.toLong() * chunkSize
                if (raf.length() < expected + length) return null
                val out = ByteArray(length)
                raf.seek(expected)
                raf.readFully(out)
                out
            }
        } catch (_: IOException) {
            null
        }
    }

    /**
     * First chunk index that is missing or fails its stored CRC — the resume
     * point. Cheap: only reads the contiguous prefix.
     */
    fun firstInvalidChunk(state: TransferState, fileId: Int): Int {
        val fs = state.files[fileId] ?: return 0
        var index = 0
        while (index < fs.chunkCount) {
            if (!fs.isChunkReceived(index)) return index
            val length = if (index == fs.chunkCount - 1) {
                val remainder = (fs.size - index.toLong() * fs.chunkSize).toInt()
                if (fs.size == 0L) 0 else remainder.coerceAtLeast(0)
            } else fs.chunkSize
            if (length == 0) return index
            val bytes = readChunk(state, fileId, index, fs.chunkSize, length)
            if (bytes == null || ChecksumVerifier.crc32Of(bytes) != fs.chunkCrcs[index]) {
                return index
            }
            index++
        }
        return index // everything valid
    }

    // ------------------------------------------------------------ persistence

    @Synchronized
    fun persist(state: TransferState) {
        val dir = File(root, state.transferId.toString())
        if (!dir.exists() && !dir.mkdirs()) return
        val tmp = File(dir, "$STATE_FILE.tmp")
        val final = File(dir, STATE_FILE)
        try {
            var attempt = 0
            var saved = false
            while (attempt < RETRY_IO_TIMES && !saved) {
                try {
                    tmp.writeText(encodeState(state), Charsets.UTF_8)
                    saved = true
                } catch (_: IOException) {
                    attempt++
                    if (attempt >= RETRY_IO_TIMES) return
                }
            }
            if (!final.delete() && final.exists()) return
            if (!tmp.renameTo(final)) {
                // Cross-filesystem safety: copy content instead.
                try {
                    final.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                    tmp.delete()
                } catch (_: Exception) { /* best effort */ }
            }
        } catch (_: Exception) { /* resume state is best-effort */ }
    }

    // ---------------------------------------------------------------- codec

    private fun encodeState(state: TransferState): String {
        val filesJson = state.files.values.joinToString(",", "[", "]") { f ->
            com.sharethis.app.core.engine.JsonCodec.obj(
                "id" to f.fileId,
                "name" to f.fileName,
                "size" to f.size,
                "mime" to f.mimeType,
                "cs" to f.chunkSize,
                "cc" to f.chunkCount,
                "mask" to maskToHex(f.receivedMask),
                "crcs" to crcsToHex(f.chunkCrcs, f.chunkCount),
                "done" to f.complete,
                "fin" to f.finalized
            )
        }
        return com.sharethis.app.core.engine.JsonCodec.obj(
            "tid" to state.transferId,
            "sid" to state.sessionId,
            "v" to 1,
            "files" to com.sharethis.app.core.engine.JsonCodec.RawJson(filesJson)
        )
    }

    private fun decodeState(text: String, transferId: Int): TransferState? {
        val map = JsonCodec.parseObject(text)
        val tid = JsonCodec.asInt(map["tid"])
        if (tid != transferId) return null
        val sid = JsonCodec.asLong(map["sid"])
        val state = TransferState(tid, sid)
        for (element in JsonCodec.parseArray(map["files"] ?: "[]")) {
            val f = JsonCodec.parseObject(element)
            val id = JsonCodec.asInt(f["id"])
            val name = JsonCodec.asString(f["name"])
            val size = JsonCodec.asLong(f["size"])
            val cc = JsonCodec.asInt(f["cc"])
            if (name.isEmpty() || cc <= 0 && size > 0) continue
            val cs = JsonCodec.asInt(f["cs"]).takeIf { it > 0 } ?: deterministicChunkSize(size)
            state.files[id] = FileState(
                fileId = id,
                fileName = name,
                size = size,
                mimeType = JsonCodec.asString(f["mime"]).ifEmpty { "application/octet-stream" },
                chunkSize = cs,
                chunkCount = cc,
                receivedMask = maskFromHex(JsonCodec.asString(f["mask"]), (cc + 63) / 64),
                chunkCrcs = crcsFromHex(JsonCodec.asString(f["crcs"]), maxOf(cc, 1)),
                complete = JsonCodec.asBoolean(f["done"]),
                finalized = JsonCodec.asBoolean(f["fin"])
            )
        }
        return state
    }

    private fun maskToHex(mask: LongArray): String {
        val sb = StringBuilder(mask.size * 16)
        for (word in mask) sb.append(String.format(java.util.Locale.US, "%016x", word))
        return sb.toString()
    }

    private fun maskFromHex(hex: String, words: Int): LongArray {
        val out = LongArray(maxOf(words, 1))
        var i = 0
        while (i < out.size && (i + 1) * 16 <= hex.length) {
            out[i] = hex.substring(i * 16, (i + 1) * 16).toLongOrNull(16) ?: 0L
            i++
        }
        return out
    }

    private fun crcsToHex(crcs: LongArray, count: Int): String {
        val sb = StringBuilder(count * 8)
        for (i in 0 until maxOf(count, 1).coerceAtMost(crcs.size)) {
            sb.append(String.format(java.util.Locale.US, "%08x", crcs[i]))
        }
        return sb.toString()
    }

    private fun crcsFromHex(hex: String, count: Int): LongArray {
        val out = LongArray(maxOf(count, 1))
        var i = 0
        while (i < out.size && (i + 1) * 8 <= hex.length) {
            out[i] = hex.substring(i * 8, (i + 1) * 8).toLongOrNull(16) ?: 0L
            i++
        }
        return out
    }

    private fun deleteRecursively(file: File) {
        try {
            file.listFiles()?.forEach { deleteRecursively(it) }
            file.delete()
        } catch (_: Exception) { /* best effort */ }
    }
}
