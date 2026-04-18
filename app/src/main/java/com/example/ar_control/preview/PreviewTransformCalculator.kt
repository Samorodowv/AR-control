package com.example.ar_control.preview

object PreviewTransformCalculator {

    fun calculate(zoomFactor: Float): PreviewTransform {
        return PreviewTransform(
            scaleX = zoomFactor,
            scaleY = zoomFactor
        )
    }
}

data class PreviewTransform(
    val scaleX: Float,
    val scaleY: Float
)
