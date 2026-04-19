package com.example.ar_control.detection

object YoloInputTensorBuilder {
    private const val CHANNEL_COUNT = 3
    private const val PADDING_RGB = 114f / 255f

    fun fillInputTensor(
        frameBytes: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        modelWidth: Int,
        modelHeight: Int,
        destination: FloatArray
    ): LetterboxFrameTransform {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source size must be positive" }
        require(modelWidth > 0 && modelHeight > 0) { "Model size must be positive" }
        require(destination.size == modelWidth * modelHeight * CHANNEL_COUNT) {
            "Unexpected destination size ${destination.size} for ${modelWidth}x${modelHeight}"
        }

        val expectedFrameSize = sourceWidth * sourceHeight * 3 / 2
        require(frameBytes.size >= expectedFrameSize) {
            "Unexpected frame size ${frameBytes.size} for ${sourceWidth}x${sourceHeight}"
        }

        val transform = LetterboxFrameTransform.forResize(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            modelWidth = modelWidth,
            modelHeight = modelHeight
        )

        var destinationIndex = 0
        for (modelY in 0 until modelHeight) {
            val sourceY = sourceCoordinate(
                coordinate = modelY,
                pad = transform.padTop,
                scale = transform.scale,
                maxExclusive = sourceHeight
            )
            for (modelX in 0 until modelWidth) {
                val sourceX = sourceCoordinate(
                    coordinate = modelX,
                    pad = transform.padLeft,
                    scale = transform.scale,
                    maxExclusive = sourceWidth
                )

                if (sourceX == null || sourceY == null) {
                    destination[destinationIndex++] = PADDING_RGB
                    destination[destinationIndex++] = PADDING_RGB
                    destination[destinationIndex++] = PADDING_RGB
                    continue
                }

                val rgb = yuv420SpToRgb(
                    frameBytes = frameBytes,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    sourceX = sourceX,
                    sourceY = sourceY
                )
                destination[destinationIndex++] = rgb.red
                destination[destinationIndex++] = rgb.green
                destination[destinationIndex++] = rgb.blue
            }
        }

        return transform
    }

    private fun sourceCoordinate(
        coordinate: Int,
        pad: Float,
        scale: Float,
        maxExclusive: Int
    ): Int? {
        val modelCoordinate = coordinate.toFloat()
        if (modelCoordinate < pad || modelCoordinate >= pad + (maxExclusive * scale)) {
            return null
        }
        return (((modelCoordinate - pad) / scale).toInt()).coerceIn(0, maxExclusive - 1)
    }

    private fun yuv420SpToRgb(
        frameBytes: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceX: Int,
        sourceY: Int
    ): RgbPixel {
        val yIndex = sourceY * sourceWidth + sourceX
        val uvPlaneStart = sourceWidth * sourceHeight
        val chromaRowStart = uvPlaneStart + ((sourceY / 2) * sourceWidth)
        val uvIndex = chromaRowStart + ((sourceX / 2) * 2)

        val y = frameBytes[yIndex].toInt() and 0xFF
        val u = frameBytes[uvIndex].toInt() and 0xFF
        val v = frameBytes[uvIndex + 1].toInt() and 0xFF

        val c = (y - 16).coerceAtLeast(0)
        val d = u - 128
        val e = v - 128

        val red = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255) / 255f
        val green = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255) / 255f
        val blue = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255) / 255f

        return RgbPixel(
            red = red,
            green = green,
            blue = blue
        )
    }

    private data class RgbPixel(
        val red: Float,
        val green: Float,
        val blue: Float
    )
}
