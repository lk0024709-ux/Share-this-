package com.sharethis.app.core.storage

import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Streaming integrity verification.
 *
 * CRC32 is the hot-path checksum (computed inline while bytes are piped, so
 * it costs no extra I/O). MD5 is available for paranoid out-of-band checks.
 * Pure JVM — unit-tested without Android.
 */
object ChecksumVerifier {

    const val STREAM_BUFFER_BYTES = 64 * 1024

    /** CRC32 over [input] without closing it. */
    fun crc32Of(input: InputStream): Long {
        val crc = CRC32()
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            crc.update(buffer, 0, read)
        }
        return crc.value
    }

    fun crc32Of(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    /** Lowercase hex MD5 over [input] without closing it. */
    fun md5Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    fun md5Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(bytes).toHex()
    }

    fun verifyCrc32(expected: Long, actual: Long): Boolean = expected == actual

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        val hex = "0123456789abcdef"
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            chars[index * 2] = hex[value ushr 4]
            chars[index * 2 + 1] = hex[value and 0x0F]
        }
        return String(chars)
    }

    /**
     * Incremental CRC32 accumulator used by [com.sharethis.app.core.engine.FastTransferEngine]
     * while piping socket bytes — zero extra passes over the data.
     */
    class StreamingCrc32 {
        private val crc = CRC32()

        fun update(buffer: ByteArray, offset: Int, length: Int) {
            crc.update(buffer, offset, length)
        }

        fun value(): Long = crc.value

        fun reset() = crc.reset()
    }
}
