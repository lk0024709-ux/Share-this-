package com.sharethis.app.core.security

/**
 * Base64url (RFC 4648 §5, unpadded) codec — pure JVM, constant table, no
 * platform dependency (android.util.Base64 is unavailable in JVM unit tests
 * and java.util.Base64 only exists on API 26+).
 *
 * Used for compact binary payloads inside QR codes, Bluetooth handshakes and
 * JSON metadata: ephemeral public keys, nonces, challenges and MACs.
 */
object Base64Url {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val LOOKUP = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 / 3) + 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val word = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(word ushr 18) and 0x3F])
            out.append(ALPHABET[(word ushr 12) and 0x3F])
            out.append(ALPHABET[(word ushr 6) and 0x3F])
            out.append(ALPHABET[word and 0x3F])
            i += 3
        }
        val remaining = bytes.size - i
        if (remaining == 1) {
            val word = (bytes[i].toInt() and 0xFF) shl 16
            out.append(ALPHABET[(word ushr 18) and 0x3F])
            out.append(ALPHABET[(word ushr 12) and 0x3F])
        } else if (remaining == 2) {
            val word = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8)
            out.append(ALPHABET[(word ushr 18) and 0x3F])
            out.append(ALPHABET[(word ushr 12) and 0x3F])
            out.append(ALPHABET[(word ushr 6) and 0x3F])
        }
        return out.toString()
    }

    /** Lenient decode: accepts standard and URL alphabets, ignores padding/whitespace. */
    fun decode(text: String): ByteArray? {
        val compact = StringBuilder(text.length)
        for (c in text) {
            when {
                c == '=' || c == '\n' || c == '\r' || c == ' ' || c == '\t' -> Unit
                c == '+' -> compact.append('-')
                c == '/' -> compact.append('_')
                else -> compact.append(c)
            }
        }
        val len = compact.length
        if (len % 4 == 1) return null
        val out = ByteArray(len * 3 / 4)
        var outIndex = 0
        var buffer = 0
        var bits = 0
        for (i in 0 until len) {
            val c = compact[i]
            val value = if (c.code < 128) LOOKUP[c.code] else -1
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outIndex++] = ((buffer ushr bits) and 0xFF).toByte()
            }
        }
        return if (outIndex == out.size) out else out.copyOf(outIndex)
    }

    fun encodeToHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val hex = "0123456789abcdef"
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            chars[index * 2] = hex[value ushr 4]
            chars[index * 2 + 1] = hex[value and 0x0F]
        }
        return String(chars)
    }

    fun decodeHex(text: String): ByteArray? {
        val clean = text.filterNot { it.isWhitespace() }
        if (clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
