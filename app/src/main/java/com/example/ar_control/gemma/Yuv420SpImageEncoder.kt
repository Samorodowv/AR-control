package com.example.ar_control.gemma

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.example.ar_control.camera.PreviewSize
import java.io.ByteArrayOutputStream

object Yuv420SpImageEncoder {
    fun convertUvOrderYuv420SpToNv21(
        frameBytes: ByteArray,
        previewSize: PreviewSize
    ): ByteArray {
        val expectedSize = expectedYuv420SpByteCount(previewSize)
        require(frameBytes.size == expectedSize) {
            "Expected $expectedSize bytes for ${previewSize.width}x${previewSize.height} " +
                "YUV420SP frame, got ${frameBytes.size}"
        }

        val yPlaneSize = previewSize.width * previewSize.height
        return frameBytes.copyOf().also { nv21 ->
            for (index in yPlaneSize until expectedSize step 2) {
                val u = nv21[index]
                nv21[index] = nv21[index + 1]
                nv21[index + 1] = u
            }
        }
    }

    fun encodeJpeg(
        frameBytes: ByteArray,
        previewSize: PreviewSize,
        quality: Int = DEFAULT_JPEG_QUALITY
    ): ByteArray {
        val nv21 = convertUvOrderYuv420SpToNv21(frameBytes, previewSize)
        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            previewSize.width,
            previewSize.height,
            null
        )
        ByteArrayOutputStream().use { output ->
            val compressed = yuvImage.compressToJpeg(
                Rect(0, 0, previewSize.width, previewSize.height),
                quality,
                output
            )
            val jpegBytes = output.toByteArray()
            if (compressed && jpegBytes.isNotEmpty()) {
                return jpegBytes
            }
        }
        return encodeJpegViaBitmap(nv21, previewSize, quality)
    }

    private fun expectedYuv420SpByteCount(previewSize: PreviewSize): Int {
        require(previewSize.width > 0 && previewSize.height > 0) {
            "Preview size must be positive"
        }
        require(previewSize.width % 2 == 0 && previewSize.height % 2 == 0) {
            "YUV420SP preview size must have even dimensions"
        }
        return previewSize.width * previewSize.height * 3 / 2
    }

    private fun encodeJpegViaBitmap(
        nv21: ByteArray,
        previewSize: PreviewSize,
        quality: Int
    ): ByteArray {
        val width = previewSize.width
        val height = previewSize.height
        val yPlaneSize = width * height
        val pixels = IntArray(yPlaneSize)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val yValue = nv21[(y * width) + x].toInt() and 0xFF
                val chromaIndex = yPlaneSize + ((y / 2) * width) + ((x / 2) * 2)
                val v = (nv21[chromaIndex].toInt() and 0xFF) - 128
                val u = (nv21[chromaIndex + 1].toInt() and 0xFF) - 128
                val r = (yValue + (1.402f * v)).toInt().coerceIn(0, 255)
                val g = (yValue - (0.344136f * u) - (0.714136f * v)).toInt().coerceIn(0, 255)
                val b = (yValue + (1.772f * u)).toInt().coerceIn(0, 255)
                pixels[(y * width) + x] = Color.rgb(r, g, b)
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            output.toByteArray()
        }
    }

    private const val DEFAULT_JPEG_QUALITY = 70
}
