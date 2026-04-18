package com.example.ar_control.preview

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

class AspectRatioTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs) {

    private var contentWidth: Int = 0
    private var contentHeight: Int = 0
    private var zoomFactor: Float = 1.0f

    fun setContentAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            clearContentAspectRatio()
            return
        }
        if (contentWidth == width && contentHeight == height) {
            return
        }
        contentWidth = width
        contentHeight = height
        requestLayout()
    }

    fun setZoomFactor(value: Float) {
        if (zoomFactor == value) {
            return
        }
        zoomFactor = value
        applyPreviewTransform()
    }

    fun clearContentAspectRatio() {
        if (contentWidth == 0 && contentHeight == 0) {
            return
        }
        contentWidth = 0
        contentHeight = 0
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)

        if (contentWidth <= 0 || contentHeight <= 0 || availableWidth <= 0 || availableHeight <= 0) {
            setMeasuredDimension(availableWidth, availableHeight)
            return
        }

        val fitted = FitCenterLayoutCalculator.calculate(
            containerWidth = availableWidth,
            containerHeight = availableHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight
        )
        setMeasuredDimension(fitted.width, fitted.height)
        applyPreviewTransform()
    }

    private fun applyPreviewTransform() {
        val transform = PreviewTransformCalculator.calculate(zoomFactor)
        pivotX = measuredWidth / 2f
        pivotY = measuredHeight / 2f
        scaleX = transform.scaleX
        scaleY = transform.scaleY
    }
}
