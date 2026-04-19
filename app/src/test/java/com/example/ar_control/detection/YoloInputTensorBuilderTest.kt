package com.example.ar_control.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class YoloInputTensorBuilderTest {

    @Test
    fun fillInputTensor_convertsNeutralChromaYuvPixelsIntoNormalizedRgb() {
        val destination = FloatArray(2 * 2 * 3)

        val transform = YoloInputTensorBuilder.fillInputTensor(
            frameBytes = byteArrayOf(
                16, 81, 145.toByte(), 235.toByte(),
                128.toByte(), 128.toByte()
            ),
            sourceWidth = 2,
            sourceHeight = 2,
            modelWidth = 2,
            modelHeight = 2,
            destination = destination
        )

        assertEquals(1f, transform.scale, 0.0001f)
        assertEquals(0f, destination[0], 0.02f)
        assertEquals(0f, destination[1], 0.02f)
        assertEquals(0f, destination[2], 0.02f)
        assertEquals(0.29f, destination[3], 0.03f)
        assertEquals(0.29f, destination[4], 0.03f)
        assertEquals(0.29f, destination[5], 0.03f)
        assertEquals(0.58f, destination[6], 0.03f)
        assertEquals(0.58f, destination[7], 0.03f)
        assertEquals(0.58f, destination[8], 0.03f)
        assertEquals(1f, destination[9], 0.02f)
        assertEquals(1f, destination[10], 0.02f)
        assertEquals(1f, destination[11], 0.02f)
    }

    @Test
    fun fillInputTensor_letterboxesWithUltralyticsPaddingColor() {
        val destination = FloatArray(4 * 4 * 3)

        val transform = YoloInputTensorBuilder.fillInputTensor(
            frameBytes = byteArrayOf(
                32, 64, 96, 128.toByte(),
                160.toByte(), 192.toByte(), 224.toByte(), 255.toByte(),
                128.toByte(), 128.toByte(), 128.toByte(), 128.toByte()
            ),
            sourceWidth = 4,
            sourceHeight = 2,
            modelWidth = 4,
            modelHeight = 4,
            destination = destination
        )

        assertEquals(1f, transform.scale, 0.0001f)
        assertEquals(0f, transform.padLeft, 0.0001f)
        assertEquals(1f, transform.padTop, 0.0001f)

        val expectedPadding = 114f / 255f
        for (index in 0 until 4 * 3) {
            assertEquals(expectedPadding, destination[index], 0.0001f)
        }
    }
}
