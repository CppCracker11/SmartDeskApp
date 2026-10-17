
package com.smartdesk.controller

import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean

class GestureFrameAnalyzer(
    private val gestureAnalyzer: GestureAnalyzer,
    private val onDetection: (Int) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val processingFrame = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (!processingFrame.compareAndSet(false, true)) {
            image.close()
            return
        }

        try {
            require(image.planes.size == 1) {
                "Expected one RGBA image plane."
            }

            val bitmap = image.toRgbaBitmap()
            val rotation = image.imageInfo.rotationDegrees

            image.close()

            gestureAnalyzer.detect(bitmap, rotation)
        } catch (exception: Exception) {
            image.close()
            processingFrame.set(false)

            onError(
                exception.localizedMessage
                    ?: "Unable to process a camera frame."
            )
        }
    }

    fun onFrameProcessed() {
        processingFrame.set(false)
    }

    private fun ImageProxy.toRgbaBitmap(): Bitmap {
        val plane = planes.first()
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = width
        val height = height

        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        android.util.Log.d(
            "SD_GESTURE",
            "Pixel bytes = " + (0..3).joinToString(", ") {
                (bytes[it].toInt() and 0xFF).toString()
            }
        )
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * rowStride + x * pixelStride

                val alpha = bytes[index].toInt() and 0xFF
                val red = bytes[index + 1].toInt() and 0xFF
                val green = bytes[index + 2].toInt() and 0xFF
                val blue = bytes[index + 3].toInt() and 0xFF

                pixels[y * width + x] =
                    (alpha shl 24) or
                            (red shl 16) or
                            (green shl 8) or
                            blue
            }
        }

        return Bitmap.createBitmap(
            pixels,
            width,
            height,
            Bitmap.Config.ARGB_8888
        )
    }
}
