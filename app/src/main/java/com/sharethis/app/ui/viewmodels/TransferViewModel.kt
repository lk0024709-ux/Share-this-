package com.sharethis.app.ui.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sharethis.app.core.engine.FastTransferEngine
import com.sharethis.app.core.network.WifiLockManager
import com.sharethis.app.core.storage.StorageBridge
import com.sharethis.app.data.enums.TransferState
import com.sharethis.app.data.models.DevicePeer
import com.sharethis.app.data.models.FileMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Owns the transfer session: socket engine + storage sink + Wi-Fi lock.
 * Survives rotation; cancelled when the UI is finished for good.
 */
class TransferViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = FastTransferEngine()
    private val storage = StorageBridge(app.applicationContext)
    private val wifiLock = WifiLockManager(app.applicationContext)

    val state: StateFlow<TransferState> = engine.state
    val progress = engine.progress

    private val _savedFiles = MutableStateFlow<List<String>>(emptyList())
    val savedFiles: StateFlow<List<String>> = _savedFiles

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var sessionJob: Job? = null

    // ---------------------------------------------------------------- sender

    fun sendFiles(peer: DevicePeer, uris: List<Uri>) {
        if (sessionJob?.isActive == true) return
        _error.value = null
        sessionJob = viewModelScope.launch {
            wifiLock.acquire()
            try {
                val requests = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val meta = storage.probeMetadata(uri)
                        FastTransferEngine.SendRequest(meta) { storage.openInput(uri) }
                    }
                }
                if (requests.isEmpty()) {
                    _error.value = "No files to send"
                    return@launch
                }
                val unknownSizes = requests.filter { it.metadata.fileSize <= 0L }
                if (unknownSizes.isNotEmpty() && requests.size == unknownSizes.size) {
                    // All sizes unknown (rare provider) — engine still streams,
                    // progress percent just stays indeterminate.
                }
                val ok = engine.send(peer.ipAddress, peer.port, requests)
                if (!ok && engine.state.value == TransferState.ERROR) {
                    _error.value = "Transfer failed — keep devices close and retry"
                }
            } catch (e: IOException) {
                _error.value = "Storage error: ${e.message}"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _error.value = e.message ?: "Send failed"
            } finally {
                wifiLock.release()
            }
        }
    }

    // --------------------------------------------------------------- receiver

    fun startReceiving(port: Int = FastTransferEngine.DEFAULT_TCP_PORT) {
        if (sessionJob?.isActive == true) return
        _error.value = null
        _savedFiles.value = emptyList()
        sessionJob = viewModelScope.launch {
            wifiLock.acquire()
            try {
                val saved = ArrayList<String>()
                val targets = HashMap<String, StorageBridge.OutputTarget>()
                val sink = object : FastTransferEngine.ReceiveSink {
                    override fun createOutput(metadata: FileMetadata): java.io.OutputStream {
                        val target = storage.createOutput(metadata.fileName, metadata.mimeType)
                        targets[metadata.fileName] = target
                        return target.stream
                    }

                    override fun onFileComplete(metadata: FileMetadata, verified: Boolean) {
                        val target = targets.remove(metadata.fileName)
                        if (verified && target != null) {
                            saved.add(target.displayPath)
                        } else if (target != null) {
                            storage.deleteTarget(target) // drop corrupt partial
                        }
                    }

                    override fun onFileFailed(metadata: FileMetadata, error: Throwable) {
                        targets.remove(metadata.fileName)?.let { storage.deleteTarget(it) }
                    }
                }
                val ok = engine.receive(port, sink)
                _savedFiles.value = saved.toList()
                if (!ok && engine.state.value == TransferState.ERROR) {
                    _error.value = "Receive failed — checksum mismatch or connection drop"
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _error.value = e.message ?: "Receive failed"
            } finally {
                wifiLock.release()
            }
        }
    }

    // ---------------------------------------------------------------- control

    fun cancel() {
        engine.cancel()
        sessionJob?.cancel()
    }

    fun reset() {
        engine.cancel()
        sessionJob?.cancel()
        sessionJob = null
        engine.reset()
        _savedFiles.value = emptyList()
        _error.value = null
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        engine.cancel()
        engine.release()
        wifiLock.forceRelease()
        super.onCleared()
    }
}
