package com.sharethis.app.core.pairing

import com.sharethis.app.core.engine.JsonCodec
import com.sharethis.app.core.security.Base64Url
import com.sharethis.app.data.models.DevicePeer
import java.security.SecureRandom

/**
 * ShareThis pairing payload v2 — the single structured object carried by
 * QR codes, the Bluetooth handshake and (a subset by) the PIN offer packet.
 *
 * Security properties:
 *  - carries only *temporary* values: a random 64-bit session id, the
 *    receiver's ephemeral public key, a random challenge and an expiry
 *    timestamp — never a permanent credential;
 *  - QR/Bluetooth are authenticated out-of-band channels (screen → camera /
 *    direct RFCOMM), so the embedded public key is trusted and later verified
 *    against the TCP peer (MITM cannot substitute it);
 *  - payloads expire ([DEFAULT_TTL_MS]) — a scanned code from yesterday
 *    cannot be replayed;
 *  - [ReplayGuard] rejects a session id that was already used successfully;
 *  - the payload version is checked, so v1 apps get a clear
 *    "incompatible versions" message instead of garbage.
 */
object PairingPayload {

    const val QR_MAGIC = "SHARETHISv2"
    const val PAYLOAD_VERSION = 2
    const val DEFAULT_TTL_MS = 2 * 60_000L
    const val MAX_ENCODED_LENGTH = 1024

    data class Payload(
        val version: Int,
        val sessionId: Long,
        val challenge: ByteArray,
        val pin: String,
        val receiverPublicKey: ByteArray?,
        val ipAddress: String,
        val port: Int,
        val ssid: String = "",
        val passphrase: String = "",
        val band: String = DevicePeer.BAND_UNKNOWN,
        val deviceName: String = "",
        val security: String = "WPA2",
        val expiresAtMs: Long = 0L
    ) {
        val isExpired: Boolean get() = expiresAtMs > 0 && System.currentTimeMillis() > expiresAtMs
    }

    sealed interface DecodeResult {
        data class Ok(val payload: Payload) : DecodeResult
        data object NotShareThis : DecodeResult
        data class WrongVersion(val found: Int) : DecodeResult
        data class Expired(val payload: Payload) : DecodeResult
        data class Malformed(val reason: String) : DecodeResult
    }

    fun build(
        sessionId: Long,
        challenge: ByteArray,
        pin: String,
        receiverPublicKey: ByteArray?,
        ipAddress: String,
        port: Int,
        ssid: String,
        passphrase: String,
        band: String,
        deviceName: String,
        security: String,
        ttlMs: Long = DEFAULT_TTL_MS,
        nowMs: Long = System.currentTimeMillis()
    ): Payload = Payload(
        version = PAYLOAD_VERSION,
        sessionId = sessionId,
        challenge = challenge,
        pin = pin,
        receiverPublicKey = receiverPublicKey,
        ipAddress = ipAddress,
        port = port,
        ssid = ssid,
        passphrase = passphrase,
        band = band,
        deviceName = deviceName.take(64),
        security = security,
        expiresAtMs = nowMs + ttlMs
    )

    fun toJson(payload: Payload): String = JsonCodec.obj(
        "v" to payload.version,
        "sid" to payload.sessionId,
        "ch" to Base64Url.encode(payload.challenge),
        "pin" to payload.pin,
        "pub" to (payload.receiverPublicKey?.let { Base64Url.encode(it) } ?: ""),
        "ip" to payload.ipAddress,
        "port" to payload.port,
        "ssid" to payload.ssid,
        "pass" to payload.passphrase,
        "band" to payload.band,
        "dev" to payload.deviceName,
        "sec" to payload.security,
        "exp" to payload.expiresAtMs
    )

    fun fromJson(json: String): DecodeResult {
        val map = try {
            JsonCodec.parseObject(json)
        } catch (_: Exception) {
            return DecodeResult.Malformed("unparseable")
        }
        if (map.isEmpty()) return DecodeResult.Malformed("empty object")
        val version = JsonCodec.asInt(map["v"], -1)
        if (version != PAYLOAD_VERSION) {
            // v1 magic ("SHARETHISv1:...") is detected at the text layer; here
            // we catch v1 JSON that made it past the prefix check.
            return if (version in 1..2) DecodeResult.WrongVersion(version)
            else DecodeResult.Malformed("bad version $version")
        }
        val sessionId = JsonCodec.asLong(map["sid"])
        val challenge = Base64Url.decode(JsonCodec.asString(map["ch"]))
        val ip = JsonCodec.asString(map["ip"])
        val port = JsonCodec.asInt(map["port"])
        if (sessionId == 0L || challenge == null || challenge.size != 16 ||
            ip.isEmpty() || port !in 1..65535
        ) return DecodeResult.Malformed("missing required fields")
        val payload = Payload(
            version = version,
            sessionId = sessionId,
            challenge = challenge,
            pin = JsonCodec.asString(map["pin"]),
            receiverPublicKey = Base64Url.decode(JsonCodec.asString(map["pub"]))
                ?.takeIf { it.isNotEmpty() },
            ipAddress = ip,
            port = port,
            ssid = JsonCodec.asString(map["ssid"]),
            passphrase = JsonCodec.asString(map["pass"]),
            band = JsonCodec.asString(map["band"]).ifEmpty { DevicePeer.BAND_UNKNOWN },
            deviceName = JsonCodec.asString(map["dev"]),
            security = JsonCodec.asString(map["sec"]).ifEmpty { "WPA2" },
            expiresAtMs = JsonCodec.asLong(map["exp"])
        )
        return if (payload.isExpired) DecodeResult.Expired(payload) else DecodeResult.Ok(payload)
    }

    /** Wraps the payload in the magic-prefixed QR text format. */
    fun encode(payload: Payload): String {
        val text = "$QR_MAGIC:${toJson(payload)}"
        require(text.length <= MAX_ENCODED_LENGTH) { "QR payload too large" }
        return text
    }

    /**
     * Parses scanned QR / Bluetooth text. Accepts the v2 magic prefix and
     * rejects everything else with a precise reason (wrong-app, old version,
     * expired, malformed).
     */
    fun decode(rawText: String): DecodeResult {
        val text = rawText.trim()
        if (text.startsWith("SHARETHISv1")) return DecodeResult.WrongVersion(1)
        if (!text.startsWith(QR_MAGIC)) return DecodeResult.NotShareThis
        val json = text.removePrefix(QR_MAGIC).trimStart(':')
        if (json.isEmpty()) return DecodeResult.Malformed("empty payload")
        if (json.length > MAX_ENCODED_LENGTH) return DecodeResult.Malformed("payload too large")
        return fromJson(json)
    }

    /**
     * Remembers consumed session ids to block replay of an old (but
     * unexpired) pairing payload. Entries self-prune once they expire.
     */
    class ReplayGuard(private val maxEntries: Int = 64) {
        private val seen = LinkedHashMap<Long, Long>() // sessionId -> expiresAt

        @Synchronized
        fun isReplay(sessionId: Long, expiresAtMs: Long, nowMs: Long): Boolean {
            prune(nowMs)
            if (seen.containsKey(sessionId)) return true
            seen[sessionId] = if (expiresAtMs > 0) expiresAtMs else nowMs + DEFAULT_TTL_MS
            while (seen.size > maxEntries) {
                val eldest = seen.keys.firstOrNull() ?: break
                seen.remove(eldest)
            }
            return false
        }

        @Synchronized
        fun forget(sessionId: Long) {
            seen.remove(sessionId)
        }

        private fun prune(nowMs: Long) {
            val iterator = seen.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value < nowMs) iterator.remove() else break
            }
        }
    }

    /** Cryptographically random session id (never derived from time/PIN). */
    fun newSessionId(random: SecureRandom = SecureRandom()): Long {
        while (true) {
            val id = random.nextLong()
            if (id != 0L) return id
        }
    }

    fun newChallenge(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(16).also { random.nextBytes(it) }
}
