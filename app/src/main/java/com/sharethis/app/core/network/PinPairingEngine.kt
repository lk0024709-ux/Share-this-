package com.sharethis.app.core.network

import com.sharethis.app.data.enums.PairingMode
import com.sharethis.app.data.models.DevicePeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.Charset

/**
 * 6-digit PIN pairing over subnet UDP broadcast.
 *
 * Flow:
 *  1. Receiver shows a random PIN (e.g. 512-890).
 *  2. Sender (already on the receiver's hotspot / same LAN) pulses
 *     `SHARETHIS_PIN|<pin>|<deviceName>` to 255.255.255.255:8888.
 *  3. Receiver validates the PIN and unicasts
 *     `SHARETHIS_OFFER|<pin>|<tcpPort>|<deviceName>|<band>` back to the
 *     sender's source port. The sender takes the offer packet's *source IP*
 *     as the receiver address — no IP enumeration needed on either side.
 *
 * Fully coroutine-cancellable; every socket is closed in `finally` via `use`.
 */
class PinPairingEngine {

    data class PinOffer(val peer: DevicePeer)

    data class ParsedDiscover(val pin: String, val deviceName: String)

    data class ParsedOffer(
        val pin: String,
        val tcpPort: Int,
        val deviceName: String,
        val band: String
    )

    companion object {
        const val PIN_PORT = 8888
        const val BROADCAST_IP = "255.255.255.255"

        const val PREFIX_DISCOVER = "SHARETHIS_PIN"
        const val PREFIX_OFFER = "SHARETHIS_OFFER"

        const val PACKET_MAX_BYTES = 1024
        const val BROADCAST_PULSE_MS = 1000L
        const val OFFER_REPEAT_COUNT = 3
        const val OFFER_REPEAT_GAP_MS = 100L

        private val UTF8: Charset = Charset.forName("UTF-8")

        fun generatePin(): String = (100000..999999).random().toString()

        fun isValidPin(pin: String): Boolean = pin.length == 6 && pin.all { it.isDigit() }

        // ------------------------------------------------------ packet codec

        internal fun buildDiscover(pin: String, deviceName: String): ByteArray =
            "$PREFIX_DISCOVER|$pin|${sanitize(deviceName)}".toByteArray(UTF8)

        internal fun parseDiscover(text: String): ParsedDiscover? {
            val parts = text.trim().split('|')
            if (parts.size != 3 || parts[0] != PREFIX_DISCOVER) return null
            if (!isValidPin(parts[1])) return null
            return ParsedDiscover(parts[1], parts[2].take(64))
        }

        internal fun buildOffer(
            pin: String,
            tcpPort: Int,
            deviceName: String,
            band: String
        ): ByteArray =
            "$PREFIX_OFFER|$pin|$tcpPort|${sanitize(deviceName)}|${sanitize(band)}"
                .toByteArray(UTF8)

        internal fun parseOffer(text: String): ParsedOffer? {
            val parts = text.trim().split('|')
            if (parts.size != 5 || parts[0] != PREFIX_OFFER) return null
            if (!isValidPin(parts[1])) return null
            val port = parts[2].toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return ParsedOffer(parts[1], port, parts[3].take(64), parts[4].take(16))
        }

        private fun sanitize(value: String): String =
            value.replace('|', ' ').replace('\n', ' ').trim().take(64)
    }

    // ---------------------------------------------------------------- sender

    /**
     * Broadcasts [pin] until a matching receiver answers or [timeoutMs]
     * elapses. Returns the peer (IP = offer source address).
     */
    suspend fun discoverByPin(
        pin: String,
        deviceName: String,
        timeoutMs: Long = 20_000L
    ): PinOffer? = withContext(Dispatchers.IO) {
        require(isValidPin(pin)) { "PIN must be 6 digits" }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = BROADCAST_PULSE_MS.toInt()
            val payload = buildDiscover(pin, deviceName)
            val target = InetSocketAddress(BROADCAST_IP, PIN_PORT)
            val buffer = ByteArray(PACKET_MAX_BYTES)
            val deadline = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline) {
                ensureActive()
                try {
                    socket.send(DatagramPacket(payload, payload.size, target))
                } catch (_: Exception) { /* transient; keep pulsing */
                }

                // Drain replies until the next pulse is due.
                val pulseEnd = System.currentTimeMillis() + BROADCAST_PULSE_MS
                while (System.currentTimeMillis() < pulseEnd &&
                    System.currentTimeMillis() < deadline
                ) {
                    ensureActive()
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, UTF8)
                        val offer = parseOffer(text) ?: continue
                        if (offer.pin != pin) continue
                        val host = packet.address?.hostAddress ?: continue
                        val peer = DevicePeer(
                            deviceName = offer.deviceName.ifEmpty { host },
                            ipAddress = host,
                            port = offer.tcpPort,
                            band = offer.band.ifEmpty { DevicePeer.BAND_UNKNOWN },
                            pairingMode = PairingMode.PIN_CODE
                        )
                        return@withContext PinOffer(peer)
                    } catch (_: SocketTimeoutException) {
                        break // pulse again
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            null
        }
    }

    // --------------------------------------------------------------- receiver

    /**
     * Listens on UDP 8888 for a sender broadcasting [expectedPin]. On match,
     * unicasts the TCP endpoint back and returns the sender's device name.
     * Returns null on timeout/cancel.
     */
    suspend fun listenForPin(
        expectedPin: String,
        tcpPort: Int,
        deviceName: String,
        band: String,
        timeoutMs: Long = 300_000L
    ): String? = withContext(Dispatchers.IO) {
        require(isValidPin(expectedPin)) { "PIN must be 6 digits" }
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(PIN_PORT))
            socket.soTimeout = BROADCAST_PULSE_MS.toInt()
            val buffer = ByteArray(PACKET_MAX_BYTES)
            val deadline = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline) {
                ensureActive()
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, UTF8)
                    val discover = parseDiscover(text) ?: continue
                    if (discover.pin != expectedPin) continue

                    // Match — reply directly to the sender's source port,
                    // repeated for UDP loss tolerance.
                    val reply = buildOffer(expectedPin, tcpPort, deviceName, band)
                    repeat(OFFER_REPEAT_COUNT) { attempt ->
                        ensureActive()
                        try {
                            socket.send(
                                DatagramPacket(reply, reply.size, packet.address, packet.port)
                            )
                        } catch (_: Exception) { /* try next repeat */
                        }
                        if (attempt < OFFER_REPEAT_COUNT - 1) {
                            try {
                                Thread.sleep(OFFER_REPEAT_GAP_MS)
                            } catch (_: InterruptedException) { /* cancelled */
                            }
                        }
                    }
                    return@withContext discover.deviceName.ifEmpty { packet.address?.hostAddress ?: "Sender" }
                } catch (_: SocketTimeoutException) {
                    // keep listening
                } catch (_: Exception) {
                    // keep listening unless cancelled
                }
            }
            null
        }
    }
}
