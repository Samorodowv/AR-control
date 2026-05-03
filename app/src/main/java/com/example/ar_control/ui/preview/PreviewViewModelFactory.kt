package com.example.ar_control.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.ar_control.camera.CameraSource
import com.example.ar_control.camera.CameraSourcePreferences
import com.example.ar_control.detection.DetectionPreferences
import com.example.ar_control.detection.ObjectDetector
import com.example.ar_control.diagnostics.SessionLog
import com.example.ar_control.face.FaceRecognizer
import com.example.ar_control.face.NoOpFaceRecognizer
import com.example.ar_control.gemma.GemmaFrameCaptioner
import com.example.ar_control.gemma.GemmaModelDownloadScheduler
import com.example.ar_control.gemma.GemmaSubtitlePreferences
import com.example.ar_control.gemma.NoOpGemmaFrameCaptioner
import com.example.ar_control.recording.ClipRepository
import com.example.ar_control.recording.DetectionAnnotationSink
import com.example.ar_control.recording.NoOpDetectionAnnotationSink
import com.example.ar_control.recording.RecordingPreferences
import com.example.ar_control.recording.VideoRecorder
import com.example.ar_control.recovery.RecoveryManager
import com.example.ar_control.usb.EyeUsbConfigurator
import com.example.ar_control.usb.UsbPermissionGateway
import com.example.ar_control.xreal.GlassesSession

class PreviewViewModelFactory(
    private val glassesSession: GlassesSession,
    private val eyeUsbConfigurator: EyeUsbConfigurator,
    private val usbPermissionGateway: UsbPermissionGateway,
    private val cameraSource: CameraSource,
    private val androidCameraSource: CameraSource = cameraSource,
    private val cameraSourcePreferences: CameraSourcePreferences = NoOpCameraSourcePreferences,
    private val recordingPreferences: RecordingPreferences,
    private val detectionPreferences: DetectionPreferences,
    private val objectDetector: ObjectDetector,
    private val detectionAnnotationSink: DetectionAnnotationSink = NoOpDetectionAnnotationSink,
    private val gemmaSubtitlePreferences: GemmaSubtitlePreferences = NoOpGemmaSubtitlePreferences,
    private val gemmaModelDownloadScheduler: GemmaModelDownloadScheduler? = null,
    private val gemmaFrameCaptioner: GemmaFrameCaptioner = NoOpGemmaFrameCaptioner,
    private val faceRecognizer: FaceRecognizer = NoOpFaceRecognizer(),
    private val clipRepository: ClipRepository,
    private val videoRecorder: VideoRecorder,
    private val recoveryManager: RecoveryManager,
    private val sessionLog: SessionLog
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PreviewViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return PreviewViewModel(
            glassesSession = glassesSession,
            eyeUsbConfigurator = eyeUsbConfigurator,
            usbPermissionGateway = usbPermissionGateway,
            cameraSource = cameraSource,
            androidCameraSource = androidCameraSource,
            cameraSourcePreferences = cameraSourcePreferences,
            recordingPreferences = recordingPreferences,
            detectionPreferences = detectionPreferences,
            objectDetector = objectDetector,
            detectionAnnotationSink = detectionAnnotationSink,
            gemmaSubtitlePreferences = gemmaSubtitlePreferences,
            gemmaModelDownloadScheduler = gemmaModelDownloadScheduler,
            gemmaFrameCaptioner = gemmaFrameCaptioner,
            faceRecognizer = faceRecognizer,
            clipRepository = clipRepository,
            videoRecorder = videoRecorder,
            recoveryManager = recoveryManager,
            sessionLog = sessionLog
        ) as T
    }
}
