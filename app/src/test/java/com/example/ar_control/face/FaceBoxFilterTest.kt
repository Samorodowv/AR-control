package com.example.ar_control.face

import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.detection.DetectionBoundingBox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceBoxFilterTest {
    private val previewSize = PreviewSize(width = 1280, height = 720)
    private val filter = FaceBoxFilter()

    @Test
    fun acceptsReasonableFaceGeometry() {
        assertTrue(
            filter.isAccepted(
                DetectionBoundingBox(left = 500f, top = 180f, right = 720f, bottom = 440f),
                previewSize
            )
        )
    }

    @Test
    fun rejectsTinyBoxesThatCauseFalsePositives() {
        assertFalse(
            filter.isAccepted(
                DetectionBoundingBox(left = 100f, top = 100f, right = 140f, bottom = 140f),
                previewSize
            )
        )
    }

    @Test
    fun rejectsVeryWideOrVeryTallBoxes() {
        assertFalse(
            filter.isAccepted(
                DetectionBoundingBox(left = 100f, top = 100f, right = 360f, bottom = 180f),
                previewSize
            )
        )
        assertFalse(
            filter.isAccepted(
                DetectionBoundingBox(left = 100f, top = 100f, right = 170f, bottom = 390f),
                previewSize
            )
        )
    }

    @Test
    fun rejectsNonPositiveOrInvalidBoxes() {
        assertFalse(
            filter.isAccepted(
                DetectionBoundingBox(left = 100f, top = 100f, right = 100f, bottom = 240f),
                previewSize
            )
        )
        assertFalse(
            filter.isAccepted(
                DetectionBoundingBox(left = 100f, top = 100f, right = 240f, bottom = 100f),
                previewSize
            )
        )
        assertFalse(
            filter.isAccepted(
                DetectionBoundingBox(left = 240f, top = 100f, right = 100f, bottom = 240f),
                previewSize
            )
        )
        assertFalse(
            filter.isAccepted(
                DetectionBoundingBox(left = 100f, top = 240f, right = 240f, bottom = 100f),
                previewSize
            )
        )
    }
}
