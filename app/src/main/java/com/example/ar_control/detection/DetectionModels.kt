package com.example.ar_control.detection

data class DetectionBoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float
        get() = (right - left).coerceAtLeast(0f)

    val height: Float
        get() = (bottom - top).coerceAtLeast(0f)
}

data class DetectedObject(
    val labelIndex: Int,
    val label: String,
    val confidence: Float,
    val boundingBox: DetectionBoundingBox
)
