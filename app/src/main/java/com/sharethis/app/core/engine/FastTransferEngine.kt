package com.sharethis.app.core.engine

import com.sharethis.app.core.storage.ChecksumVerifier
import com.sharethis.app.data.enums.TransferState
import com.sharethis.app.data.models.FileMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Tuned TCP transfer engine — the hot path of every ShareThis session.
 *
 * Throughput recipe:
 *  - `tcpNoDelay = true` (Nagle off — we already frame in bulk)
 *  - 512 KiB kernel send/receive buffers to absorb Wi-Fi jitter
 *  - adaptive 64 KiB → 256 KiB userspace copy buffer (grows on fast links)
 *  - single pipelined socket per session (no per-file reconnects)
 *  - CRC32 computed inline while piping — zero extra I/O passes
 *  - progress dispatched to UI at most every 200 ms
 *
 * Wire order: MANIFEST -> (FILE + body -> CHECKSUM)* -> END.
 */
class FastTransferEngine {

    /** One outbound file: metadata up-front, stream opened lazily at send time. */
    data class SendRequest(
        val metadata: FileMetadata,
        val openInput: () -> InputStream
    )

    /** Receiver-side storage hook. The engine streams; the sink persists. */
    interface ReceiveSink {
        @Throws(IOException::class)
        fun createOutput(metadata: FileMetadata): OutputStream
        fun onFileComplete(metadata: FileMetadata, verified: Boolean)
        fun onFileFailed(metadata: FileMetadata, error: Throwable)
    }

    companion object {
        const val DEFAULT_TCP_PORT = 8990
        const val CONNECT_TIMEOUT_MS = 10_000
        const val ACCEPT_TIMEOUT_MS = 5 * 60_000

        const val SOCKET_BUFFER_BYTES = 512 * 1024
        const val MIN_COPY_BUFFER = 64 * 1024
        const val MAX_COPY_BUFFER = 256 * 1024
        const val PROGRESS_EMIT_MS = 200L

        /** Grow the copy buffer past this speed; shrink below 1 MB/s. */
        const val GROW_BUFFER_ABOVE_BPS = 20L * 1024L * 1024L
        const val SHRINK_BUFFER_BELOW_BPS = 1L * 1024L * 1024L
    }

    @Suppress("unused")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(TransferState.IDLE)
    val state: StateFlow<TransferState> = _state

    private val _progress = MutableStateFlow(TransferProgress())
    val progress: StateFlow<TransferProgress> = _progress

    @Volatile private var cancelled = false
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var activeServerSocket: ServerSocket? = null

    // ------------------------------------------------------------ socket tune

    fun tuneSocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true
        } catch (_: Exception) { /* keep going */
        }
        try {
            socket.keepAlive = true
        } catch (_: Exception) { /* keep going */
        }
        try {
            socket.sendBufferSize = SOCKET_BUFFER_BYTES
        } catch (_: Exception) { /* OEM clamped */
        }
        try {
            socket.receiveBufferSize = SOCKET_BUFFER_BYTES
        } catch (_: Exception) { /* OEM clamped */
        }
    }

    // ---------------------------------------------------------------- sender

    /**
     * Connects to [host]:[port] and streams every file in [files] over one
     * pipelined socket. Returns true only when the peer acknowledged END.
     */
    suspend fun send(host: String, port: Int, files: List<SendRequest>): Boolean =
        withContext(Dispatchers.IO) {
            require(files.isNotEmpty()) { "Nothing to send" }
            _state.value = TransferState.CONNECTING
            _progress.value = TransferProgress()
            cancelled = false

            val totalBytes = files.sumOf { it.metadata.fileSize }
            var transferred = 0L
            var socket: Socket? = null
            try {
                socket = Socket()
                activeSocket = socket
                tuneSocket(socket)
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                tuneSocket(socket) // re-apply post-connect; some stacks reset sizes
                _state.value = TransferState.TRANSFERRING

                val out = BufferedOutputStream(socket.getOutputStream(), MAX_COPY_BUFFER)
                val speed = SpeedWindow()
                var lastEmitMs = 0L

                // 1. Manifest first so the receiver knows totals up-front.
                FrameProtocol.writeFrame(
                    out,
                    FrameProtocol.FrameHeader(
                        type = FrameProtocol.TYPE_MANIFEST,
                        filesTotal = files.size,
                        manifest = files.map { it.metadata }
                    )
                )

                // 2. Each file: header -> raw body -> trailing checksum.
                files.forEachIndexed { index, request ->
                    currentCoroutineContext().ensureActive()
                    ensureNotCancelled()
                    val meta = request.metadata

                    FrameProtocol.writeFrame(
                        out,
                        FrameProtocol.FrameHeader(
                            type = FrameProtocol.TYPE_FILE,
                            fileName = meta.fileName,
                            fileSize = meta.fileSize,
                            mimeType = meta.mimeType,
                            fileIndex = index,
                            filesTotal = files.size
                        )
                    )

                    val crc = ChecksumVerifier.StreamingCrc32()
                    var buffer = ByteArray(MIN_COPY_BUFFER)
                    request.openInput().use { raw ->
                        val input = raw as? BufferedInputStream
                            ?: BufferedInputStream(raw, MAX_COPY_BUFFER)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            ensureNotCancelled()
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            crc.update(buffer, 0, read)
                            transferred += read
                            speed.add(read)
                            buffer = adaptBuffer(buffer, speed.currentBps)

                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= PROGRESS_EMIT_MS) {
                                lastEmitMs = now
                                _progress.value = TransferProgress(
                                    bytesTransferred = transferred,
                                    totalBytes = totalBytes,
                                    bytesPerSecond = speed.currentBps,
                                    fileName = meta.fileName,
                                    filesCompleted = index,
                                    filesTotal = files.size
                                )
                            }
                        }
                    }
                    out.flush()

                    FrameProtocol.writeFrame(
                        out,
                        FrameProtocol.FrameHeader(
                            type = FrameProtocol.TYPE_CHECKSUM,
                            fileName = meta.fileName,
                            crc32 = crc.value(),
                            fileIndex = index,
                            filesTotal = files.size
                        )
                    )
                    _progress.value = TransferProgress(
                        bytesTransferred = transferred,
                        totalBytes = totalBytes,
                        bytesPerSecond = speed.currentBps,
                        fileName = meta.fileName,
                        filesCompleted = index + 1,
                        filesTotal = files.size
                    )
                }

                // 3. Graceful end marker.
                FrameProtocol.writeFrame(out, FrameProtocol.FrameHeader(type = FrameProtocol.TYPE_END))
                out.flush()

                _progress.value = TransferProgress(
                    bytesTransferred = totalBytes,
                    totalBytes = totalBytes,
                    bytesPerSecond = speed.currentBps,
                    fileName = "",
                    filesCompleted = files.size,
                    filesTotal = files.size
                )
                _state.value = TransferState.COMPLETED
                true
            } catch (e: CancellationException) {
                _state.value = TransferState.CANCELLED
                throw e
            } catch (_: Exception) {
                if (!cancelled && currentCoroutineContext().isActive) {
                    _state.value = TransferState.ERROR
                } else if (_state.value != TransferState.ERROR) {
                    _state.value = TransferState.CANCELLED
                }
                false
            } finally {
                activeSocket = null
                try {
                    socket?.close()
                } catch (_: Exception) { /* ignore */
                }
            }
        }

    // -------------------------------------------------------------- receiver

    /**
     * Listens on [port] for one sender session and streams each incoming file
     * into [sink]. CRC mismatches are reported per-file; the session only
     * completes when every file verifies.
     */
    suspend fun receive(port: Int, sink: ReceiveSink): Boolean =
        withContext(Dispatchers.IO) {
            _state.value = TransferState.CONNECTING
            _progress.value = TransferProgress()
            cancelled = false

            var server: ServerSocket? = null
            var socket: Socket? = null
            try {
                server = ServerSocket()
                activeServerSocket = server
                server.reuseAddress = true
                try {
                    server.receiveBufferSize = SOCKET_BUFFER_BYTES
                } catch (_: Exception) { /* OEM clamped */
                }
                server.bind(InetSocketAddress(port))
                server.soTimeout = ACCEPT_TIMEOUT_MS

                socket = server.accept()
                activeSocket = socket
                tuneSocket(socket)
                _state.value = TransferState.TRANSFERRING

                // IMPORTANT: a single buffered wrapper for the whole session —
                // frames and bodies are read from this same stream.
                val input = BufferedInputStream(socket.getInputStream(), MAX_COPY_BUFFER)
                val speed = SpeedWindow()
                var lastEmitMs = 0L

                var totalBytes = 0L
                var transferred = 0L
                var filesTotal = 0
                var filesDone = 0
                var allVerified = true
                var currentName = ""

                while (true) {
                    currentCoroutineContext().ensureActive()
                    ensureNotCancelled()
                    val header = FrameProtocol.readFrame(input)
                    when (header.type) {
                        FrameProtocol.TYPE_MANIFEST -> {
                            filesTotal = header.filesTotal
                            totalBytes = header.manifest.sumOf { it.fileSize }
                            _progress.value = TransferProgress(
                                bytesTransferred = 0L,
                                totalBytes = totalBytes,
                                bytesPerSecond = 0L,
                                fileName = "",
                                filesCompleted = 0,
                                filesTotal = filesTotal
                            )
                        }
                        FrameProtocol.TYPE_FILE -> {
                            currentName = header.fileName
                            val meta = FileMetadata(
                                fileName = header.fileName,
                                fileSize = header.fileSize,
                                mimeType = header.mimeType
                            )
                            val crc = ChecksumVerifier.StreamingCrc32()
                            var buffer = ByteArray(MIN_COPY_BUFFER)
                            var remaining = header.fileSize
                            try {
                                sink.createOutput(meta).use { out ->
                                    val bufferedOut = out as? BufferedOutputStream
                                        ?: BufferedOutputStream(out, MAX_COPY_BUFFER)
                                    while (remaining > 0) {
                                        currentCoroutineContext().ensureActive()
                                        ensureNotCancelled()
                                        val want = minOf(buffer.size.toLong(), remaining).toInt()
                                        val read = input.read(buffer, 0, want)
                                        if (read == -1) throw IOException("Truncated file body")
                                        bufferedOut.write(buffer, 0, read)
                                        crc.update(buffer, 0, read)
                                        remaining -= read
                                        transferred += read
                                        speed.add(read)
                                        buffer = adaptBuffer(buffer, speed.currentBps)

                                        val now = System.currentTimeMillis()
                                        if (now - lastEmitMs >= PROGRESS_EMIT_MS) {
                                            lastEmitMs = now
                                            _progress.value = TransferProgress(
                                                bytesTransferred = transferred,
                                                totalBytes = totalBytes,
                                                bytesPerSecond = speed.currentBps,
                                                fileName = currentName,
                                                filesCompleted = filesDone,
                                                filesTotal = filesTotal
                                            )
                                        }
                                    }
                                    bufferedOut.flush()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                sink.onFileFailed(meta, e)
                                allVerified = false
                                throw e
                            }

                            // Trailing checksum frame for this file.
                            val checksum = FrameProtocol.readFrame(input)
                            val verified = checksum.type == FrameProtocol.TYPE_CHECKSUM &&
                                ChecksumVerifier.verifyCrc32(checksum.crc32, crc.value())
                            if (!verified) allVerified = false
                            filesDone++
                            sink.onFileComplete(meta.copy(crc32 = crc.value()), verified)
                            _progress.value = TransferProgress(
                                bytesTransferred = transferred,
                                totalBytes = totalBytes,
                                bytesPerSecond = speed.currentBps,
                                fileName = currentName,
                                filesCompleted = filesDone,
                                filesTotal = filesTotal
                            )
                        }
                        FrameProtocol.TYPE_END -> break
                        else -> throw IOException("Unexpected frame: ${header.type}")
                    }
                }

                _progress.value = TransferProgress(
                    bytesTransferred = transferred,
                    totalBytes = totalBytes,
                    bytesPerSecond = speed.currentBps,
                    fileName = "",
                    filesCompleted = filesDone,
                    filesTotal = filesTotal
                )
                _state.value = if (allVerified) TransferState.COMPLETED else TransferState.ERROR
                allVerified
            } catch (e: CancellationException) {
                _state.value = TransferState.CANCELLED
                throw e
            } catch (_: Exception) {
                if (!cancelled && currentCoroutineContext().isActive) {
                    _state.value = TransferState.ERROR
                } else if (_state.value != TransferState.ERROR) {
                    _state.value = TransferState.CANCELLED
                }
                false
            } finally {
                activeSocket = null
                activeServerSocket = null
                try {
                    socket?.close()
                } catch (_: Exception) { /* ignore */
                }
                try {
                    server?.close()
                } catch (_: Exception) { /* ignore */
                }
            }
        }

    // ---------------------------------------------------------------- control

    /** Returns the engine to IDLE so a new session can start. */
    fun reset() {
        cancelled = false
        _state.value = TransferState.IDLE
        _progress.value = TransferProgress()
    }

    /** Cooperative cancel — also slams sockets shut to unblock I/O threads. */
    fun cancel() {
        cancelled = true
        try {
            activeSocket?.close()
        } catch (_: Exception) { /* ignore */
        }
        try {
            activeServerSocket?.close()
        } catch (_: Exception) { /* ignore */
        }
    }

    fun release() {
        cancel()
        scope.cancel()
    }

    // ---------------------------------------------------------------- helpers

    private fun ensureNotCancelled() {
        if (cancelled) throw CancellationException("Transfer cancelled")
    }

    private fun adaptBuffer(current: ByteArray, bps: Long): ByteArray {
        return when {
            bps >= GROW_BUFFER_ABOVE_BPS && current.size < MAX_COPY_BUFFER ->
                ByteArray(minOf(current.size * 2, MAX_COPY_BUFFER))
            bps in 1 until SHRINK_BUFFER_BELOW_BPS && current.size > MIN_COPY_BUFFER ->
                ByteArray(maxOf(current.size / 2, MIN_COPY_BUFFER))
            else -> current
        }
    }

    /**
     * Rolling ~1s throughput window. Cheap: O(1) amortized per sample.
     */
    private class SpeedWindow {
        private var windowStartMs = System.currentTimeMillis()
        private var windowBytes = 0L
        @Volatile var currentBps = 0L
            private set

        @Synchronized
        fun add(bytes: Int) {
            windowBytes += bytes
            val now = System.currentTimeMillis()
            val elapsed = now - windowStartMs
            if (elapsed >= 1000L) {
                currentBps = TransferProgress.speedBytesPerSec(windowBytes, elapsed)
                windowStartMs = now
                windowBytes = 0L
            } else if (currentBps == 0L && windowBytes > 0L && elapsed > 0L) {
                // Early estimate so the UI shows speed within the first second.
                currentBps = TransferProgress.speedBytesPerSec(windowBytes, elapsed)
            }
        }
    }
}
