package com.example.ar_control.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterboxFrameTransformTest {

    @Test
    fun forResize_mapsModelCoordinatesBackToSourceSpace() {
        val transform = LetterboxFrameTransform.forResize(
            sourceWidth = 1280,
            sourceHeight = 720,
            modelWidth = 640,
            modelHeight = 640
        )

        val mapped = transform.mapModelRectToSource(
            left = 160f,
            top = 140f,
            right = 480f,
            bottom = 500f
        )

        assertEquals(320f, mapped.left, 0.001f)
        assertEquals(0f, mapped.top, 0.001f)
        assertEquals(960f, mapped.right, 0.001f)
        assertEquals(720f, mapped.bottom, 0.001f)
    }
}
