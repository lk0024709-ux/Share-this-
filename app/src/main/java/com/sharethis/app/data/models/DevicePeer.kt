package com.sharethis.app.data.models

import com.sharethis.app.data.enums.PairingMode
import java.io.Serializable

/**
 * A discovered transfer peer: IP + high-speed TCP port + radio band info.
 */
data class DevicePeer(
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val band: String = BAND_UNKNOWN,
    val pairingMode: PairingMode = PairingMode.PIN_CODE,
    val discoveredAtMs: Long = System.currentTimeMillis()
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L
        const val BAND_5GHZ = "5GHz"
        const val BAND_2_4GHZ = "2.4GHz"
        const val BAND_UNKNOWN = "Unknown"
    }

    val displayAddress: String get() = "$ipAddress:$port"

    val isHighThroughputBand: Boolean get() = band == BAND_5GHZ
}
