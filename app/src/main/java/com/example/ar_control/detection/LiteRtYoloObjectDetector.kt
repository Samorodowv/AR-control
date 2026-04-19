package com.example.ar_control.detection

import android.content.Context
import android.content.res.AssetManager
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.diagnostics.NoOpSessionLog
import com.example.ar_control.diagnostics.SessionLog
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

class LiteRtYoloObjectDetector(
    context: Context,
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET_PATH,
    private val labelsAssetPath: String = DEFAULT_LABELS_ASSET_PATH,
    private val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    private val iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
    private val maxResults: Int = DEFAULT_MAX_RESULTS,
    private val numThreads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
    private val sessionLog: SessionLog = NoOpSessionLog
) : ObjectDetector {

    private val appContext = context.applicationContext

    override fun start(
        previewSize: PreviewSize,
        onDetectionsUpdated: (List<DetectedObject>) -> Unit
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
                numThreads = numThreads,
                sessionLog = sessionLog
            ),
            onDetectionsUpdated = onDetectionsUpdated
        )
    }

    internal class LiteRtYoloFrameProcessor(
        assets: AssetManager,
        modelAssetPath: String,
        labelsAssetPath: String,
        private val confidenceThreshold: Float,
        private val iouThreshold: Float,
        private val maxResults: Int,
        numThreads: Int,
        private val sessionLog: SessionLog
    ) : FrameDetectionProcessor {

        private val interpreter: Interpreter
        private val labels: List<String>
        private val modelWidth: Int
        private val modelHeight: Int
        private val predictionCount: Int
        private val outputChannelCount: Int
        private val inputFloats: FloatArray
        private val inputBuffer: ByteBuffer
        private val inputFloatBuffer: FloatBuffer
        private val outputFloats: FloatArray
        private val outputBuffer: ByteBuffer
        private val outputFloatBuffer: FloatBuffer

        init {
            interpreter = Interpreter(
                loadModelFile(assets, modelAssetPath),
                Interpreter.Options().apply {
                    setNumThreads(numThreads)
                }
            )
            interpreter.allocateTensors()

            val inputShape = interpreter.getInputTensor(0).shape()
            require(inputShape.size == 4 && inputShape[0] == 1 && inputShape[3] == 3) {
                "Unexpected YOLO input shape ${inputShape.contentToString()}"
            }

            val outputShape = interpreter.getOutputTensor(0).shape()
            require(outputShape.size == 3 && outputShape[0] == 1) {
                "Unexpected YOLO output shape ${outputShape.contentToString()}"
            }

            modelHeight = inputShape[1]
            modelWidth = inputShape[2]
            outputChannelCount = outputShape[1]
            predictionCount = outputShape[2]

            labels = assets.open(labelsAssetPath).bufferedReader().use { reader ->
                reader.readLines().map(String::trim).filter(String::isNotEmpty)
            }

            require(labels.size == outputChannelCount - 4) {
                "Expected ${outputChannelCount - 4} labels but found ${labels.size}"
            }

            inputFloats = FloatArray(modelWidth * modelHeight * 3)
            inputBuffer = ByteBuffer.allocateDirect(inputFloats.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            inputFloatBuffer = inputBuffer.asFloatBuffer()

            outputFloats = FloatArray(outputChannelCount * predictionCount)
            outputBuffer = ByteBuffer.allocateDirect(outputFloats.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            outputFloatBuffer = outputBuffer.asFloatBuffer()

            sessionLog.record(
                TAG,
                "Loaded YOLO model ${modelWidth}x${modelHeight}, output=${outputShape.contentToString()}, labels=${labels.size}"
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

            inputFloatBuffer.rewind()
            inputFloatBuffer.put(inputFloats)
            inputBuffer.rewind()

            outputFloatBuffer.rewind()
            outputBuffer.rewind()

            interpreter.run(inputBuffer, outputBuffer)

            outputFloatBuffer.rewind()
            outputFloatBuffer.get(outputFloats)
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
            interpreter.close()
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

        private fun loadModelFile(
            assets: AssetManager,
            assetPath: String
        ): MappedByteBuffer {
            val descriptor = assets.openFd(assetPath)
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                return channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength
                )
            }
        }
    }

    private companion object {
        const val TAG = "LiteRtYoloObjectDetector"
        const val DEFAULT_MODEL_ASSET_PATH = "models/yolo26n_int8.tflite"
        const val DEFAULT_LABELS_ASSET_PATH = "models/yolo26_labels.txt"
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.25f
        const val DEFAULT_IOU_THRESHOLD = 0.45f
        const val DEFAULT_MAX_RESULTS = 20
    }
}
