package com.example.ar_control.preview

object PreviewTransformCalculator {

    fun calculate(
        zoomFactor: Float,
        previewRotationDegrees: Int = 0
    ): PreviewTransform {
        return PreviewTransform(
            scaleX = zoomFactor,
            scaleY = zoomFactor,
            textureRotationDegrees = 0
        )
    }
}

data class PreviewTransform(
    val scaleX: Float,
    val scaleY: Float,
    val textureRotationDegrees: Int
)
