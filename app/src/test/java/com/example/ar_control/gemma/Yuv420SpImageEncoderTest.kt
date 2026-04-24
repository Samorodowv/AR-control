package com.example.ar_control.gemma

import com.example.ar_control.ArControlApp
import com.example.ar_control.camera.PreviewSize
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(application = ArControlApp::class)
@RunWith(RobolectricTestRunner::class)
class Yuv420SpImageEncoderTest {
    @Test
    fun convertUvOrderYuv420SpToNv21_swapsChromaPairs() {
        val frameBytes = byteArrayOf(
            0x10,
            0x20,
            0x30,
            0x40,
            0x55,
            0x66
        )

        val nv21 = Yuv420SpImageEncoder.convertUvOrderYuv420SpToNv21(
            frameBytes = frameBytes,
            previewSize = PreviewSize(width = 2, height = 2)
        )

        assertArrayEquals(
            byteArrayOf(
                0x10,
                0x20,
                0x30,
                0x40,
                0x66,
                0x55
            ),
            nv21
        )
    }

    @Test
    fun encodeJpeg_returnsJpegBytes() {
        val previewSize = PreviewSize(width = 16, height = 16)
        val frameBytes = ByteArray(previewSize.width * previewSize.height * 3 / 2) {
            0x80.toByte()
        }

        val jpegBytes = Yuv420SpImageEncoder.encodeJpeg(
            frameBytes = frameBytes,
            previewSize = previewSize
        )

        assertTrue(jpegBytes.isNotEmpty())
        assertTrue(jpegBytes[0] == 0xFF.toByte())
        assertTrue(jpegBytes[1] == 0xD8.toByte())
    }

    @Test
    fun fallbackArgbConversion_usesLimitedRangeYuvCoefficients() {
        val previewSize = PreviewSize(width = 2, height = 2)
        val nv21 = byteArrayOf(
            0x52,
            0x52,
            0x52,
            0x52,
            0x5A,
            0xF0.toByte()
        )

        val pixels = Yuv420SpImageEncoder.convertNv21ToArgbPixelsForFallback(
            nv21 = nv21,
            previewSize = previewSize
        )

        val pixel = pixels.first()
        assertEquals(0xFF, pixel ushr 24)
        assertEquals(16, pixel.red())
        assertEquals(64, pixel.green())
        assertEquals(255, pixel.blue())
    }

    private fun Int.red(): Int = (this shr 16) and 0xFF

    private fun Int.green(): Int = (this shr 8) and 0xFF

    private fun Int.blue(): Int = this and 0xFF
}
