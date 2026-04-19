package com.example.ar_control.recording

import com.example.ar_control.ArControlApp
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.detection.DetectedObject
import com.example.ar_control.detection.DetectionBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(application = ArControlApp::class)
@RunWith(RobolectricTestRunner::class)
class YuvDetectionAnnotationRendererTest {

    @Test
    fun annotate_marksBoundingBoxAndLabelPixelsInYPlane() {
        val width = 32
        val height = 16
        val frame = ByteArray(width * height * 3 / 2) { 100.toByte() }
        val renderer = YuvDetectionAnnotationRenderer()

        renderer.annotate(
            frameBytes = frame,
            frameWidth = width,
            frameHeight = height,
            snapshot = DetectionAnnotationSnapshot(
                previewSize = PreviewSize(width = width, height = height),
                detections = listOf(
                    DetectedObject(
                        labelIndex = 0,
                        label = "person",
                        confidence = 0.93f,
                        boundingBox = DetectionBoundingBox(
                            left = 4f,
                            top = 6f,
                            right = 20f,
                            bottom = 12f
                        )
                    )
                )
            )
        )

        val yPlane = frame.copyOfRange(0, width * height)
        assertNotEquals(100, yPlane[(6 * width) + 4].toInt() and 0xFF)
        assertTrue(yPlane.copyOfRange(0, width * 4).any { (it.toInt() and 0xFF) != 100 })
    }

    @Test
    fun annotate_withoutSnapshot_leavesFrameUntouched() {
        val width = 8
        val height = 8
        val frame = ByteArray(width * height * 3 / 2) { 90.toByte() }
        val renderer = YuvDetectionAnnotationRenderer()

        renderer.annotate(
            frameBytes = frame,
            frameWidth = width,
            frameHeight = height,
            snapshot = null
        )

        assertEquals(90, frame[0].toInt() and 0xFF)
        assertEquals(90, frame[(width * height) - 1].toInt() and 0xFF)
    }
}
