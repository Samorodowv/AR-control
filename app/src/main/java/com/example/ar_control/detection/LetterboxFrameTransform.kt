package com.example.ar_control.detection

data class LetterboxFrameTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val modelWidth: Int,
    val modelHeight: Int,
    val scale: Float,
    val padLeft: Float,
    val padTop: Float
) {
    fun mapModelRectToSource(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): DetectionBoundingBox {
        return DetectionBoundingBox(
            left = ((left - padLeft) / scale).coerceIn(0f, sourceWidth.toFloat()),
            top = ((top - padTop) / scale).coerceIn(0f, sourceHeight.toFloat()),
            right = ((right - padLeft) / scale).coerceIn(0f, sourceWidth.toFloat()),
            bottom = ((bottom - padTop) / scale).coerceIn(0f, sourceHeight.toFloat())
        )
    }

    companion object {
        fun forResize(
            sourceWidth: Int,
            sourceHeight: Int,
            modelWidth: Int,
            modelHeight: Int
        ): LetterboxFrameTransform {
            require(sourceWidth > 0 && sourceHeight > 0) { "Source size must be positive" }
            require(modelWidth > 0 && modelHeight > 0) { "Model size must be positive" }
            val scale = minOf(
                modelWidth.toFloat() / sourceWidth.toFloat(),
                modelHeight.toFloat() / sourceHeight.toFloat()
            )
            val scaledWidth = sourceWidth * scale
            val scaledHeight = sourceHeight * scale
            return LetterboxFrameTransform(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                modelWidth = modelWidth,
                modelHeight = modelHeight,
                scale = scale,
                padLeft = (modelWidth - scaledWidth) / 2f,
                padTop = (modelHeight - scaledHeight) / 2f
            )
        }
    }
}
