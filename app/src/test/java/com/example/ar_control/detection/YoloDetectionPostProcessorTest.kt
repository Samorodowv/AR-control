package com.example.ar_control.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class YoloDetectionPostProcessorTest {

    @Test
    fun process_filtersByConfidenceAndSuppressesOverlappingBoxes() {
        val predictionCount = 3
        val numClasses = 2
        val transform = LetterboxFrameTransform.forResize(
            sourceWidth = 640,
            sourceHeight = 640,
            modelWidth = 640,
            modelHeight = 640
        )
        val output = floatArrayOf(
            100f, 102f, 300f,
            100f, 102f, 200f,
            40f, 42f, 50f,
            40f, 42f, 60f,
            0.9f, 0.85f, 0.1f,
            0.1f, 0.05f, 0.8f
        )

        val detections = YoloDetectionPostProcessor.process(
            output = output,
            predictionCount = predictionCount,
            labels = listOf("person", "car"),
            numClasses = numClasses,
            confidenceThreshold = 0.25f,
            iouThreshold = 0.5f,
            maxResults = 10,
            frameTransform = transform
        )

        assertEquals(2, detections.size)
        assertEquals("person", detections[0].label)
        assertEquals(0.9f, detections[0].confidence, 0.0001f)
        assertEquals(80f, detections[0].boundingBox.left, 0.001f)
        assertEquals(80f, detections[0].boundingBox.top, 0.001f)
        assertEquals(120f, detections[0].boundingBox.right, 0.001f)
        assertEquals(120f, detections[0].boundingBox.bottom, 0.001f)
        assertEquals("car", detections[1].label)
        assertEquals(0.8f, detections[1].confidence, 0.0001f)
        assertEquals(275f, detections[1].boundingBox.left, 0.001f)
        assertEquals(170f, detections[1].boundingBox.top, 0.001f)
        assertEquals(325f, detections[1].boundingBox.right, 0.001f)
        assertEquals(230f, detections[1].boundingBox.bottom, 0.001f)
    }
}
