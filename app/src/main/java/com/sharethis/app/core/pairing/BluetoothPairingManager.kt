package com.sharethis.app.core.pairing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.UUID

/**
 * Classic-Bluetooth handshake transport.
 *
 * Bluetooth is used EXCLUSIVELY to exchange the Wi-Fi credentials + TCP port
 * (a ~200 byte JSON blob). The instant the handshake completes,
 * [shutdownRadio] kills discovery and closes every BT socket so the 2.4 GHz
 * radio stops colliding with the high-throughput Wi-Fi transfer.
 *
 * Direction: the RECEIVER hosts the RFCOMM server and pushes its
 * [QrCodePayloadHandler.QrPayload] JSON; the SENDER connects and reads it.
 * Insecure RFCOMM is deliberate — it skips OS pairing friction for a
 * 2-second exchange of temporary hotspot credentials.
 */
class BluetoothPairingManager(private val context: Context) {

    data class BtDevice(val name: String, val address: String)

    sealed interface BtResult {
        data class ConfigSent(val deviceName: String) : BtResult
        data class ConfigReceived(val payloadJson: String, val deviceName: String) : BtResult
        data class Failed(val reason: String) : BtResult
    }

    companion object {
        // Fixed app UUID — both ends must agree.
        val APP_UUID: UUID = UUID.fromString("8ce96b4e-8c9a-4c7e-9f1a-2b3c4d5e6f70")
        const val SERVICE_NAME = "ShareThis"
        const val ACCEPT_TIMEOUT_MS = 45_000
        const val MAX_PAYLOAD_BYTES = 8 * 1024

        private val UTF8: Charset = Charset.forName("UTF-8")
    }

    private val adapter: BluetoothAdapter?
        get() = (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE)
            as? BluetoothManager)?.adapter

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var discoveryReceiver: BroadcastReceiver? = null

    private val _discovered = MutableStateFlow<List<BtDevice>>(emptyList())
    val discovered: StateFlow<List<BtDevice>> = _discovered

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering

    // ------------------------------------------------------------------ state

    fun isSupported(): Boolean = adapter != null

    fun isEnabled(): Boolean = try {
        adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    fun enableIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    fun discoverableIntent(durationSec: Int = 120): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationSec)

    fun bondedDevices(): List<BtDevice> {
        return try {
            adapter?.bondedDevices
                ?.map { BtDevice(it.name ?: it.address, it.address) }
                .orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    // --------------------------------------------------------------- discovery

    /**
     * Starts classic discovery, emitting found devices on [discovered].
     * Call [stopDiscovery] (or any handshake method) to end it.
     */
    fun startDiscovery() {
        val bluetooth = adapter ?: return
        try {
            if (bluetooth.isDiscovering) bluetooth.cancelDiscovery()
        } catch (_: SecurityException) {
            return
        }
        _discovered.value = emptyList()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? =
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE,
                                    BluetoothDevice::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                        if (device != null) {
                            try {
                                val entry = BtDevice(device.name ?: device.address, device.address)
                                val current = _discovered.value
                                if (current.none { it.address == entry.address }) {
                                    _discovered.value = current + entry
                                }
                            } catch (_: SecurityException) { /* ignore */
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _discovering.value = false
                }
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            context.applicationContext.registerReceiver(receiver, filter)
            discoveryReceiver = receiver
            _discovering.value = bluetooth.startDiscovery()
        } catch (_: SecurityException) {
            _discovering.value = false
        } catch (_: Exception) {
            _discovering.value = false
        }
    }

    fun stopDiscovery() {
        try {
            if (adapter?.isDiscovering == true) adapter?.cancelDiscovery()
        } catch (_: Exception) { /* ignore */
        }
        _discovering.value = false
        unregisterDiscoveryReceiver()
    }

    private fun unregisterDiscoveryReceiver() {
        val receiver = discoveryReceiver ?: return
        discoveryReceiver = null
        try {
            context.applicationContext.unregisterReceiver(receiver)
        } catch (_: Exception) { /* already unregistered */
        }
    }

    // ------------------------------------------------------- receiver: serve

    /**
     * Receiver side: listens for one sender, pushes [payloadJson], then
     * immediately kills the Bluetooth radio.
     */
    suspend fun hostAndSendConfig(payloadJson: String): BtResult =
        withContext(Dispatchers.IO) {
            stopDiscovery()
            val bluetooth = adapter ?: return@withContext BtResult.Failed("Bluetooth unavailable")
            if (!bluetooth.isEnabled) return@withContext BtResult.Failed("Bluetooth is off")
            try {
                val server = bluetooth.listenUsingInsecureRfcommWithServiceRecord(
                    SERVICE_NAME, APP_UUID
                )
                serverSocket = server
                val socket = try {
                    server.accept(ACCEPT_TIMEOUT_MS)
                } catch (_: IOException) {
                    return@withContext BtResult.Failed("No sender connected in time")
                }
                clientSocket = socket
                val deviceName = try {
                    socket.remoteDevice?.name ?: "Sender"
                } catch (_: SecurityException) {
                    "Sender"
                }
                try {
                    writeFramed(socket.outputStream, payloadJson)
                    socket.outputStream.flush()
                } catch (e: IOException) {
                    return@withContext BtResult.Failed("Handshake write failed: ${e.message}")
                } finally {
                    shutdownRadio() // kill radio the instant bytes are out
                }
                BtResult.ConfigSent(deviceName)
            } catch (_: SecurityException) {
                shutdownRadio()
                BtResult.Failed("Bluetooth permission denied")
            } catch (e: IOException) {
                shutdownRadio()
                BtResult.Failed(e.message ?: "Bluetooth server error")
            } catch (e: Exception) {
                shutdownRadio()
                BtResult.Failed(e.message ?: "Bluetooth error")
            }
        }

    // --------------------------------------------------------- sender: fetch

    /**
     * Sender side: connects to the receiver's [address], reads the payload
     * JSON, then immediately kills the Bluetooth radio.
     */
    suspend fun connectAndReceiveConfig(address: String): BtResult =
        withContext(Dispatchers.IO) {
            stopDiscovery()
            val bluetooth = adapter ?: return@withContext BtResult.Failed("Bluetooth unavailable")
            if (!bluetooth.isEnabled) return@withContext BtResult.Failed("Bluetooth is off")
            try {
                val device = try {
                    bluetooth.getRemoteDevice(address)
                } catch (e: IllegalArgumentException) {
                    return@withContext BtResult.Failed("Bad device address: ${e.message}")
                }
                val socket = device.createInsecureRfcommSocketToServiceRecord(APP_UUID)
                clientSocket = socket
                try {
                    socket.connect()
                } catch (e: IOException) {
                    shutdownRadio()
                    return@withContext BtResult.Failed(
                        "Could not connect — keep devices close and the receiver waiting"
                    )
                }
                val deviceName = try {
                    device.name ?: address
                } catch (_: SecurityException) {
                    address
                }
                val json = try {
                    readFramed(socket.inputStream)
                } catch (e: IOException) {
                    return@withContext BtResult.Failed("Handshake read failed: ${e.message}")
                } finally {
                    shutdownRadio() // kill radio the instant bytes are in
                }
                BtResult.ConfigReceived(json, deviceName)
            } catch (_: SecurityException) {
                shutdownRadio()
                BtResult.Failed("Bluetooth permission denied")
            } catch (e: Exception) {
                shutdownRadio()
                BtResult.Failed(e.message ?: "Bluetooth error")
            }
        }

    // ------------------------------------------------------------ radio kill

    /**
     * Stops discovery/advertising and closes every BT socket. Called
     * immediately after ANY handshake completes or fails.
     */
    fun shutdownRadio() {
        try {
            if (adapter?.isDiscovering == true) adapter?.cancelDiscovery()
        } catch (_: Exception) { /* ignore */
        }
        _discovering.value = false
        unregisterDiscoveryReceiver()
        try {
            serverSocket?.close()
        } catch (_: Exception) { /* ignore */
        }
        try {
            clientSocket?.close()
        } catch (_: Exception) { /* ignore */
        }
        serverSocket = null
        clientSocket = null
    }

    // ---------------------------------------------------------------- framing

    /** 4-byte big-endian length prefix + UTF-8 JSON. */
    private fun writeFramed(out: java.io.OutputStream, text: String) {
        val bytes = text.toByteArray(UTF8)
        if (bytes.size > MAX_PAYLOAD_BYTES) throw IOException("Payload too large")
        out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
        out.write(bytes)
        out.flush()
    }

    private fun readFramed(input: InputStream): String {
        val lengthBytes = ByteArray(4)
        readFully(input, lengthBytes, 0, 4)
        val length = ByteBuffer.wrap(lengthBytes).int
        if (length <= 0 || length > MAX_PAYLOAD_BYTES) throw IOException("Bad payload length")
        val payload = ByteArray(length)
        readFully(input, payload, 0, length)
        return String(payload, UTF8)
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var remaining = length
        var position = offset
        while (remaining > 0) {
            val read = input.read(buffer, position, remaining)
            if (read == -1) throw IOException("Truncated Bluetooth payload")
            position += read
            remaining -= read
        }
    }
}
