package com.sharethis.app.ui.send

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sharethis.app.core.engine.TransferProgress
import com.sharethis.app.core.network.PinPairingEngine
import com.sharethis.app.core.pairing.BluetoothPairingManager
import com.sharethis.app.core.pairing.QrCodePayloadHandler
import com.sharethis.app.core.permissions.PermissionManager
import com.sharethis.app.core.storage.StorageBridge
import com.sharethis.app.data.enums.PairingMode
import com.sharethis.app.data.enums.TransferState
import com.sharethis.app.data.models.DevicePeer
import com.sharethis.app.databinding.ActivitySendBinding
import com.sharethis.app.databinding.ItemBtDeviceBinding
import com.sharethis.app.databinding.ItemFileBinding
import com.sharethis.app.ui.viewmodels.PairingViewModel
import com.sharethis.app.ui.viewmodels.TransferViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sender flow: pick files → pair (PIN / QR / Bluetooth) → watch it fly.
 *
 * Pairing transports only exchange hotspot credentials + TCP port; bytes
 * always move over the tuned Wi-Fi socket in [TransferViewModel].
 */
class SendActivity : AppCompatActivity() {

    companion object {
        private const val STEP_FILES = 0
        private const val STEP_PAIRING = 1
        private const val STEP_TRANSFER = 2
        private const val STEP_DONE = 3
    }

    data class FileEntry(val uri: Uri, val name: String, val sizeLabel: String, val bytes: Long)

    private lateinit var binding: ActivitySendBinding
    private lateinit var transferVM: TransferViewModel
    private lateinit var pairingVM: PairingViewModel
    private lateinit var storage: StorageBridge

    private val fileEntries = ArrayList<FileEntry>()
    private lateinit var fileAdapter: SimpleFileAdapter
    private val btRows = ArrayList<BluetoothPairingManager.BtDevice>()
    private lateinit var btAdapter: SimpleBtAdapter

    private var peer: DevicePeer? = null
    private var currentStep = STEP_FILES
    private var pairingMode = PairingMode.PIN_CODE
    private var transferInFlight = false

    private var pendingWifiAction: (() -> Unit)? = null
    private var pendingBtAction: (() -> Unit)? = null

    // ------------------------------------------------------------ launchers

    private val pickFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) addFiles(uris)
        }

    private val wifiPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val action = pendingWifiAction
            pendingWifiAction = null
            if (grants.values.all { it }) {
                action?.invoke()
            } else {
                toast("Nearby Wi-Fi permission is needed to reach the receiver")
            }
        }

    private val btPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val action = pendingBtAction
            pendingBtAction = null
            if (grants.values.all { it }) {
                action?.invoke()
            } else {
                toast("Bluetooth permission is needed for the handshake")
            }
        }

    private val qrLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val text = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_TEXT)
                if (text != null) handleQrText(text)
            }
        }

    private val btEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val action = pendingBtAction
            pendingBtAction = null
            if (result.resultCode == RESULT_OK) {
                action?.invoke()
            } else {
                toast("Bluetooth must be on for Bluetooth pairing")
            }
        }

    // -------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySendBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Send files"

        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        transferVM = ViewModelProvider(this, factory)[TransferViewModel::class.java]
        pairingVM = ViewModelProvider(this, factory)[PairingViewModel::class.java]
        storage = StorageBridge(applicationContext)

        fileAdapter = SimpleFileAdapter(fileEntries)
        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = fileAdapter

        btAdapter = SimpleBtAdapter(btRows) { device -> joinViaBluetooth(device) }
        binding.rvBtDevices.layoutManager = LinearLayoutManager(this)
        binding.rvBtDevices.adapter = btAdapter

        binding.btnPickFiles.setOnClickListener { pickFilesLauncher.launch(arrayOf("*/*")) }
        binding.btnClearFiles.setOnClickListener { clearFiles() }
        binding.btnFilesNext.setOnClickListener {
            if (fileEntries.isEmpty()) {
                toast("Pick at least one file first")
            } else {
                showStep(STEP_PAIRING)
            }
        }
        binding.btnPairingBack.setOnClickListener { showStep(STEP_FILES) }

        binding.btnPinMode.setOnClickListener { selectMode(PairingMode.PIN_CODE) }
        binding.btnQrMode.setOnClickListener { selectMode(PairingMode.QR_CODE) }
        binding.btnBtMode.setOnClickListener { selectMode(PairingMode.BLUETOOTH) }

        binding.btnDiscover.setOnClickListener { startPinDiscover() }
        binding.btnBtRefresh.setOnClickListener { refreshBtList() }
        binding.btnCancelTransfer.setOnClickListener { transferVM.cancel() }
        binding.btnDoneHome.setOnClickListener { finish() }
        binding.btnSendMore.setOnClickListener {
            transferVM.reset()
            pairingVM.setIdle()
            peer = null
            transferInFlight = false
            showStep(STEP_FILES)
        }

        observeViewModels()
        selectMode(PairingMode.PIN_CODE)
        showStep(STEP_FILES)
        refreshFilesUi()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        pairingVM.stopBtDiscovery()
        super.onDestroy()
    }

    // ----------------------------------------------------------------- files

    private fun addFiles(uris: List<Uri>) {
        lifecycleScope.launch(Dispatchers.IO) {
            for (uri in uris) {
                if (fileEntries.any { it.uri == uri }) continue
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) { /* not all providers support this */
                }
                val meta = try {
                    storage.probeMetadata(uri)
                } catch (_: Exception) {
                    continue
                }
                fileEntries.add(
                    FileEntry(uri, meta.fileName, meta.humanSize(), meta.fileSize)
                )
            }
            withContext(Dispatchers.Main) {
                fileAdapter.notifyDataSetChanged()
                refreshFilesUi()
            }
        }
    }

    private fun clearFiles() {
        fileEntries.clear()
        fileAdapter.notifyDataSetChanged()
        refreshFilesUi()
    }

    private fun refreshFilesUi() {
        val empty = fileEntries.isEmpty()
        binding.tvFilesEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvFiles.visibility = if (empty) View.GONE else View.VISIBLE
        binding.btnClearFiles.visibility = if (empty) View.GONE else View.VISIBLE
        val totalBytes = fileEntries.sumOf { it.bytes }
        binding.tvFilesCount.text = if (empty) {
            "No files selected"
        } else {
            "${fileEntries.size} file(s) · ${formatBytes(totalBytes)}"
        }
    }

    // --------------------------------------------------------------- pairing

    private fun selectMode(mode: PairingMode) {
        pairingMode = mode
        binding.btnPinMode.isSelected = mode == PairingMode.PIN_CODE
        binding.btnQrMode.isSelected = mode == PairingMode.QR_CODE
        binding.btnBtMode.isSelected = mode == PairingMode.BLUETOOTH
        binding.layoutPin.visibility =
            if (mode == PairingMode.PIN_CODE) View.VISIBLE else View.GONE
        binding.layoutBt.visibility =
            if (mode == PairingMode.BLUETOOTH) View.VISIBLE else View.GONE
        if (mode == PairingMode.QR_CODE) {
            launchQrScanner()
        } else if (mode == PairingMode.BLUETOOTH) {
            refreshBtList()
        } else {
            pairingVM.stopBtDiscovery()
        }
    }

    private fun startPinDiscover() {
        val pin = binding.pinEntry.pin
        if (!PinPairingEngine.isValidPin(pin)) {
            toast("Enter the 6-digit PIN shown on the receiver")
            return
        }
        ensureWifiPermissions {
            binding.tvPinStatus.text = "Broadcasting PIN…"
            pairingVM.discoverWithPin(pin)
        }
    }

    private fun launchQrScanner() {
        val missing = PermissionManager.missingPermissions(
            this, PermissionManager.cameraPermission()
        )
        if (missing.isNotEmpty()) {
            // QrScannerActivity requests the camera permission itself.
        }
        qrLauncher.launch(
            android.content.Intent(this, QrScannerActivity::class.java)
        )
    }

    private fun handleQrText(text: String) {
        val payload = QrCodePayloadHandler.decodePayload(text)
        if (payload == null) {
            toast("That is not a ShareThis code")
            return
        }
        ensureWifiPermissions {
            binding.tvPeerBanner.visibility = View.VISIBLE
            binding.tvPeerBanner.text = "QR accepted — joining ${payload.ssid}…"
            pairingVM.joinHotspotPayload(payload, PairingMode.QR_CODE)
        }
    }

    private fun refreshBtList() {
        if (!pairingVM.isBluetoothSupported()) {
            binding.tvBtStatus.text = "This device has no Bluetooth radio"
            return
        }
        ensureBtPermissions {
            ensureBtEnabled {
                val bonded = pairingVM.bondedDevices()
                mergeBtRows(bonded, pairingVM.btDiscovered.value)
                binding.tvBtStatus.text =
                    "Tap the receiver (pair once in system Settings if missing)"
                pairingVM.startBtDiscovery()
            }
        }
    }

    private fun mergeBtRows(
        bonded: List<BluetoothPairingManager.BtDevice>,
        discovered: List<BluetoothPairingManager.BtDevice>
    ) {
        val merged = ArrayList<BluetoothPairingManager.BtDevice>()
        merged.addAll(bonded)
        discovered.forEach { found ->
            if (merged.none { it.address == found.address }) merged.add(found)
        }
        btRows.clear()
        btRows.addAll(merged)
        btAdapter.notifyDataSetChanged()
        binding.tvBtStatus.text = if (merged.isEmpty()) {
            "Scanning for nearby receivers… keep the receiver's Bluetooth standby on"
        } else {
            "Found ${merged.size} device(s) — tap the receiver"
        }
    }

    private fun joinViaBluetooth(device: BluetoothPairingManager.BtDevice) {
        ensureBtPermissions {
            ensureBtEnabled {
                ensureWifiPermissions {
                    binding.tvPeerBanner.visibility = View.VISIBLE
                    binding.tvPeerBanner.text = "Bluetooth handshake with ${device.name}…"
                    pairingVM.joinViaBluetooth(device.address)
                }
            }
        }
    }

    // -------------------------------------------------------------- transfer

    private fun observeViewModels() {
        lifecycleScope.launch {
            pairingVM.uiState.collect { handlePairingState(it) }
        }
        lifecycleScope.launch {
            pairingVM.btDiscovered.collect { discovered ->
                if (pairingMode == PairingMode.BLUETOOTH) {
                    mergeBtRows(pairingVM.bondedDevices(), discovered)
                }
            }
        }
        lifecycleScope.launch {
            transferVM.state.collect { handleTransferState(it) }
        }
        lifecycleScope.launch {
            transferVM.progress.collect { renderProgress(it) }
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
            is PairingViewModel.PairingUiState.Idle -> Unit
            is PairingViewModel.PairingUiState.Working -> {
                binding.tvPeerBanner.visibility = View.VISIBLE
                binding.tvPeerBanner.text = state.message
                if (pairingMode == PairingMode.PIN_CODE) {
                    binding.tvPinStatus.text = state.message
                } else if (pairingMode == PairingMode.BLUETOOTH) {
                    binding.tvBtStatus.text = state.message
                }
            }
            is PairingViewModel.PairingUiState.PeerFound -> {
                peer = state.peer
                pairingVM.stopBtDiscovery()
                binding.tvPeerBanner.visibility = View.VISIBLE
                binding.tvPeerBanner.text =
                    "Connected to ${state.peer.deviceName} · ${state.peer.displayAddress} · ${state.peer.band} (${state.via.title})"
                if (fileEntries.isEmpty()) {
                    toast("Connected! Now pick files to send")
                    showStep(STEP_FILES)
                } else {
                    startTransfer()
                }
            }
            is PairingViewModel.PairingUiState.Failed -> {
                binding.tvPeerBanner.visibility = View.VISIBLE
                binding.tvPeerBanner.text = state.message
                if (pairingMode == PairingMode.PIN_CODE) binding.tvPinStatus.text = state.message
                toast(state.message)
            }
            else -> Unit // receiver-only states
        }
    }

    private fun startTransfer() {
        val target = peer ?: return
        if (fileEntries.isEmpty() || transferInFlight) return
        transferInFlight = true
        showStep(STEP_TRANSFER)
        binding.radialProgress.reset()
        transferVM.sendFiles(target, fileEntries.map { it.uri })
    }

    private fun handleTransferState(state: TransferState) {
        when (state) {
            TransferState.CONNECTING -> binding.tvTransferDetail.text = "Connecting…"
            TransferState.TRANSFERRING -> binding.tvTransferDetail.text = "Transferring…"
            TransferState.COMPLETED -> {
                transferInFlight = false
                val progress = transferVM.progress.value
                binding.tvDoneTitle.text = "Sent ${progress.filesTotal} file(s)"
                binding.tvDoneSummary.text =
                    "${formatBytes(progress.totalBytes)} delivered to ${peer?.deviceName ?: "receiver"}"
                showStep(STEP_DONE)
            }
            TransferState.ERROR -> {
                transferInFlight = false
                showStep(STEP_PAIRING)
            }
            TransferState.CANCELLED -> {
                transferInFlight = false
                if (currentStep == STEP_TRANSFER) showStep(STEP_PAIRING)
            }
            else -> Unit
        }
    }

    private fun renderProgress(progress: TransferProgress) {
        if (currentStep != STEP_TRANSFER) return
        binding.radialProgress.setProgress(progress.fraction)
        binding.radialProgress.setSublabel(progress.speedLabel())
        binding.tvSpeed.text = progress.speedLabel()
        binding.tvEta.text = progress.etaLabel()
        binding.tvCurrentFile.text = progress.fileName.ifEmpty { "Preparing…" }
        binding.tvTransferDetail.text =
            "File ${progress.filesCompleted + 1} of ${progress.filesTotal} · ${progress.percent}%"
    }

    // ------------------------------------------------------------------ steps

    private fun showStep(step: Int) {
        currentStep = step
        binding.sectionFiles.visibility = if (step == STEP_FILES) View.VISIBLE else View.GONE
        binding.sectionPairing.visibility = if (step == STEP_PAIRING) View.VISIBLE else View.GONE
        binding.sectionTransfer.visibility = if (step == STEP_TRANSFER) View.VISIBLE else View.GONE
        binding.sectionDone.visibility = if (step == STEP_DONE) View.VISIBLE else View.GONE
        binding.tvStepTitle.text = when (step) {
            STEP_FILES -> "Step 1 of 3 · Choose files"
            STEP_PAIRING -> "Step 2 of 3 · Pair with receiver"
            STEP_TRANSFER -> "Step 3 of 3 · Sending"
            else -> "Done"
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

    // ---------------------------------------------------------------- helpers

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "${value.toLong()} B" else "%.1f %s".format(value, units[unit])
    }

    // --------------------------------------------------------------- adapters

    private class SimpleFileAdapter(
        private val items: List<FileEntry>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<SimpleFileAdapter.Holder>() {

        class Holder(val binding: ItemFileBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val binding = ItemFileBinding.inflate(
                android.view.LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.binding.tvFileName.text = item.name
            holder.binding.tvFileSize.text = item.sizeLabel
        }

        override fun getItemCount(): Int = items.size
    }

    private class SimpleBtAdapter(
        private val items: List<BluetoothPairingManager.BtDevice>,
        private val onClick: (BluetoothPairingManager.BtDevice) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<SimpleBtAdapter.Holder>() {

        class Holder(val binding: ItemBtDeviceBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val binding = ItemBtDeviceBinding.inflate(
                android.view.LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.binding.tvBtName.text = item.name
            holder.binding.tvBtAddress.text = item.address
            holder.binding.root.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
