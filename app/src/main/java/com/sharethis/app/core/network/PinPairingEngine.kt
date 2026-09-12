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

/**
 * 6-digit PIN pairing over subnet UDP broadcast.
 *
 * Flow (wire format in [PinProtocol]):
 *  1. Receiver shows a random PIN (e.g. 512-890).
 *  2. Sender (already on the receiver's hotspot / same LAN) pulses the PIN
 *     to 255.255.255.255:8888.
 *  3. Receiver validates the PIN and unicasts an offer back to the sender's
 *     source port containing the TCP endpoint + session id + challenge. The
 *     sender takes the offer packet's *source IP* as the receiver address.
 *
 * The PIN itself is never used as an encryption key — it participates in the
 * session key derivation during the authenticated TCP handshake
 * (see SessionCrypto) and binds the exchange against man-in-the-middle.
 *
 * Fully coroutine-cancellable; every socket is closed in `finally` via `use`.
 */
class PinPairingEngine {

    data class PinOffer(
        val peer: DevicePeer,
        val sessionId: Long,
        val challenge: ByteArray
    )

    companion object {
        val PIN_PORT = PinProtocol.PIN_PORT
        val BROADCAST_IP = PinProtocol.BROADCAST_IP

        fun generatePin(): String = PinProtocol.generatePin()
        fun isValidPin(pin: String): Boolean = PinProtocol.isValidPin(pin)
    }

    // ---------------------------------------------------------------- sender

    /**
     * Broadcasts [pin] until a matching receiver answers or [timeoutMs]
     * elapses. Returns the peer (IP = offer source address) plus the session
     * id + challenge needed for the authenticated TCP handshake.
     */
    suspend fun discoverByPin(
        pin: String,
        deviceName: String,
        timeoutMs: Long = 20_000L
    ): PinOffer? = withContext(Dispatchers.IO) {
        require(isValidPin(pin)) { "PIN must be 6 digits" }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = PinProtocol.BROADCAST_PULSE_MS.toInt()
            val payload = PinProtocol.buildDiscover(pin, deviceName)
            val target = InetSocketAddress(BROADCAST_IP, PIN_PORT)
            val buffer = ByteArray(PinProtocol.PACKET_MAX_BYTES)
            val deadline = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline) {
                ensureActive()
                try {
                    socket.send(DatagramPacket(payload, payload.size, target))
                } catch (_: Exception) { /* transient; keep pulsing */ }

                // Drain replies until the next pulse is due.
                val pulseEnd = System.currentTimeMillis() + PinProtocol.BROADCAST_PULSE_MS
                while (System.currentTimeMillis() < pulseEnd &&
                    System.currentTimeMillis() < deadline
                ) {
                    ensureActive()
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val offer = PinProtocol.parseOffer(text) ?: continue
                        if (offer.pin != pin) continue
                        val host = packet.address?.hostAddress ?: continue
                        val peer = DevicePeer(
                            deviceName = offer.deviceName.ifEmpty { host },
                            ipAddress = host,
                            port = offer.tcpPort,
                            band = offer.band.ifEmpty { DevicePeer.BAND_UNKNOWN },
                            pairingMode = PairingMode.PIN_CODE
                        )
                        return@withContext PinOffer(peer, offer.sessionId, offer.challenge)
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
     * unicasts the offer (TCP endpoint + session + challenge) back and
     * returns the sender's device name. Returns null on timeout/cancel.
     */
    suspend fun listenForPin(
        expectedPin: String,
        tcpPort: Int,
        deviceName: String,
        band: String,
        sessionId: Long,
        challenge: ByteArray,
        timeoutMs: Long = 300_000L
    ): String? = withContext(Dispatchers.IO) {
        require(isValidPin(expectedPin)) { "PIN must be 6 digits" }
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(PIN_PORT))
            socket.soTimeout = PinProtocol.BROADCAST_PULSE_MS.toInt()
            val buffer = ByteArray(PinProtocol.PACKET_MAX_BYTES)
            val deadline = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline) {
                ensureActive()
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val discover = PinProtocol.parseDiscover(text) ?: continue
                    if (discover.pin != expectedPin) continue

                    // Match — reply directly to the sender's source port,
                    // repeated for UDP loss tolerance.
                    val reply = PinProtocol.buildOffer(
                        expectedPin, tcpPort, deviceName, band, sessionId, challenge
                    )
                    repeat(PinProtocol.OFFER_REPEAT_COUNT) { attempt ->
                        ensureActive()
                        try {
                            socket.send(
                                DatagramPacket(reply, reply.size, packet.address, packet.port)
                            )
                        } catch (_: Exception) { /* try next repeat */ }
                        if (attempt < PinProtocol.OFFER_REPEAT_COUNT - 1) {
                            try {
                                Thread.sleep(PinProtocol.OFFER_REPEAT_GAP_MS)
                            } catch (_: InterruptedException) { /* cancelled */ }
                        }
                    }
                    return@withContext discover.deviceName.ifEmpty {
                        packet.address?.hostAddress ?: "Sender"
                    }
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
