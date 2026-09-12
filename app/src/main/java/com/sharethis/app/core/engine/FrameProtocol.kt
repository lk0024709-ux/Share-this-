package com.sharethis.app.core.engine

import com.sharethis.app.core.security.SessionCrypto
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * ShareThis wire protocol v2 — binary framing, pure JVM, fully unit-tested.
 *
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                    MAGIC "SHT2" (4 bytes)                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |VERSION|  TYPE |    FLAGS     |        (reserved=0)            |  1+1+2+4
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                        SESSION ID (int64 BE)                  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                        TRANSFER ID (int32 BE)                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         FILE ID (int32 BE)                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                        CHUNK INDEX (int32 BE)                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     SEQUENCE (int64 BE, per-direction)        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      PAYLOAD LENGTH (int32 BE)                |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                       META LENGTH (int32 BE)                  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     HEADER CRC32 (over bytes 0..43)           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * /        META (metaLength bytes, UTF-8 JSON)                    /
 * /        PAYLOAD (payloadLength bytes, file chunk or sealed)    /
 * ```
 *
 * 48-byte header. After authentication every frame is sealed with the
 * session AEAD: [2-byte meta length][meta][payload] is encrypted+authenticated
 * as one blob and carried as the frame payload with FLAG_ENCRYPTED.
 *
 * Every received value is validated — magic, version, bounds, lengths,
 * session/transfer identity and header CRC — before any allocation.
 */
object FrameProtocol {

    val MAGIC = byteArrayOf(0x53, 0x48, 0x54, 0x32) // "SHT2"
    private val MAGIC_V1 = byteArrayOf(0x53, 0x48, 0x54, 0x31) // "SHT1" (v1 peer)

    const val VERSION = 2
    const val HEADER_BYTES = 52
    const val PREFIX_BYTES = 48 // everything before the CRC field

    // Frame types (1 byte on the wire).
    const val TYPE_HELLO = 1          // plaintext: sender handshake
    const val TYPE_AUTH_OK = 2        // plaintext: receiver handshake + finish MAC + resume offer
    const val TYPE_AUTH_CONFIRM = 3   // plaintext: sender finish MAC
    const val TYPE_MANIFEST = 4       // sealed: file list for the transfer
    const val TYPE_FILE_META = 5      // sealed: per-file metadata + chunk size
    const val TYPE_CHUNK = 6          // sealed: one chunk of file bytes
    const val TYPE_FILE_DONE = 7      // sealed: sender's SHA-256 + CRC32 for the file
    const val TYPE_FILE_ACK = 8       // sealed (receiver->sender): file result
    const val TYPE_END = 9            // sealed: sender finished the whole batch
    const val TYPE_ABORT = 10         // either side: fatal error report
    const val TYPE_PAUSE = 11         // sender: cooperative pause (resume supported)
    const val TYPE_MANIFEST_ACK = 12  // sealed (receiver->sender): storage pre-flight result

    // Flags.
    const val FLAG_ENCRYPTED = 1
    const val FLAG_LAST_CHUNK = 2

    /** Chunk payloads are capped at 4 MiB (+ AEAD overhead) — memory-exhaustion guard. */
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
    const val MAX_META_BYTES = 64 * 1024

    private val VALID_TYPES = setOf(
        TYPE_HELLO, TYPE_AUTH_OK, TYPE_AUTH_CONFIRM, TYPE_MANIFEST, TYPE_FILE_META,
        TYPE_CHUNK, TYPE_FILE_DONE, TYPE_FILE_ACK, TYPE_END, TYPE_ABORT, TYPE_PAUSE,
        TYPE_MANIFEST_ACK
    )

    class ProtocolException(message: String) : IOException(message)

    class UnsupportedProtocolException(message: String) : IOException(message)

    /** A parsed frame. Meta is raw JSON bytes; payload is body bytes (or sealed blob). */
    class Frame(
        val type: Int,
        val flags: Int,
        val sessionId: Long,
        val transferId: Int,
        val fileId: Int,
        val chunkIndex: Int,
        val sequence: Long,
        val meta: ByteArray,
        val payload: ByteArray,
        val payloadOffset: Int = 0,
        val payloadLength: Int = payload.size
    ) {
        val isEncrypted: Boolean get() = flags and FLAG_ENCRYPTED != 0

        /** Copies the payload region into a fresh array (tests / control frames). */
        fun payloadCopy(): ByteArray =
            if (payloadOffset == 0 && payloadLength == payload.size) payload
            else payload.copyOfRange(payloadOffset, payloadOffset + payloadLength)
    }

    /**
     * Validated header without meta/payload — lets the engine reuse fixed
     * buffers for chunk payloads instead of allocating per frame.
     */
    class FrameHeader(
        val type: Int,
        val flags: Int,
        val sessionId: Long,
        val transferId: Int,
        val fileId: Int,
        val chunkIndex: Int,
        val sequence: Long,
        val metaLength: Int,
        val payloadLength: Int
    ) {
        val isEncrypted: Boolean get() = flags and FLAG_ENCRYPTED != 0
        val isLastChunk: Boolean get() = flags and FLAG_LAST_CHUNK != 0
    }

    // ------------------------------------------------------------------ write

    @Throws(IOException::class)
    fun writeFrame(
        out: OutputStream,
        type: Int,
        flags: Int,
        sessionId: Long,
        transferId: Int,
        fileId: Int,
        chunkIndex: Int,
        sequence: Long,
        meta: ByteArray,
        payload: ByteArray
    ) {
        require(type in VALID_TYPES) { "Unknown frame type $type" }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Payload too large: ${payload.size}" }
        require(meta.size <= MAX_META_BYTES) { "Meta too large: ${meta.size}" }
        if (sequence < 0) throw ProtocolException("Sequence must be >= 0")

        val header = ByteBuffer.allocate(HEADER_BYTES)
        header.put(MAGIC)
        header.put(VERSION.toByte())
        header.put(type.toByte())
        header.putShort(flags.toShort())
        header.putInt(0) // reserved
        header.putLong(sessionId)
        header.putInt(transferId)
        header.putInt(fileId)
        header.putInt(chunkIndex)
        header.putLong(sequence)
        header.putInt(payload.size)
        header.putInt(meta.size)
        val crc = CRC32().apply { update(header.array(), 0, PREFIX_BYTES) }
        header.putInt(crc.value.toInt())
        out.write(header.array())
        if (meta.isNotEmpty()) out.write(meta)
        if (payload.isNotEmpty()) out.write(payload)
    }

    // ------------------------------------------------------------------- read

    /**
     * Reads and validates one frame header. Peer-controlled lengths are
     * clamped by [MAX_PAYLOAD_BYTES]/[MAX_META_BYTES] so a hostile peer
     * cannot trigger multi-gigabyte allocations.
     */
    @Throws(IOException::class)
    fun readHeader(input: InputStream): FrameHeader {
        val header = ByteArray(HEADER_BYTES)
        readFully(input, header, 0, HEADER_BYTES)
        for (i in MAGIC.indices) {
            if (header[i] == MAGIC[i]) continue
            if (header[i] == MAGIC_V1[i]) {
                throw UnsupportedProtocolException(
                    "Peer runs ShareThis v1 (protocol 1) — update ShareThis on both devices"
                )
            }
            throw ProtocolException("Bad frame magic — peer is not speaking ShareThis v2")
        }
        val buffer = ByteBuffer.wrap(header)
        buffer.position(4)
        val version = buffer.get().toInt() and 0xFF
        if (version != VERSION) {
            throw UnsupportedProtocolException(
                "Incompatible ShareThis protocol version: $version (this device speaks $VERSION)"
            )
        }
        val type = buffer.get().toInt() and 0xFF
        if (type !in VALID_TYPES) throw ProtocolException("Unknown frame type: $type")
        val flags = buffer.short.toInt() and 0xFFFF
        buffer.getInt() // reserved
        val sessionId = buffer.long
        val transferId = buffer.int
        val fileId = buffer.int
        val chunkIndex = buffer.int
        val sequence = buffer.long
        val payloadLength = buffer.int
        val metaLength = buffer.int
        val headerCrc = buffer.int
        if (sequence < 0) throw ProtocolException("Negative sequence number")
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
            throw ProtocolException("Invalid payload length: $payloadLength")
        }
        if (metaLength < 0 || metaLength > MAX_META_BYTES) {
            throw ProtocolException("Invalid meta length: $metaLength")
        }
        val crc = CRC32().apply { update(header, 0, PREFIX_BYTES) }
        if (crc.value.toInt() != headerCrc) {
            throw ProtocolException("Header CRC mismatch — corrupted connection")
        }
        return FrameHeader(
            type, flags, sessionId, transferId, fileId, chunkIndex,
            sequence, metaLength, payloadLength
        )
    }

    /**
     * Reads a full frame (header + meta + payload) with fresh allocations —
     * convenience for tests and small control frames.
     */
    @Throws(IOException::class)
    fun readFrame(input: InputStream): Frame {
        val h = readHeader(input)
        val meta = if (h.metaLength > 0) ByteArray(h.metaLength) else ByteArray(0)
        if (h.metaLength > 0) readFully(input, meta, 0, h.metaLength)
        val payload = if (h.payloadLength > 0) ByteArray(h.payloadLength) else ByteArray(0)
        if (h.payloadLength > 0) readFully(input, payload, 0, h.payloadLength)
        return Frame(
            h.type, h.flags, h.sessionId, h.transferId, h.fileId, h.chunkIndex,
            h.sequence, meta, payload
        )
    }

    @Throws(IOException::class)
    fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var remaining = length
        var position = offset
        while (remaining > 0) {
            val read = input.read(buffer, position, remaining)
            if (read == -1) throw EOFException("Unexpected end of stream")
            position += read
            remaining -= read
        }
    }

    /**
     * Reads exactly [length] bytes unless EOF arrives first. Returns the
     * number of bytes read (== length on success) or -1 on premature EOF.
     */
    @Throws(IOException::class)
    fun readOrEof(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var remaining = length
        var position = offset
        while (remaining > 0) {
            val read = input.read(buffer, position, remaining)
            if (read == -1) {
                return if (remaining == length) -1 else position - offset
            }
            position += read
            remaining -= read
        }
        return length
    }

    // --------------------------------------------------------------- sealing

    /**
     * Seals [meta] + [payload] into one AEAD blob:
     * `seal(2-byte-metaLen || meta || payload, sequence)`.
     */
    fun seal(
        aead: SessionCrypto.Aead,
        sequence: Long,
        meta: ByteArray,
        payload: ByteArray
    ): ByteArray = seal(aead, sequence, meta, payload, 0, payload.size)

    /** Region variant — lets the engine seal a chunk in place, zero copy. */
    fun seal(
        aead: SessionCrypto.Aead,
        sequence: Long,
        meta: ByteArray,
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray {
        require(meta.size <= MAX_META_BYTES) { "Meta too large" }
        val plain = ByteArray(2 + meta.size + length)
        plain[0] = ((meta.size ushr 8) and 0xFF).toByte()
        plain[1] = (meta.size and 0xFF).toByte()
        if (meta.isNotEmpty()) System.arraycopy(meta, 0, plain, 2, meta.size)
        if (length > 0) System.arraycopy(buffer, offset, plain, 2 + meta.size, length)
        return aead.seal(plain, sequence)
    }

    /** Result of opening a sealed frame — payload stays zero-copy inside [payload]. */
    class OpenedFrame(
        val meta: ByteArray,
        val payload: ByteArray,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    /** Opens a sealed blob; returns meta + payload view. Throws on any tampering. */
    fun openSealed(
        aead: SessionCrypto.Aead,
        blob: ByteArray,
        sequence: Long
    ): OpenedFrame {
        val plain = aead.open(blob, sequence)
        if (plain.size < 2) throw ProtocolException("Sealed frame too short")
        val metaLen = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
        if (2 + metaLen > plain.size) throw ProtocolException("Sealed meta length out of bounds")
        return OpenedFrame(
            meta = plain.copyOfRange(2, 2 + metaLen),
            payload = plain,
            payloadOffset = 2 + metaLen,
            payloadLength = plain.size - 2 - metaLen
        )
    }
}
