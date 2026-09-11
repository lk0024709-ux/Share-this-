package com.sharethis.app.data.enums

/**
 * The three offline pairing transports. All of them exist only to exchange
 * hotspot credentials + the TCP transfer port; the actual file bytes always
 * travel over the tuned high-throughput Wi-Fi socket.
 */
enum class PairingMode(val title: String) {
    BLUETOOTH("Bluetooth"),
    QR_CODE("QR Code"),
    PIN_CODE("PIN Code")
}
