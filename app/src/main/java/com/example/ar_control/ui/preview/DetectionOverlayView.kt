package com.example.ar_control.ui.preview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.example.ar_control.detection.DetectedObject
import com.example.ar_control.detection.DetectionOverlayMapper
import com.example.ar_control.preview.FitCenterLayoutCalculator
import com.example.ar_control.preview.PreviewTransformCalculator
import kotlin.math.roundToInt

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
        color = Color.rgb(171, 242, 87)
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 171, 242, 87)
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            12f,
            resources.displayMetrics
        )
    }
    private val textBounds = android.graphics.Rect()
    private val labelRect = RectF()

    private var contentWidth: Int = 0
    private var contentHeight: Int = 0
    private var zoomFactor: Float = 1.0f
    private var detections: List<DetectedObject> = emptyList()

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
        invalidate()
    }

    fun clearContentAspectRatio() {
        if (contentWidth == 0 && contentHeight == 0) {
            return
        }
        contentWidth = 0
        contentHeight = 0
        requestLayout()
        invalidate()
    }

    fun setZoomFactor(value: Float) {
        if (zoomFactor == value) {
            return
        }
        zoomFactor = value
        applyPreviewTransform()
    }

    fun setDetections(value: List<DetectedObject>) {
        detections = value
        invalidate()
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (contentWidth <= 0 || contentHeight <= 0 || measuredWidth <= 0 || measuredHeight <= 0) {
            return
        }
        val labelPadding = density * 4f
        val labelMargin = density * 4f

        for (detection in detections) {
            val mappedBox = DetectionOverlayMapper.mapToView(
                sourceBox = detection.boundingBox,
                sourceWidth = contentWidth,
                sourceHeight = contentHeight,
                viewWidth = measuredWidth,
                viewHeight = measuredHeight
            )
            canvas.drawRect(
                mappedBox.left,
                mappedBox.top,
                mappedBox.right,
                mappedBox.bottom,
                boxPaint
            )

            val label = buildString {
                append(detection.label)
                append(' ')
                append((detection.confidence * 100f).roundToInt())
                append('%')
            }
            labelTextPaint.getTextBounds(label, 0, label.length, textBounds)
            val labelLeft = mappedBox.left.coerceAtLeast(0f)
            val labelBottom = (mappedBox.top - labelMargin).coerceAtLeast(textBounds.height() + (labelPadding * 2f))
            labelRect.set(
                labelLeft,
                labelBottom - textBounds.height() - (labelPadding * 2f),
                (labelLeft + textBounds.width() + (labelPadding * 2f)).coerceAtMost(measuredWidth.toFloat()),
                labelBottom
            )
            canvas.drawRoundRect(labelRect, density * 4f, density * 4f, labelBackgroundPaint)
            canvas.drawText(
                label,
                labelRect.left + labelPadding,
                labelRect.bottom - labelPadding,
                labelTextPaint
            )
        }
    }

    private fun applyPreviewTransform() {
        val transform = PreviewTransformCalculator.calculate(zoomFactor)
        pivotX = measuredWidth / 2f
        pivotY = measuredHeight / 2f
        scaleX = transform.scaleX
        scaleY = transform.scaleY
    }
}
