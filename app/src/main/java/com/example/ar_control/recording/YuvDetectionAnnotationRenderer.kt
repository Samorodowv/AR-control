package com.example.ar_control.recording

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.example.ar_control.detection.DetectionOverlayMapper
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class YuvDetectionAnnotationRenderer {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val textBounds = Rect()

    fun annotate(
        frameBytes: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        snapshot: DetectionAnnotationSnapshot?
    ) {
        if (snapshot == null || snapshot.detections.isEmpty()) {
            return
        }

        val yPlaneSize = frameWidth * frameHeight
        require(frameBytes.size >= yPlaneSize) {
            "Unexpected frame size ${frameBytes.size} for ${frameWidth}x${frameHeight}"
        }

        val strokeThickness = max(1, min(frameWidth, frameHeight) / 180)
        val labelPadding = max(2, min(frameWidth, frameHeight) / 200)
        textPaint.textSize = max(12f, frameHeight / 28f)

        for (detection in snapshot.detections) {
            val mappedBox = DetectionOverlayMapper.mapToView(
                sourceBox = detection.boundingBox,
                sourceWidth = snapshot.previewSize.width,
                sourceHeight = snapshot.previewSize.height,
                viewWidth = frameWidth,
                viewHeight = frameHeight
            )
            val left = mappedBox.left.roundToInt().coerceIn(0, frameWidth - 1)
            val top = mappedBox.top.roundToInt().coerceIn(0, frameHeight - 1)
            val right = mappedBox.right.roundToInt().coerceIn(left + 1, frameWidth)
            val bottom = mappedBox.bottom.roundToInt().coerceIn(top + 1, frameHeight)

            drawBox(frameBytes, frameWidth, frameHeight, left, top, right, bottom, strokeThickness)
            drawLabel(
                frameBytes = frameBytes,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                left = left,
                top = top,
                bottom = bottom,
                label = buildString {
                    append(detection.label)
                    append(' ')
                    append((detection.confidence * 100f).roundToInt())
                    append('%')
                },
                labelPadding = labelPadding
            )
        }
    }

    private fun drawBox(
        frameBytes: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        strokeThickness: Int
    ) {
        repeat(strokeThickness) { offset ->
            drawHorizontalLine(frameBytes, frameWidth, frameHeight, top + offset, left, right, 235)
            drawHorizontalLine(frameBytes, frameWidth, frameHeight, bottom - 1 - offset, left, right, 235)
            drawVerticalLine(frameBytes, frameWidth, frameHeight, left + offset, top, bottom, 235)
            drawVerticalLine(frameBytes, frameWidth, frameHeight, right - 1 - offset, top, bottom, 235)
        }
    }

    private fun drawLabel(
        frameBytes: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        left: Int,
        top: Int,
        bottom: Int,
        label: String,
        labelPadding: Int
    ) {
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        val fontMetrics = textPaint.fontMetrics
        val labelHeight = (textBounds.height() + (labelPadding * 2)).coerceAtLeast(1)
        val labelWidth = (textBounds.width() + (labelPadding * 2)).coerceAtLeast(1)
        val labelLeft = left.coerceIn(0, (frameWidth - labelWidth).coerceAtLeast(0))
        val proposedTop = top - labelHeight - labelPadding
        val labelTop = if (proposedTop >= 0) {
            proposedTop
        } else {
            (bottom + labelPadding).coerceAtMost((frameHeight - labelHeight).coerceAtLeast(0))
        }

        fillRect(
            frameBytes = frameBytes,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            left = labelLeft,
            top = labelTop,
            right = labelLeft + labelWidth,
            bottom = labelTop + labelHeight,
            luma = 24
        )

        val bitmap = Bitmap.createBitmap(labelWidth, labelHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val baseline = labelHeight - labelPadding - fontMetrics.descent
            canvas.drawText(label, labelPadding.toFloat(), baseline, textPaint)
            val pixels = IntArray(labelWidth * labelHeight)
            bitmap.getPixels(pixels, 0, labelWidth, 0, 0, labelWidth, labelHeight)
            blitTextPixels(
                frameBytes = frameBytes,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                destinationLeft = labelLeft,
                destinationTop = labelTop,
                labelWidth = labelWidth,
                labelHeight = labelHeight,
                pixels = pixels
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun blitTextPixels(
        frameBytes: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        destinationLeft: Int,
        destinationTop: Int,
        labelWidth: Int,
        labelHeight: Int,
        pixels: IntArray
    ) {
        for (y in 0 until labelHeight) {
            val targetY = destinationTop + y
            if (targetY !in 0 until frameHeight) {
                continue
            }
            for (x in 0 until labelWidth) {
                val targetX = destinationLeft + x
                if (targetX !in 0 until frameWidth) {
                    continue
                }
                val argb = pixels[(y * labelWidth) + x]
                val alpha = Color.alpha(argb)
                if (alpha == 0) {
                    continue
                }
                val luma = rgbToLuma(Color.red(argb), Color.green(argb), Color.blue(argb))
                frameBytes[(targetY * frameWidth) + targetX] = luma.toByte()
            }
        }
    }

    private fun fillRect(
        frameBytes: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        luma: Int
    ) {
        val clampedLeft = left.coerceIn(0, frameWidth)
        val clampedTop = top.coerceIn(0, frameHeight)
        val clampedRight = right.coerceIn(clampedLeft, frameWidth)
        val clampedBottom = bottom.coerceIn(clampedTop, frameHeight)
        for (y in clampedTop until clampedBottom) {
            val rowOffset = y * frameWidth
            for (x in clampedLeft until clampedRight) {
                frameBytes[rowOffset + x] = luma.toByte()
            }
        }
    }

    private fun drawHorizontalLine(
        frameBytes: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        y: Int,
        left: Int,
        right: Int,
        luma: Int
    ) {
        if (y !in 0 until frameHeight) {
            return
        }
        val rowOffset = y * frameWidth
        for (x in left.coerceAtLeast(0) until right.coerceAtMost(frameWidth)) {
            frameBytes[rowOffset + x] = luma.toByte()
        }
    }

    private fun drawVerticalLine(
        frameBytes: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        x: Int,
        top: Int,
        bottom: Int,
        luma: Int
    ) {
        if (x !in 0 until frameWidth) {
            return
        }
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(frameHeight)) {
            frameBytes[(y * frameWidth) + x] = luma.toByte()
        }
    }

    private fun rgbToLuma(red: Int, green: Int, blue: Int): Int {
        return ((red * 77) + (green * 150) + (blue * 29)) shr 8
    }
}
