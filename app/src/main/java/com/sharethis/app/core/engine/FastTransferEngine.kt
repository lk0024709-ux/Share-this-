package com.sharethis.app.core.engine

import com.sharethis.app.core.security.Base64Url
import com.sharethis.app.core.security.SessionCrypto
import com.sharethis.app.core.storage.ChecksumVerifier
import com.sharethis.app.core.storage.SafeNames
import com.sharethis.app.core.storage.SpoolManager
import com.sharethis.app.data.enums.QueueItemStatus
import com.sharethis.app.data.enums.TransferState
import com.sharethis.app.data.models.ConnectTarget
import com.sharethis.app.data.models.ErrorCategory
import com.sharethis.app.data.models.FileMetadata
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey

/**
 * ShareThis v2 transfer engine — the hot path of every session.
 *
 * Wire model (see [FrameProtocol]):
 *   HELLO → AUTH_OK → AUTH_CONFIRM           (ECDH + PIN/OOB authenticated)
 *   → MANIFEST → MANIFEST_ACK                (storage pre-flight)
 *   → (FILE_META → CHUNK* → FILE_DONE → FILE_ACK)* → END
 *
 * Everything after AUTH_CONFIRM is sealed with the session AEAD
 * (AES-256-GCM, unique nonce per frame, strict per-direction sequence
 * numbers — replay and reorder are impossible by construction).
 *
 * Reliability model:
 *  - every socket read has a timeout — the engine can never hang;
 *  - the sender reconnects with bounded retries, resuming per-file at the
 *    last chunk the receiver actually persisted (chunk-granular resume);
 *  - the receiver keeps accepting within a patience window after a drop so
 *    the sender's automatic reconnect lands back in the same session;
 *  - cancellation closes sockets immediately and propagates everywhere.
 *
 * Memory model: one chunk buffer + socket buffers, regardless of file size
 * (multi-GB files stream through 64 KiB–4 MiB chunks).
 *
 * This class is deliberately free of Android and coroutine dependencies:
 * blocking calls + listener callbacks, so the entire engine (including real
 * TCP sessions over loopback) is integration-testable on the JVM. The
 * Android layer runs it on Dispatchers.IO and maps listeners to StateFlows.
 */
class FastTransferEngine(config: EngineConfig = EngineConfig()) {

    // ------------------------------------------------------------ config

    data class EngineConfig(
        val connectTimeoutMs: Int = 15_000,
        val acceptTimeoutMs: Int = 20_000,          // initial wait for the sender
        val resumePatienceMs: Long = 10 * 60_000,   // receiver waits this long for reconnects
        val handshakeTimeoutMs: Int = 15_000,
        val readTimeoutMs: Int = 45_000,            // inactivity watchdog mid-transfer
        val ackTimeoutMs: Int = 60_000,
        val sendRetries: Int = 3,
        val progressEmitMs: Long = 200L,
        val socketBufferBytes: Int = 512 * 1024
    )

    class StorageSpaceException(message: String) : IOException(message)

    /** Raised when the peer sends an ABORT frame. */
    class AbortReceived(val category: ErrorCategory, message: String) : IOException(message)

    interface Listener {
        fun onState(state: TransferState, reason: String) {}
        fun onProgress(progress: TransferProgress) {}
        fun onQueueItem(fileId: Int, status: QueueItemStatus, detail: String = "") {}
        fun onSessionEstablished(sessionId: Long, transferId: Int) {}
        fun onLog(tag: String, message: String) {}
    }

    /** One outbound file. [open] is called per (re)connect with a resume offset. */
    interface SendSource {
        val metadata: FileMetadata
        fun open(offset: Long): InputStream
    }

    /** Receiver-side storage hook. Implemented by the Android storage bridge. */
    interface ReceiveSink {
        /** Called once per manifest — verify space, prepare the destination. */
        @Throws(StorageSpaceException::class)
        fun onManifest(files: List<FileMetadata>)

        /**
         * A file arrived and its SHA-256 matched. Finalize it into user
         * storage (duplicate policy handled here). Return the final display
         * path, or null when the file was skipped.
         */
        @Throws(IOException::class)
        fun onFileVerified(metadata: FileMetadata, spoolFile: File): String?

        fun onFileFailed(metadata: FileMetadata, error: Throwable)
    }

    /** Receiver session parameters, created once per hosted session. */
    class ReceiverSession(
        val sessionId: Long,
        val pin: String,
        val receiverChallenge: ByteArray,
        val receiverKeyPair: KeyPair,
        val spoolRoot: File,
        val deviceName: String,
        val appVersion: String
    )

    data class SessionResult(
        val ok: Boolean,
        val paused: Boolean = false,
        val cancelled: Boolean = false,
        val category: ErrorCategory = ErrorCategory.UNKNOWN_ERROR,
        val detail: String = "",
        val filesVerified: Int = 0,
        val filesFailed: Int = 0,
        val bytesTransferred: Long = 0
    )

    private val cfg = config
    private val random = SecureRandom()

    @Volatile private var cancelled = false
    @Volatile private var pauseRequested = false
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var activeServer: ServerSocket? = null

    // ----------------------------------------------------------- control

    fun cancel() {
        cancelled = true
        pauseRequested = false
        closeActive()
    }

    /** Sender-side cooperative pause; the engine resumes when [resume] is called. */
    fun pause() { pauseRequested = true }

    fun resume() { pauseRequested = false }

    fun reset() {
        cancelled = false
        pauseRequested = false
    }

    private fun closeActive() {
        try { activeSocket?.close() } catch (_: Exception) { }
        try { activeServer?.close() } catch (_: Exception) { }
    }

    // ================================================================ sender

    /**
     * Streams [sources] to [target]. Blocking — run on a worker thread.
     * Automatically reconnects + resumes on drops (bounded by
     * [EngineConfig.sendRetries]). Returns a terminal [SessionResult].
     */
    fun send(
        target: ConnectTarget,
        sources: List<SendSource>,
        deviceName: String,
        appVersion: String,
        listener: Listener,
        transferIdHint: Int = 0
    ): SessionResult {
        require(sources.isNotEmpty()) { "Nothing to send" }
        cancelled = false
        pauseRequested = false
        // One FSM per session — an engine instance may host both roles
        // (tests) or consecutive sessions; sharing one FSM would let
        // concurrent sessions race each other's state.
        val fsm = ConnectionFsm()
        fsm.transition(TransferState.CONNECTING, target.host)
        listener.onState(fsm.state, "Connecting to ${target.host}:${target.port}")

        val queue = TransferQueue(sources.map { it.metadata })
        val speed = SpeedWindow()
        val totalBytes = queue.totalBytes
        val shaCache = HashMap<Int, String>()

        // Session identity: stable across reconnects so the receiver can
        // resume; fresh random per send() batch (or caller-provided for
        // retry-after-restart flows).
        val transferId = if (transferIdHint > 0) transferIdHint
        else 1 + random.nextInt(Int.MAX_VALUE - 1)
        listener.onSessionEstablished(target.sessionId, transferId)

        var attempt = 0
        var manifestSent = false
        var resumeMap: MutableMap<Int, ResumeEntry> = HashMap()

        while (true) {
            if (cancelled) return cancelledResult(fsm, queue)
            var socket: Socket? = null
            try {
                socket = Socket()
                activeSocket = socket
                socket.tcpNoDelay = true
                socket.keepAlive = true
                try { socket.sendBufferSize = cfg.socketBufferBytes } catch (_: Exception) { }
                try { socket.receiveBufferSize = cfg.socketBufferBytes } catch (_: Exception) { }
                socket.connect(InetSocketAddress(target.host, target.port), cfg.connectTimeoutMs)
                socket.soTimeout = cfg.handshakeTimeoutMs

                val out = BufferedOutputStream(socket.getOutputStream(), cfg.socketBufferBytes)
                val input = BufferedInputStream(socket.getInputStream(), cfg.socketBufferBytes)

                // ---- handshake -------------------------------------------
                val senderPair = SessionCrypto.generateKeyPair(random)
                val senderChallenge = ByteArray(SessionCrypto.CHALLENGE_BYTES)
                    .also { random.nextBytes(it) }
                val senderPub = SessionCrypto.encodePublicKey(senderPair.public as ECPublicKey)

                FrameProtocol.writeFrame(
                    out, FrameProtocol.TYPE_HELLO, 0, target.sessionId, transferId, 0, 0, 0,
                    JsonCodec.obj(
                        "v" to FrameProtocol.VERSION,
                        "app" to appVersion,
                        "name" to deviceName.take(64),
                        "sid" to target.sessionId,
                        "tid" to transferId,
                        "pub" to Base64Url.encode(senderPub),
                        "ch" to Base64Url.encode(senderChallenge),
                        "auth" to target.authMode,
                        "resume" to (attempt > 0)
                    ).toByteArray(Charsets.UTF_8),
                    ByteArray(0)
                )
                out.flush()

                fsm.transition(TransferState.AUTHENTICATING, "waiting for receiver")
                listener.onState(fsm.state, "Authenticating")
                val authOk = expectFrame(input, FrameProtocol.TYPE_AUTH_OK, target.sessionId, transferId)
                val authMeta = JsonCodec.parseObject(String(authOk.meta, Charsets.UTF_8))
                val rcvPubBytes = Base64Url.decode(JsonCodec.asString(authMeta["pub"]))
                    ?: throw FrameProtocol.ProtocolException("Bad receiver public key")
                val rcvChallenge = Base64Url.decode(JsonCodec.asString(authMeta["ch"]))
                val rcvMac = Base64Url.decode(JsonCodec.asString(authMeta["mac"]))
                if (rcvChallenge == null || rcvMac == null ||
                    rcvChallenge.size != SessionCrypto.CHALLENGE_BYTES
                ) throw FrameProtocol.ProtocolException("Malformed AUTH_OK")
                // QR/Bluetooth mode: the receiver's public key arrived over an
                // authenticated out-of-band channel — the TCP peer must match it.
                if (target.receiverPublicKey != null &&
                    !SessionCrypto.constantTimeEquals(rcvPubBytes, target.receiverPublicKey)
                ) {
                    return fail(fsm, listener, queue, ErrorCategory.AUTHENTICATION_FAILED,
                        "Receiver identity does not match the pairing code")
                }
                val rcvPub = SessionCrypto.decodePublicKey(rcvPubBytes)
                    ?: throw FrameProtocol.ProtocolException("Bad receiver key encoding")
                val ecdh = SessionCrypto.ecdhSecret(senderPair.private, rcvPub)
                    ?: return fail(fsm, listener, queue, ErrorCategory.AUTHENTICATION_FAILED,
                        "Key agreement failed")
                // The PIN always participates in the KDF: it arrives via the
                // pairing payload (QR/BT — an authenticated out-of-band
                // channel) or via direct user entry. This removes any
                // downgrade ambiguity between pairing modes.
                val keys = SessionCrypto.deriveKeys(
                    ecdh, target.pin, senderChallenge, rcvChallenge, senderFirst = true
                )
                val expectRcvMac = SessionCrypto.finishMac(
                    keys.finishKey, AUTH_LABEL, target.sessionId, senderPub, rcvPubBytes,
                    senderChallenge, rcvChallenge, DIRECTION_RECEIVER
                )
                if (!SessionCrypto.constantTimeEquals(rcvMac, expectRcvMac)) {
                    return fail(fsm, listener, queue, ErrorCategory.AUTHENTICATION_FAILED,
                        "Pairing check failed — wrong PIN or stale session")
                }
                val sndMac = SessionCrypto.finishMac(
                    keys.finishKey, AUTH_LABEL, target.sessionId, senderPub, rcvPubBytes,
                    senderChallenge, rcvChallenge, DIRECTION_SENDER
                )
                FrameProtocol.writeFrame(
                    out, FrameProtocol.TYPE_AUTH_CONFIRM, 0, target.sessionId, transferId,
                    0, 0, 1,
                    JsonCodec.obj("mac" to Base64Url.encode(sndMac)).toByteArray(Charsets.UTF_8),
                    ByteArray(0)
                )
                out.flush()

                val sendAead = SessionCrypto.newAead(keys.sendKey, keys.sendNoncePrefix)
                val recvAead = SessionCrypto.newAead(keys.recvKey, keys.recvNoncePrefix)
                val channel = SealedChannel(out, sendAead, target.sessionId, transferId)
                val reader = FrameReader(input, recvAead, target.sessionId, transferId)

                fsm.transition(TransferState.NEGOTIATING, "negotiating resume")
                listener.onState(fsm.state, "Preparing transfer")

                // Trust the receiver's report on what already arrived.
                val offered = parseResumeOffer(JsonCodec.asString(authMeta["resume"]))
                if (offered.isNotEmpty()) {
                    resumeMap = offered
                    sources.forEachIndexed { id, src ->
                        val entry = resumeMap[id] ?: return@forEachIndexed
                        when (entry.state) {
                            ResumeEntry.DONE, ResumeEntry.RECEIVED ->
                                queue.updateBytes(id, src.metadata.fileSize)
                            ResumeEntry.PART ->
                                queue.updateBytes(
                                    id,
                                    (entry.chunk.toLong() * entry.chunkSize)
                                        .coerceAtMost(src.metadata.fileSize)
                                )
                        }
                    }
                    listener.onLog("TRANSFER",
                        "resume offer: ${resumeMap.values.count { it.state != ResumeEntry.NONE }} file(s) known")
                }

                // ---- manifest + receiver storage pre-flight ----------------
                fsm.transition(TransferState.CONNECTED, "connected")
                if (!manifestSent) {
                    channel.write(FrameProtocol.TYPE_MANIFEST, 0, 0, 0,
                        manifestJson(sources).toByteArray(Charsets.UTF_8), ByteArray(0), ByteArray(0))
                    socket.soTimeout = cfg.handshakeTimeoutMs
                    val ack = reader.read()
                    if (ack.type != FrameProtocol.TYPE_MANIFEST_ACK) {
                        throw FrameProtocol.ProtocolException(
                            "Expected MANIFEST_ACK, got ${ack.type}"
                        )
                    }
                    val ackMeta = JsonCodec.parseObject(String(ack.meta, Charsets.UTF_8))
                    if (!JsonCodec.asBoolean(ackMeta["ok"])) {
                        val msg = JsonCodec.asString(ackMeta["msg"])
                            .ifEmpty { "Receiver rejected the transfer" }
                        return fail(fsm, listener, queue, categoryFromMessage(msg), msg)
                    }
                    manifestSent = true
                }
                socket.soTimeout = cfg.readTimeoutMs
                fsm.transition(TransferState.TRANSFERRING, "transferring")
                listener.onState(fsm.state, "Transferring")
                emit(listener, queue, queue.bytesDone, totalBytes, 0, "", target.transportLabel)

                // ---- stream files ------------------------------------------
                for (id in sources.indices) {
                    if (cancelled) return cancelledResult(fsm, queue)
                    val meta = sources[id].metadata
                    if (meta.fileSize < 0L) {
                        queue.markFailed(id, "Unknown file size")
                        listener.onQueueItem(id, QueueItemStatus.FAILED, "unknown size")
                        continue
                    }
                    val entry = resumeMap[id]
                    if (entry != null && entry.state == ResumeEntry.DONE) {
                        queue.markVerified(id)
                        listener.onQueueItem(id, QueueItemStatus.VERIFIED)
                        continue
                    }
                    queue.markActive(id)
                    listener.onQueueItem(id, QueueItemStatus.ACTIVE)
                    sendFile(socket, channel, reader, sources[id], id, queue, entry,
                        shaCache, speed, listener, target.transportLabel)
                }

                channel.write(FrameProtocol.TYPE_END, 0, 0, 0,
                    JsonCodec.obj().toByteArray(Charsets.UTF_8), ByteArray(0), ByteArray(0))

                fsm.transition(TransferState.VERIFYING, "finalizing")
                try {
                    socket.soTimeout = 1_000
                    reader.drain { }
                } catch (_: Exception) { }

                val failed = queue.filesFailed
                val resultOk = failed == 0 && queue.filesVerified == queue.filesTotal
                fsm.transition(
                    if (resultOk) TransferState.COMPLETED else TransferState.TRANSFER_FAILED,
                    if (resultOk) "completed" else "$failed file(s) failed"
                )
                listener.onState(
                    fsm.state, if (resultOk) "Completed" else "Finished with failures"
                )
                emit(listener, queue, queue.bytesDone, totalBytes, speed.bps(), "", target.transportLabel)
                return SessionResult(
                    ok = resultOk,
                    category = if (resultOk) ErrorCategory.UNKNOWN_ERROR
                    else ErrorCategory.TRANSFER_CORRUPTED,
                    detail = if (resultOk) "" else "$failed file(s) failed",
                    filesVerified = queue.filesVerified,
                    filesFailed = failed,
                    bytesTransferred = queue.bytesDone
                )
            } catch (e: Exception) {
                if (cancelled) return cancelledResult(fsm, queue)
                if (e is PauseSignal) {
                    fsm.transition(TransferState.PAUSED, "paused by user")
                    listener.onState(fsm.state, "Paused")
                    val deadline = System.currentTimeMillis() + PAUSE_PATIENCE_MS
                    while (pauseRequested && !cancelled && System.currentTimeMillis() < deadline) {
                        Thread.sleep(200)
                    }
                    if (cancelled) return cancelledResult(fsm, queue)
                    if (pauseRequested) {
                        return SessionResult(
                            ok = false, paused = true, category = ErrorCategory.CANCELLED_BY_USER,
                            detail = "Paused", filesVerified = queue.filesVerified,
                            filesFailed = queue.filesFailed, bytesTransferred = queue.bytesDone
                        )
                    }
                    fsm.transition(TransferState.RECONNECTING, "resuming after pause")
                    listener.onState(fsm.state, "Resuming…")
                    continue
                }
                val category = categorize(e)
                listener.onLog("TRANSFER", "drop: ${e.javaClass.simpleName}: ${e.message}")
                if (e is FrameProtocol.UnsupportedProtocolException) {
                    return fail(fsm, listener, queue, ErrorCategory.UNSUPPORTED_PROTOCOL,
                        e.message ?: "incompatible protocol")
                }
                if (category == ErrorCategory.AUTHENTICATION_FAILED ||
                    category == ErrorCategory.UNSUPPORTED_PROTOCOL ||
                    category == ErrorCategory.INSUFFICIENT_STORAGE ||
                    category == ErrorCategory.FILE_NOT_FOUND
                ) {
                    return fail(fsm, listener, queue, category, e.message ?: "transfer failed")
                }
                attempt++
                if (attempt <= cfg.sendRetries) {
                    fsm.transition(TransferState.RECONNECTING, "attempt $attempt/${cfg.sendRetries}")
                    listener.onState(
                        fsm.state,
                        "Connection lost — resuming (attempt $attempt of ${cfg.sendRetries})"
                    )
                    listener.onLog("TRANSFER", "reconnect attempt $attempt")
                    try {
                        Thread.sleep(RECONNECT_BACKOFF_MS * attempt)
                    } catch (_: InterruptedException) { }
                    continue
                }
                return fail(fsm, listener, queue, category, e.message ?: "transfer failed")
            } finally {
                activeSocket = null
                try { socket?.close() } catch (_: Exception) { }
            }
        }
    }

    /** Streams one file (with resume offset), waits for its ACK. */
    private fun sendFile(
        socket: Socket,
        channel: SealedChannel,
        reader: FrameReader,
        source: SendSource,
        fileId: Int,
        queue: TransferQueue,
        resumeEntry: ResumeEntry?,
        shaCache: MutableMap<Int, String>,
        speed: SpeedWindow,
        listener: Listener,
        transportLabel: String
    ) {
        val meta = source.metadata
        val chunkSize = SpoolManager.deterministicChunkSize(meta.fileSize)
        val chunkCount = ((meta.fileSize + chunkSize - 1) / chunkSize).toInt()
        var lastEmit = 0L

        channel.write(FrameProtocol.TYPE_FILE_META, 0, fileId, 0,
            fileMetaJson(meta, chunkSize, chunkCount).toByteArray(Charsets.UTF_8),
            ByteArray(0), ByteArray(0))

        if (resumeEntry != null && resumeEntry.state == ResumeEntry.RECEIVED) {
            // Receiver already has every chunk — just close the file properly.
            val sha = shaCache[fileId] ?: hashWholeFile(source)
            channel.write(FrameProtocol.TYPE_FILE_DONE, 0, fileId, 0,
                JsonCodec.obj("sha" to sha).toByteArray(Charsets.UTF_8),
                ByteArray(0), ByteArray(0))
            shaCache[fileId] = sha
            waitAck(reader, socket, fileId, queue, listener)
            return
        }

        val startChunk = resumeEntry?.chunk ?: 0
        val offset = (startChunk.toLong() * chunkSize).coerceAtMost(meta.fileSize)

        // Whole-file SHA-256: hash the already-transferred prefix, then the
        // new chunks — one pass over the source in total.
        val sha = ChecksumVerifier.StreamingSha256()
        if (offset > 0) {
            source.open(0).use { prefix ->
                hashPrefixInto(sha, prefix, offset, ByteArray(1 shl 20))
            }
        }

        val chunkBuffer = ByteArray(chunkSize)
        source.open(offset).use { raw ->
            val input = raw as? BufferedInputStream
                ?: BufferedInputStream(raw, cfg.socketBufferBytes)
            var chunkIndex = startChunk
            var remaining = meta.fileSize - offset
            while (remaining > 0) {
                if (cancelled) throw CancellationSignal()
                if (pauseRequested) throw PauseSignal()
                val want = minOf(chunkBuffer.size.toLong(), remaining).toInt()
                val read = FrameProtocol.readOrEof(input, chunkBuffer, 0, want)
                if (read <= 0) throw IOException("Source ended early: ${meta.fileName}")
                val last = remaining - read <= 0
                sha.update(chunkBuffer, 0, read)
                channel.write(
                    FrameProtocol.TYPE_CHUNK,
                    if (last) FrameProtocol.FLAG_LAST_CHUNK else 0,
                    fileId, chunkIndex,
                    ByteArray(0), ByteArray(0),
                    chunkBuffer, 0, read
                )
                remaining -= read
                queue.updateBytes(
                    fileId,
                    ((chunkIndex + 1).toLong() * chunkSize).coerceAtMost(meta.fileSize)
                )
                speed.add(read.toLong())
                drainControl(reader)
                val now = System.currentTimeMillis()
                if (now - lastEmit >= cfg.progressEmitMs) {
                    lastEmit = now
                    emit(listener, queue, queue.bytesDone, queue.totalBytes, speed.bps(),
                        meta.fileName, transportLabel)
                }
                chunkIndex++
            }
        }
        val shaHex = sha.digestHex()
        shaCache[fileId] = shaHex
        channel.write(FrameProtocol.TYPE_FILE_DONE, 0, fileId, 0,
            JsonCodec.obj("sha" to shaHex).toByteArray(Charsets.UTF_8),
            ByteArray(0), ByteArray(0))
        waitAck(reader, socket, fileId, queue, listener)
    }

    /** Hashes [length] bytes of an already-open stream into [sha] (resume prefix). */
    private fun hashPrefixInto(
        sha: ChecksumVerifier.StreamingSha256,
        input: InputStream,
        length: Long,
        buffer: ByteArray
    ): Long {
        var remaining = length
        while (remaining > 0) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, want)
            if (read <= 0) break
            sha.update(buffer, 0, read)
            remaining -= read
        }
        return length - remaining
    }

    /** One-shot whole-source SHA-256 (only needed when the cache is cold). */
    private fun hashWholeFile(source: SendSource): String {
        val sha = ChecksumVerifier.StreamingSha256()
        source.open(0).use { input ->
            hashPrefixInto(sha, input, source.metadata.fileSize, ByteArray(1 shl 20))
        }
        return sha.digestHex()
    }

    private fun waitAck(
        reader: FrameReader,
        socket: Socket,
        fileId: Int,
        queue: TransferQueue,
        listener: Listener
    ) {
        val previousTimeout = socket.soTimeout
        socket.soTimeout = cfg.ackTimeoutMs
        try {
            val ack = reader.read()
            if (ack.type == FrameProtocol.TYPE_ABORT) {
                val meta = JsonCodec.parseObject(String(ack.meta, Charsets.UTF_8))
                val msg = JsonCodec.asString(meta["msg"])
                throw AbortReceived(categoryFromMessage(msg), msg)
            }
            if (ack.type != FrameProtocol.TYPE_FILE_ACK) {
                throw FrameProtocol.ProtocolException("Expected FILE_ACK, got ${ack.type}")
            }
            val ackMeta = JsonCodec.parseObject(String(ack.meta, Charsets.UTF_8))
            if (JsonCodec.asInt(ackMeta["id"]) != fileId) {
                throw FrameProtocol.ProtocolException("ACK for wrong file")
            }
            if (JsonCodec.asBoolean(ackMeta["ok"])) {
                queue.markVerified(fileId)
                listener.onQueueItem(fileId, QueueItemStatus.VERIFIED)
            } else {
                val msg = JsonCodec.asString(ackMeta["msg"]).ifEmpty { "verification failed" }
                queue.markFailed(fileId, msg)
                listener.onQueueItem(fileId, QueueItemStatus.FAILED, msg)
            }
        } finally {
            try { socket.soTimeout = previousTimeout } catch (_: Exception) { }
        }
    }

    /** Non-blocking read of control frames the peer may have pushed mid-stream. */
    private fun drainControl(reader: FrameReader) {
        while (reader.available() > 0) {
            val frame = reader.read()
            if (frame.type == FrameProtocol.TYPE_ABORT) {
                val meta = JsonCodec.parseObject(String(frame.meta, Charsets.UTF_8))
                val msg = JsonCodec.asString(meta["msg"])
                throw AbortReceived(categoryFromMessage(msg), msg)
            }
        }
    }

    // ============================================================== receiver

    /**
     * Accepts one sender session on [port], streaming files into the sink via
     * [session.spoolRoot]. Blocking — run on a worker thread. Keeps accepting
     * reconnects (for resume) within [EngineConfig.resumePatienceMs].
     */
    fun receive(
        session: ReceiverSession,
        port: Int,
        sink: ReceiveSink,
        listener: Listener
    ): SessionResult {
        cancelled = false
        pauseRequested = false
        val fsm = ConnectionFsm()
        fsm.transition(TransferState.CONNECTING, "waiting for sender")
        listener.onState(fsm.state, "Waiting for sender…")

        val spool = SpoolManager(session.spoolRoot)
        val ctx = ReceiveContext(spool)
        var everConnected = false

        val server = ServerSocket()
        activeServer = server
        try {
            server.reuseAddress = true
            try { server.receiveBufferSize = cfg.socketBufferBytes } catch (_: Exception) { }
            server.bind(InetSocketAddress(port), 2)
            val overallDeadline = System.currentTimeMillis() + cfg.resumePatienceMs

            while (true) {
                val acceptDeadline = if (everConnected) overallDeadline
                else System.currentTimeMillis() + cfg.acceptTimeoutMs
                try {
                    server.soTimeout = ((acceptDeadline - System.currentTimeMillis())
                        .coerceAtLeast(1_000)).toInt()
                } catch (e: Exception) {
                    // Raced with cancel() closing the server socket.
                    if (cancelled) return cancelledResult(fsm, ctx.queue)
                    return fail(fsm, listener, ctx.queue, ErrorCategory.NETWORK_UNAVAILABLE,
                        "Receiver socket error: ${e.message}")
                }
                val socket = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    if (everConnected || System.currentTimeMillis() >= overallDeadline) {
                        return fail(fsm, listener, ctx.queue,
                            if (everConnected) ErrorCategory.REMOTE_DISCONNECTED
                            else ErrorCategory.PAIRING_TIMEOUT,
                            if (everConnected) "Sender did not reconnect in time"
                            else "No sender connected in time")
                    }
                    continue
                } catch (e: Exception) {
                    // Server socket closed (cancel) or fatal server error.
                    if (cancelled) return cancelledResult(fsm, ctx.queue)
                    return fail(fsm, listener, ctx.queue, ErrorCategory.NETWORK_UNAVAILABLE,
                        "Receiver socket error: ${e.message}")
                }
                if (cancelled) return cancelledResult(fsm, ctx.queue)
                everConnected = true
                activeSocket = socket

                try {
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    try { socket.sendBufferSize = cfg.socketBufferBytes } catch (_: Exception) { }
                    try { socket.receiveBufferSize = cfg.socketBufferBytes } catch (_: Exception) { }
                    socket.soTimeout = cfg.handshakeTimeoutMs
                    val input = BufferedInputStream(socket.getInputStream(), cfg.socketBufferBytes)
                    val out = BufferedOutputStream(socket.getOutputStream(), cfg.socketBufferBytes)

                    val result = receiveConnection(socket, input, out, session, sink, listener, ctx, fsm)
                    if (result != null) return result
                    // null → the sender dropped; keep accepting (resume window).
                } catch (e: Exception) {
                    if (cancelled) return cancelledResult(fsm, ctx.queue)
                    listener.onLog("TRANSFER", "sender drop: ${e.javaClass.simpleName}: ${e.message}")
                    if (e is FrameProtocol.UnsupportedProtocolException) {
                        return fail(fsm, listener, ctx.queue, ErrorCategory.UNSUPPORTED_PROTOCOL,
                            e.message ?: "")
                    }
                    if (e is AbortReceived) {
                        return fail(fsm, listener, ctx.queue, e.category, e.message ?: "aborted")
                    }
                    // Persist what we have; go back to accepting.
                    ctx.state?.let { spool.persist(it) }
                } finally {
                    activeSocket = null
                    try { socket.close() } catch (_: Exception) { }
                }
            }
        } finally {
            activeServer = null
            try { server.close() } catch (_: Exception) { }
        }
    }

    /** Per-receive() mutable context that survives sender reconnects. */
    private class ReceiveContext(val spool: SpoolManager) {
        var state: SpoolManager.TransferState? = null
        var queue: TransferQueue? = null
        var totalBytes: Long = 0
        val speed = SpeedWindow()
        var lastEmit = 0L
    }

    /** Handles one (possibly re-)connected sender. Returns null to keep accepting. */
    private fun receiveConnection(
        socket: Socket,
        input: BufferedInputStream,
        out: OutputStream,
        session: ReceiverSession,
        sink: ReceiveSink,
        listener: Listener,
        ctx: ReceiveContext,
        fsm: ConnectionFsm
    ): SessionResult? {
        val spool = ctx.spool

        // ---- handshake (receiver side) --------------------------------
        // HELLO is read leniently (no session check) so an unknown-session
        // peer receives a clean ABORT instead of an unexplained drop.
        val hello = FrameProtocol.readFrame(input)
        if (hello.type == FrameProtocol.TYPE_ABORT) {
            val meta = JsonCodec.parseObject(String(hello.meta, Charsets.UTF_8))
            val msg = JsonCodec.asString(meta["msg"])
            throw AbortReceived(categoryFromMessage(msg), msg)
        }
        if (hello.type != FrameProtocol.TYPE_HELLO) {
            throw FrameProtocol.ProtocolException("Expected HELLO, got ${hello.type}")
        }
        val helloMeta = JsonCodec.parseObject(String(hello.meta, Charsets.UTF_8))
        if (JsonCodec.asLong(helloMeta["sid"]) != session.sessionId ||
            hello.sessionId != session.sessionId
        ) {
            abort(out, session.sessionId, hello.transferId,
                ErrorCategory.AUTHENTICATION_FAILED, "Unknown session — start a fresh session")
            return null
        }
        val transferId = hello.transferId
        val senderPubBytes = Base64Url.decode(JsonCodec.asString(helloMeta["pub"]))
            ?: throw FrameProtocol.ProtocolException("Bad sender key")
        val senderChallenge = Base64Url.decode(JsonCodec.asString(helloMeta["ch"]))
        if (senderChallenge == null ||
            senderChallenge.size != SessionCrypto.CHALLENGE_BYTES
        ) throw FrameProtocol.ProtocolException("Bad sender challenge")
        val senderPub = SessionCrypto.decodePublicKey(senderPubBytes)
            ?: throw FrameProtocol.ProtocolException("Bad sender key encoding")

        fsm.transition(TransferState.AUTHENTICATING, "authenticating sender")
        listener.onState(fsm.state, "Authenticating sender")

        val ecdh = SessionCrypto.ecdhSecret(session.receiverKeyPair.private, senderPub)
        if (ecdh == null) {
            abort(out, session.sessionId, transferId,
                ErrorCategory.AUTHENTICATION_FAILED, "Key agreement failed")
            return fail(fsm, listener, ctx.queue, ErrorCategory.AUTHENTICATION_FAILED,
                "Key agreement failed")
        }
        // Challenge ordering is sender-first on both sides. The PIN is always
        // bound into the KDF (it traveled inside the pairing payload).
        val keys = SessionCrypto.deriveKeys(
            ecdh, session.pin, session.receiverChallenge, senderChallenge, senderFirst = false
        )
        val rcvPubBytes = SessionCrypto.encodePublicKey(
            session.receiverKeyPair.public as ECPublicKey
        )
        val rcvMac = SessionCrypto.finishMac(
            keys.finishKey, AUTH_LABEL, session.sessionId, senderPubBytes, rcvPubBytes,
            senderChallenge, session.receiverChallenge, DIRECTION_RECEIVER
        )

        // Resume state keyed by transferId — survives receiver restarts.
        val state = spool.load(transferId) ?: spool.create(transferId, session.sessionId)
        ctx.state = state

        FrameProtocol.writeFrame(
            out, FrameProtocol.TYPE_AUTH_OK, 0, session.sessionId, transferId, 0, 0, 0,
            JsonCodec.obj(
                "pub" to Base64Url.encode(rcvPubBytes),
                "ch" to Base64Url.encode(session.receiverChallenge),
                "mac" to Base64Url.encode(rcvMac),
                "app" to session.appVersion,
                "name" to session.deviceName.take(64),
                "resume" to JsonCodec.RawJson(resumeOfferJson(state, spool))
            ).toByteArray(Charsets.UTF_8),
            ByteArray(0)
        )
        out.flush()

        val expectSndMac = SessionCrypto.finishMac(
            keys.finishKey, AUTH_LABEL, session.sessionId, senderPubBytes, rcvPubBytes,
            senderChallenge, session.receiverChallenge, DIRECTION_SENDER
        )
        // A peer that reached the confirm stage knows the session id, so a
        // bad or missing confirm is a genuine pairing failure — terminate
        // (rather than waiting for another sender).
        val confirm = try {
            expectFrame(input, FrameProtocol.TYPE_AUTH_CONFIRM, session.sessionId, transferId)
        } catch (e: Exception) {
            if (cancelled) throw e
            abort(out, session.sessionId, transferId,
                ErrorCategory.AUTHENTICATION_FAILED, "PIN check failed")
            return fail(fsm, listener, ctx.queue, ErrorCategory.AUTHENTICATION_FAILED,
                "Sender authentication failed — wrong PIN")
        }
        val confirmMeta = JsonCodec.parseObject(String(confirm.meta, Charsets.UTF_8))
        val sndMac = Base64Url.decode(JsonCodec.asString(confirmMeta["mac"]))
        if (sndMac == null || !SessionCrypto.constantTimeEquals(sndMac, expectSndMac)) {
            abort(out, session.sessionId, transferId,
                ErrorCategory.AUTHENTICATION_FAILED, "PIN check failed")
            return fail(fsm, listener, ctx.queue, ErrorCategory.AUTHENTICATION_FAILED,
                "Sender authentication failed — wrong PIN")
        }

        // Direction naming: "send" = sender→receiver, "recv" = receiver→sender.
        // The receiver therefore OPENS sender frames with the send key and
        // SEALS its own frames (ACKs, aborts) with the recv key.
        val senderToReceiver = SessionCrypto.newAead(keys.sendKey, keys.sendNoncePrefix)
        val receiverToSender = SessionCrypto.newAead(keys.recvKey, keys.recvNoncePrefix)
        val channel = SealedChannel(out, receiverToSender, session.sessionId, transferId)
        val reader = FrameReader(input, senderToReceiver, session.sessionId, transferId)

        fsm.transition(TransferState.NEGOTIATING, "negotiating")
        socket.soTimeout = cfg.readTimeoutMs

        // ---- session body ---------------------------------------------
        while (true) {
            if (cancelled) return cancelledResult(fsm, ctx.queue)
            val frame = reader.read()
            when (frame.type) {
                FrameProtocol.TYPE_MANIFEST -> handleManifest(fsm, frame, channel, ctx, sink, listener)

                FrameProtocol.TYPE_FILE_META -> {
                    val meta = JsonCodec.parseObject(String(frame.meta, Charsets.UTF_8))
                    val fileId = frame.fileId
                    val name = SafeNames.sanitize(JsonCodec.asString(meta["name"]))
                    val size = JsonCodec.asLong(meta["size"])
                    val mime = JsonCodec.asString(meta["mime"])
                        .ifEmpty { FileMetadata.MIME_DEFAULT }
                    val cs = JsonCodec.asInt(meta["cs"])
                        .takeIf { it in 1..FrameProtocol.MAX_PAYLOAD_BYTES }
                        ?: SpoolManager.deterministicChunkSize(size)
                    val cc = JsonCodec.asInt(meta["cc"])
                    if (size < 0 || cc < 0 || cc != ((size + cs - 1) / cs).toInt()) {
                        throw FrameProtocol.ProtocolException("Corrupted file metadata")
                    }
                    spool.openFileState(state, fileId, name, size, mime, cs, cc)
                    if (size == 0L) {
                        // Zero-byte files never receive chunks; touch the spool
                        // so the finalize/copy step has a real file to read.
                        try { spool.spoolFile(state, fileId).createNewFile() } catch (_: Exception) { }
                    }
                    ctx.queue?.markActive(fileId)
                    listener.onQueueItem(fileId, QueueItemStatus.ACTIVE)
                }

                FrameProtocol.TYPE_CHUNK -> {
                    val fileId = frame.fileId
                    val fs = state.files[fileId]
                        ?: throw FrameProtocol.ProtocolException("CHUNK for unknown file $fileId")
                    if (frame.chunkIndex < 0 || frame.chunkIndex >= fs.chunkCount) {
                        throw FrameProtocol.ProtocolException("Chunk index out of range")
                    }
                    val expected = firstMissingChunk(fs)
                    // Strict ordering with one exception: the sender may
                    // re-send a chunk below the watermark after a resume
                    // revalidation mismatch — idempotent overwrite.
                    if (frame.chunkIndex > expected) {
                        throw FrameProtocol.ProtocolException(
                            "Unexpected chunk sequence: got ${frame.chunkIndex}, expected $expected"
                        )
                    }
                    val length = frame.payloadLength
                    if (length > fs.chunkSize + 64) {
                        throw FrameProtocol.ProtocolException("Oversized chunk payload")
                    }
                    val crc = ChecksumVerifier.crc32Of(frame.payload, frame.payloadOffset, length)
                    spool.writeChunk(
                        state, fileId, frame.chunkIndex, fs.chunkSize,
                        frame.payload, frame.payloadOffset, length
                    )
                    fs.markChunk(frame.chunkIndex, crc)
                    ctx.queue?.updateBytes(fileId, receivedBytesFor(fs))
                    ctx.speed.add(length.toLong())
                    val now = System.currentTimeMillis()
                    val queue = ctx.queue
                    if (queue != null && now - ctx.lastEmit >= cfg.progressEmitMs) {
                        ctx.lastEmit = now
                        emit(listener, queue, queue.bytesDone, ctx.totalBytes,
                            ctx.speed.bps(), fs.fileName, "Wi-Fi")
                    }
                }

                FrameProtocol.TYPE_FILE_DONE -> {
                    val fileId = frame.fileId
                    val fs = state.files[fileId]
                        ?: throw FrameProtocol.ProtocolException("FILE_DONE for unknown file")
                    val meta = JsonCodec.parseObject(String(frame.meta, Charsets.UTF_8))
                    val expectedSha = JsonCodec.asString(meta["sha"])
                    fsm.transition(TransferState.VERIFYING, "verifying ${fs.fileName}")
                    listener.onState(fsm.state, "Verifying ${fs.fileName}")
                    val spoolFile = spool.spoolFile(state, fileId)
                    val actualSha = if (!spoolFile.exists() && fs.size == 0L) {
                        // Zero-byte files never touch the spool.
                        ChecksumVerifier.sha256Hex(ByteArray(0))
                    } else try {
                        spoolFile.inputStream().use { ChecksumVerifier.sha256Hex(it) }
                    } catch (_: Exception) { "" }
                    val fileMeta = FileMetadata(fs.fileName, fs.size, fs.mimeType)
                    if (actualSha.isNotEmpty() && actualSha == expectedSha) {
                        fs.complete = true
                        spool.persist(state)
                        val savedPath = try {
                            sink.onFileVerified(fileMeta, spoolFile)
                        } catch (e: Exception) {
                            listener.onLog("STORAGE", "finalize failed: ${e.message}")
                            sink.onFileFailed(fileMeta, e)
                            null
                        }
                        if (savedPath != null) {
                            fs.finalized = true
                            channel.write(FrameProtocol.TYPE_FILE_ACK, 0, fileId, 0,
                                ackJson(fileId, true, "").toByteArray(Charsets.UTF_8),
                                ByteArray(0), ByteArray(0))
                            ctx.queue?.markVerified(fileId)
                            listener.onQueueItem(fileId, QueueItemStatus.VERIFIED)
                            listener.onLog("VERIFY", "verified ${fs.fileName}")
                        } else {
                            channel.write(FrameProtocol.TYPE_FILE_ACK, 0, fileId, 0,
                                ackJson(fileId, false, "STORAGE_ACCESS_FAILED|finalize skipped")
                                    .toByteArray(Charsets.UTF_8),
                                ByteArray(0), ByteArray(0))
                            ctx.queue?.markSkipped(fileId)
                            listener.onQueueItem(fileId, QueueItemStatus.SKIPPED,
                                "skipped on receiver")
                        }
                        spool.persist(state)
                        spool.spoolFile(state, fileId).delete()
                    } else {
                        listener.onLog("VERIFY", "SHA-256 mismatch for ${fs.fileName}")
                        channel.write(FrameProtocol.TYPE_FILE_ACK, 0, fileId, 0,
                            ackJson(fileId, false, "VERIFICATION_FAILED|SHA-256 mismatch")
                                .toByteArray(Charsets.UTF_8),
                            ByteArray(0), ByteArray(0))
                        ctx.queue?.markFailed(fileId, "SHA-256 mismatch")
                        listener.onQueueItem(fileId, QueueItemStatus.FAILED, "SHA-256 mismatch")
                        sink.onFileFailed(fileMeta, IOException("SHA-256 mismatch"))
                        spool.spoolFile(state, fileId).delete()
                        state.files.remove(fileId)
                        spool.persist(state)
                    }
                    fsm.transition(TransferState.TRANSFERRING, "receiving")
                    listener.onState(fsm.state, "Receiving")
                }

                FrameProtocol.TYPE_END -> {
                    spool.delete(state.transferId)
                    val queue = ctx.queue
                    val verified = queue?.filesVerified ?: 0
                    val failed = queue?.filesFailed ?: 0
                    fsm.transition(
                        if (failed == 0) TransferState.COMPLETED else TransferState.TRANSFER_FAILED,
                        "completed"
                    )
                    listener.onState(
                        fsm.state,
                        if (failed == 0) "Completed" else "Finished with failures"
                    )
                    if (queue != null) {
                        emit(listener, queue, queue.bytesDone, ctx.totalBytes,
                            ctx.speed.bps(), "", "Wi-Fi")
                    }
                    return SessionResult(
                        ok = failed == 0,
                        category = if (failed == 0) ErrorCategory.UNKNOWN_ERROR
                        else ErrorCategory.VERIFICATION_FAILED,
                        detail = if (failed == 0) "" else "$failed file(s) failed verification",
                        filesVerified = verified,
                        filesFailed = failed,
                        bytesTransferred = queue?.bytesDone ?: 0
                    )
                }

                FrameProtocol.TYPE_PAUSE -> {
                    spool.persist(state)
                    return null // go back to accepting; sender reconnects
                }

                FrameProtocol.TYPE_ABORT -> {
                    val meta = JsonCodec.parseObject(String(frame.meta, Charsets.UTF_8))
                    val msg = JsonCodec.asString(meta["msg"])
                    throw AbortReceived(categoryFromMessage(msg), msg)
                }

                else -> throw FrameProtocol.ProtocolException("Unexpected frame ${frame.type}")
            }
        }
    }

    private fun handleManifest(
        fsm: ConnectionFsm,
        frame: FrameProtocol.Frame,
        channel: SealedChannel,
        ctx: ReceiveContext,
        sink: ReceiveSink,
        listener: Listener
    ) {
        val meta = JsonCodec.parseObject(String(frame.meta, Charsets.UTF_8))
        val indexed = ArrayList<Pair<Int, FileMetadata>>()
        for (element in JsonCodec.parseArray(meta["files"] ?: "[]")) {
            val f = JsonCodec.parseObject(element)
            val id = JsonCodec.asInt(f["id"])
            val name = SafeNames.sanitize(JsonCodec.asString(f["name"]))
            val size = JsonCodec.asLong(f["size"])
            val mime = JsonCodec.asString(f["mime"]).ifEmpty { FileMetadata.MIME_DEFAULT }
            if (size < 0) continue
            indexed.add(id to FileMetadata(name, size, mime))
        }
        try {
            sink.onManifest(indexed.map { it.second })
        } catch (e: StorageSpaceException) {
            channel.write(FrameProtocol.TYPE_MANIFEST_ACK, 0, 0, 0,
                JsonCodec.obj(
                    "ok" to false,
                    "msg" to "INSUFFICIENT_STORAGE|${e.message ?: "not enough space"}"
                ).toByteArray(Charsets.UTF_8), ByteArray(0), ByteArray(0))
            throw AbortReceived(
                ErrorCategory.INSUFFICIENT_STORAGE, e.message ?: "Not enough storage space"
            )
        } catch (e: Exception) {
            channel.write(FrameProtocol.TYPE_MANIFEST_ACK, 0, 0, 0,
                JsonCodec.obj(
                    "ok" to false,
                    "msg" to "STORAGE_ACCESS_FAILED|${e.message ?: "storage error"}"
                ).toByteArray(Charsets.UTF_8), ByteArray(0), ByteArray(0))
            throw AbortReceived(
                ErrorCategory.STORAGE_ACCESS_FAILED, e.message ?: "Storage error"
            )
        }
        channel.write(FrameProtocol.TYPE_MANIFEST_ACK, 0, 0, 0,
            JsonCodec.obj("ok" to true).toByteArray(Charsets.UTF_8),
            ByteArray(0), ByteArray(0))
        if (ctx.queue == null) {
            ctx.queue = TransferQueue(indexed.map { it.second })
            ctx.totalBytes = ctx.queue!!.totalBytes
            fsm.transition(TransferState.TRANSFERRING, "receiving")
            listener.onState(fsm.state, "Receiving")
            emit(listener, ctx.queue!!, 0, ctx.totalBytes, 0, "", "Wi-Fi")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun firstMissingChunk(fs: SpoolManager.FileState): Int {
        var index = 0
        while (index < fs.chunkCount && fs.isChunkReceived(index)) index++
        return index
    }

    private fun receivedBytesFor(fs: SpoolManager.FileState): Long {
        var total = 0L
        for (i in 0 until fs.chunkCount) {
            if (!fs.isChunkReceived(i)) break
            total += if (i == fs.chunkCount - 1) {
                (fs.size - i.toLong() * fs.chunkSize).coerceAtLeast(0)
            } else fs.chunkSize.toLong()
        }
        return total
    }

    private fun expectFrame(
        input: InputStream,
        expectedType: Int,
        sessionId: Long,
        transferId: Int
    ): FrameProtocol.Frame {
        val frame = FrameProtocol.readFrame(input)
        if (frame.type == FrameProtocol.TYPE_ABORT) {
            val meta = JsonCodec.parseObject(String(frame.meta, Charsets.UTF_8))
            val msg = JsonCodec.asString(meta["msg"])
            throw AbortReceived(categoryFromMessage(msg), msg)
        }
        if (frame.type != expectedType) {
            throw FrameProtocol.ProtocolException(
                "Expected frame $expectedType, got ${frame.type}"
            )
        }
        if (frame.sessionId != sessionId) {
            throw FrameProtocol.ProtocolException("Session mismatch")
        }
        if (transferId >= 0 && frame.transferId != transferId) {
            throw FrameProtocol.ProtocolException("Transfer mismatch")
        }
        return frame
    }

    private fun abort(
        out: OutputStream,
        sessionId: Long,
        transferId: Int,
        category: ErrorCategory,
        message: String
    ) {
        try {
            FrameProtocol.writeFrame(
                out, FrameProtocol.TYPE_ABORT, 0, sessionId, transferId, 0, 0, 0,
                JsonCodec.obj(
                    "cat" to category.name,
                    "msg" to "${category.name}|$message"
                ).toByteArray(Charsets.UTF_8),
                ByteArray(0)
            )
            out.flush()
        } catch (_: Exception) { }
    }

    private fun manifestJson(sources: List<SendSource>): String {
        val files = sources.mapIndexed { id, src ->
            JsonCodec.obj(
                "id" to id,
                "name" to src.metadata.fileName,
                "size" to src.metadata.fileSize,
                "mime" to src.metadata.mimeType
            )
        }.joinToString(",", "[", "]")
        return JsonCodec.obj("files" to JsonCodec.RawJson(files))
    }

    private fun fileMetaJson(meta: FileMetadata, chunkSize: Int, chunkCount: Int): String =
        JsonCodec.obj(
            "id" to 0, // fileId rides in the frame header; kept for debuggability
            "name" to meta.fileName,
            "size" to meta.fileSize,
            "mime" to meta.mimeType,
            "cs" to chunkSize,
            "cc" to chunkCount
        )

    private fun ackJson(id: Int, ok: Boolean, msg: String): String =
        JsonCodec.obj("id" to id, "ok" to ok, "msg" to msg)

    private fun resumeOfferJson(state: SpoolManager.TransferState, spool: SpoolManager): String {
        val entries = state.files.values.joinToString(",", "[", "]") { f ->
            val st = when {
                f.finalized -> ResumeEntry.DONE
                f.complete -> ResumeEntry.RECEIVED
                else -> ResumeEntry.PART
            }
            JsonCodec.obj(
                "id" to f.fileId,
                "st" to st,
                "chunk" to if (st == ResumeEntry.PART) {
                    spool.firstInvalidChunk(state, f.fileId)
                } else 0,
                "cs" to f.chunkSize
            )
        }
        return JsonCodec.obj("tid" to state.transferId, "files" to JsonCodec.RawJson(entries))
    }

    private fun parseResumeOffer(json: String): MutableMap<Int, ResumeEntry> {
        val map = HashMap<Int, ResumeEntry>()
        if (json.isEmpty() || json == "null") return map
        val obj = JsonCodec.parseObject(json)
        for (element in JsonCodec.parseArray(obj["files"] ?: "[]")) {
            val f = JsonCodec.parseObject(element)
            val id = JsonCodec.asInt(f["id"])
            map[id] = ResumeEntry(
                state = JsonCodec.asString(f["st"]),
                chunk = JsonCodec.asInt(f["chunk"]),
                chunkSize = JsonCodec.asInt(f["cs"])
            )
        }
        return map
    }

    private fun categoryFromMessage(msg: String): ErrorCategory {
        val tag = msg.substringBefore('|')
        return ErrorCategory.values().firstOrNull { it.name == tag }
            ?: ErrorCategory.UNKNOWN_ERROR
    }

    private fun categorize(e: Exception): ErrorCategory = when (e) {
        is StorageSpaceException -> ErrorCategory.INSUFFICIENT_STORAGE
        is AbortReceived -> e.category
        is FrameProtocol.UnsupportedProtocolException -> ErrorCategory.UNSUPPORTED_PROTOCOL
        is SecurityException,
        is javax.crypto.AEADBadTagException -> ErrorCategory.AUTHENTICATION_FAILED
        is FrameProtocol.ProtocolException ->
            if (e.message?.contains("auth", ignoreCase = true) == true ||
                e.message?.contains("PIN", ignoreCase = true) == true
            ) ErrorCategory.AUTHENTICATION_FAILED else ErrorCategory.TRANSFER_CORRUPTED
        is java.io.FileNotFoundException -> ErrorCategory.FILE_NOT_FOUND
        is SocketTimeoutException -> ErrorCategory.REMOTE_DISCONNECTED
        is java.net.ConnectException -> ErrorCategory.NETWORK_UNAVAILABLE
        is java.net.UnknownHostException -> ErrorCategory.NETWORK_UNAVAILABLE
        else -> ErrorCategory.REMOTE_DISCONNECTED
    }

    private fun fail(
        fsm: ConnectionFsm,
        listener: Listener,
        queue: TransferQueue?,
        category: ErrorCategory,
        detail: String
    ): SessionResult {
        val state = when (category) {
            ErrorCategory.AUTHENTICATION_FAILED -> TransferState.AUTHENTICATION_FAILED
            ErrorCategory.PAIRING_TIMEOUT -> TransferState.PAIRING_FAILED
            ErrorCategory.NETWORK_UNAVAILABLE,
            ErrorCategory.REMOTE_DISCONNECTED,
            ErrorCategory.UNSUPPORTED_PROTOCOL -> TransferState.CONNECTION_FAILED
            else -> TransferState.TRANSFER_FAILED
        }
        fsm.transition(state, detail)
        listener.onState(fsm.state, category.userMessage)
        listener.onLog("ERROR", "$category: $detail")
        return SessionResult(
            ok = false,
            category = category,
            detail = detail,
            filesVerified = queue?.filesVerified ?: 0,
            filesFailed = queue?.filesFailed ?: 0,
            bytesTransferred = queue?.bytesDone ?: 0
        )
    }

    private fun cancelledResult(fsm: ConnectionFsm? = null, queue: TransferQueue?): SessionResult {
        // The session fsm may be null when cancelling outside an active session.
        if (fsm != null) fsm.transition(TransferState.CANCELLED, "cancelled")
        return SessionResult(
            ok = false, cancelled = true, category = ErrorCategory.CANCELLED_BY_USER,
            detail = "Cancelled", filesVerified = queue?.filesVerified ?: 0,
            filesFailed = queue?.filesFailed ?: 0, bytesTransferred = queue?.bytesDone ?: 0
        )
    }

    private fun emit(
        listener: Listener,
        queue: TransferQueue,
        bytes: Long,
        totalBytes: Long,
        bps: Long,
        fileName: String,
        transportLabel: String
    ) {
        listener.onProgress(
            TransferProgress(
                bytesTransferred = bytes,
                totalBytes = totalBytes,
                bytesPerSecond = bps,
                fileName = fileName,
                filesCompleted = queue.filesDone,
                filesTotal = queue.filesTotal,
                filesVerified = queue.filesVerified,
                filesFailed = queue.filesFailed,
                transportLabel = transportLabel
            )
        )
    }

    // ------------------------------------------------------------ signals

    private class PauseSignal : Exception()
    private class CancellationSignal : Exception()

    class ResumeEntry(val state: String, val chunk: Int, val chunkSize: Int) {
        companion object {
            const val DONE = "done"
            const val RECEIVED = "recv"
            const val PART = "part"
            const val NONE = "none"
        }
    }

    // --------------------------------------------------------- frame plumbing

    /** Sealed-frame writer with a per-direction sequence counter. */
    private class SealedChannel(
        private val out: OutputStream,
        private val aead: SessionCrypto.Aead,
        private val sessionId: Long,
        private val transferId: Int
    ) {
        private var seq = 0L

        fun write(
            type: Int,
            flags: Int,
            fileId: Int,
            chunkIndex: Int,
            meta: ByteArray,
            payload: ByteArray,
            payloadBuffer: ByteArray,
            payloadOffset: Int = 0,
            payloadLength: Int = payloadBuffer.size - payloadOffset
        ) {
            val blob = if (payloadBuffer.isEmpty()) {
                FrameProtocol.seal(aead, seq, meta, payload)
            } else {
                FrameProtocol.seal(aead, seq, meta, payloadBuffer, payloadOffset, payloadLength)
            }
            FrameProtocol.writeFrame(
                out, type, flags or FrameProtocol.FLAG_ENCRYPTED,
                sessionId, transferId, fileId, chunkIndex, seq,
                ByteArray(0), blob
            )
            seq++
            out.flush()
        }
    }

    /**
     * Frame reader with strict per-direction sequence enforcement and a
     * reusable network buffer (no per-chunk allocation on the receive path).
     */
    private class FrameReader(
        private val input: BufferedInputStream,
        private val aead: SessionCrypto.Aead,
        private val sessionId: Long,
        private val transferId: Int
    ) {
        private var expectedSeq = 0L
        private var netBuffer = ByteArray(256 * 1024)

        fun available(): Int = try { input.available() } catch (_: Exception) { 0 }

        fun read(): FrameProtocol.Frame {
            val header = FrameProtocol.readHeader(input)
            if (header.sessionId != sessionId) {
                throw FrameProtocol.ProtocolException("Session mismatch on frame ${header.type}")
            }
            if (header.transferId != transferId) {
                throw FrameProtocol.ProtocolException("Transfer mismatch on frame ${header.type}")
            }
            if (header.sequence != expectedSeq) {
                throw FrameProtocol.ProtocolException(
                    "Frame sequence ${header.sequence} != expected $expectedSeq"
                )
            }
            expectedSeq++

            val meta: ByteArray
            if (!header.isEncrypted) {
                meta = if (header.metaLength <= 0) ByteArray(0) else ByteArray(header.metaLength)
                if (header.metaLength > 0) {
                    FrameProtocol.readFully(input, meta, 0, header.metaLength)
                }
                if (header.payloadLength <= 0) {
                    return FrameProtocol.Frame(
                        header.type, header.flags, header.sessionId, header.transferId,
                        header.fileId, header.chunkIndex, header.sequence, meta, ByteArray(0)
                    )
                }
                if (header.payloadLength > netBuffer.size) netBuffer = ByteArray(header.payloadLength)
                FrameProtocol.readFully(input, netBuffer, 0, header.payloadLength)
                val payload = if (header.payloadLength == netBuffer.size) netBuffer
                else java.util.Arrays.copyOf(netBuffer, header.payloadLength)
                return FrameProtocol.Frame(
                    header.type, header.flags, header.sessionId, header.transferId,
                    header.fileId, header.chunkIndex, header.sequence, meta, payload
                )
            }

            // Sealed frame: meta + payload travel as one encrypted blob.
            if (header.payloadLength > netBuffer.size) netBuffer = ByteArray(header.payloadLength)
            FrameProtocol.readFully(input, netBuffer, 0, header.payloadLength)
            val opened = FrameProtocol.openSealed(
                aead,
                if (header.payloadLength == netBuffer.size) netBuffer
                else netBuffer.copyOf(header.payloadLength),
                header.sequence
            )
            return FrameProtocol.Frame(
                header.type, header.flags, header.sessionId, header.transferId,
                header.fileId, header.chunkIndex, header.sequence,
                opened.meta, opened.payload, opened.payloadOffset, opened.payloadLength
            )
        }

        /** Reads and discards pending frames (graceful END wait). */
        fun drain(handler: (FrameProtocol.Frame) -> Unit) {
            while (available() > 0) handler(read())
        }
    }

    companion object {
        const val DEFAULT_TCP_PORT = 8990
        const val AUTH_LABEL = "sharethis/v2/auth"
        const val DIRECTION_SENDER = 0
        const val DIRECTION_RECEIVER = 1
        const val PAUSE_PATIENCE_MS = 10 * 60_000L
        const val RECONNECT_BACKOFF_MS = 750L
    }
}
