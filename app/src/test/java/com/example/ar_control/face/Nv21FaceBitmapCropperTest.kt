package com.example.ar_control.face

import android.graphics.Rect
import com.example.ar_control.ArControlApp
import com.example.ar_control.camera.PreviewSize
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(application = ArControlApp::class)
@RunWith(RobolectricTestRunner::class)
class Nv21FaceBitmapCropperTest {
    @Test
    fun cropToBitmap_returnsRequestedClampedCropSize() {
        val previewSize = PreviewSize(width = 4, height = 4)
        val nv21 = ByteArray(previewSize.width * previewSize.height * 3 / 2) { 0x80.toByte() }

        val bitmap = Nv21FaceBitmapCropper.cropToBitmap(
            nv21 = nv21,
            previewSize = previewSize,
            bounds = Rect(-1, -1, 3, 3)
        )

        assertEquals(3, bitmap.width)
        assertEquals(3, bitmap.height)
        bitmap.recycle()
    }

    @Test
    fun cropToBitmap_usesCorrectChromaPairsForOddLeftCrop() {
        val bitmap = Nv21FaceBitmapCropper.cropToBitmap(
            nv21 = fourByFourNv21(),
            previewSize = FOUR_BY_FOUR,
            bounds = Rect(1, 0, 4, 3)
        )

        try {
            assertEquals(3, bitmap.width)
            assertEquals(3, bitmap.height)
            assertArrayEquals(
                intArrayOf(
                    rgb(0, 152, 0),
                    rgb(16, 64, 255),
                    rgb(70, 118, 255),
                    rgb(0, 246, 0),
                    rgb(51, 99, 255),
                    rgb(88, 136, 255),
                    rgb(255, 146, 0),
                    rgb(26, 255, 255),
                    rgb(45, 255, 255)
                ),
                bitmap.pixels()
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun cropToBitmap_readsLowerRightCropWithoutExceedingLastChromaColumn() {
        val bitmap = Nv21FaceBitmapCropper.cropToBitmap(
            nv21 = fourByFourNv21(),
            previewSize = FOUR_BY_FOUR,
            bounds = Rect(2, 2, 4, 4)
        )

        try {
            assertEquals(2, bitmap.width)
            assertEquals(2, bitmap.height)
            assertArrayEquals(
                intArrayOf(
                    rgb(26, 255, 255),
                    rgb(45, 255, 255),
                    rgb(0, 128, 153),
                    rgb(0, 147, 171)
                ),
                bitmap.pixels()
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun cropToBitmap_matchesFallbackLimitedRangeCoefficientsForKnownValue() {
        val bitmap = Nv21FaceBitmapCropper.cropToBitmap(
            nv21 = fourByFourNv21(),
            previewSize = FOUR_BY_FOUR,
            bounds = Rect(1, 0, 2, 1)
        )

        try {
            val pixel = bitmap.getPixel(0, 0)
            assertEquals(0xFF, pixel ushr 24)
            assertEquals(0, pixel.red())
            assertEquals(152, pixel.green())
            assertEquals(0, pixel.blue())
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun cropToBitmap_throwsWhenBoundsAreFullyOutsidePreview() {
        val previewSize = PreviewSize(width = 4, height = 4)
        val nv21 = ByteArray(previewSize.width * previewSize.height * 3 / 2) { 0x80.toByte() }

        val error = assertThrows(IllegalArgumentException::class.java) {
            Nv21FaceBitmapCropper.cropToBitmap(
                nv21 = nv21,
                previewSize = previewSize,
                bounds = Rect(4, 1, 6, 3)
            )
        }
        assertEquals("Face crop must intersect preview bounds", error.message)
    }

    @Test
    fun cropToBitmap_throwsWhenBoundsHaveNoPositiveArea() {
        val previewSize = PreviewSize(width = 4, height = 4)
        val nv21 = ByteArray(previewSize.width * previewSize.height * 3 / 2) { 0x80.toByte() }

        val error = assertThrows(IllegalArgumentException::class.java) {
            Nv21FaceBitmapCropper.cropToBitmap(
                nv21 = nv21,
                previewSize = previewSize,
                bounds = Rect(3, 1, 2, 3)
            )
        }
        assertEquals("Face crop must have positive width and height", error.message)
    }

    @Test
    fun cropToBitmap_throwsWhenNv21BufferIsTruncated() {
        val nv21 = ByteArray((FOUR_BY_FOUR.width * FOUR_BY_FOUR.height * 3 / 2) - 1) { 0x80.toByte() }

        val error = assertThrows(IllegalArgumentException::class.java) {
            Nv21FaceBitmapCropper.cropToBitmap(
                nv21 = nv21,
                previewSize = FOUR_BY_FOUR,
                bounds = Rect(0, 0, 2, 2)
            )
        }
        assertEquals("Expected 24 bytes for 4x4 NV21 frame, got 23", error.message)
    }

    @Test
    fun cropToBitmap_throwsWhenNv21BufferIsOversized() {
        val nv21 = ByteArray((FOUR_BY_FOUR.width * FOUR_BY_FOUR.height * 3 / 2) + 1) { 0x80.toByte() }

        val error = assertThrows(IllegalArgumentException::class.java) {
            Nv21FaceBitmapCropper.cropToBitmap(
                nv21 = nv21,
                previewSize = FOUR_BY_FOUR,
                bounds = Rect(0, 0, 2, 2)
            )
        }
        assertEquals("Expected 24 bytes for 4x4 NV21 frame, got 25", error.message)
    }

    @Test
    fun cropToBitmap_throwsWhenPreviewDimensionsAreOdd() {
        val previewSize = PreviewSize(width = 3, height = 4)
        val nv21 = ByteArray(previewSize.width * previewSize.height * 3 / 2) { 0x80.toByte() }

        val error = assertThrows(IllegalArgumentException::class.java) {
            Nv21FaceBitmapCropper.cropToBitmap(
                nv21 = nv21,
                previewSize = previewSize,
                bounds = Rect(0, 0, 2, 2)
            )
        }
        assertEquals("NV21 preview dimensions must be even, got 3x4", error.message)
    }

    private fun fourByFourNv21(): ByteArray {
        return byteArrayOf(
            0x21,
            0x10,
            0x52,
            0x80.toByte(),
            0x41,
            0x60,
            0x70,
            0x90.toByte(),
            0xA0.toByte(),
            0xB0.toByte(),
            0xC0.toByte(),
            0xD0.toByte(),
            0xE0.toByte(),
            0xF0.toByte(),
            0x40,
            0x50,
            0x02,
            0x00,
            0x5A,
            0xF0.toByte(),
            0xE0.toByte(),
            0x20,
            0x10,
            0xB0.toByte()
        )
    }

    private fun android.graphics.Bitmap.pixels(): IntArray {
        return IntArray(width * height).also { pixels ->
            getPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int {
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun Int.red(): Int = (this shr 16) and 0xFF

    private fun Int.green(): Int = (this shr 8) and 0xFF

    private fun Int.blue(): Int = this and 0xFF

    private companion object {
        val FOUR_BY_FOUR = PreviewSize(width = 4, height = 4)
    }
}
