package com.example.ar_control.detection

import android.content.Context
import android.content.res.AssetManager
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.diagnostics.NoOpSessionLog
import com.example.ar_control.diagnostics.SessionLog
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer

class LiteRtYoloObjectDetector(
    context: Context,
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET_PATH,
    private val labelsAssetPath: String = DEFAULT_LABELS_ASSET_PATH,
    private val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    private val iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
    private val maxResults: Int = DEFAULT_MAX_RESULTS,
    private val sessionLog: SessionLog = NoOpSessionLog
) : ObjectDetector {

    private val appContext = context.applicationContext

    override fun start(
        previewSize: PreviewSize,
        onDetectionsUpdated: (List<DetectedObject>) -> Unit,
        onSessionStatsUpdated: (DetectionSessionStats) -> Unit
    ): ObjectDetectionSession {
        sessionLog.record(
            TAG,
            "Starting LiteRT YOLO detector for ${previewSize.width}x${previewSize.height}"
        )
        return BackgroundObjectDetectionSession(
            previewSize = previewSize,
            processor = LiteRtYoloFrameProcessor(
                assets = appContext.assets,
                modelAssetPath = modelAssetPath,
                labelsAssetPath = labelsAssetPath,
                confidenceThreshold = confidenceThreshold,
                iouThreshold = iouThreshold,
                maxResults = maxResults,
                sessionLog = sessionLog
            ),
            onDetectionsUpdated = onDetectionsUpdated,
            onSessionStatsUpdated = onSessionStatsUpdated
        )
    }

    internal class LiteRtYoloFrameProcessor(
        assets: AssetManager,
        modelAssetPath: String,
        labelsAssetPath: String,
        private val confidenceThreshold: Float,
        private val iouThreshold: Float,
        private val maxResults: Int,
        private val sessionLog: SessionLog
    ) : FrameDetectionProcessor {

        override val runtimeBackendLabel: String

        private val environment: Environment
        private val compiledModel: CompiledModel
        private val inputBuffers: List<TensorBuffer>
        private val outputBuffers: List<TensorBuffer>
        private val labels: List<String>
        private val modelWidth: Int
        private val modelHeight: Int
        private val outputChannelCount: Int
        private val inputFloats: FloatArray

        init {
            labels = assets.open(labelsAssetPath).bufferedReader().use { reader ->
                reader.readLines().map(String::trim).filter(String::isNotEmpty)
            }
            outputChannelCount = labels.size + BOX_CHANNEL_COUNT
            modelWidth = DEFAULT_MODEL_INPUT_WIDTH
            modelHeight = DEFAULT_MODEL_INPUT_HEIGHT
            inputFloats = FloatArray(modelWidth * modelHeight * INPUT_CHANNEL_COUNT)

            environment = Environment.create()
            val requestedAccelerators = automaticAccelerators(
                availableAccelerators = runCatching { environment.getAvailableAccelerators() }
                    .getOrDefault(emptySet())
            )
            val preferredBackendLabel = formatAcceleratorLabel(requestedAccelerators)

            val compiledModelSetup = runCatching {
                CompiledModel.create(
                    assets,
                    modelAssetPath,
                    CompiledModel.Options(*requestedAccelerators.toTypedArray()),
                    environment
                )
            }.map { model ->
                CompiledModelSetup(
                    model = model,
                    backendLabel = preferredBackendLabel
                )
            }.recoverCatching { error ->
                sessionLog.record(
                    TAG,
                    "CompiledModel creation failed for $preferredBackendLabel; retrying CPU: ${error.message ?: "unknown_error"}"
                )
                CompiledModelSetup(
                    model = CompiledModel.create(
                        assets,
                        modelAssetPath,
                        CompiledModel.Options(Accelerator.CPU),
                        environment
                    ),
                    backendLabel = "CPU fallback"
                )
            }.getOrElse { error ->
                environment.close()
                throw error
            }

            compiledModel = compiledModelSetup.model
            runtimeBackendLabel = compiledModelSetup.backendLabel
            inputBuffers = compiledModel.createInputBuffers()
            require(inputBuffers.size == 1) {
                "Expected one YOLO input buffer but found ${inputBuffers.size}"
            }

            outputBuffers = compiledModel.createOutputBuffers()
            require(outputBuffers.size == 1) {
                "Expected one YOLO output buffer but found ${outputBuffers.size}"
            }

            sessionLog.record(
                TAG,
                "Loaded YOLO CompiledModel ${modelWidth}x${modelHeight}, accelerators=$runtimeBackendLabel, labels=${labels.size}"
            )
        }

        override fun process(
            frameBytes: ByteArray,
            previewSize: PreviewSize,
            timestampNanos: Long
        ): List<DetectedObject> {
            val transform = YoloInputTensorBuilder.fillInputTensor(
                frameBytes = frameBytes,
                sourceWidth = previewSize.width,
                sourceHeight = previewSize.height,
                modelWidth = modelWidth,
                modelHeight = modelHeight,
                destination = inputFloats
            )

            inputBuffers.single().writeFloat(inputFloats)
            compiledModel.run(inputBuffers, outputBuffers)

            val outputFloats = outputBuffers.single().readFloat()
            require(outputFloats.size % outputChannelCount == 0) {
                "Unexpected YOLO output size ${outputFloats.size} for $outputChannelCount channels"
            }

            val predictionCount = outputFloats.size / outputChannelCount
            denormalizeBoxChannelsInPlace(outputFloats, predictionCount, modelWidth, modelHeight)

            return YoloDetectionPostProcessor.process(
                output = outputFloats,
                predictionCount = predictionCount,
                labels = labels,
                numClasses = labels.size,
                confidenceThreshold = confidenceThreshold,
                iouThreshold = iouThreshold,
                maxResults = maxResults,
                frameTransform = transform
            )
        }

        override fun close() {
            runCatching { compiledModel.close() }
            runCatching { environment.close() }
        }

        private fun denormalizeBoxChannelsInPlace(
            output: FloatArray,
            predictionCount: Int,
            modelWidth: Int,
            modelHeight: Int
        ) {
            for (predictionIndex in 0 until predictionCount) {
                output[channelOffset(0, predictionCount, predictionIndex)] *= modelWidth.toFloat()
                output[channelOffset(1, predictionCount, predictionIndex)] *= modelHeight.toFloat()
                output[channelOffset(2, predictionCount, predictionIndex)] *= modelWidth.toFloat()
                output[channelOffset(3, predictionCount, predictionIndex)] *= modelHeight.toFloat()
            }
        }

        private fun channelOffset(
            channelIndex: Int,
            predictionCount: Int,
            predictionIndex: Int
        ): Int {
            return (channelIndex * predictionCount) + predictionIndex
        }

        private fun automaticAccelerators(
            availableAccelerators: Set<Accelerator>
        ): List<Accelerator> {
            val normalized = availableAccelerators
                .filter { it != Accelerator.NONE }
                .toMutableSet()
                .apply { add(Accelerator.CPU) }

            return ACCELERATOR_PRIORITY.filter(normalized::contains)
                .ifEmpty { listOf(Accelerator.CPU) }
        }

        private fun formatAcceleratorLabel(accelerators: List<Accelerator>): String {
            return accelerators.joinToString(
                separator = "->",
                prefix = "AUTO "
            ) { accelerator ->
                when (accelerator) {
                    Accelerator.NPU -> "NPU"
                    Accelerator.GPU -> "GPU"
                    Accelerator.CPU -> "CPU"
                    Accelerator.NONE -> "NONE"
                }
            }
        }

        private data class CompiledModelSetup(
            val model: CompiledModel,
            val backendLabel: String
        )
    }

    private companion object {
        const val TAG = "LiteRtYoloObjectDetector"
        const val DEFAULT_MODEL_ASSET_PATH = "models/yolo26n_int8.tflite"
        const val DEFAULT_LABELS_ASSET_PATH = "models/yolo26_labels.txt"
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.25f
        const val DEFAULT_IOU_THRESHOLD = 0.45f
        const val DEFAULT_MAX_RESULTS = 20
        const val DEFAULT_MODEL_INPUT_WIDTH = 640
        const val DEFAULT_MODEL_INPUT_HEIGHT = 640
        const val INPUT_CHANNEL_COUNT = 3
        const val BOX_CHANNEL_COUNT = 4
        val ACCELERATOR_PRIORITY = listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)
    }
}
