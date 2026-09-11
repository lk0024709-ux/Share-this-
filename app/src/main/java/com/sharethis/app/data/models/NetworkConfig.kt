package com.sharethis.app.data.models

import java.io.Serializable

/**
 * Hotspot credentials + transfer endpoint published by the receiver.
 * This is the payload exchanged over Bluetooth / QR / PIN handshake.
 */
data class NetworkConfig(
    val ssid: String,
    val passphrase: String,
    val ipAddress: String,
    val port: Int,
    val band: String = DevicePeer.BAND_UNKNOWN,
    val deviceName: String = "",
    val security: String = SECURITY_WPA2
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L
        const val SECURITY_OPEN = "OPEN"
        const val SECURITY_WPA2 = "WPA2"
        const val SECURITY_WPA3 = "WPA3"
    }

    val isOpen: Boolean get() = security == SECURITY_OPEN || passphrase.isEmpty()
}
