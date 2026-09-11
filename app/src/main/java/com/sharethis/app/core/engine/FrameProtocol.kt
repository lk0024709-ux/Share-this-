package com.sharethis.app.core.engine

import com.sharethis.app.data.models.FileMetadata
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * ShareThis wire framing — pure JVM, fully unit-tested.
 *
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     MAGIC "SHT1" (4 bytes)                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  PROTOCOL_VERSION (int32 BE)                  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                   HEADER_LEN (int32 BE, bytes)                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * /              HEADER_JSON (HEADER_LEN bytes, UTF-8)            /
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * /        BODY (raw file bytes, only for TYPE_FILE frames)      /
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ```
 *
 * Frame sequence per session:
 *   MANIFEST -> (FILE + body -> CHECKSUM)* -> END
 *
 * The trailing CHECKSUM frame lets the sender compute CRC32 *while*
 * streaming, so multi-GB files never need a second pre-read pass.
 */
object FrameProtocol {

    private val MAGIC = byteArrayOf(0x53, 0x48, 0x54, 0x31) // "SHT1"
    private val UTF8: Charset = Charset.forName("UTF-8")

    const val PROTOCOL_VERSION = 1

    const val TYPE_MANIFEST = "MANIFEST"
    const val TYPE_FILE = "FILE"
    const val TYPE_CHECKSUM = "CHECKSUM"
    const val TYPE_END = "END"

    const val MAX_HEADER_BYTES = 256 * 1024

    private const val PREFIX_BYTES = 12 // magic(4) + version(4) + headerLen(4)

    data class FrameHeader(
        val type: String,
        val fileName: String = "",
        val fileSize: Long = 0L,
        val mimeType: String = FileMetadata.MIME_DEFAULT,
        val crc32: Long = 0L,
        val fileIndex: Int = 0,
        val filesTotal: Int = 0,
        val manifest: List<FileMetadata> = emptyList()
    )

    // ------------------------------------------------------------------ codec

    fun encodeHeader(header: FrameHeader): ByteArray {
        val filesJson = header.manifest.joinToString(",", "[", "]") { file ->
            JsonCodec.obj(
                "name" to file.fileName,
                "size" to file.fileSize,
                "mime" to file.mimeType
            )
        }
        val json = JsonCodec.obj(
            "v" to PROTOCOL_VERSION,
            "type" to header.type,
            "name" to header.fileName,
            "size" to header.fileSize,
            "mime" to header.mimeType,
            "crc" to header.crc32,
            "idx" to header.fileIndex,
            "total" to header.filesTotal,
            "files" to JsonCodec.RawJson(filesJson)
        )
        return json.toByteArray(UTF8)
    }

    fun decodeHeader(bytes: ByteArray): FrameHeader {
        val map = JsonCodec.parseObject(String(bytes, UTF8))
        val manifest = JsonCodec.parseArray(map["files"] ?: "[]").mapNotNull { element ->
            val file = JsonCodec.parseObject(element)
            val name = JsonCodec.asString(file["name"])
            if (name.isEmpty()) return@mapNotNull null
            FileMetadata(
                fileName = name,
                fileSize = JsonCodec.asLong(file["size"]),
                mimeType = JsonCodec.asString(file["mime"]).ifEmpty { FileMetadata.MIME_DEFAULT }
            )
        }
        return FrameHeader(
            type = JsonCodec.asString(map["type"]),
            fileName = JsonCodec.asString(map["name"]),
            fileSize = JsonCodec.asLong(map["size"]),
            mimeType = JsonCodec.asString(map["mime"]).ifEmpty { FileMetadata.MIME_DEFAULT },
            crc32 = JsonCodec.asLong(map["crc"]),
            fileIndex = JsonCodec.asInt(map["idx"]),
            filesTotal = JsonCodec.asInt(map["total"]),
            manifest = manifest
        )
    }

    // ------------------------------------------------------------------ I/O

    @Throws(IOException::class)
    fun writeFrame(out: OutputStream, header: FrameHeader) {
        val json = encodeHeader(header)
        if (json.size > MAX_HEADER_BYTES) {
            throw IOException("Header too large: ${json.size} bytes")
        }
        val prefix = ByteBuffer.allocate(PREFIX_BYTES)
        prefix.put(MAGIC)
        prefix.putInt(PROTOCOL_VERSION)
        prefix.putInt(json.size)
        out.write(prefix.array())
        out.write(json)
        out.flush()
    }

    @Throws(IOException::class)
    fun readFrame(input: InputStream): FrameHeader {
        val prefix = ByteArray(PREFIX_BYTES)
        readFully(input, prefix, 0, PREFIX_BYTES)
        for (i in MAGIC.indices) {
            if (prefix[i] != MAGIC[i]) {
                throw IOException("Bad frame magic — peer is not speaking ShareThis protocol")
            }
        }
        val buffer = ByteBuffer.wrap(prefix)
        buffer.position(4)
        val version = buffer.int
        if (version != PROTOCOL_VERSION) {
            throw IOException("Unsupported protocol version: $version")
        }
        val headerLen = buffer.int
        if (headerLen <= 0 || headerLen > MAX_HEADER_BYTES) {
            throw IOException("Invalid header length: $headerLen")
        }
        val json = ByteArray(headerLen)
        readFully(input, json, 0, headerLen)
        return decodeHeader(json)
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
}
