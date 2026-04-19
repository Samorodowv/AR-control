package com.example.ar_control.detection

import kotlin.math.max
import kotlin.math.min

object YoloDetectionPostProcessor {
    fun process(
        output: FloatArray,
        predictionCount: Int,
        labels: List<String>,
        numClasses: Int,
        confidenceThreshold: Float,
        iouThreshold: Float,
        maxResults: Int,
        frameTransform: LetterboxFrameTransform
    ): List<DetectedObject> {
        val channelCount = 4 + numClasses
        require(output.size == channelCount * predictionCount) {
            "Unexpected YOLO output size ${output.size} for predictionCount=$predictionCount numClasses=$numClasses"
        }

        val candidates = buildList {
            for (predictionIndex in 0 until predictionCount) {
                val labelIndex = findBestLabelIndex(output, predictionCount, numClasses, predictionIndex)
                val confidence = output[channelOffset(4 + labelIndex, predictionCount, predictionIndex)]
                if (confidence < confidenceThreshold) {
                    continue
                }

                val centerX = output[channelOffset(0, predictionCount, predictionIndex)]
                val centerY = output[channelOffset(1, predictionCount, predictionIndex)]
                val width = output[channelOffset(2, predictionCount, predictionIndex)]
                val height = output[channelOffset(3, predictionCount, predictionIndex)]

                val mappedBox = frameTransform.mapModelRectToSource(
                    left = centerX - (width / 2f),
                    top = centerY - (height / 2f),
                    right = centerX + (width / 2f),
                    bottom = centerY + (height / 2f)
                )
                add(
                    DetectedObject(
                        labelIndex = labelIndex,
                        label = labels.getOrNull(labelIndex) ?: labelIndex.toString(),
                        confidence = confidence,
                        boundingBox = mappedBox
                    )
                )
            }
        }.sortedByDescending { it.confidence }

        val selected = mutableListOf<DetectedObject>()
        for (candidate in candidates) {
            val overlaps = selected.any { existing ->
                existing.labelIndex == candidate.labelIndex &&
                    intersectionOverUnion(existing.boundingBox, candidate.boundingBox) > iouThreshold
            }
            if (!overlaps) {
                selected += candidate
            }
            if (selected.size == maxResults) {
                break
            }
        }
        return selected
    }

    private fun findBestLabelIndex(
        output: FloatArray,
        predictionCount: Int,
        numClasses: Int,
        predictionIndex: Int
    ): Int {
        var bestLabelIndex = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (classIndex in 0 until numClasses) {
            val score = output[channelOffset(4 + classIndex, predictionCount, predictionIndex)]
            if (score > bestScore) {
                bestScore = score
                bestLabelIndex = classIndex
            }
        }
        return bestLabelIndex
    }

    private fun channelOffset(
        channelIndex: Int,
        predictionCount: Int,
        predictionIndex: Int
    ): Int {
        return (channelIndex * predictionCount) + predictionIndex
    }

    private fun intersectionOverUnion(
        first: DetectionBoundingBox,
        second: DetectionBoundingBox
    ): Float {
        val overlapLeft = max(first.left, second.left)
        val overlapTop = max(first.top, second.top)
        val overlapRight = min(first.right, second.right)
        val overlapBottom = min(first.bottom, second.bottom)
        val overlapWidth = (overlapRight - overlapLeft).coerceAtLeast(0f)
        val overlapHeight = (overlapBottom - overlapTop).coerceAtLeast(0f)
        val intersectionArea = overlapWidth * overlapHeight
        if (intersectionArea <= 0f) {
            return 0f
        }
        val unionArea = (first.width * first.height) + (second.width * second.height) - intersectionArea
        return if (unionArea <= 0f) 0f else intersectionArea / unionArea
    }
}
