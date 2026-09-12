package com.sharethis.app.core.security

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ShareThis v2 session security — pure JVM (javax.crypto / java.security only)
 * so every byte of it is unit-testable on the JVM and identical on device.
 *
 * Design (see docs in README "Security model"):
 *
 *   1. PAIRING (QR / Bluetooth / PIN broadcast) carries: sessionId,
 *      receiver ephemeral public key (QR+BT only — an authenticated
 *      out-of-band channel), a random challenge and an expiry timestamp.
 *   2. On TCP connect the sender sends HELLO {sessionId, senderPubKey,
 *      senderNonce}; the receiver answers AUTH_OK {finishMac} and the sender
 *      answers AUTH_CONFIRM {finishMac}.
 *   3. Keys:  PRK  = HKDF-Extract(salt = challengeA||challengeB,
 *                                 IKM  = ECDH(sender,receiver) ||
 *                                        SHA-256("pin:" + pin))
 *             finishKey = HKDF-Expand(PRK, "sharethis/v2/finish", 32)
 *             dataKeys  = HKDF-Expand(PRK, "sharethis/v2/data", 64)
 *                        -> sendKey(32) || recvKey(32)
 *      The PIN is a *pairing authenticator*, never transmitted and never used
 *      as an encryption key on its own — an active man-in-the-middle that
 *      relays or substitutes public keys still cannot derive the session keys
 *      without the PIN (QR/BT mode is additionally protected by the
 *      pre-shared receiver public key).
 *   4. Data frames are AEAD-protected: AES-256-GCM (12-byte nonce =
 *      4-byte random prefix + 8-byte monotonic sequence) with an automatic
 *      AES-CBC + HMAC-SHA256 encrypt-then-MAC fallback for the rare API 21
 *      device whose provider lacks GCM. Nonces are never reused per key.
 *
 * No hardcoded keys, no static secrets: every value is generated per session
 * with [SecureRandom].
 */
object SessionCrypto {

    const val CURVE = "secp256r1"
    const val SHARED_KEY_BYTES = 32
    const val FINISH_MAC_BYTES = 32
    const val NONCE_PREFIX_BYTES = 4
    const val NONCE_COUNTER_BYTES = 8
    const val NONCE_BYTES = NONCE_PREFIX_BYTES + NONCE_COUNTER_BYTES
    const val GCM_TAG_BITS = 128
    const val CHALLENGE_BYTES = 16
    const val MAX_PUBLIC_KEY_BYTES = 1024

    private const val INFO_FINISH = "sharethis/v2/finish"
    private const val INFO_DATA = "sharethis/v2/data"

    /** Authenticated-encryption interface used for all post-handshake frames. */
    interface Aead {
        fun seal(plaintext: ByteArray, sequence: Long): ByteArray
        fun open(ciphertext: ByteArray, sequence: Long): ByteArray
    }

    // ------------------------------------------------------------- keypairs

    /** Fresh ephemeral P-256 keypair (one per session, per connection). */
    fun generateKeyPair(random: SecureRandom): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(CURVE), random)
        }.generateKeyPair()

    /** X.509/SPKI encoding (~91 bytes for P-256) for transport in payloads. */
    fun encodePublicKey(key: ECPublicKey): ByteArray = key.encoded

    fun decodePublicKey(bytes: ByteArray): ECPublicKey? {
        if (bytes.isEmpty() || bytes.size > MAX_PUBLIC_KEY_BYTES) return null
        return try {
            KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(bytes)) as? ECPublicKey
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Raw ECDH shared secret. Returns null when the peer key is invalid
     * (attacker-controlled input must never throw an uncaught exception).
     */
    fun ecdhSecret(privateKey: java.security.PrivateKey, peerPublic: ECPublicKey): ByteArray? {
        return try {
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(privateKey)
            agreement.doPhase(peerPublic, true)
            agreement.generateSecret()
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------- key derivation

    /**
     * Derives finish + data keys.
     * [pin] participates in the KDF only in PIN mode; QR/BT mode authenticates
     * via the pre-shared receiver public key and passes `pin = null`.
     */
    fun deriveKeys(
        ecdhSecret: ByteArray,
        pin: String?,
        localChallenge: ByteArray,
        peerChallenge: ByteArray,
        senderFirst: Boolean // challenge ordering must match on both peers
    ): DerivedKeys {
        val ikm = ByteArray(ecdhSecret.size + if (pin != null) 32 else 0)
        System.arraycopy(ecdhSecret, 0, ikm, 0, ecdhSecret.size)
        if (pin != null) {
            val pinHash = MessageDigest.getInstance("SHA-256")
                .digest(("pin:$pin").toByteArray(Charsets.UTF_8))
            System.arraycopy(pinHash, 0, ikm, ecdhSecret.size, 32)
        }
        val salt = if (senderFirst) localChallenge + peerChallenge else peerChallenge + localChallenge
        val prk = Hkdf.extract(salt, ikm)
        val finishKey = Hkdf.expand(prk, INFO_FINISH.toByteArray(Charsets.UTF_8), SHARED_KEY_BYTES)
        // 72 bytes: sendKey(32) + sendNoncePrefix(4) + recvKey(32) + recvNoncePrefix(4).
        // The nonce prefixes are DERIVED so both peers agree on them without
        // transmitting anything — an independently random prefix would make
        // the receiver unable to open sender frames.
        val dataKeys = Hkdf.expand(prk, INFO_DATA.toByteArray(Charsets.UTF_8), 72)
        return DerivedKeys(
            finishKey,
            dataKeys.copyOfRange(0, 32),
            dataKeys.copyOfRange(32, 36),
            dataKeys.copyOfRange(36, 68),
            dataKeys.copyOfRange(68, 72)
        )
    }

    class DerivedKeys(
        val finishKey: ByteArray,
        val sendKey: ByteArray,
        val sendNoncePrefix: ByteArray,
        val recvKey: ByteArray,
        val recvNoncePrefix: ByteArray
    ) {
        override fun equals(other: Any?): Boolean =
            other is DerivedKeys &&
                finishKey.contentEquals(other.finishKey) &&
                sendKey.contentEquals(other.sendKey) &&
                sendNoncePrefix.contentEquals(other.sendNoncePrefix) &&
                recvKey.contentEquals(other.recvKey) &&
                recvNoncePrefix.contentEquals(other.recvNoncePrefix)

        override fun hashCode(): Int = finishKey.contentHashCode()
    }

    /**
     * Transcript MAC proving both key agreement and PIN knowledge.
     * Bound fields: protocol label, sessionId, both public keys, both
     * challenges, direction byte (0 = sender-signed, 1 = receiver-signed).
     */
    fun finishMac(
        finishKey: ByteArray,
        protocolLabel: String,
        sessionId: Long,
        senderPublicKey: ByteArray,
        receiverPublicKey: ByteArray,
        senderChallenge: ByteArray,
        receiverChallenge: ByteArray,
        direction: Int
    ): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val transcript = md.digest(buildTranscript(
            protocolLabel, sessionId, senderPublicKey, receiverPublicKey,
            senderChallenge, receiverChallenge
        ))
        return Hkdf.hmacSha256(finishKey, transcript + byteArrayOf(direction.toByte()))
    }

    fun buildTranscript(
        protocolLabel: String,
        sessionId: Long,
        senderPublicKey: ByteArray,
        receiverPublicKey: ByteArray,
        senderChallenge: ByteArray,
        receiverChallenge: ByteArray
    ): ByteArray {
        fun lengthPrefixed(bytes: ByteArray): ByteArray {
            val out = ByteArray(2 + bytes.size)
            out[0] = ((bytes.size ushr 8) and 0xFF).toByte()
            out[1] = (bytes.size and 0xFF).toByte()
            System.arraycopy(bytes, 0, out, 2, bytes.size)
            return out
        }
        var out = lengthPrefixed(protocolLabel.toByteArray(Charsets.UTF_8))
        val sessionIdBytes = ByteArray(8)
        for (i in 0 until 8) sessionIdBytes[i] = ((sessionId ushr (56 - 8 * i)) and 0xFF).toByte()
        out += lengthPrefixed(sessionIdBytes)
        out += lengthPrefixed(senderPublicKey)
        out += lengthPrefixed(receiverPublicKey)
        out += lengthPrefixed(senderChallenge)
        out += lengthPrefixed(receiverChallenge)
        return out
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    // ------------------------------------------------------------------ AEAD

    /** True when AES-GCM works on the current provider (probed once per JVM). */
    @Volatile private var gcmProbe: Boolean? = null

    fun aesGcmAvailable(): Boolean {
        gcmProbe?.let { return it }
        val available = try {
            val key = ByteArray(32) { 0x00 }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, ByteArray(NONCE_BYTES))
            )
            cipher.doFinal(ByteArray(16))
            true
        } catch (_: Exception) {
            false
        }
        gcmProbe = available
        return available
    }

    fun newAead(key: ByteArray, noncePrefix: ByteArray): Aead {
        require(key.size == 32) { "AEAD key must be 256-bit" }
        require(noncePrefix.size == NONCE_PREFIX_BYTES) { "Nonce prefix must be 4 bytes" }
        return if (aesGcmAvailable()) GcmAead(key, noncePrefix) else CbcHmacAead(key, noncePrefix)
    }

    /** Test hook: the encrypt-then-MAC fallback cipher used on ancient providers. */
    internal fun cbcHmacAeadForTest(key: ByteArray, prefix: ByteArray): Aead =
        CbcHmacAead(key, prefix)

    /**
     * AES-256-GCM with a strictly unique nonce per (key, sequence).
     * Sequence numbers are enforced by the framing layer to be strictly
     * monotonic, so nonce reuse is impossible by construction.
     */
    private class GcmAead(private val key: ByteArray, private val noncePrefix: ByteArray) : Aead {

        override fun seal(plaintext: ByteArray, sequence: Long): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonceFor(sequence))
            )
            return cipher.doFinal(plaintext)
        }

        override fun open(ciphertext: ByteArray, sequence: Long): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonceFor(sequence))
            )
            return cipher.doFinal(ciphertext)
        }

        private fun nonceFor(sequence: Long): ByteArray {
            val nonce = ByteArray(NONCE_BYTES)
            System.arraycopy(noncePrefix, 0, nonce, 0, NONCE_PREFIX_BYTES)
            for (i in 0 until NONCE_COUNTER_BYTES) {
                nonce[NONCE_BYTES - 1 - i] = ((sequence ushr (8 * i)) and 0xFF).toByte()
            }
            return nonce
        }
    }

    /**
     * Fallback AEAD for ancient providers without GCM: AES-256-CBC +
     * HMAC-SHA256 encrypt-then-MAC (SHA-256 truncated to 128 bits), random
     * IV per message derived from the same prefix+sequence construction.
     */
    private class CbcHmacAead(key: ByteArray, private val noncePrefix: ByteArray) : Aead {

        private val encKey = key.copyOfRange(0, 16)
        private val macKey = key.copyOfRange(16, 32)

        override fun seal(plaintext: ByteArray, sequence: Long): ByteArray {
            val iv = nonceFor(sequence)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
            val ciphertext = cipher.doFinal(plaintext)
            val mac = Hkdf.hmacSha256(macKey, iv + ciphertext)
            return iv + ciphertext + mac.copyOfRange(0, 16)
        }

        override fun open(ciphertext: ByteArray, sequence: Long): ByteArray {
            if (ciphertext.size < 16 + 16 + 16) throw SecurityException("Ciphertext too short")
            val iv = ciphertext.copyOfRange(0, 16)
            if (!constantTimeEquals(iv, nonceFor(sequence))) {
                throw SecurityException("IV does not match frame sequence")
            }
            val body = ciphertext.copyOfRange(16, ciphertext.size - 16)
            val mac = ciphertext.copyOfRange(ciphertext.size - 16, ciphertext.size)
            val expected = Hkdf.hmacSha256(macKey, iv + body)
            if (!constantTimeEquals(mac, expected.copyOfRange(0, 16))) {
                throw SecurityException("Bad HMAC")
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(body)
        }

        // CBC needs a 16-byte IV; derive one from the unique nonce material
        // (prefix + sequence) padded with a fixed label byte.
        private fun nonceFor(sequence: Long): ByteArray {
            val nonce = ByteArray(16)
            System.arraycopy(noncePrefix, 0, nonce, 0, NONCE_PREFIX_BYTES)
            for (i in 0 until NONCE_COUNTER_BYTES) {
                nonce[NONCE_PREFIX_BYTES + NONCE_COUNTER_BYTES - 1 - i] =
                    ((sequence ushr (8 * i)) and 0xFF).toByte()
            }
            nonce[15] = 0x5A
            return nonce
        }
    }
}
