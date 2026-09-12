package com.sharethis.app.ui.viewmodels

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sharethis.app.core.engine.FastTransferEngine
import com.sharethis.app.core.network.NetworkBandManager
import com.sharethis.app.core.network.PinPairingEngine
import com.sharethis.app.core.pairing.BluetoothPairingManager
import com.sharethis.app.core.pairing.PairingPayload
import com.sharethis.app.core.pairing.QrCodePayloadHandler
import com.sharethis.app.data.enums.PairingMode
import com.sharethis.app.data.models.ConnectTarget
import com.sharethis.app.data.models.DevicePeer
import com.sharethis.app.data.models.NetworkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns all pairing transports — PIN broadcast, QR payloads, the Bluetooth
 * handshake and hotspot start/stop on the receiver.
 *
 * v2: every successful pairing produces a [ConnectTarget] carrying the
 * session id, PIN (always bound into the key derivation) and, when the
 * channel was out-of-band (QR / Bluetooth), the receiver's ephemeral public
 * key — so the TCP handshake can authenticate against a MITM substitution.
 */
class PairingViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface PairingUiState {
        data object Idle : PairingUiState
        data class Working(val message: String) : PairingUiState
        data class PeerFound(
            val peer: DevicePeer,
            val via: PairingMode,
            /** v2: endpoint + session material for the encrypted handshake. */
            val target: ConnectTarget? = null
        ) : PairingUiState
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

    /** Last connect target produced by a pairing flow (consumed on send). */
    private val _lastTarget = MutableStateFlow<ConnectTarget?>(null)
    val lastTarget: StateFlow<ConnectTarget?> = _lastTarget

    private var pinJob: Job? = null
    private var btJob: Job? = null

    // -------------------------------------------------------------- receiver

    fun is5GhzCapable(): Boolean = netBand.is5GHzSupported()

    fun bandSummary(): String = netBand.bandSummary()

    fun deviceName(): String = netBand.deviceName()

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

    /**
     * Builds the v2 pairing payload (session id, PIN, receiver public key,
     * endpoint, hotspot credentials, expiry) for the QR code and the
     * Bluetooth standby channel. [sessionInfo] comes from
     * [TransferViewModel.prepareReceiverSession].
     */
    fun buildPairingPayload(
        sessionInfo: TransferViewModel.ReceiverSessionInfo
    ): PairingPayload.Payload {
        val config = _hotspotConfig.value ?: NetworkConfig(
            ssid = "", passphrase = "", ipAddress = "0.0.0.0", port = sessionInfo.port
        )
        return PairingPayload.build(
            sessionId = sessionInfo.sessionId,
            challenge = sessionInfo.receiverChallenge,
            pin = sessionInfo.pin,
            receiverPublicKey = sessionInfo.receiverPublicKey,
            ipAddress = config.ipAddress,
            port = sessionInfo.port,
            ssid = config.ssid,
            passphrase = config.passphrase,
            band = config.band,
            deviceName = netBand.deviceName(),
            security = config.security
        )
    }

    /** Background PIN listener — replies to senders matching our session. */
    fun listenForSender(
        sessionId: Long,
        challenge: ByteArray,
        timeoutMs: Long = 300_000L
    ) {
        pinJob?.cancel()
        val pin = _pinCode.value ?: return
        val config = _hotspotConfig.value ?: return
        pinJob = viewModelScope.launch(Dispatchers.IO) {
            val sender = pinEngine.listenForPin(
                expectedPin = pin,
                tcpPort = config.port,
                deviceName = netBand.deviceName(),
                band = config.band,
                sessionId = sessionId,
                challenge = challenge,
                timeoutMs = timeoutMs
            )
            if (sender != null) {
                _uiState.value = PairingUiState.SenderMatched(sender)
            }
        }
    }

    /** Receiver-side Bluetooth standby: push the v2 payload to one sender. */
    fun startBluetoothStandby(pairingPayloadJson: String) {
        btJob?.cancel()
        btJob = viewModelScope.launch {
            _uiState.value = PairingUiState.Working("Bluetooth standby — waiting for sender…")
            when (val result = btManager.hostAndSendConfig(pairingPayloadJson)) {
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
                _lastTarget.value = ConnectTarget(
                    host = offer.peer.ipAddress,
                    port = offer.peer.port,
                    sessionId = offer.sessionId,
                    pin = pin,
                    transportLabel = "PIN"
                )
                _uiState.value =
                    PairingUiState.PeerFound(offer.peer, PairingMode.PIN_CODE, _lastTarget.value)
            } else {
                _uiState.value = PairingUiState.Failed(
                    "No receiver answered — join its hotspot, then check the PIN"
                )
            }
        }
    }

    /**
     * v2 payload (QR / Bluetooth): join the receiver hotspot when the payload
     * carries one, then surface the connect target with the receiver's
     * authenticated public key for the ECDH handshake.
     */
    fun joinFromPairing(payload: PairingPayload.Payload, via: PairingMode) {
        viewModelScope.launch {
            val peer = DevicePeer(
                deviceName = payload.deviceName.ifEmpty { payload.ipAddress },
                ipAddress = payload.ipAddress,
                port = payload.port,
                band = payload.band,
                pairingMode = via
            )
            val target = ConnectTarget(
                host = payload.ipAddress,
                port = payload.port,
                sessionId = payload.sessionId,
                pin = payload.pin,
                receiverPublicKey = payload.receiverPublicKey,
                receiverChallenge = payload.challenge,
                authMode = if (payload.receiverPublicKey != null) {
                    ConnectTarget.AuthMode.PRE_SHARED_KEY
                } else {
                    ConnectTarget.AuthMode.PIN
                },
                transportLabel = via.title
            )
            if (payload.ssid.isBlank()) {
                // Same-LAN receiver (no hotspot join needed).
                _lastTarget.value = target
                _uiState.value = PairingUiState.PeerFound(peer, via, target)
                return@launch
            }
            _uiState.value = PairingUiState.Working("Joining ${payload.ssid}…")
            val config = NetworkConfig(
                ssid = payload.ssid,
                passphrase = payload.passphrase,
                ipAddress = payload.ipAddress,
                port = payload.port,
                band = payload.band,
                deviceName = payload.deviceName,
                security = payload.security
            )
            when (val result = netBand.connectToHotspot(config)) {
                is NetworkBandManager.JoinResult.Connected -> {
                    _lastTarget.value = target
                    _uiState.value = PairingUiState.PeerFound(peer, via, target)
                }
                is NetworkBandManager.JoinResult.Failed ->
                    _uiState.value = PairingUiState.Failed(result.reason)
            }
        }
    }

    /** Legacy (v1) QR payload — hotspot credentials only, PIN flow follows. */
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
                    _uiState.value = PairingUiState.PeerFound(peer, via, null)
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
                    // v2 payload first (carries the session crypto material).
                    val payload = PairingPayload.decode(result.payloadJson)
                    val v2 = payload as? PairingPayload.DecodeResult.Ok
                    if (v2 != null) {
                        if (v2.payload.isExpired) {
                            _uiState.value = PairingUiState.Failed(
                                "The receiver's pairing window expired — ask it to restart standby"
                            )
                        } else {
                            joinFromPairing(v2.payload, PairingMode.BLUETOOTH)
                        }
                        return@launch
                    }
                    val legacy = QrCodePayloadHandler.QrPayload.fromJson(result.payloadJson)
                    if (legacy == null) {
                        _uiState.value = PairingUiState.Failed("Receiver sent a bad handshake")
                    } else {
                        joinHotspotPayload(legacy, PairingMode.BLUETOOTH)
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
        _lastTarget.value = null
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
