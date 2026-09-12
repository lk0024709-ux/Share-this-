package com.sharethis.app.core.network

import com.sharethis.app.core.engine.JsonCodec
import com.sharethis.app.core.security.Base64Url
import java.security.SecureRandom

/**
 * PIN pairing packet codec v2 (pure JVM — the socket engine lives in
 * [PinPairingEngine], the wire format is unit-tested here).
 *
 * Flow over subnet UDP broadcast:
 *  1. Sender pulses  `SHARETHIS_PIN2|<pin>|<name>` to 255.255.255.255:8888.
 *  2. Receiver validates the PIN and unicasts
 *     `SHARETHIS_OFFER2|<pin>|<tcpPort>|<name>|<band>|<sid>|<challenge>`
 *     back to the sender's source port. The sender takes the packet's source
 *     IP as the receiver address — no IP enumeration on either side.
 *
 * The offer carries the session id + receiver challenge needed for the
 * authenticated TCP handshake. The receiver's *public key* is intentionally
 * NOT in the offer (UDP is unauthenticated): in PIN mode the shared PIN
 * itself binds the key exchange, so a man-in-the-middle cannot derive the
 * session keys.
 */
object PinProtocol {

    const val PIN_PORT = 8888
    const val BROADCAST_IP = "255.255.255.255"

    const val PREFIX_DISCOVER = "SHARETHIS_PIN2"
    const val PREFIX_OFFER = "SHARETHIS_OFFER2"

    const val PACKET_MAX_BYTES = 512
    const val BROADCAST_PULSE_MS = 1000L
    const val OFFER_REPEAT_COUNT = 3
    const val OFFER_REPEAT_GAP_MS = 100L

    data class ParsedDiscover(val pin: String, val deviceName: String)

    data class ParsedOffer(
        val pin: String,
        val tcpPort: Int,
        val deviceName: String,
        val band: String,
        val sessionId: Long,
        val challenge: ByteArray
    )

    fun generatePin(random: SecureRandom = SecureRandom()): String =
        (random.nextInt(900000) + 100000).toString()

    fun isValidPin(pin: String): Boolean = pin.length == 6 && pin.all { it.isDigit() }

    // ------------------------------------------------------ packet codec

    fun buildDiscover(pin: String, deviceName: String): ByteArray =
        "$PREFIX_DISCOVER|$pin|${sanitize(deviceName)}".toByteArray(Charsets.UTF_8)

    fun parseDiscover(text: String): ParsedDiscover? {
        val parts = text.trim().split('|')
        if (parts.size != 3 || parts[0] != PREFIX_DISCOVER) return null
        if (!isValidPin(parts[1])) return null
        return ParsedDiscover(parts[1], parts[2].take(64))
    }

    fun buildOffer(
        pin: String,
        tcpPort: Int,
        deviceName: String,
        band: String,
        sessionId: Long,
        challenge: ByteArray
    ): ByteArray =
        ("$PREFIX_OFFER|$pin|$tcpPort|${sanitize(deviceName)}|${sanitize(band)}" +
            "|${Base64Url.encodeToHex(sessionId.toByteArray())}" +
            "|${Base64Url.encode(challenge)}").toByteArray(Charsets.UTF_8)

    fun parseOffer(text: String): ParsedOffer? {
        val parts = text.trim().split('|')
        if (parts.size != 7 || parts[0] != PREFIX_OFFER) return null
        if (!isValidPin(parts[1])) return null
        val port = parts[2].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val sessionId = sessionIdFromHex(parts[5]) ?: return null
        val challenge = Base64Url.decode(parts[6]) ?: return null
        if (challenge.size != 16) return null
        return ParsedOffer(
            pin = parts[1],
            tcpPort = port,
            deviceName = parts[3].take(64),
            band = parts[4].take(16),
            sessionId = sessionId,
            challenge = challenge
        )
    }

    private fun Long.toByteArray(): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = ((this ushr (56 - 8 * i)) and 0xFF).toByte()
        return out
    }

    private fun sessionIdFromHex(hex: String): Long? {
        if (hex.length != 16) return null
        var value = 0L
        for (i in 0 until 8) {
            val byte = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            value = (value shl 8) or byte.toLong()
        }
        return value
    }

    private fun sanitize(value: String): String =
        value.replace('|', ' ').replace('\n', ' ').trim().take(64)
}
