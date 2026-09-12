package com.sharethis.app.ui.viewmodels

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sharethis.app.core.engine.FastTransferEngine
import com.sharethis.app.core.engine.TransferProgress
import com.sharethis.app.core.network.WifiLockManager
import com.sharethis.app.core.pairing.PairingPayload
import com.sharethis.app.core.security.SessionCrypto
import com.sharethis.app.core.storage.ReceiveSinkAdapter
import com.sharethis.app.core.storage.StorageBridge
import com.sharethis.app.core.service.TransferForegroundService
import com.sharethis.app.data.models.DuplicatePolicy
import com.sharethis.app.data.enums.QueueItemStatus
import com.sharethis.app.data.enums.TransferState
import com.sharethis.app.data.models.ConnectTarget
import com.sharethis.app.data.models.FileMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom

/**
 * Owns the transfer session against the v2 [FastTransferEngine]:
 * encrypted authenticated socket + storage sink + Wi-Fi lock + foreground
 * service lifecycle. Survives rotation; the foreground service keeps the
 * process important while a transfer runs with the screen off.
 *
 * Observables keep the same names the UI already binds to
 * (state / progress / savedFiles / error) — plus a per-file queue for
 * "Sending 3 of 17" style summaries.
 */
class TransferViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "TransferViewModel"
        private const val APP_VERSION = "2.0.0"
    }

    /** Per-file queue row for the UI. */
    data class QueueEntry(
        val fileId: Int,
        val fileName: String,
        val status: QueueItemStatus,
        val detail: String = ""
    )

    /** Receiver session material generated locally, before the port opens. */
    data class ReceiverSessionInfo(
        val sessionId: Long,
        val pin: String,
        val port: Int,
        val receiverChallenge: ByteArray,
        /** X.509-encoded ephemeral public key — goes into the QR/BT payload. */
        val receiverPublicKey: ByteArray
    )

    private val engine = FastTransferEngine()
    private val storage = StorageBridge(app.applicationContext)
    private val wifiLock = WifiLockManager(app.applicationContext)
    private val random = SecureRandom()

    private val _state = MutableStateFlow(TransferState.IDLE)
    val state: StateFlow<TransferState> = _state

    private val _progress = MutableStateFlow(TransferProgress())
    val progress: StateFlow<TransferProgress> = _progress

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue: StateFlow<List<QueueEntry>> = _queue

    private val _savedFiles = MutableStateFlow<List<String>>(emptyList())
    val savedFiles: StateFlow<List<String>> = _savedFiles

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var sessionJob: Job? = null
    private var preparedSession: FastTransferEngine.ReceiverSession? = null
    private var preparedPort: Int = 0
    private var fileNamesById: Map<Int, String> = emptyMap()
    private val lastResult = MutableStateFlow<FastTransferEngine.SessionResult?>(null)

    val duplicatePolicy: DuplicatePolicy = DuplicatePolicy.KEEP_BOTH

    /** Last session outcome (null while a session is still running). */
    val sessionResult: StateFlow<FastTransferEngine.SessionResult?> = lastResult

    private val engineListener = object : FastTransferEngine.Listener {
        override fun onState(state: TransferState, reason: String) {
            _state.value = state
            if (state.isTerminal) {
                TransferForegroundService.stop(app())
            } else {
                TransferForegroundService.update(app(), state, _progress.value)
            }
        }

        override fun onProgress(progress: TransferProgress) {
            _progress.value = progress
            if (!_state.value.isTerminal) {
                TransferForegroundService.update(app(), _state.value, progress)
            }
        }

        override fun onQueueItem(fileId: Int, status: QueueItemStatus, detail: String) {
            val name = fileNamesById[fileId] ?: "file ${fileId + 1}"
            val current = _queue.value.toMutableList()
            val index = current.indexOfFirst { it.fileId == fileId }
            val entry = QueueEntry(fileId, name, status, detail)
            if (index >= 0) current[index] = entry else current.add(entry)
            _queue.value = current
        }
    }

    // ---------------------------------------------------------------- sender

    fun sendFiles(target: ConnectTarget, uris: List<Uri>) {
        if (sessionJob?.isActive == true) return
        _error.value = null
        lastResult.value = null
        _queue.value = emptyList()
        _savedFiles.value = emptyList()
        sessionJob = viewModelScope.launch {
            wifiLock.acquire()
            TransferForegroundService.ActiveTransfer.engine = engine
            TransferForegroundService.start(app(), true)
            try {
                val sources = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri -> buildSendSource(uri) }
                }
                if (sources.isEmpty()) {
                    _error.value = "No files could be read — pick the files again"
                    return@launch
                }
                fileNamesById = sources.withIndex().associate { (i, s) -> i to s.metadata.fileName }
                val result = withContext(Dispatchers.IO) {
                    engine.send(target, sources, deviceName(), APP_VERSION, engineListener)
                }
                handleResult(result)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "send failed", e)
                _error.value = friendly(e)
            } finally {
                wifiLock.release()
                TransferForegroundService.stop(app())
            }
        }
    }

    // --------------------------------------------------------------- receiver

    /**
     * Generates the local half of the secure session (session id, PIN bound
     * into the KDF, ephemeral ECDH key pair, nonce challenge). Call before
     * [startReceiving]; the returned info feeds the QR / Bluetooth payloads.
     */
    fun prepareReceiverSession(pin: String, port: Int): ReceiverSessionInfo {
        val sessionId = PairingPayload.newSessionId()
        val challenge = PairingPayload.newChallenge()
        val keyPair = SessionCrypto.generateKeyPair(random)
        preparedPort = port
        preparedSession = FastTransferEngine.ReceiverSession(
            sessionId = sessionId,
            pin = pin,
            receiverChallenge = challenge,
            receiverKeyPair = keyPair,
            spoolRoot = spoolRoot(),
            deviceName = deviceName(),
            appVersion = APP_VERSION
        )
        return ReceiverSessionInfo(
            sessionId = sessionId,
            pin = pin,
            port = port,
            receiverChallenge = challenge,
            receiverPublicKey = keyPair.public.encoded
        )
    }

    fun startReceiving() {
        if (sessionJob?.isActive == true) return
        val session = preparedSession ?: run {
            _error.value = "Session not prepared — start receiving again"
            return
        }
        _error.value = null
        lastResult.value = null
        _queue.value = emptyList()
        _savedFiles.value = emptyList()
        val saved = ArrayList<String>()
        sessionJob = viewModelScope.launch {
            wifiLock.acquire()
            TransferForegroundService.ActiveTransfer.engine = engine
            TransferForegroundService.start(app(), false)
            try {
                val sink = ReceiveSinkAdapter(
                    app().applicationContext,
                    policy = duplicatePolicy,
                    events = object : ReceiveSinkAdapter.Events {
                        override fun onSaved(displayPath: String, metadata: FileMetadata) {
                            synchronized(saved) { saved.add(displayPath) }
                            _savedFiles.value = synchronized(saved) { saved.toList() }
                        }
                    }
                )
                val result = withContext(Dispatchers.IO) {
                    engine.receive(session, preparedPort, sink, engineListener)
                }
                handleResult(result)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "receive failed", e)
                _error.value = friendly(e)
            } finally {
                wifiLock.release()
                TransferForegroundService.stop(app())
            }
        }
    }

    // ---------------------------------------------------------------- control

    fun pause() = engine.pause()
    fun resume() = engine.resume()

    fun cancel() {
        engine.cancel()
        // The engine unblocks on its own; the coroutine observes the
        // cancelled result. Force-cut only if it somehow hangs.
        sessionJob?.let { job ->
            viewModelScope.launch {
                kotlinx.coroutines.delay(2_000)
                if (job.isActive) job.cancel()
            }
        }
    }

    fun reset() {
        engine.cancel()
        sessionJob?.cancel()
        sessionJob = null
        preparedSession = null
        preparedPort = 0
        _state.value = TransferState.IDLE
        _progress.value = TransferProgress()
        _queue.value = emptyList()
        _savedFiles.value = emptyList()
        _error.value = null
        lastResult.value = null
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        engine.cancel()
        wifiLock.forceRelease()
        TransferForegroundService.stop(app())
        super.onCleared()
    }

    // --------------------------------------------------------------- helpers

    private fun handleResult(result: FastTransferEngine.SessionResult) {
        lastResult.value = result
        if (!result.ok && !result.cancelled && !result.paused) {
            _error.value = result.category.userMessage
            if (result.detail.isNotBlank()) {
                Log.w(TAG, "session failed: ${result.category}: ${result.detail}")
            }
        }
    }

    private fun app(): Application = getApplication()

    private fun deviceName(): String = try {
        android.os.Build.MODEL ?: "Android device"
    } catch (_: Exception) {
        "Android device"
    }

    /** Spool survives process death (resume) — keep it in filesDir, not cache. */
    private fun spoolRoot(): File = File(getApplication<Application>().filesDir, "spool")

    /**
     * Builds a resumable [FastTransferEngine.SendSource] for any picker Uri.
     * Providers that cannot report a size (rare streaming sources) are
     * spooled to a private temp file first so the manifest always carries an
     * exact byte length.
     */
    private fun buildSendSource(uri: Uri): FastTransferEngine.SendSource? {
        return try {
            val meta = storage.probeMetadata(uri)
            if (meta.fileSize > 0L) {
                UriSendSource(meta, storage, uri, null)
            } else if (meta.fileSize == 0L && storage.exactSizeBytes(uri) == 0L) {
                // Genuinely empty — send as-is.
                UriSendSource(meta, storage, uri, null)
            } else {
                // Unknown size — spool first.
                val spooled = spoolUnknownSize(uri) ?: return null
                UriSendSource(
                    FileMetadata(meta.fileName, spooled.length(), meta.mimeType),
                    storage, uri, spooled
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot probe $uri", e)
            null
        }
    }

    private fun spoolUnknownSize(uri: Uri): File? {
        val dir = File(getApplication<Application>().cacheDir, "send-spool").apply {
            if (!exists()) mkdirs()
        }
        val target = File(dir, "src-${System.currentTimeMillis()}-${random.nextInt(1_000_000)}")
        return try {
            storage.openInput(uri).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, 256 * 1024)
                }
            }
            target
        } catch (e: Exception) {
            Log.w(TAG, "cannot spool $uri", e)
            target.delete()
            null
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is IOException -> "Storage or connection error: ${e.message ?: "please retry"}"
        is SecurityException ->
            "A required permission was denied. You can grant it or use another pairing method."
        else -> "Something went wrong. Please try again."
    }

    /**
     * Opens a content Uri at a resume offset. [skip] is looped because
     * InputStream.skip may return early on some providers.
     */
    private class UriSendSource(
        override val metadata: FileMetadata,
        private val bridge: StorageBridge,
        private val uri: Uri,
        private val spooledFile: File?
    ) : FastTransferEngine.SendSource {

        override fun open(offset: Long): InputStream {
            val stream = if (spooledFile != null) {
                FileInputStream(spooledFile)
            } else {
                bridge.openInput(uri)
            }
            if (offset > 0L) {
                try {
                    var skipped = 0L
                    while (skipped < offset) {
                        val n = stream.skip(offset - skipped)
                        if (n <= 0L) {
                            // Fall back to byte-wise skipping.
                            var byte = stream.read()
                            if (byte < 0) break
                            skipped++
                        } else {
                            skipped += n
                        }
                    }
                    if (skipped < offset) {
                        stream.close()
                        throw IOException("Cannot seek to resume offset $offset")
                    }
                } catch (e: Exception) {
                    try { stream.close() } catch (_: Exception) { }
                    throw e
                }
            }
            return stream
        }
    }
}
