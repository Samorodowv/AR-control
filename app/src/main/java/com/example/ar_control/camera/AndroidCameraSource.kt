package com.example.ar_control.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Size
import android.view.Surface
import com.example.ar_control.diagnostics.NoOpSessionLog
import com.example.ar_control.diagnostics.SessionLog
import com.example.ar_control.recording.RecordingInputTarget
import com.example.ar_control.recording.VideoFramePixelFormat
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidCameraSource(
    context: Context,
    private val sessionLog: SessionLog = NoOpSessionLog
) : CameraSource {

    private val cameraManager = checkNotNull(context.applicationContext.getSystemService(CameraManager::class.java))
    private val mutex = Mutex()

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var analysisThread: HandlerThread? = null
    private var analysisHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var lastFrameCallbackTimestampNanos: Long? = null
    private var previewSize: PreviewSize? = null

    override suspend fun start(surfaceToken: CameraSource.SurfaceToken): CameraSource.StartResult {
        return mutex.withLock {
            stopLocked()
            val textureView = (surfaceToken as? TextureViewSurfaceToken)?.textureView
                ?: return@withLock CameraSource.StartResult.Failed("android_camera_unsupported_surface")
            val surfaceTexture = textureView.surfaceTexture
                ?: return@withLock CameraSource.StartResult.Failed("missing_surface")
            val cameraId = selectCameraId()
                ?: return@withLock CameraSource.StartResult.Failed("android_camera_missing")
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val selectedSize = selectPreviewSize(characteristics)
            val previewRotationDegrees = calculateAndroidCameraPreviewRotationDegrees(
                sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION),
                displayRotationDegrees = textureView.display?.rotation.toSurfaceRotationDegrees(),
                lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            )
            val handler = ensureCameraHandler()

            sessionLog.record(
                "AndroidCameraSource",
                "Opening Android camera id=$cameraId as ${selectedSize.width}x${selectedSize.height}, rotation=$previewRotationDegrees"
            )

            surfaceTexture.setDefaultBufferSize(selectedSize.width, selectedSize.height)
            val surface = Surface(surfaceTexture)
            try {
                val device = openCamera(cameraId, handler)
                val session = createCaptureSession(device, listOf(surface), handler)
                startRepeatingRequest(device, session, listOf(surface), handler)
                cameraDevice = device
                captureSession = session
                previewSurface = surface
                previewSize = PreviewSize(
                    width = selectedSize.width,
                    height = selectedSize.height,
                    rotationDegrees = previewRotationDegrees
                )
                sessionLog.record("AndroidCameraSource", "Android camera preview started")
                CameraSource.StartResult.Started(checkNotNull(previewSize))
            } catch (error: CancellationException) {
                surface.release()
                throw error
            } catch (error: Exception) {
                surface.release()
                stopLocked()
                val reason = error.message ?: "android_camera_open_failed"
                sessionLog.record("AndroidCameraSource", "Android camera preview failed: $reason")
                CameraSource.StartResult.Failed(reason)
            }
        }
    }

    override suspend fun stop() {
        mutex.withLock {
            stopLocked()
        }
    }

    override suspend fun startRecording(target: RecordingInputTarget): CameraSource.RecordingStartResult {
        return mutex.withLock {
            val callbackTarget = target as? RecordingInputTarget.FrameCallbackTarget
                ?: return@withLock CameraSource.RecordingStartResult.Failed(
                    "android_camera_frame_callback_required"
                )
            val device = cameraDevice
                ?: return@withLock CameraSource.RecordingStartResult.Failed("recording_camera_not_open")
            val surface = previewSurface
                ?: return@withLock CameraSource.RecordingStartResult.Failed("missing_surface")
            val size = previewSize
                ?: return@withLock CameraSource.RecordingStartResult.Failed("missing_preview_size")
            val handler = ensureCameraHandler()
            val analysisHandler = ensureAnalysisHandler()

            closeImageReader()
            lastFrameCallbackTimestampNanos = null
            val reader = ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.YUV_420_888,
                MAX_IMAGE_READER_IMAGES
            )
            reader.setOnImageAvailableListener(
                { imageReader -> dispatchLatestFrame(imageReader, callbackTarget) },
                analysisHandler
            )

            return@withLock try {
                captureSession?.close()
                val surfaces = listOf(surface, reader.surface)
                val session = createCaptureSession(device, surfaces, handler)
                startRepeatingRequest(device, session, surfaces, handler)
                imageReader = reader
                captureSession = session
                sessionLog.record("AndroidCameraSource", "Android camera frame callback capture started")
                CameraSource.RecordingStartResult.Started
            } catch (error: CancellationException) {
                reader.close()
                throw error
            } catch (error: Exception) {
                reader.close()
                val reason = error.message ?: "recording_start_failed"
                sessionLog.record("AndroidCameraSource", "Android camera frame callback capture failed: $reason")
                CameraSource.RecordingStartResult.Failed(reason)
            }
        }
    }

    override suspend fun stopRecording() {
        mutex.withLock {
            closeImageReader()
            val device = cameraDevice ?: return@withLock
            val surface = previewSurface ?: return@withLock
            val handler = ensureCameraHandler()
            runCatching {
                captureSession?.close()
                val session = createCaptureSession(device, listOf(surface), handler)
                startRepeatingRequest(device, session, listOf(surface), handler)
                captureSession = session
            }.onFailure { error ->
                sessionLog.record(
                    "AndroidCameraSource",
                    "Android camera preview restore after frame callback failed: ${error.message ?: "unknown_error"}"
                )
            }
        }
    }

    private fun ensureCameraHandler(): Handler {
        cameraHandler?.let { return it }
        val thread = HandlerThread("AndroidCameraSource").also { it.start() }
        val handler = Handler(thread.looper)
        cameraThread = thread
        cameraHandler = handler
        return handler
    }

    private fun ensureAnalysisHandler(): Handler {
        analysisHandler?.let { return it }
        val thread = HandlerThread(
            "AndroidCameraAnalysis",
            Process.THREAD_PRIORITY_BACKGROUND
        ).also { it.start() }
        val handler = Handler(thread.looper)
        analysisThread = thread
        analysisHandler = handler
        return handler
    }

    private fun selectCameraId(): String? {
        val candidates = cameraManager.cameraIdList.map { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            CameraCandidate(cameraId, facing)
        }
        val facingOrder = listOf(
            CameraCharacteristics.LENS_FACING_BACK,
            CameraCharacteristics.LENS_FACING_EXTERNAL,
            CameraCharacteristics.LENS_FACING_FRONT
        )
        return facingOrder.firstNotNullOfOrNull { facing ->
            candidates.firstOrNull { it.facing == facing }?.cameraId
        } ?: candidates.firstOrNull()?.cameraId
    }

    private fun selectPreviewSize(characteristics: CameraCharacteristics): Size {
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSizes = streamMap
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.toList()
            .orEmpty()
        val yuvSizes = streamMap
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.toList()
            .orEmpty()
        val sizes = previewSizes
            .filter { previewSize -> yuvSizes.any { it.width == previewSize.width && it.height == previewSize.height } }
            .ifEmpty { previewSizes }
        if (sizes.isEmpty()) {
            return Size(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT)
        }
        return sizes.firstOrNull { it.width == DEFAULT_PREVIEW_WIDTH && it.height == DEFAULT_PREVIEW_HEIGHT }
            ?: sizes
                .filter { it.width >= it.height }
                .filter { it.width <= DEFAULT_PREVIEW_WIDTH && it.height <= DEFAULT_PREVIEW_HEIGHT }
                .maxByOrNull { it.width * it.height }
            ?: sizes.maxBy { it.width * it.height }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(cameraId: String, handler: Handler): CameraDevice {
        return suspendCancellableCoroutine { continuation ->
            val callback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (continuation.isActive) {
                        continuation.resume(camera)
                    } else {
                        camera.close()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(IllegalStateException("android_camera_disconnected")))
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(IllegalStateException("android_camera_error_$error")))
                    }
                }
            }
            cameraManager.openCamera(cameraId, callback, handler)
            continuation.invokeOnCancellation {
                sessionLog.record("AndroidCameraSource", "Android camera open cancelled")
            }
        }
    }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        handler: Handler
    ): CameraCaptureSession {
        return suspendCancellableCoroutine { continuation ->
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    continuation.resume(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (continuation.isActive) {
                        continuation.resumeWith(
                            Result.failure(IllegalStateException("android_camera_session_config_failed"))
                        )
                    }
                }
            }
            device.createCaptureSession(surfaces, callback, handler)
        }
    }

    private fun startRepeatingRequest(
        device: CameraDevice,
        session: CameraCaptureSession,
        surfaces: List<Surface>,
        handler: Handler
    ) {
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            surfaces.forEach(::addTarget)
        }.build()
        session.setRepeatingRequest(request, null, handler)
    }

    private fun dispatchLatestFrame(
        reader: ImageReader,
        target: RecordingInputTarget.FrameCallbackTarget
    ) {
        val image = reader.acquireLatestImage() ?: return
        image.use {
            runCatching {
                if (!shouldDispatchFrame(it.timestamp, target.minimumFrameIntervalNanos)) {
                    return@runCatching
                }
                val bytes = it.toSemiPlanarBytes(target.pixelFormat)
                target.frameConsumer.onFrame(ByteBuffer.wrap(bytes), it.timestamp)
            }.onFailure { error ->
                sessionLog.record(
                    "AndroidCameraSource",
                    "Android camera frame dispatch failed: ${error.message ?: "unknown_error"}"
                )
            }
        }
    }

    private fun shouldDispatchFrame(
        timestampNanos: Long,
        minimumFrameIntervalNanos: Long
    ): Boolean {
        if (minimumFrameIntervalNanos <= 0L) {
            return true
        }
        val previousTimestamp = lastFrameCallbackTimestampNanos
        if (
            previousTimestamp != null &&
            timestampNanos - previousTimestamp < minimumFrameIntervalNanos
        ) {
            return false
        }
        lastFrameCallbackTimestampNanos = timestampNanos
        return true
    }

    private fun Image.toSemiPlanarBytes(pixelFormat: VideoFramePixelFormat): ByteArray {
        val frameSize = width * height
        val output = ByteArray(frameSize + frameSize / 2)
        copyPlaneToArray(
            plane = planes[0],
            width = width,
            height = height,
            output = output,
            outputOffset = 0,
            outputPixelStride = 1
        )

        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        var outputIndex = frameSize
        for (row in 0 until chromaHeight) {
            val uRowOffset = row * uPlane.rowStride
            val vRowOffset = row * vPlane.rowStride
            for (column in 0 until chromaWidth) {
                val u = uBuffer.get(uRowOffset + column * uPlane.pixelStride)
                val v = vBuffer.get(vRowOffset + column * vPlane.pixelStride)
                when (pixelFormat) {
                    VideoFramePixelFormat.YUV420SP -> {
                        output[outputIndex++] = u
                        output[outputIndex++] = v
                    }

                    VideoFramePixelFormat.NV21 -> {
                        output[outputIndex++] = v
                        output[outputIndex++] = u
                    }
                }
            }
        }
        return output
    }

    private fun copyPlaneToArray(
        plane: Image.Plane,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int,
        outputPixelStride: Int
    ) {
        val buffer = plane.buffer.duplicate()
        var outputIndex = outputOffset
        for (row in 0 until height) {
            val rowOffset = row * plane.rowStride
            for (column in 0 until width) {
                output[outputIndex] = buffer.get(rowOffset + column * plane.pixelStride)
                outputIndex += outputPixelStride
            }
        }
    }

    private fun closeImageReader() {
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        lastFrameCallbackTimestampNanos = null
    }

    private fun stopLocked() {
        closeImageReader()
        runCatching { captureSession?.stopRepeating() }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        previewSurface?.release()
        previewSurface = null
        previewSize = null
        analysisThread?.quitSafely()
        analysisThread = null
        analysisHandler = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    private data class CameraCandidate(
        val cameraId: String,
        val facing: Int?
    )

    private companion object {
        const val DEFAULT_PREVIEW_WIDTH = 1920
        const val DEFAULT_PREVIEW_HEIGHT = 1080
        const val MAX_IMAGE_READER_IMAGES = 2
    }
}

internal fun calculateAndroidCameraPreviewRotationDegrees(
    sensorOrientationDegrees: Int?,
    displayRotationDegrees: Int?,
    lensFacing: Int?
): Int {
    val sensorOrientation = sensorOrientationDegrees ?: return 0
    val displayRotation = displayRotationDegrees ?: 0
    return when (lensFacing) {
        CameraCharacteristics.LENS_FACING_FRONT -> sensorOrientation + displayRotation
        else -> sensorOrientation - displayRotation
    }.normalizedDegrees()
}

private fun Int?.toSurfaceRotationDegrees(): Int {
    return when (this) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}

private fun Int.normalizedDegrees(): Int {
    return ((this % FULL_ROTATION_DEGREES) + FULL_ROTATION_DEGREES) % FULL_ROTATION_DEGREES
}

private const val FULL_ROTATION_DEGREES = 360
