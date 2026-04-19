package com.example.ar_control.recording

import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.detection.DetectedObject

interface DetectionAnnotationSink {
    fun updateDetections(
        previewSize: PreviewSize,
        detections: List<DetectedObject>
    )

    fun clearDetections()
}

data class DetectionAnnotationSnapshot(
    val previewSize: PreviewSize,
    val detections: List<DetectedObject>
)

object NoOpDetectionAnnotationSink : DetectionAnnotationSink {
    override fun updateDetections(
        previewSize: PreviewSize,
        detections: List<DetectedObject>
    ) = Unit

    override fun clearDetections() = Unit
}
