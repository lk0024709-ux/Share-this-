package com.sharethis.app.ui.receive

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.sharethis.app.core.engine.TransferProgress
import com.sharethis.app.core.pairing.PairingPayload
import com.sharethis.app.core.pairing.QrCodePayloadHandler
import com.sharethis.app.ui.viewmodels.TransferViewModel
import com.sharethis.app.core.permissions.PermissionManager
import com.sharethis.app.data.enums.TransferState
import com.sharethis.app.data.models.NetworkConfig
import com.sharethis.app.databinding.ActivityReceiveBinding
import com.sharethis.app.ui.viewmodels.PairingViewModel
import com.sharethis.app.ui.viewmodels.TransferViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receiver flow: start hotspot → show PIN + QR + credentials → accept one
 * sender → verify checksums → land files in Download/ShareThis.
 */
class ReceiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiveBinding
    private lateinit var transferVM: TransferViewModel
    private lateinit var pairingVM: PairingViewModel

    private var pendingWifiAction: (() -> Unit)? = null
    private var pendingBtAction: (() -> Unit)? = null
    private var sessionActive = false
    private var btStandbyOn = false
    private var serverStarted = false
    private var sessionInfo: TransferViewModel.ReceiverSessionInfo? = null

    private val wifiPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val action = pendingWifiAction
            pendingWifiAction = null
            if (grants.values.all { it }) {
                action?.invoke()
            } else {
                toast("Nearby Wi-Fi permission is needed to host the hotspot")
            }
        }

    private val btPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val action = pendingBtAction
            pendingBtAction = null
            if (grants.values.all { it }) {
                action?.invoke()
            } else {
                toast("Bluetooth permission is needed for Bluetooth standby")
            }
        }

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { /* best effort */ }

    private val btEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val action = pendingBtAction
            pendingBtAction = null
            if (result.resultCode == RESULT_OK) action?.invoke()
        }

    // -------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Receive files"

        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        transferVM = ViewModelProvider(this, factory)[TransferViewModel::class.java]
        pairingVM = ViewModelProvider(this, factory)[PairingViewModel::class.java]

        binding.btnStartReceive.setOnClickListener { startReceiving() }
        binding.btnStopReceive.setOnClickListener { stopAll() }
        binding.btnReceiveReset.setOnClickListener { resetUi() }
        binding.btnBtStandby.setOnClickListener { toggleBtStandby() }

        observeViewModels()
        resetUi()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        transferVM.cancel()
        pairingVM.stopAll()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- session

    private fun startReceiving() {
        ensureWifiPermissions {
            ensureLegacyWritePermissions {
                sessionActive = true
                serverStarted = false
                binding.btnStartReceive.visibility = View.GONE
                binding.btnStopReceive.visibility = View.VISIBLE
                binding.cardHotspot.visibility = View.VISIBLE
                binding.tvWaitingStatus.text = "Starting hotspot…"
                pairingVM.prepareReceiver()
            }
        }
    }

    private fun stopAll() {
        transferVM.cancel()
        pairingVM.stopAll()
        sessionActive = false
        serverStarted = false
        btStandbyOn = false
        binding.tvWaitingStatus.text = "Stopped"
        binding.btnStartReceive.visibility = View.VISIBLE
        binding.btnStopReceive.visibility = View.GONE
        binding.btnBtStandby.text = "Bluetooth standby: off"
    }

    private fun resetUi() {
        transferVM.reset()
        binding.radialProgressRecv.reset()
        binding.tvRecvSpeed.text = "—"
        binding.tvRecvEta.text = ""
        binding.tvRecvFile.text = "Waiting for sender…"
        binding.tvSavedFiles.text = ""
        binding.cardHotspot.visibility = View.GONE
        binding.ivQr.setImageDrawable(null)
        binding.tvPinCode.text = "––––––"
        binding.btnStartReceive.visibility = View.VISIBLE
        binding.btnStopReceive.visibility = View.GONE
        binding.btnReceiveReset.visibility = View.GONE
        binding.tvWaitingStatus.text = "Tap Start to host a session"
    }

    // --------------------------------------------------------------- pairing

    private fun observeViewModels() {
        lifecycleScope.launch {
            pairingVM.uiState.collect { handlePairingState(it) }
        }
        lifecycleScope.launch {
            pairingVM.pinCode.collect { pin ->
                binding.tvPinCode.text = pin?.let { "${it.take(3)}-${it.drop(3)}" } ?: "––––––"
            }
        }
        lifecycleScope.launch {
            transferVM.state.collect { handleTransferState(it) }
        }
        lifecycleScope.launch {
            transferVM.progress.collect { renderProgress(it) }
        }
        lifecycleScope.launch {
            transferVM.savedFiles.collect { saved ->
                if (saved.isNotEmpty()) {
                    binding.tvSavedFiles.text = "Saved:\n" + saved.joinToString("\n")
                }
            }
        }
        lifecycleScope.launch {
            transferVM.error.collect { message ->
                if (message != null) {
                    toast(message)
                    transferVM.clearError()
                }
            }
        }
    }

    private fun handlePairingState(state: PairingViewModel.PairingUiState) {
        when (state) {
            is PairingViewModel.PairingUiState.Working -> {
                binding.tvWaitingStatus.text = state.message
            }
            is PairingViewModel.PairingUiState.HotspotReady -> {
                if (!serverStarted) {
                    val pin = pairingVM.pinCode.value
                    if (pin == null) {
                        pairingVM.setIdle()
                        toast("Could not create a session — tap Start again")
                        return
                    }
                    serverStarted = true
                    val info = transferVM.prepareReceiverSession(pin, state.config.port)
                    sessionInfo = info
                    pairingVM.listenForSender(info.sessionId, info.receiverChallenge)
                    transferVM.startReceiving()
                }
                renderHotspot(state.config)
            }
            is PairingViewModel.PairingUiState.SenderMatched -> {
                binding.tvWaitingStatus.text = "Sender matched: ${state.deviceName} — transfer starting…"
            }
            is PairingViewModel.PairingUiState.Failed -> {
                binding.tvWaitingStatus.text = state.message
                toast(state.message)
            }
            else -> Unit
        }
    }

    private fun renderHotspot(config: NetworkConfig) {
        binding.cardHotspot.visibility = View.VISIBLE
        binding.tvSsid.text = "Hotspot: ${config.ssid}"
        binding.tvPassword.text = if (config.isOpen) {
            "Password: (open network)"
        } else {
            "Password: ${config.passphrase}"
        }
        binding.tvBandInfo.text =
            "Band: ${config.band} · Port ${config.port} · ${pairingVM.bandSummary()}"
        binding.tvWaitingStatus.text =
            "Sender: join ${config.ssid}, then enter PIN or scan QR"

        val info = sessionInfo
        if (info == null) {
            // Hotspot restarted without a session — fall back to credentials QR.
            lifecycleScope.launch(Dispatchers.Default) {
                val legacy = QrCodePayloadHandler.QrPayload.fromNetworkConfig(config)
                val bitmap = try {
                    QrCodePayloadHandler.encodeToBitmap(
                        QrCodePayloadHandler.encodePayload(legacy), 640
                    )
                } catch (_: Exception) {
                    null
                }
                withContext(Dispatchers.Main) {
                    if (bitmap != null) binding.ivQr.setImageBitmap(bitmap)
                }
            }
            return
        }

        // v2 QR: session id + PIN + receiver public key + endpoint + expiry.
        lifecycleScope.launch(Dispatchers.Default) {
            val payload = pairingVM.buildPairingPayload(info)
            val bitmap = try {
                QrCodePayloadHandler.encodeToBitmap(PairingPayload.encode(payload), 640)
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (bitmap != null) binding.ivQr.setImageBitmap(bitmap)
            }
        }
    }

    // -------------------------------------------------------------- transfer

    private fun handleTransferState(state: TransferState) {
        when (state) {
            TransferState.CONNECTING -> binding.tvRecvFile.text = "Waiting for sender…"
            TransferState.TRANSFERRING -> Unit // progress renderer drives labels
            TransferState.COMPLETED -> {
                val progress = transferVM.progress.value
                binding.tvRecvFile.text =
                    "Received ${progress.filesTotal} file(s) · checksums verified"
                binding.tvWaitingStatus.text = "Transfer complete"
                binding.btnReceiveReset.visibility = View.VISIBLE
                binding.btnStopReceive.visibility = View.GONE
                binding.btnStartReceive.visibility = View.VISIBLE
            }
            TransferState.ERROR, TransferState.CONNECTION_FAILED,
            TransferState.AUTHENTICATION_FAILED, TransferState.TRANSFER_FAILED,
            TransferState.VERIFICATION_FAILED, TransferState.PAIRING_FAILED -> {
                binding.tvWaitingStatus.text = "Transfer failed — tap Start to host again"
                binding.btnStopReceive.visibility = View.GONE
                binding.btnStartReceive.visibility = View.VISIBLE
            }
            TransferState.CANCELLED -> {
                if (sessionActive) binding.tvWaitingStatus.text = "Session stopped"
            }
            else -> Unit
        }
    }

    private fun renderProgress(progress: TransferProgress) {
        if (!sessionActive && progress.totalBytes == 0L) return
        binding.radialProgressRecv.setProgress(progress.fraction)
        binding.radialProgressRecv.setSublabel(progress.speedLabel())
        binding.tvRecvSpeed.text = progress.speedLabel()
        binding.tvRecvEta.text = progress.etaLabel()
        if (progress.fileName.isNotEmpty()) binding.tvRecvFile.text = progress.fileName
    }

    // ------------------------------------------------------- bluetooth standby

    private fun toggleBtStandby() {
        if (btStandbyOn) {
            btStandbyOn = false
            pairingVM.stopBluetoothStandby()
            binding.btnBtStandby.text = "Bluetooth standby: off"
            return
        }
        val info = sessionInfo
        if (pairingVM.hotspotConfig.value == null || info == null) {
            toast("Start receiving first — Bluetooth shares the pairing payload")
            return
        }
        if (!pairingVM.isBluetoothSupported()) {
            toast("This device has no Bluetooth radio")
            return
        }
        ensureBtPermissions {
            ensureBtEnabled {
                btStandbyOn = true
                binding.btnBtStandby.text = "Bluetooth standby: ON"
                val payload = pairingVM.buildPairingPayload(info)
                pairingVM.startBluetoothStandby(PairingPayload.toJson(payload))
                // Best-effort discoverability so the sender's scan can find us.
                try {
                    discoverableLauncher.launch(pairingVM.bluetoothDiscoverableIntent())
                } catch (_: Exception) { /* receiver still works via bonded list */
                }
            }
        }
    }

    // ------------------------------------------------------------- permissions

    private fun ensureWifiPermissions(action: () -> Unit) {
        val missing = PermissionManager.missingPermissions(this, PermissionManager.wifiPermissions())
        if (missing.isEmpty()) {
            action()
        } else {
            pendingWifiAction = action
            wifiPermLauncher.launch(missing)
        }
    }

    private fun ensureLegacyWritePermissions(action: () -> Unit) {
        val missing = PermissionManager.missingPermissions(
            this, PermissionManager.legacyWritePermission()
        )
        if (missing.isEmpty()) {
            action()
        } else {
            pendingWifiAction = action
            wifiPermLauncher.launch(missing)
        }
    }

    private fun ensureBtPermissions(action: () -> Unit) {
        val missing =
            PermissionManager.missingPermissions(this, PermissionManager.bluetoothPermissions())
        if (missing.isEmpty()) {
            action()
        } else {
            pendingBtAction = action
            btPermLauncher.launch(missing)
        }
    }

    private fun ensureBtEnabled(action: () -> Unit) {
        if (pairingVM.isBluetoothEnabled()) {
            action()
        } else {
            pendingBtAction = action
            try {
                btEnableLauncher.launch(pairingVM.bluetoothEnableIntent())
            } catch (_: Exception) {
                pendingBtAction = null
                toast("Could not enable Bluetooth")
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

}
