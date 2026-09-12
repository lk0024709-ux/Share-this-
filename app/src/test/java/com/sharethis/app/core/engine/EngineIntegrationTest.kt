package com.sharethis.app.core.engine

import com.sharethis.app.core.security.SessionCrypto
import com.sharethis.app.data.enums.TransferState
import com.sharethis.app.data.models.ConnectTarget
import com.sharethis.app.data.models.ErrorCategory
import com.sharethis.app.data.models.FileMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Full sender → receiver integration tests over real TCP loopback sockets:
 * real ECDH handshake, real AES-GCM framing, real spooling and resume.
 *
 * These exercise the exact code path a device runs — only the transport is
 * 127.0.0.1 instead of Wi-Fi.
 */
class EngineIntegrationTest {

    private class Harness(
        val sessionId: Long = PairingIds.newSessionId(),
        val pin: String = "512890",
        val spoolRoot: File = Files.createTempDirectory("spool").toFile(),
        val outDir: File = Files.createTempDirectory("out").toFile()
    ) {
        val challenge = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val keyPair = SessionCrypto.generateKeyPair(SecureRandom())
        val savedPaths = ArrayList<String>()
        val failedFiles = ArrayList<String>()
        var failSpaceCheck = false
        var manifestSeen: List<FileMetadata> = emptyList()

        fun newEngine(cfg: FastTransferEngine.EngineConfig = FastTransferEngine.EngineConfig()) =
            FastTransferEngine(cfg)

        fun newSession(@Suppress("UNUSED_PARAMETER") engine: FastTransferEngine): FastTransferEngine.ReceiverSession =
            FastTransferEngine.ReceiverSession(
                sessionId, pin, challenge, keyPair, spoolRoot,
                "Receiver", "2.0.0-test"
            )

        fun sink(): FastTransferEngine.ReceiveSink = object : FastTransferEngine.ReceiveSink {
            override fun onManifest(files: List<FileMetadata>) {
                manifestSeen = files
                if (failSpaceCheck) {
                    throw FastTransferEngine.StorageSpaceException(
                        "Required: 5.4 GB, Available: 2.1 GB"
                    )
                }
            }

            override fun onFileVerified(metadata: FileMetadata, spoolFile: File): String? {
                val target = File(outDir, metadata.fileName)
                spoolFile.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                val path = target.absolutePath
                synchronized(savedPaths) { savedPaths.add(path) }
                return path
            }

            override fun onFileFailed(metadata: FileMetadata, error: Throwable) {
                synchronized(failedFiles) { failedFiles.add(metadata.fileName) }
            }
        }
    }

    /** File-backed send source with read accounting. */
    private class FileSource(
        private val file: File,
        val reads: LongAdderBox = LongAdderBox()
    ) : FastTransferEngine.SendSource {
        override val metadata = FileMetadata(
            file.name, file.length(), "application/octet-stream"
        )

        override fun open(offset: Long): InputStream {
            val raw = FileInputStream(file)
            var skipped = 0L
            while (skipped < offset) {
                val n = raw.skip(offset - skipped)
                if (n <= 0) break
                skipped += n
            }
            reads.add(skipped)
            return object : FilterInputStream(raw) {
                override fun read(): Int {
                    val r = super.read()
                    if (r >= 0) reads.add(1)
                    return r
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val r = super.read(b, off, len)
                    if (r > 0) reads.add(r.toLong())
                    return r
                }
            }
        }
    }

    private class LongAdderBox {
        @Volatile private var total = 0L
        fun add(n: Long) { total += n }
        fun get(): Long = total
    }

    private object PairingIds {
        private val random = SecureRandom()
        fun newSessionId(): Long {
            while (true) {
                val id = random.nextLong()
                if (id != 0L) return id
            }
        }
    }

    private fun freePort(): Int =
        ServerSocket(0).use { it.localPort }

    /** Blocks until the receiver's server socket is accepting connections. */
    private fun awaitPortOpen(port: Int, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("127.0.0.1", port).use { return }
            } catch (_: Exception) {
                Thread.sleep(25)
            }
        }
        throw AssertionError("receiver never opened port $port")
    }

    private fun tempFile(name: String, size: Int, seed: Int = 7): File {
        val file = Files.createTempDirectory("src").resolve(name).toFile()
        file.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            var written = 0
            var state = seed
            while (written < size) {
                for (i in buffer.indices) {
                    state = state * 1103515245 + 12345
                    buffer[i] = ((state ushr 16) % 251).toByte()
                }
                val n = minOf(buffer.size, size - written)
                out.write(buffer, 0, n)
                written += n
            }
        }
        return file
    }

    private fun runReceiver(
        harness: Harness,
        @Suppress("UNUSED_PARAMETER") engine: FastTransferEngine,
        port: Int,
        pool: ExecutorService
    ): Future<FastTransferEngine.SessionResult> =
        pool.submit(Callable {
            engine.receive(harness.newSession(engine), port, harness.sink(),
                object : FastTransferEngine.Listener { })
        })

    // ---------------------------------------------------------------- tests

    @Test
    fun `full transfer of mixed files verifies and saves byte-identical copies`() {
        val harness = Harness()
        val port = freePort()
        val pool = Executors.newCachedThreadPool()
        try {
            val small = tempFile("नोट्स.txt", 120)
            val empty = tempFile("empty.bin", 0)
            val big = tempFile("video file.mp4", 3 * 1024 * 1024 + 17)
            val engine = FastTransferEngine()
            val receiver = runReceiver(harness, engine, port, pool).also { awaitPortOpen(port) }

            val listener = RecordingListener()
            val result = engine.send(
                ConnectTarget("127.0.0.1", port, harness.sessionId, pin = harness.pin),
                listOf(FileSource(small), FileSource(empty), FileSource(big)),
                "Sender", "2.0.0-test", listener
            )

            assertTrue("send failed: ${result.category} ${result.detail}", result.ok)
            assertEquals(3, result.filesVerified)
            assertEquals(0, result.filesFailed)
            assertEquals(TransferState.COMPLETED, listener.lastState)

            val receiverResult = receiver.get(10, TimeUnit.SECONDS)
            assertTrue(receiverResult.ok)

            // Byte-identical, safe names, verified.
            assertArrayEquals(small.readBytes(), File(harness.outDir, "नोट्स.txt").readBytes())
            assertArrayEquals(big.readBytes(), File(harness.outDir, "video file.mp4").readBytes())
            assertEquals(3, harness.savedPaths.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `wrong pin fails authentication without transferring anything`() {
        val harness = Harness()
        val port = freePort()
        val pool = Executors.newCachedThreadPool()
        try {
            val file = tempFile("secret.jpg", 5000)
            val engine = FastTransferEngine()
            val receiver = runReceiver(harness, engine, port, pool).also { awaitPortOpen(port) }
            val result = engine.send(
                ConnectTarget("127.0.0.1", port, harness.sessionId, pin = "000000"),
                listOf(FileSource(file)), "Sender", "2.0.0-test",
                RecordingListener()
            )
            assertTrue(!result.ok)
            assertEquals(ErrorCategory.AUTHENTICATION_FAILED, result.category)
            assertEquals(0, harness.savedPaths.size)
            receiver.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `unknown session id is rejected`() {
        val harness = Harness()
        val port = freePort()
        val pool = Executors.newCachedThreadPool()
        try {
            val file = tempFile("a.txt", 100)
            val engine = FastTransferEngine()
            val receiver = runReceiver(harness, engine, port, pool).also { awaitPortOpen(port) }
            val result = engine.send(
                ConnectTarget("127.0.0.1", port, harness.sessionId + 1, pin = harness.pin),
                listOf(FileSource(file)), "Sender", "2.0.0-test", RecordingListener()
            )
            assertTrue(!result.ok)
            assertEquals(ErrorCategory.AUTHENTICATION_FAILED, result.category)
            assertEquals(0, harness.savedPaths.size)
            // The receiver intentionally stays available (DoS-resistant) —
            // cancel it explicitly.
            engine.cancel()
            receiver.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `mismatched pre-shared key fails authentication`() { 
        val harness = Harness()
        val port = freePort()
        val pool = Executors.newCachedThreadPool()
        try {
            val file = tempFile("a.txt", 100)
            val engine = FastTransferEngine()
            val receiver = runReceiver(harness, engine, port, pool).also { awaitPortOpen(port) }
            val evilKey = SessionCrypto.generateKeyPair(SecureRandom())
            val result = engine.send(
                ConnectTarget(
                    "127.0.0.1", port, harness.sessionId,
                    receiverPublicKey = SessionCrypto.encodePublicKey(evilKey.public as java.security.interfaces.ECPublicKey),
                    authMode = ConnectTarget.AuthMode.PRE_SHARED_KEY,
                    pin = harness.pin
                ),
                listOf(FileSource(file)), "Sender", "2.0.0-test", RecordingListener()
            )
            assertTrue(!result.ok)
            assertEquals(ErrorCategory.AUTHENTICATION_FAILED, result.category)
            receiver.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `source hiccup resumes within one send call`() {
        val harness = Harness()
        val port = freePort()
        val pool = Executors.newCachedThreadPool()
        try {
            val file = tempFile("large.bin", 2 * 1024 * 1024 + 300) // 512KiB chunks → 5 chunks
            val reads = LongAdderBox()
            var failedOnce = false
            val flakySource = object : FastTransferEngine.SendSource {
                override val metadata = FileMetadata(file.name, file.length(), "application/octet-stream")
                override fun open(offset: Long): InputStream {
                    val base = FileInputStream(file)
                    var skipped = 0L
                    while (skipped < offset) {
                        val n = base.skip(offset - skipped)
                        if (n <= 0) break
                        skipped += n
                    }
                    reads.add(skipped)
                    if (!failedOnce && offset == 0L) {
                        failedOnce = true
                        // Dies ~60% into the first attempt.
                        return object : FilterInputStream(base) {
                            var served = 0L
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                if (served > 1_300_000) throw IOException("provider hiccup")
                                val r = super.read(b, off, len)
                                if (r > 0) { served += r; reads.add(r.toLong()) }
                                return r
                            }
                        }
                    }
                    return base
                }
            }
            val engine = FastTransferEngine()
            val receiver = runReceiver(harness, engine, port, pool).also { awaitPortOpen(port) }
            val result = engine.send(
                ConnectTarget("127.0.0.1", port, harness.sessionId, pin = harness.pin),
                listOf(flakySource), "Sender", "2.0.0-test", RecordingListener()
            )
            assertTrue("resume failed: ${result.detail}", result.ok)
            assertEquals(1, result.filesVerified)
            assertArrayEquals(file.readBytes(), File(harness.outDir, file.name).readBytes())
            // The failed attempt's bytes were partially re-read, but the
            // second attempt resumed — well under 2x.
            assertTrue("re-read too much: ${reads.get()}", reads.get() < 2L * file.length())
            receiver.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `resume survives receiver restart via persisted transfer id`() {
        val harness = Harness()
        val port = freePort()
        val pool = Executors.newCachedThreadPool()
        try {
            val file = tempFile("restart.bin", 6 * 1024 * 1024 + 300_000)
            val reads = LongAdderBox()

            // ---- attempt 1: sender dies at ~40%, no retries, receiver patience short.
            var failedOnce = false
            val dyingSource = object : FastTransferEngine.SendSource {
                override val metadata = FileMetadata(file.name, file.length(), "application/octet-stream")
                override fun open(offset: Long): InputStream {
                    val base = FileInputStream(file)
                    var skipped = 0L
                    while (skipped < offset) {
                        val n = base.skip(offset - skipped)
                        if (n <= 0) break
                        skipped += n
                    }
                    reads.add(skipped)
                    if (!failedOnce && offset == 0L) {
                        failedOnce = true
                        return object : FilterInputStream(base) {
                            var served = 0L
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                if (served > 2_600_000) throw IOException("app killed")
                                val r = super.read(b, off, len)
                                if (r > 0) { served += r; reads.add(r.toLong()) }
                                return r
                            }
                        }
                    }
                    return base
                }
            }
            val engine1 = FastTransferEngine(
                FastTransferEngine.EngineConfig(sendRetries = 0, resumePatienceMs = 1500)
            )
            val tidBox = LongAdderBox()
            val receiver1 = runReceiver(harness, engine1, port, pool).also { awaitPortOpen(port) }
            val listener1 = object : FastTransferEngine.Listener {
                override fun onSessionEstablished(sessionId: Long, transferId: Int) {
                    tidBox.add(transferId.toLong())
                }
            }
            val result1 = engine1.send(
                ConnectTarget("127.0.0.1", port, harness.sessionId, pin = harness.pin),
                listOf(dyingSource), "Sender", "2.0.0-test", listener1
            )
            assertTrue(!result1.ok)
            val receiverResult1 = receiver1.get(15, TimeUnit.SECONDS)
            assertTrue(!receiverResult1.ok)
            assertEquals(0, harness.savedPaths.size)

            // ---- attempt 2: fresh receiver process (new session identity,
            // same spool root), sender reuses the transfer id.
            val engine2 = FastTransferEngine(
                FastTransferEngine.EngineConfig(acceptTimeoutMs = 5000)
            )
            val newSessionId = PairingIds.newSessionId()
            val receiver2 = pool.submit(Callable {
                engine2.receive(
                    FastTransferEngine.ReceiverSession(
                        newSessionId, harness.pin, harness.challenge, harness.keyPair,
                        harness.spoolRoot, "Receiver", "2.0.0-test"
                    ),
                    port, harness.sink(), object : FastTransferEngine.Listener { }
                )
            }).also { awaitPortOpen(port) }
            val target2 = ConnectTarget(
                "127.0.0.1", port, newSessionId, pin = harness.pin
            )
            val result2 = engine2.send(
                target2, listOf(FileSource(file, reads)), "Sender", "2.0.0-test",
                RecordingListener(), transferIdHint = tidBox.get().toInt()
            )
            assertTrue("restart resume failed: ${result2.detail}", result2.ok)
            assertArrayEquals(file.readBytes(), File(harness.outDir, file.name).readBytes())
            // 40% + 100% of the file — the first 400KB were not re-transferred.
            // Chunk-granular resume: ~2 MB of 512 KiB chunks were skipped on
            // attempt 2 — total reads stay well under 1.6x the file size.
            assertTrue("expected resume, read ${reads.get()} of ${file.length()}",
                reads.get() < (file.length() * 1.6).toLong())
            receiver2.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `insufficient storage is reported before any transfer`() {
        val harness = Harness()
        harness.failSpaceCheck = true
        val port = freePort()
        val pool = Executors.newCachedThreadPool()
        try {
            val file = tempFile("huge.zip", 100_000)
            val engine = FastTransferEngine()
            val receiver = runReceiver(harness, engine, port, pool).also { awaitPortOpen(port) }
            val result = engine.send(
                ConnectTarget("127.0.0.1", port, harness.sessionId, pin = harness.pin),
                listOf(FileSource(file)), "Sender", "2.0.0-test", RecordingListener()
            )
            assertTrue(!result.ok)
            assertEquals(ErrorCategory.INSUFFICIENT_STORAGE, result.category)
            assertEquals(1, harness.manifestSeen.size)
            assertEquals(0, harness.savedPaths.size)
            receiver.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `mid-transfer cancel returns cancelled result`() {
        val harness = Harness()
        val port = freePort()
        val pool = Executors.newCachedThreadPool()
        try {
            val file = tempFile("slow.bin", 32 * 1024 * 1024)
            // Throttled source: ~30 chunks/s max so we can cancel mid-flight.
            val slowSource = object : FastTransferEngine.SendSource {
                override val metadata = FileMetadata(file.name, file.length(), "application/octet-stream")
                override fun open(offset: Long): InputStream =
                    ThrottledInputStream(FileInputStream(file), offset, 30)
            }
            val engine = FastTransferEngine()
            val receiver = runReceiver(harness, engine, port, pool).also { awaitPortOpen(port) }
            val senderPool = Executors.newSingleThreadExecutor()
            val sender = senderPool.submit(Callable {
                engine.send(
                    ConnectTarget("127.0.0.1", port, harness.sessionId, pin = harness.pin),
                    listOf(slowSource), "Sender", "2.0.0-test", RecordingListener()
                )
            })
            Thread.sleep(800) // let it get a few chunks in, nowhere near done
            engine.cancel()
            val result = sender.get(10, TimeUnit.SECONDS)
            assertTrue(result.cancelled)
            assertEquals(ErrorCategory.CANCELLED_BY_USER, result.category)
            assertTrue("nothing should be finalized after cancel", harness.savedPaths.isEmpty())
            receiver.get(15, TimeUnit.SECONDS)
            senderPool.shutdownNow()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `hostile garbage connection does not kill the receiver`() {
        val harness = Harness()
        val port = freePort()
        val pool = Executors.newCachedThreadPool()
        try {
            val engine = FastTransferEngine(
                FastTransferEngine.EngineConfig(acceptTimeoutMs = 8000)
            )
            val receiver = runReceiver(harness, engine, port, pool).also { awaitPortOpen(port) }

            // Blast garbage at the receiver.
            repeat(3) {
                Socket("127.0.0.1", port).use { socket ->
                    socket.getOutputStream().write(ByteArray(256) { (it * 13).toByte() })
                    socket.getOutputStream().flush()
                }
            }

            // The real sender still completes afterwards.
            val file = tempFile("ok.txt", 50_000)
            val result = engine.send(
                ConnectTarget("127.0.0.1", port, harness.sessionId, pin = harness.pin),
                listOf(FileSource(file)), "Sender", "2.0.0-test", RecordingListener()
            )
            assertTrue("receiver should survive garbage: ${result.detail}", result.ok)
            assertArrayEquals(file.readBytes(), File(harness.outDir, file.name).readBytes())
            receiver.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `progress reports queue summary`() {
        val harness = Harness()
        val port = freePort()
        val pool = Executors.newCachedThreadPool()
        try {
            val a = tempFile("a.bin", 200_000)
            val b = tempFile("b.bin", 200_000)
            val engine = FastTransferEngine()
            val receiver = runReceiver(harness, engine, port, pool).also { awaitPortOpen(port) }
            val listener = RecordingListener()
            val result = engine.send(
                ConnectTarget("127.0.0.1", port, harness.sessionId, pin = harness.pin),
                listOf(FileSource(a), FileSource(b)), "Sender", "2.0.0-test", listener
            )
            assertTrue(result.ok)
            val last = listener.lastProgress
            assertNotNull(last)
            assertEquals(2, last!!.filesTotal)
            assertEquals(2, last.filesVerified)
            assertEquals(400_000L, last.bytesTransferred)
            receiver.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    // -------------------------------------------------------------- helpers

    private class ThrottledInputStream(
        input: InputStream,
        skipTo: Long,
        private val chunksPerSecond: Int
    ) : FilterInputStream(skipFully(input, skipTo)) {
        private var sinceLast = 0L
        private var count = 0

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            maybeThrottle()
            val r = super.read(b, off, len)
            if (r > 0) count += r
            return r
        }

        private fun maybeThrottle() {
            if (count >= 64 * 1024) {
                count = 0
                val elapsed = System.currentTimeMillis() - sinceLast
                val target = 1000L / chunksPerSecond
                if (elapsed < target) {
                    try { Thread.sleep(target - elapsed) } catch (_: InterruptedException) { }
                }
                sinceLast = System.currentTimeMillis()
            }
        }
    }

    private companion object {
        fun skipFully(input: InputStream, to: Long): InputStream {
            var skipped = 0L
            while (skipped < to) {
                val n = input.skip(to - skipped)
                if (n <= 0) break
                skipped += n
            }
            return input
        }
    }

    private class RecordingListener : FastTransferEngine.Listener {
        @Volatile var lastState: TransferState = TransferState.IDLE
        @Volatile var lastProgress: TransferProgress? = null
        override fun onState(state: TransferState, reason: String) { lastState = state }
        override fun onProgress(progress: TransferProgress) { lastProgress = progress }
    }
}
