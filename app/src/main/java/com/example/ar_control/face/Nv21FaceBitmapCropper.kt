package com.example.ar_control.face

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.ar_control.camera.PreviewSize

object Nv21FaceBitmapCropper {
    fun cropToBitmap(
        nv21: ByteArray,
        previewSize: PreviewSize,
        bounds: Rect
    ): Bitmap {
        val frameSize = validateNv21Layout(nv21, previewSize)
        val crop = clampedCrop(previewSize, bounds)
        val pixels = IntArray(crop.width() * crop.height())
        for (row in 0 until crop.height()) {
            val sourceY = crop.top + row
            val uvRow = frameSize + (sourceY / 2) * previewSize.width
            for (column in 0 until crop.width()) {
                val sourceX = crop.left + column
                val y = nv21[sourceY * previewSize.width + sourceX].toInt() and 0xFF
                val uvIndex = uvRow + ((sourceX / 2) * 2)
                val v = nv21[uvIndex].toInt() and 0xFF
                val u = nv21[uvIndex + 1].toInt() and 0xFF
                pixels[row * crop.width() + column] = limitedRangeYuvToArgb(y, u, v)
            }
        }
        return Bitmap.createBitmap(
            pixels,
            crop.width(),
            crop.height(),
            Bitmap.Config.ARGB_8888
        )
    }

    private fun validateNv21Layout(nv21: ByteArray, previewSize: PreviewSize): Int {
        val width = previewSize.width
        val height = previewSize.height
        require(width > 0 && height > 0) {
            "NV21 preview dimensions must be positive, got ${width}x$height"
        }
        require(width % 2 == 0 && height % 2 == 0) {
            "NV21 preview dimensions must be even, got ${width}x$height"
        }
        val frameSize = width * height
        val expectedSize = frameSize * 3 / 2
        require(nv21.size == expectedSize) {
            "Expected $expectedSize bytes for ${width}x$height NV21 frame, got ${nv21.size}"
        }
        return frameSize
    }

    private fun clampedCrop(previewSize: PreviewSize, bounds: Rect): Rect {
        require(bounds.width() > 0 && bounds.height() > 0) {
            "Face crop must have positive width and height"
        }
        val crop = Rect(bounds)
        require(crop.intersect(0, 0, previewSize.width, previewSize.height)) {
            "Face crop must intersect preview bounds"
        }
        return crop
    }

    private fun limitedRangeYuvToArgb(y: Int, u: Int, v: Int): Int {
        val c = (y - 16).coerceAtLeast(0)
        val d = u - 128
        val e = v - 128
        val red = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
        val green = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
        val blue = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
        return Color.rgb(red, green, blue)
    }
}
