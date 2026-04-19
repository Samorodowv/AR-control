package com.example.ar_control.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class LiteRtYoloObjectDetectorTest {

    @Test
    fun defaultModelAssetPath_pointsToBundledYolo26nModel() {
        val field = LiteRtYoloObjectDetector::class.java.getDeclaredField("DEFAULT_MODEL_ASSET_PATH")
        field.isAccessible = true

        assertEquals("models/yolo26n_int8.tflite", field.get(null))
    }
}
