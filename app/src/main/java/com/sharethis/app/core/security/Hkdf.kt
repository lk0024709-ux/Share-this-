package com.sharethis.app.core.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 5869 HKDF over HMAC-SHA256 — pure JVM, zero platform dependencies.
 *
 * Used to derive the ShareThis session keys from the ECDH shared secret plus
 * the pairing secret (PIN) and both sides' nonces. HKDF is preferred over raw
 * hashing the shared secret because it gives independent, domain-separated
 * keys (finish-MAC key, send key, receive key) from one extraction.
 */
object Hkdf {

    private const val HASH_LEN = 32 // SHA-256

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-Extract (step 1). Salt may be empty. */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmacSha256(actualSalt, ikm)
    }

    /**
     * HKDF-Expand (step 2). Output length must satisfy
     * `1 <= length <= 255 * HASH_LEN`. [info] binds the derived key to its
     * purpose (e.g. "sharethis/v2/data") and the session transcript.
     */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN) { "Invalid HKDF output length: $length" }
        val out = ByteArray(length)
        var generated = 0
        var counter = 1
        var previous = ByteArray(0)
        while (generated < length) {
            val input = ByteArray(previous.size + info.size + 1)
            System.arraycopy(previous, 0, input, 0, previous.size)
            System.arraycopy(info, 0, input, previous.size, info.size)
            input[input.size - 1] = counter.toByte()
            previous = hmacSha256(prk, input)
            val copy = minOf(previous.size, length - generated)
            System.arraycopy(previous, 0, out, generated, copy)
            generated += copy
            counter++
        }
        return out
    }

    /** Convenience: extract + expand in one call. */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)
}
