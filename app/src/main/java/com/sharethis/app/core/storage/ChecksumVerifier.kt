package com.sharethis.app.core.storage

import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Streaming integrity verification.
 *
 *  - CRC32: fast, *non-cryptographic* corruption detection — used for quick
 *    spool re-validation during resume and as a legacy per-file hint. It is
       never a security mechanism (the AEAD tag + SHA-256 are).
 *  - SHA-256: the whole-file cryptographic verdict. Both sender and receiver
 *    compute it while streaming (zero extra I/O passes); the receiver
 *    compares digests before a file is marked VERIFIED.
 *
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

    /** CRC32 over a region of [bytes]. */
    fun crc32Of(bytes: ByteArray, offset: Int, length: Int): Long {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value
    }

    fun crc32Of(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    /** Lowercase hex SHA-256 over [input] without closing it. */
    fun sha256Hex(input: InputStream): String {
        return toHex(sha256(input))
    }

    fun sha256Hex(bytes: ByteArray): String = toHex(sha256(bytes))

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
        return digest.digest()
    }

    fun verifyCrc32(expected: Long, actual: Long): Boolean = expected == actual

    fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val hex = "0123456789abcdef"
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            chars[index * 2] = hex[value ushr 4]
            chars[index * 2 + 1] = hex[value and 0x0F]
        }
        return String(chars)
    }

    /**
     * Incremental CRC32 accumulator used while piping socket bytes —
     * zero extra passes over the data.
     */
    class StreamingCrc32 {
        private val crc = CRC32()

        fun update(buffer: ByteArray, offset: Int, length: Int) {
            crc.update(buffer, offset, length)
        }

        val value: Long get() = crc.value

        fun reset() = crc.reset()
    }

    /**
     * Incremental SHA-256 accumulator — the sender computes the file digest
     * while streaming, the receiver while re-assembling, so multi-GB files
     * never need a second pre-read pass.
     */
    class StreamingSha256 {
        private val digest = MessageDigest.getInstance("SHA-256")

        fun update(buffer: ByteArray, offset: Int, length: Int) {
            digest.update(buffer, offset, length)
        }

        fun digestHex(): String = toHex(digest.digest())

        /** After [digestHex] the instance is consumed — reset for reuse. */
        fun reset() {
            digest.reset()
        }
    }
}
