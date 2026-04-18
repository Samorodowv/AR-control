package com.example.ar_control.camera

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.view.Surface
import androidx.core.content.ContextCompat
import com.example.ar_control.diagnostics.NoOpSessionLog
import com.example.ar_control.diagnostics.SessionLog
import com.example.ar_control.recording.RecordingInputTarget
import com.example.ar_control.recording.VideoFramePixelFormat
import com.example.ar_control.usb.UsbDeviceMatcher
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface UvcRecordingSession {
    fun startSurfaceCapture(surface: Surface)
    fun stopSurfaceCapture()
    fun startFrameCapture(callback: IFrameCallback, pixelFormat: Int)
    fun stopFrameCapture()
}

fun interface UvcRecordingSessionFactory {
    fun create(camera: UVCCamera): UvcRecordingSession
}

enum class UvcCaptureState {
    IDLE,
    STARTING,
    RUNNING
}

private enum class UvcRecordingMode {
    SURFACE,
    FRAME_CALLBACK
}

private class AndroidUvcRecordingSession(
    private val camera: UVCCamera
) : UvcRecordingSession {
    override fun startSurfaceCapture(surface: Surface) {
        camera.startCapture(surface)
    }

    override fun stopSurfaceCapture() {
        camera.stopCapture()
    }

    override fun startFrameCapture(callback: IFrameCallback, pixelFormat: Int) {
        camera.setFrameCallback(callback, pixelFormat)
    }

    override fun stopFrameCapture() {
        camera.setFrameCallback(null, 0)
    }
}

class AndroidUvcLibraryAdapter(
    context: Context,
    private val usbManager: UsbManager = checkNotNull(context.getSystemService(UsbManager::class.java)),
    private val cameraFactory: () -> UVCCamera = { UVCCamera() },
    private val sessionCoordinator: UvcOpenSessionCoordinator = UvcOpenSessionCoordinator(),
    private val sessionLog: SessionLog = NoOpSessionLog,
    private val recordingSessionFactory: UvcRecordingSessionFactory =
        UvcRecordingSessionFactory(::AndroidUvcRecordingSession)
) : UvcLibraryAdapter {

    private val appContext = context.applicationContext
    private val stateLock = Any()

    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private var recordingSession: UvcRecordingSession? = null
    private var captureState = UvcCaptureState.IDLE
    private var recordingMode: UvcRecordingMode? = null

    override suspend fun open(surfaceToken: CameraSource.SurfaceToken): UvcLibraryAdapter.OpenResult {
        if (!hasRuntimeCameraPermission()) {
            return logAndReturn(
                "Missing CAMERA runtime permission for USB video device access",
                UvcLibraryAdapter.OpenResult.MissingCameraPermission
            )
        }
        val textureToken = surfaceToken as? TextureViewSurfaceToken
            ?: return logAndReturn(
                "Missing TextureView surface token for preview",
                UvcLibraryAdapter.OpenResult.MissingSurface
            )
        val surfaceTexture = textureToken.textureView.surfaceTexture
            ?: return logAndReturn(
                "TextureView surface texture is not available",
                UvcLibraryAdapter.OpenResult.MissingSurface
            )
        val device = findCameraCandidate() ?: return logAndReturn(
            "No XREAL UVC camera device found. Connected devices: ${describeUsbDevices()}",
            UvcLibraryAdapter.OpenResult.MissingDevice
        )

        val session = sessionCoordinator.beginSession()
        releaseOwnedResources()

        val listener = PermissionListener(device.deviceName)
        val monitor = USBMonitor(appContext, listener)

        return try {
            if (!adoptMonitor(session, monitor)) {
                runCatching { monitor.destroy() }
                return logAndReturn(
                    "Failed to adopt USB monitor session for ${device.summary()}",
                    UvcLibraryAdapter.OpenResult.OpenFailed
                )
            }

            sessionLog.record("AndroidUvcLibraryAdapter", "Opening UVC preview for ${device.summary()}")
            monitor.register()
            val controlBlock = awaitControlBlock(monitor, listener, device, session)
            session.throwIfInactive()
            val camera = cameraFactory()

            try {
                camera.open(controlBlock)
            } catch (_: Exception) {
                runCatching { camera.destroy() }
                releaseOwnedResources(session)
                return logAndReturn(
                    "Failed to open UVC camera for ${device.summary()}",
                    UvcLibraryAdapter.OpenResult.OpenFailed
                )
            }

            session.throwIfInactive()
            val supportedSizes = runCatching { camera.supportedSizeList }
                .getOrDefault(emptyList())
            sessionLog.record(
                "AndroidUvcLibraryAdapter",
                "UVC supported sizes for ${device.summary()}: ${
                    supportedSizes.joinToString(separator = ", ") { "${it.width}x${it.height}" }.ifEmpty { "unknown" }
                }"
            )
            val previewConfig = try {
                UvcPreviewConfigurator.configure(UvcPreviewConfigCamera(camera))
            } catch (error: Exception) {
                runCatching { camera.destroy() }
                releaseOwnedResources(session)
                return logAndReturn(
                    "Failed to configure UVC preview for ${device.summary()}: ${error.message ?: "unknown_error"}",
                    UvcLibraryAdapter.OpenResult.PreviewStartFailed
                )
            }
            sessionLog.record(
                "AndroidUvcLibraryAdapter",
                "Configured UVC preview for ${device.summary()} as ${previewConfig.width}x${previewConfig.height} ${previewConfig.frameFormatName()}"
            )

            if (!adoptCamera(session, camera)) {
                runCatching { camera.destroy() }
                releaseOwnedResources(session)
                return logAndReturn(
                    "Failed to adopt camera session for ${device.summary()}",
                    UvcLibraryAdapter.OpenResult.OpenFailed
                )
            }

            try {
                camera.setPreviewTexture(surfaceTexture)
                camera.startPreview()
                session.throwIfInactive()
            } catch (_: Exception) {
                releaseOwnedResources(session)
                return logAndReturn(
                    "Failed to start UVC preview for ${device.summary()}",
                    UvcLibraryAdapter.OpenResult.PreviewStartFailed
                )
            }

            sessionLog.record("AndroidUvcLibraryAdapter", "UVC preview started for ${device.summary()}")
            UvcLibraryAdapter.OpenResult.Started(
                previewSize = PreviewSize(
                    width = previewConfig.width,
                    height = previewConfig.height
                )
            )
        } catch (_: UvcSessionClosedException) {
            releaseOwnedResources(session)
            logAndReturn(
                "UVC session was closed while opening ${device.summary()}",
                UvcLibraryAdapter.OpenResult.OpenFailed
            )
        } catch (error: CancellationException) {
            releaseOwnedResources(session)
            throw error
        } catch (_: Exception) {
            releaseOwnedResources(session)
            logAndReturn(
                "Unexpected failure while opening ${device.summary()}",
                UvcLibraryAdapter.OpenResult.OpenFailed
            )
        }
    }

    override suspend fun stop() {
        sessionCoordinator.cancelCurrent()
        releaseOwnedResources()
    }

    override suspend fun startRecording(target: RecordingInputTarget): UvcLibraryAdapter.RecordingResult {
        val reservedRecordingSession = synchronized(stateLock) {
            if (captureState != UvcCaptureState.IDLE) {
                return logRecordingResult(
                    "Cannot start UVC recording capture because recording is already running",
                    UvcLibraryAdapter.RecordingResult.AlreadyRecording
                )
            }
            val existingRecordingSession = recordingSession ?: return logRecordingResult(
                "Cannot start UVC recording capture because camera is not open",
                UvcLibraryAdapter.RecordingResult.NotOpen
            )
            captureState = UvcCaptureState.STARTING
            existingRecordingSession
        } ?: return logRecordingResult(
            "Cannot start UVC recording capture because camera is not open",
            UvcLibraryAdapter.RecordingResult.NotOpen
        )

        return runCatching {
            synchronized(stateLock) {
                if (recordingSession !== reservedRecordingSession) {
                    return@runCatching UvcLibraryAdapter.RecordingResult.Failed
                }
            }
            when (target) {
                is RecordingInputTarget.SurfaceTarget -> {
                    reservedRecordingSession.startSurfaceCapture(target.surface)
                    synchronized(stateLock) {
                        recordingMode = UvcRecordingMode.SURFACE
                    }
                }

                is RecordingInputTarget.FrameCallbackTarget -> {
                    val frameCallback = CameraFrameCallback(target.frameConsumer)
                    reservedRecordingSession.startFrameCapture(
                        callback = frameCallback,
                        pixelFormat = target.pixelFormat.toUvcPixelFormat()
                    )
                    synchronized(stateLock) {
                        recordingMode = UvcRecordingMode.FRAME_CALLBACK
                    }
                }
            }
            synchronized(stateLock) {
                if (recordingSession !== reservedRecordingSession) {
                    stopRecordingSession(reservedRecordingSession, recordingMode)
                    return@runCatching UvcLibraryAdapter.RecordingResult.Failed
                }
                captureState = UvcCaptureState.RUNNING
            }
            logRecordingResult(
                "UVC recording capture started using ${recordingMode ?: "unknown"}",
                UvcLibraryAdapter.RecordingResult.Started
            )
        }.getOrElse { error ->
            synchronized(stateLock) {
                if (recordingSession === reservedRecordingSession) {
                    captureState = UvcCaptureState.IDLE
                    recordingMode = null
                }
            }
            logRecordingResult(
                "UVC recording capture failed: ${error.message ?: "unknown_error"}",
                UvcLibraryAdapter.RecordingResult.Failed
            )
        }
    }

    override suspend fun stopRecording() {
        val recordingToStop = synchronized(stateLock) {
            if (captureState != UvcCaptureState.RUNNING) {
                sessionLog.record("AndroidUvcLibraryAdapter", "Ignoring UVC recording stop because capture is not running")
                return
            }
            val activeSession = recordingSession ?: return
            val activeMode = recordingMode ?: return
            activeSession to activeMode
        } ?: return

        try {
            sessionLog.record("AndroidUvcLibraryAdapter", "Stopping UVC recording capture")
            stopRecordingSession(recordingToStop.first, recordingToStop.second)
            synchronized(stateLock) {
                if (recordingSession === recordingToStop.first) {
                    captureState = UvcCaptureState.IDLE
                    recordingMode = null
                }
            }
            sessionLog.record("AndroidUvcLibraryAdapter", "UVC recording capture stopped")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val reason = error.message ?: "unknown_error"
            sessionLog.record("AndroidUvcLibraryAdapter", "UVC recording capture stop failed: $reason")
            throw error
        }
    }

    private fun findCameraCandidate(): UsbDevice? {
        return usbManager.deviceList.values
            .filter(UsbDeviceMatcher::isCameraCandidate)
            .sortedWith(compareBy<UsbDevice>({ it.vendorId }, { it.productId }, { it.deviceName }))
            .firstOrNull()
    }

    private suspend fun awaitControlBlock(
        monitor: USBMonitor,
        listener: PermissionListener,
        device: UsbDevice,
        session: UvcOpenSessionCoordinator.Session
    ): USBMonitor.UsbControlBlock {
        session.throwIfInactive()
        if (usbManager.hasPermission(device)) {
            sessionLog.record(
                "AndroidUvcLibraryAdapter",
                "UVC permission already granted for ${device.summary()}"
            )
            return monitor.openDevice(device)
        }

        return suspendCancellableCoroutine { continuation ->
            session.registerCancellationHandler { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }

            listener.onResult = { result ->
                if (continuation.isActive) {
                    session.clearCancellationHandler()
                    listener.onResult = null
                    when (result) {
                        is PermissionResult.Connected -> {
                            sessionLog.record(
                                "AndroidUvcLibraryAdapter",
                                "UVC device connected for ${device.summary()}"
                            )
                            continuation.resume(result.controlBlock)
                        }
                        PermissionResult.Cancelled -> {
                            sessionLog.record(
                                "AndroidUvcLibraryAdapter",
                                "UVC permission denied for ${device.summary()}"
                            )
                            continuation.resumeWithException(IllegalStateException("usb_permission_denied"))
                        }
                        PermissionResult.Detached -> {
                            sessionLog.record(
                                "AndroidUvcLibraryAdapter",
                                "UVC device detached during open for ${device.summary()}"
                            )
                            continuation.resumeWithException(IllegalStateException("usb_device_detached"))
                        }
                    }
                }
            }

            continuation.invokeOnCancellation {
                listener.onResult = null
                session.clearCancellationHandler()
            }

            sessionLog.record(
                "AndroidUvcLibraryAdapter",
                "Requesting UVC permission for ${device.summary()}"
            )
            val requestFailed = monitor.requestPermission(device)
            if (requestFailed && continuation.isActive) {
                sessionLog.record(
                    "AndroidUvcLibraryAdapter",
                    "UVC permission request failed for ${device.summary()}"
                )
                listener.onResult = null
                session.clearCancellationHandler()
                continuation.resumeWithException(IllegalStateException("usb_permission_request_failed"))
            }
        }
    }

    private fun adoptMonitor(
        session: UvcOpenSessionCoordinator.Session,
        monitor: USBMonitor
    ): Boolean {
        synchronized(stateLock) {
            if (!session.isActive()) {
                return false
            }
            usbMonitor = monitor
            return true
        }
    }

    private fun adoptCamera(
        session: UvcOpenSessionCoordinator.Session,
        camera: UVCCamera
    ): Boolean {
        synchronized(stateLock) {
            if (!session.isActive()) {
                return false
            }
            uvcCamera = camera
            recordingSession = recordingSessionFactory.create(camera)
            captureState = UvcCaptureState.IDLE
            recordingMode = null
            return true
        }
    }

    private fun releaseOwnedResources(
        session: UvcOpenSessionCoordinator.Session? = null
    ) {
        val camera: UVCCamera?
        val monitor: USBMonitor?
        val activeRecordingSession: UvcRecordingSession?
        val previousCaptureState: UvcCaptureState
        val previousRecordingMode: UvcRecordingMode?
        synchronized(stateLock) {
            if (session != null && session.isActive()) {
                camera = uvcCamera
                monitor = usbMonitor
                activeRecordingSession = recordingSession
                previousCaptureState = captureState
                previousRecordingMode = recordingMode
                uvcCamera = null
                usbMonitor = null
                recordingSession = null
                captureState = UvcCaptureState.IDLE
                recordingMode = null
            } else if (session == null) {
                camera = uvcCamera
                monitor = usbMonitor
                activeRecordingSession = recordingSession
                previousCaptureState = captureState
                previousRecordingMode = recordingMode
                uvcCamera = null
                usbMonitor = null
                recordingSession = null
                captureState = UvcCaptureState.IDLE
                recordingMode = null
            } else {
                camera = null
                monitor = null
                activeRecordingSession = null
                previousCaptureState = UvcCaptureState.IDLE
                previousRecordingMode = null
            }
        }

        sessionLog.record(
            "AndroidUvcLibraryAdapter",
            "Releasing UVC resources: captureState=$previousCaptureState, recordingMode=${previousRecordingMode ?: "none"}, hasCamera=${camera != null}, hasMonitor=${monitor != null}"
        )

        runCatching {
            if (previousCaptureState == UvcCaptureState.RUNNING) {
                sessionLog.record("AndroidUvcLibraryAdapter", "Stopping active UVC capture during resource release")
                if (activeRecordingSession != null && previousRecordingMode != null) {
                    stopRecordingSession(activeRecordingSession, previousRecordingMode)
                }
            }
        }.exceptionOrNull()?.let { error ->
            sessionLog.record(
                "AndroidUvcLibraryAdapter",
                "UVC capture stop during release failed: ${error.message ?: "unknown_error"}"
            )
        }
        runCatching {
            if (camera != null) {
                sessionLog.record("AndroidUvcLibraryAdapter", "Stopping UVC preview")
                camera.stopPreview()
            }
        }.exceptionOrNull()?.let { error ->
            sessionLog.record(
                "AndroidUvcLibraryAdapter",
                "UVC preview stop failed: ${error.message ?: "unknown_error"}"
            )
        }
        runCatching {
            if (camera != null) {
                sessionLog.record("AndroidUvcLibraryAdapter", "Destroying UVC camera")
                camera.destroy()
            }
        }.exceptionOrNull()?.let { error ->
            sessionLog.record(
                "AndroidUvcLibraryAdapter",
                "UVC camera destroy failed: ${error.message ?: "unknown_error"}"
            )
        }
        runCatching {
            if (monitor != null) {
                sessionLog.record("AndroidUvcLibraryAdapter", "Unregistering USB monitor")
                monitor.unregister()
            }
        }.exceptionOrNull()?.let { error ->
            sessionLog.record(
                "AndroidUvcLibraryAdapter",
                "USB monitor unregister failed: ${error.message ?: "unknown_error"}"
            )
        }
        runCatching {
            if (monitor != null) {
                sessionLog.record("AndroidUvcLibraryAdapter", "Destroying USB monitor")
                monitor.destroy()
            }
        }.exceptionOrNull()?.let { error ->
            sessionLog.record(
                "AndroidUvcLibraryAdapter",
                "USB monitor destroy failed: ${error.message ?: "unknown_error"}"
            )
        }
    }

    private fun logAndReturn(
        message: String,
        result: UvcLibraryAdapter.OpenResult
    ): UvcLibraryAdapter.OpenResult {
        sessionLog.record("AndroidUvcLibraryAdapter", message)
        return result
    }

    private fun logRecordingResult(
        message: String,
        result: UvcLibraryAdapter.RecordingResult
    ): UvcLibraryAdapter.RecordingResult {
        sessionLog.record("AndroidUvcLibraryAdapter", message)
        return result
    }

    private fun hasRuntimeCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun describeUsbDevices(): String {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            return "none"
        }
        return devices.joinToString(separator = "; ") { device -> device.summary() }
    }

    private fun UsbDevice.summary(): String {
        val interfaces = (0 until interfaceCount)
            .joinToString(separator = ",") { index ->
                deviceClassName(getInterface(index).interfaceClass)
            }
        return "vid=0x${vendorId.toString(16)}, pid=0x${productId.toString(16)}, name=$deviceName, interfaces=$interfaces"
    }

    private fun deviceClassName(interfaceClass: Int): String {
        return when (interfaceClass) {
            UsbConstants.USB_CLASS_HID -> "HID"
            UsbConstants.USB_CLASS_VIDEO -> "VIDEO"
            else -> interfaceClass.toString()
        }
    }

    private fun PreviewConfigAttempt.frameFormatName(): String {
        return when (frameFormat) {
            UVCCamera.FRAME_FORMAT_MJPEG -> "MJPEG"
            UVCCamera.DEFAULT_PREVIEW_MODE -> "YUYV"
            else -> frameFormat.toString()
        }
    }

    private fun stopRecordingSession(
        session: UvcRecordingSession,
        mode: UvcRecordingMode?
    ) {
        when (mode) {
            UvcRecordingMode.SURFACE -> session.stopSurfaceCapture()
            UvcRecordingMode.FRAME_CALLBACK -> session.stopFrameCapture()
            null -> session.stopFrameCapture()
        }
    }

    private fun VideoFramePixelFormat.toUvcPixelFormat(): Int {
        return when (this) {
            VideoFramePixelFormat.YUV420SP -> UVCCamera.PIXEL_FORMAT_YUV420SP
            VideoFramePixelFormat.NV21 -> UVCCamera.PIXEL_FORMAT_NV21
        }
    }

    private class CameraFrameCallback(
        private val consumer: com.example.ar_control.recording.VideoFrameConsumer
    ) : IFrameCallback {
        override fun onFrame(frame: ByteBuffer) {
            consumer.onFrame(frame, System.nanoTime())
        }
    }

    private sealed interface PermissionResult {
        data class Connected(val controlBlock: USBMonitor.UsbControlBlock) : PermissionResult
        data object Cancelled : PermissionResult
        data object Detached : PermissionResult
    }

    private class PermissionListener(
        private val targetDeviceName: String
    ) : USBMonitor.OnDeviceConnectListener {
        var onResult: ((PermissionResult) -> Unit)? = null

        override fun onAttach(device: UsbDevice) = Unit

        override fun onDettach(device: UsbDevice) {
            if (device.deviceName == targetDeviceName) {
                onResult?.invoke(PermissionResult.Detached)
            }
        }

        override fun onConnect(
            device: UsbDevice,
            ctrlBlock: USBMonitor.UsbControlBlock,
            createNew: Boolean
        ) {
            if (device.deviceName == targetDeviceName) {
                val resultHandler = onResult
                if (resultHandler != null) {
                    resultHandler(PermissionResult.Connected(ctrlBlock))
                } else {
                    runCatching { ctrlBlock.close() }
                }
            }
        }

        override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) = Unit

        override fun onCancel(device: UsbDevice?) {
            if (device == null || device.deviceName == targetDeviceName) {
                onResult?.invoke(PermissionResult.Cancelled)
            }
        }
    }
}
