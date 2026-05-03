package com.example.ar_control.face

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer

class LiteRtFaceEmbeddingModel private constructor(
    private val environment: Environment,
    private val compiledModel: CompiledModel,
    private val inputBuffers: List<TensorBuffer>,
    private val outputBuffers: List<TensorBuffer>
) : FaceEmbeddingModel {

    override fun embed(faceBitmap: Bitmap): FaceEmbedding {
        val inputFloats = faceBitmap.toInputTensor()
        inputBuffers.single().writeFloat(inputFloats)
        compiledModel.run(inputBuffers, outputBuffers)
        return FaceEmbedding(outputBuffers.single().readFloat())
    }

    override fun close() {
        runCatching { compiledModel.close() }
        runCatching { environment.close() }
    }

    private fun Bitmap.toInputTensor(): FloatArray {
        val resized = if (width == INPUT_WIDTH && height == INPUT_HEIGHT) {
            this
        } else {
            scale(INPUT_WIDTH, INPUT_HEIGHT)
        }
        val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
        resized.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)
        if (resized !== this) {
            resized.recycle()
        }
        val output = FloatArray(INPUT_WIDTH * INPUT_HEIGHT * CHANNEL_COUNT)
        var outputIndex = 0
        for (pixel in pixels) {
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            output[outputIndex++] = normalizeChannel(red)
            output[outputIndex++] = normalizeChannel(green)
            output[outputIndex++] = normalizeChannel(blue)
        }
        return output
    }

    private fun normalizeChannel(channel: Int): Float {
        return (channel / 127.5f) - 1f
    }

    companion object {
        const val DEFAULT_MODEL_ASSET_PATH = "models/face_embedding.tflite"
        const val INPUT_WIDTH = 112
        const val INPUT_HEIGHT = 112
        private const val CHANNEL_COUNT = 3

        fun openOrNull(
            context: Context,
            modelAssetPath: String = DEFAULT_MODEL_ASSET_PATH
        ): LiteRtFaceEmbeddingModel? {
            val assets = context.applicationContext.assets
            if (!assets.exists(modelAssetPath)) {
                return null
            }
            val environment = Environment.create()
            val compiledModel = runCatching {
                CompiledModel.create(
                    assets,
                    modelAssetPath,
                    CompiledModel.Options(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU),
                    environment
                )
            }.recoverCatching {
                CompiledModel.create(
                    assets,
                    modelAssetPath,
                    CompiledModel.Options(Accelerator.CPU),
                    environment
                )
            }.getOrElse { error ->
                environment.close()
                throw error
            }
            val inputBuffers = compiledModel.createInputBuffers()
            val outputBuffers = compiledModel.createOutputBuffers()
            require(inputBuffers.size == 1) {
                "Expected one face embedding input buffer but found ${inputBuffers.size}"
            }
            require(outputBuffers.size == 1) {
                "Expected one face embedding output buffer but found ${outputBuffers.size}"
            }
            return LiteRtFaceEmbeddingModel(
                environment = environment,
                compiledModel = compiledModel,
                inputBuffers = inputBuffers,
                outputBuffers = outputBuffers
            )
        }

        private fun AssetManager.exists(assetPath: String): Boolean {
            return runCatching {
                open(assetPath).use { true }
            }.getOrDefault(false)
        }
    }
}

