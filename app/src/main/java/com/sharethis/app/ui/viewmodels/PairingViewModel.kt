package com.sharethis.app.ui.viewmodels

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sharethis.app.core.engine.FastTransferEngine
import com.sharethis.app.core.network.NetworkBandManager
import com.sharethis.app.core.network.PinPairingEngine
import com.sharethis.app.core.pairing.BluetoothPairingManager
import com.sharethis.app.core.pairing.QrCodePayloadHandler
import com.sharethis.app.data.enums.PairingMode
import com.sharethis.app.data.models.DevicePeer
import com.sharethis.app.data.models.NetworkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns all three pairing transports: PIN broadcast, QR payloads and the
 * Bluetooth handshake — plus hotspot start/stop on the receiver.
 */
class PairingViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface PairingUiState {
        data object Idle : PairingUiState
        data class Working(val message: String) : PairingUiState
        data class PeerFound(val peer: DevicePeer, val via: PairingMode) : PairingUiState
        data class HotspotReady(val config: NetworkConfig) : PairingUiState
        data class SenderMatched(val deviceName: String) : PairingUiState
        data class Failed(val message: String) : PairingUiState
    }

    private val netBand = NetworkBandManager(app.applicationContext)
    private val pinEngine = PinPairingEngine()
    private val btManager = BluetoothPairingManager(app.applicationContext)

    private val _uiState = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val uiState: StateFlow<PairingUiState> = _uiState

    private val _pinCode = MutableStateFlow<String?>(null)
    val pinCode: StateFlow<String?> = _pinCode

    private val _hotspotConfig = MutableStateFlow<NetworkConfig?>(null)
    val hotspotConfig: StateFlow<NetworkConfig?> = _hotspotConfig

    val btDiscovered: StateFlow<List<BluetoothPairingManager.BtDevice>> = btManager.discovered
    val btDiscovering: StateFlow<Boolean> = btManager.discovering

    private var pinJob: Job? = null
    private var btJob: Job? = null

    // -------------------------------------------------------------- receiver

    fun is5GhzCapable(): Boolean = netBand.is5GHzSupported()

    fun bandSummary(): String = netBand.bandSummary()

    fun prepareReceiver(tcpPort: Int = FastTransferEngine.DEFAULT_TCP_PORT) {
        viewModelScope.launch {
            _uiState.value = PairingUiState.Working("Starting hotspot…")
            val pin = PinPairingEngine.generatePin()
            _pinCode.value = pin
            when (val result = netBand.startHotspot(prefer5Ghz = true, port = tcpPort)) {
                is NetworkBandManager.HotspotResult.Started -> {
                    _hotspotConfig.value = result.config
                    _uiState.value = PairingUiState.HotspotReady(result.config)
                }
                is NetworkBandManager.HotspotResult.Failed -> {
                    _uiState.value = PairingUiState.Failed(result.reason)
                }
            }
        }
    }

    /** Background PIN listener — informational (receiver hosts the server). */
    fun listenForSender(timeoutMs: Long = 300_000L) {
        pinJob?.cancel()
        val pin = _pinCode.value ?: return
        val config = _hotspotConfig.value ?: return
        pinJob = viewModelScope.launch(Dispatchers.IO) {
            val sender = pinEngine.listenForPin(
                expectedPin = pin,
                tcpPort = config.port,
                deviceName = netBand.deviceName(),
                band = config.band,
                timeoutMs = timeoutMs
            )
            if (sender != null) {
                _uiState.value = PairingUiState.SenderMatched(sender)
            }
        }
    }

    /** Receiver-side Bluetooth standby: push hotspot payload to one sender. */
    fun startBluetoothStandby() {
        btJob?.cancel()
        val config = _hotspotConfig.value ?: return
        val payload = QrCodePayloadHandler.QrPayload.fromNetworkConfig(config).toJson()
        btJob = viewModelScope.launch {
            _uiState.value = PairingUiState.Working("Bluetooth standby — waiting for sender…")
            when (val result = btManager.hostAndSendConfig(payload)) {
                is BluetoothPairingManager.BtResult.ConfigSent ->
                    _uiState.value = PairingUiState.SenderMatched(result.deviceName)
                is BluetoothPairingManager.BtResult.Failed ->
                    _uiState.value = PairingUiState.Failed(result.reason)
                else -> Unit
            }
        }
    }

    fun stopBluetoothStandby() {
        btJob?.cancel()
        btJob = null
        btManager.shutdownRadio()
    }

    // ---------------------------------------------------------------- sender

    fun discoverWithPin(pin: String, timeoutMs: Long = 20_000L) {
        viewModelScope.launch {
            _uiState.value = PairingUiState.Working("Broadcasting PIN — searching for receiver…")
            val offer = try {
                pinEngine.discoverByPin(pin, netBand.deviceName(), timeoutMs)
            } catch (e: Exception) {
                _uiState.value = PairingUiState.Failed(e.message ?: "Discovery failed")
                return@launch
            }
            if (offer != null) {
                _uiState.value = PairingUiState.PeerFound(offer.peer, PairingMode.PIN_CODE)
            } else {
                _uiState.value = PairingUiState.Failed(
                    "No receiver answered — join its hotspot, then check the PIN"
                )
            }
        }
    }

    fun joinHotspotPayload(payload: QrCodePayloadHandler.QrPayload, via: PairingMode) {
        viewModelScope.launch {
            _uiState.value = PairingUiState.Working("Joining ${payload.ssid}…")
            when (val result = netBand.connectToHotspot(payload.toNetworkConfig())) {
                is NetworkBandManager.JoinResult.Connected -> {
                    val peer = DevicePeer(
                        deviceName = payload.deviceName.ifEmpty { payload.ip },
                        ipAddress = payload.ip,
                        port = payload.port,
                        band = payload.band,
                        pairingMode = via
                    )
                    _uiState.value = PairingUiState.PeerFound(peer, via)
                }
                is NetworkBandManager.JoinResult.Failed ->
                    _uiState.value = PairingUiState.Failed(result.reason)
            }
        }
    }

    fun joinViaBluetooth(address: String) {
        viewModelScope.launch {
            _uiState.value = PairingUiState.Working("Bluetooth handshake…")
            when (val result = btManager.connectAndReceiveConfig(address)) {
                is BluetoothPairingManager.BtResult.ConfigReceived -> {
                    val payload = QrCodePayloadHandler.QrPayload.fromJson(result.payloadJson)
                    if (payload == null) {
                        _uiState.value = PairingUiState.Failed("Sender sent a bad handshake")
                    } else {
                        joinHotspotPayload(payload, PairingMode.BLUETOOTH)
                    }
                }
                is BluetoothPairingManager.BtResult.Failed ->
                    _uiState.value = PairingUiState.Failed(result.reason)
                else -> Unit
            }
        }
    }

    // ------------------------------------------------------------- bluetooth

    fun isBluetoothSupported(): Boolean = btManager.isSupported()

    fun isBluetoothEnabled(): Boolean = btManager.isEnabled()

    fun bluetoothEnableIntent(): Intent = btManager.enableIntent()

    fun bluetoothDiscoverableIntent(): Intent = btManager.discoverableIntent()

    fun bondedDevices(): List<BluetoothPairingManager.BtDevice> = btManager.bondedDevices()

    fun startBtDiscovery() = btManager.startDiscovery()

    fun stopBtDiscovery() = btManager.stopDiscovery()

    // ----------------------------------------------------------------- reset

    fun setIdle() {
        _uiState.value = PairingUiState.Idle
    }

    fun stopAll() {
        pinJob?.cancel()
        pinJob = null
        btJob?.cancel()
        btJob = null
        btManager.shutdownRadio()
        netBand.stopHotspot()
        netBand.disconnectFromHotspot()
        _pinCode.value = null
        _hotspotConfig.value = null
        _uiState.value = PairingUiState.Idle
    }

    override fun onCleared() {
        pinJob?.cancel()
        btJob?.cancel()
        btManager.shutdownRadio()
        netBand.stopHotspot()
        netBand.disconnectFromHotspot()
        super.onCleared()
    }
}
