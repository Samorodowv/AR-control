package com.example.ar_control.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionOverlayMapperTest {

    @Test
    fun mapToView_scalesSourceBoxIntoOverlayBounds() {
        val mapped = DetectionOverlayMapper.mapToView(
            sourceBox = DetectionBoundingBox(
                left = 50f,
                top = 25f,
                right = 150f,
                bottom = 75f
            ),
            sourceWidth = 200,
            sourceHeight = 100,
            viewWidth = 400,
            viewHeight = 200
        )

        assertEquals(100f, mapped.left, 0.001f)
        assertEquals(50f, mapped.top, 0.001f)
        assertEquals(300f, mapped.right, 0.001f)
        assertEquals(150f, mapped.bottom, 0.001f)
    }
}
