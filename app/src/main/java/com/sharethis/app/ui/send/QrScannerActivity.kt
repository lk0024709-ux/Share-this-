package com.sharethis.app.ui.send

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.sharethis.app.core.pairing.QrCodePayloadHandler
import com.sharethis.app.databinding.ActivityQrScannerBinding
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Offline QR scanner (CameraX + ZXing, no Play Services).
 * Returns the raw QR text via [EXTRA_QR_TEXT] when a ShareThis code is seen.
 */
class QrScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QR_TEXT = "qr_text"
        private const val FRAME_THROTTLE_MS = 150L
    }

    private lateinit var binding: ActivityQrScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private var handled = false
    private var lastFrameMs = 0L

    private val cameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is needed to scan", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnScannerCancel.setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(binding.previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy) }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                    )
                } catch (_: Exception) {
                    Toast.makeText(this, "Camera unavailable", Toast.LENGTH_LONG).show()
                    finish()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun processFrame(proxy: ImageProxy) {
        try {
            if (handled) return
            val now = System.currentTimeMillis()
            if (now - lastFrameMs < FRAME_THROTTLE_MS) return
            lastFrameMs = now

            val nv21 = yuv420888ToNv21(proxy) ?: return
            val text = QrCodePayloadHandler.decodeYuvData(nv21, proxy.width, proxy.height)
            if (text != null && QrCodePayloadHandler.decodePayload(text) != null) {
                handled = true
                runOnUiThread {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_QR_TEXT, text))
                    finish()
                }
            }
        } catch (_: Exception) { /* keep scanning */
        } finally {
            try {
                proxy.close()
            } catch (_: Exception) { /* ignore */
            }
        }
    }

    /**
     * Packs YUV_420_888 planes into NV21 expected by ZXing's
     * [com.google.zxing.PlanarYUVLuminanceSource], honoring row/pixel strides.
     */
    private fun yuv420888ToNv21(proxy: ImageProxy): ByteArray? {
        val width = proxy.width
        val height = proxy.height
        val planes = proxy.planes
        if (planes.size < 3) return null
        try {
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            val nv21 = ByteArray(width * height * 3 / 2)

            // Y plane (full resolution).
            if (yRowStride == width) {
                yBuffer.get(nv21, 0, width * height)
            } else {
                var dest = 0
                val row = ByteArray(width)
                val duplicated = (yBuffer as ByteBuffer).duplicate()
                for (r in 0 until height) {
                    duplicated.position(r * yRowStride)
                    duplicated.get(row, 0, width)
                    System.arraycopy(row, 0, nv21, dest, width)
                    dest += width
                }
            }

            // Interleaved VU plane (quarter resolution, NV21 = V first).
            var dest = width * height
            val uvWidth = width / 2
            val uvHeight = height / 2
            for (r in 0 until uvHeight) {
                for (c in 0 until uvWidth) {
                    val vIndex = r * uvRowStride + c * uvPixelStride
                    val uIndex = r * vPlane.rowStride + c * vPlane.pixelStride
                    if (vIndex >= vBuffer.limit() || uIndex >= uBuffer.limit()) {
                        return null
                    }
                    nv21[dest++] = vBuffer.get(vIndex)
                    nv21[dest++] = uBuffer.get(uIndex)
                }
            }
            return nv21
        } catch (_: Exception) {
            return null
        }
    }
}
