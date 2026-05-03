package com.example.ar_control.face

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FaceNetImagePreprocessorTest {

    @Test
    fun standardizePixels_centersAndScalesRgbChannels() {
        val pixels = intArrayOf(
            0x000000,
            0xFFFFFF
        )

        val standardized = FaceNetImagePreprocessor.standardizeRgbPixels(pixels)

        assertArrayEquals(
            floatArrayOf(-1f, -1f, -1f, 1f, 1f, 1f),
            standardized,
            0.0001f
        )
    }

    @Test
    fun standardizePixels_constantImageReturnsZeros() {
        val pixels = intArrayOf(
            0x7F7F7F,
            0x7F7F7F
        )

        val standardized = FaceNetImagePreprocessor.standardizeRgbPixels(pixels)

        assertArrayEquals(
            FloatArray(6),
            standardized,
            0.0001f
        )
    }
}
