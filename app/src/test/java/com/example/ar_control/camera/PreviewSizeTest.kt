package com.example.ar_control.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewSizeTest {

    @Test
    fun displayDimensionsUseBufferDimensionsWhenNotRotated() {
        val size = PreviewSize(width = 1920, height = 1080, rotationDegrees = 0)

        assertEquals(1920, size.displayWidth)
        assertEquals(1080, size.displayHeight)
    }

    @Test
    fun displayDimensionsSwapBufferDimensionsWhenRotatedSideways() {
        val size = PreviewSize(width = 1920, height = 1080, rotationDegrees = 90)

        assertEquals(1080, size.displayWidth)
        assertEquals(1920, size.displayHeight)
    }
}
