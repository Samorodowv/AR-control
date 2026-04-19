package com.example.ar_control.detection

object DetectionOverlayMapper {
    fun mapToView(
        sourceBox: DetectionBoundingBox,
        sourceWidth: Int,
        sourceHeight: Int,
        viewWidth: Int,
        viewHeight: Int
    ): DetectionBoundingBox {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source size must be positive" }
        require(viewWidth > 0 && viewHeight > 0) { "View size must be positive" }
        val scaleX = viewWidth.toFloat() / sourceWidth.toFloat()
        val scaleY = viewHeight.toFloat() / sourceHeight.toFloat()
        return DetectionBoundingBox(
            left = sourceBox.left * scaleX,
            top = sourceBox.top * scaleY,
            right = sourceBox.right * scaleX,
            bottom = sourceBox.bottom * scaleY
        )
    }
}
