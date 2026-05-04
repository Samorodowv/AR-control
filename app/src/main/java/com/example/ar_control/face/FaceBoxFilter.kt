package com.example.ar_control.face

import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.detection.DetectionBoundingBox

class FaceBoxFilter(
    private val minWidthRatio: Float = 0.045f,
    private val minHeightRatio: Float = 0.065f,
    private val minAreaRatio: Float = 0.004f,
    private val minAspectRatio: Float = 0.60f,
    private val maxAspectRatio: Float = 1.65f
) {
    fun isAccepted(
        box: DetectionBoundingBox,
        previewSize: PreviewSize
    ): Boolean {
        val width = box.width
        val height = box.height
        if (width <= 0f || height <= 0f) {
            return false
        }
        if (width < previewSize.width * minWidthRatio) {
            return false
        }
        if (height < previewSize.height * minHeightRatio) {
            return false
        }
        val areaRatio = (width * height) / (previewSize.width * previewSize.height)
        if (areaRatio < minAreaRatio) {
            return false
        }
        val aspectRatio = width / height
        return aspectRatio in minAspectRatio..maxAspectRatio
    }
}
