package com.example.ar_control.preview

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.TextureView

class AspectRatioTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs) {

    private var contentWidth: Int = 0
    private var contentHeight: Int = 0
    private var contentRotationDegrees: Int = 0
    private var zoomFactor: Float = 1.0f

    fun setContentAspectRatio(width: Int, height: Int, rotationDegrees: Int = 0) {
        if (width <= 0 || height <= 0) {
            clearContentAspectRatio()
            return
        }
        val normalizedRotation = rotationDegrees.normalizedDegrees()
        if (
            contentWidth == width &&
            contentHeight == height &&
            contentRotationDegrees == normalizedRotation
        ) {
            return
        }
        contentWidth = width
        contentHeight = height
        contentRotationDegrees = normalizedRotation
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
        contentRotationDegrees = 0
        setTransform(Matrix())
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
            contentWidth = displayContentWidth,
            contentHeight = displayContentHeight
        )
        setMeasuredDimension(fitted.width, fitted.height)
        applyPreviewTransform()
    }

    private fun applyPreviewTransform() {
        val transform = PreviewTransformCalculator.calculate(
            zoomFactor = zoomFactor,
            previewRotationDegrees = contentRotationDegrees
        )
        pivotX = measuredWidth / 2f
        pivotY = measuredHeight / 2f
        scaleX = transform.scaleX
        scaleY = transform.scaleY
        setTransform(Matrix())
    }

    private val displayContentWidth: Int
        get() = if (contentRotationDegrees == 90 || contentRotationDegrees == 270) {
            contentHeight
        } else {
            contentWidth
        }

    private val displayContentHeight: Int
        get() = if (contentRotationDegrees == 90 || contentRotationDegrees == 270) {
            contentWidth
        } else {
            contentHeight
        }
}

private fun Int.normalizedDegrees(): Int {
    return ((this % FULL_ROTATION_DEGREES) + FULL_ROTATION_DEGREES) % FULL_ROTATION_DEGREES
}

private const val FULL_ROTATION_DEGREES = 360
