package com.example.ar_control.ui.preview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.example.ar_control.detection.DetectedObject
import com.example.ar_control.detection.DetectionBoundingBox
import com.example.ar_control.detection.DetectionOverlayMapper
import com.example.ar_control.face.FaceAccessStatus
import com.example.ar_control.face.FaceBoundingBox
import com.example.ar_control.preview.FitCenterLayoutCalculator
import com.example.ar_control.preview.PreviewTransformCalculator
import java.util.Locale
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
    private val faceBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
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
    private val hudBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(176, 0, 0, 0)
    }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            11f,
            resources.displayMetrics
        )
    }
    private val hudRect = RectF()

    private var contentWidth: Int = 0
    private var contentHeight: Int = 0
    private var zoomFactor: Float = 1.0f
    private var detections: List<DetectedObject> = emptyList()
    private var faces: List<FaceBoundingBox> = emptyList()
    private var previewFps: Float? = null
    private var inferenceFps: Float = 0f
    private var inferenceBackendLabel: String? = null

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

    fun setFaces(value: List<FaceBoundingBox>) {
        faces = value
        invalidate()
    }

    fun setHud(
        previewFps: Float?,
        inferenceFps: Float,
        inferenceBackendLabel: String?
    ) {
        this.previewFps = previewFps
        this.inferenceFps = inferenceFps
        this.inferenceBackendLabel = inferenceBackendLabel
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
        for (detection in detections) {
            val label = buildString {
                append(detection.label)
                append(' ')
                append((detection.confidence * 100f).roundToInt())
                append('%')
            }
            drawLabeledBox(
                canvas = canvas,
                sourceBox = detection.boundingBox,
                label = label,
                outlinePaint = boxPaint,
                backgroundPaint = labelBackgroundPaint
            )
        }

        for (face in faces) {
            drawFaceBox(
                canvas = canvas,
                sourceBox = face.boundingBox,
                accessStatus = face.accessStatus
            )
        }

        drawHud(canvas)
    }

    private fun drawLabeledBox(
        canvas: Canvas,
        sourceBox: DetectionBoundingBox,
        label: String,
        outlinePaint: Paint,
        backgroundPaint: Paint
    ) {
        val labelPadding = density * 4f
        val labelMargin = density * 4f
        val mappedBox = DetectionOverlayMapper.mapToView(
            sourceBox = sourceBox,
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
            outlinePaint
        )

        labelTextPaint.getTextBounds(label, 0, label.length, textBounds)
        val labelLeft = mappedBox.left.coerceAtLeast(0f)
        val labelBottom = (mappedBox.top - labelMargin).coerceAtLeast(textBounds.height() + (labelPadding * 2f))
        labelRect.set(
            labelLeft,
            labelBottom - textBounds.height() - (labelPadding * 2f),
            (labelLeft + textBounds.width() + (labelPadding * 2f)).coerceAtMost(measuredWidth.toFloat()),
            labelBottom
        )
        canvas.drawRoundRect(labelRect, density * 4f, density * 4f, backgroundPaint)
        canvas.drawText(
            label,
            labelRect.left + labelPadding,
            labelRect.bottom - labelPadding,
            labelTextPaint
        )
    }

    private fun drawFaceBox(
        canvas: Canvas,
        sourceBox: DetectionBoundingBox,
        accessStatus: FaceAccessStatus?
    ) {
        val mappedBox = DetectionOverlayMapper.mapToView(
            sourceBox = sourceBox,
            sourceWidth = contentWidth,
            sourceHeight = contentHeight,
            viewWidth = measuredWidth,
            viewHeight = measuredHeight
        )
        faceBoxPaint.color = faceBoxColor(accessStatus)
        canvas.drawRect(
            mappedBox.left,
            mappedBox.top,
            mappedBox.right,
            mappedBox.bottom,
            faceBoxPaint
        )
    }

    private fun applyPreviewTransform() {
        val transform = PreviewTransformCalculator.calculate(zoomFactor)
        pivotX = measuredWidth / 2f
        pivotY = measuredHeight / 2f
        scaleX = transform.scaleX
        scaleY = transform.scaleY
    }

    private fun drawHud(canvas: Canvas) {
        val lines = mutableListOf<String>()
        previewFps?.let { lines += "Preview ${formatFps(it)} FPS" }
        inferenceBackendLabel?.let { backend ->
            val inferenceLabel = if (inferenceFps > 0f) {
                "Infer ${formatFps(inferenceFps)} FPS"
            } else {
                "Infer -- FPS"
            }
            lines += inferenceLabel
            lines += backend
        }

        if (lines.isEmpty()) {
            return
        }

        val padding = density * 8f
        val margin = density * 8f
        val cornerRadius = density * 8f
        val lineSpacing = density * 2f
        val lineHeight = hudTextPaint.fontSpacing
        val textWidth = lines.maxOf { hudTextPaint.measureText(it) }
        val contentHeight = (lineHeight * lines.size) + (lineSpacing * (lines.size - 1))

        hudRect.set(
            measuredWidth - margin - textWidth - (padding * 2f),
            margin,
            measuredWidth - margin,
            margin + contentHeight + (padding * 2f)
        )
        canvas.drawRoundRect(hudRect, cornerRadius, cornerRadius, hudBackgroundPaint)

        var baseline = hudRect.top + padding - hudTextPaint.ascent()
        for (line in lines) {
            canvas.drawText(line, hudRect.left + padding, baseline, hudTextPaint)
            baseline += lineHeight + lineSpacing
        }
    }

    private fun formatFps(value: Float): String {
        return String.format(Locale.US, "%.1f", value)
    }
}

internal fun faceBoxColor(accessStatus: FaceAccessStatus?): Int {
    return when (accessStatus) {
        FaceAccessStatus.BANNED -> Color.RED
        FaceAccessStatus.APPROVED -> Color.GREEN
        null -> Color.WHITE
    }
}
