package com.example.ar_control.preview

object FitCenterLayoutCalculator {

    fun calculate(
        containerWidth: Int,
        containerHeight: Int,
        contentWidth: Int,
        contentHeight: Int
    ): FittedSize {
        if (containerWidth <= 0 || containerHeight <= 0 || contentWidth <= 0 || contentHeight <= 0) {
            return FittedSize(width = containerWidth.coerceAtLeast(0), height = containerHeight.coerceAtLeast(0))
        }

        val widthLimited = containerWidth.toLong() * contentHeight <=
            containerHeight.toLong() * contentWidth

        return if (widthLimited) {
            FittedSize(
                width = containerWidth,
                height = (contentHeight.toLong() * containerWidth / contentWidth).toInt()
            )
        } else {
            FittedSize(
                width = (contentWidth.toLong() * containerHeight / contentHeight).toInt(),
                height = containerHeight
            )
        }
    }
}

data class FittedSize(
    val width: Int,
    val height: Int
)
