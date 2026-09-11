package com.sharethis.app.core.pairing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.sharethis.app.core.engine.JsonCodec
import com.sharethis.app.data.models.DevicePeer
import com.sharethis.app.data.models.NetworkConfig

/**
 * QR fallback transport — fully offline (ZXing core, no Play Services).
 *
 * The QR encodes: magic + JSON{ssid, passphrase, ip, port, band, device,
 * security}. The payload codec is pure JVM and unit-tested; only bitmap
 * rendering / YUV decoding touch Android/ZXing classes.
 */
object QrCodePayloadHandler {

    const val QR_MAGIC = "SHARETHISv1"

    data class QrPayload(
        val ssid: String,
        val passphrase: String,
        val ip: String,
        val port: Int,
        val band: String = DevicePeer.BAND_UNKNOWN,
        val deviceName: String = "",
        val security: String = NetworkConfig.SECURITY_WPA2
    ) {
        fun toJson(): String = JsonCodec.obj(
            "ssid" to ssid,
            "pass" to passphrase,
            "ip" to ip,
            "port" to port,
            "band" to band,
            "device" to deviceName,
            "sec" to security
        )

        fun toNetworkConfig(): NetworkConfig = NetworkConfig(
            ssid = ssid,
            passphrase = passphrase,
            ipAddress = ip,
            port = port,
            band = band,
            deviceName = deviceName,
            security = security
        )

        companion object {
            fun fromJson(json: String): QrPayload? {
                val map = JsonCodec.parseObject(json)
                val ssid = JsonCodec.asString(map["ssid"])
                val ip = JsonCodec.asString(map["ip"])
                val port = JsonCodec.asInt(map["port"])
                if (ssid.isEmpty() || ip.isEmpty() || port !in 1..65535) return null
                return QrPayload(
                    ssid = ssid,
                    passphrase = JsonCodec.asString(map["pass"]),
                    ip = ip,
                    port = port,
                    band = JsonCodec.asString(map["band"]).ifEmpty { DevicePeer.BAND_UNKNOWN },
                    deviceName = JsonCodec.asString(map["device"]),
                    security = JsonCodec.asString(map["sec"]).ifEmpty { NetworkConfig.SECURITY_WPA2 }
                )
            }

            fun fromNetworkConfig(config: NetworkConfig): QrPayload = QrPayload(
                ssid = config.ssid,
                passphrase = config.passphrase,
                ip = config.ipAddress,
                port = config.port,
                band = config.band,
                deviceName = config.deviceName,
                security = config.security
            )
        }
    }

    /** Wraps a payload in the magic-prefixed QR text format. */
    fun encodePayload(payload: QrPayload): String = "$QR_MAGIC:${payload.toJson()}"

    /** Parses scanned QR text; null when it isn't a ShareThis code. */
    fun decodePayload(rawText: String): QrPayload? {
        val text = rawText.trim()
        if (!text.startsWith(QR_MAGIC)) return null
        val json = text.removePrefix(QR_MAGIC).trimStart(':')
        if (json.isEmpty()) return null
        return QrPayload.fromJson(json)
    }

    // ------------------------------------------------------- bitmap rendering

    @Throws(Exception::class)
    fun encodeToBitmap(payloadText: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(payloadText, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    // ------------------------------------------------------------- YUV decode

    /**
     * Decodes NV21 (YUV_420_888 planar-packed) camera bytes. Returns the raw
     * QR text, or null when no code is visible in this frame.
     */
    fun decodeYuvData(yuv: ByteArray, width: Int, height: Int): String? {
        if (yuv.size < width * height) return null
        return try {
            val source = PlanarYUVLuminanceSource(yuv, width, height, 0, 0, width, height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val hints = mapOf(DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE)
            QRCodeReader().decode(bitmap, hints).text
        } catch (_: Exception) {
            null
        }
    }
}
