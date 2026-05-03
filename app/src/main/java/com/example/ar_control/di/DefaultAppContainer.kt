package com.example.ar_control.di

import android.app.Application
import android.hardware.usb.UsbManager
import android.os.Environment
import androidx.work.WorkManager
import com.example.ar_control.camera.AndroidCameraSource
import com.example.ar_control.camera.AndroidUvcLibraryAdapter
import com.example.ar_control.camera.CameraSource
import com.example.ar_control.camera.CameraSourcePreferences
import com.example.ar_control.camera.SharedPreferencesCameraSourcePreferences
import com.example.ar_control.camera.UvcCameraSource
import com.example.ar_control.detection.DetectionPreferences
import com.example.ar_control.detection.LiteRtYoloObjectDetector
import com.example.ar_control.detection.NoOpObjectDetector
import com.example.ar_control.detection.ObjectDetector
import com.example.ar_control.detection.SharedPreferencesDetectionPreferences
import com.example.ar_control.diagnostics.DiagnosticsReportBuilder
import com.example.ar_control.diagnostics.PersistentSessionLog
import com.example.ar_control.diagnostics.SessionLog
import com.example.ar_control.face.FaceEmbeddingStore
import com.example.ar_control.face.FaceRecognitionPreferences
import com.example.ar_control.face.FaceRecognizer
import com.example.ar_control.face.MlKitFaceRecognizer
import com.example.ar_control.face.SharedPreferencesFaceRecognitionPreferences
import com.example.ar_control.face.SharedPreferencesFaceEmbeddingStore
import com.example.ar_control.gemma.GemmaFrameCaptioner
import com.example.ar_control.gemma.GemmaModelDownloadScheduler
import com.example.ar_control.gemma.GemmaSubtitlePreferences
import com.example.ar_control.gemma.LiteRtGemmaFrameCaptioner
import com.example.ar_control.gemma.SharedPreferencesGemmaSubtitlePreferences
import com.example.ar_control.gemma.WorkManagerGemmaModelDownloadScheduler
import com.example.ar_control.recording.AndroidClipFileSharer
import com.example.ar_control.recording.ClipFileSharer
import com.example.ar_control.recording.ClipRepository
import com.example.ar_control.recording.DetectionAnnotationSink
import com.example.ar_control.recording.JsonClipRepository
import com.example.ar_control.recording.MediaCodecVideoRecorder
import com.example.ar_control.recording.RecordingPreferences
import com.example.ar_control.recording.SharedPreferencesRecordingPreferences
import com.example.ar_control.recording.VideoRecorder
import com.example.ar_control.recovery.AndroidClipMetadataReader
import com.example.ar_control.recovery.DefaultRecoveryManager
import com.example.ar_control.recovery.FileRecoveryStateStore
import com.example.ar_control.recovery.RecoveryManager
import com.example.ar_control.ui.preview.PreviewViewModelFactory
import com.example.ar_control.usb.AndroidEyeUsbConfigurator
import com.example.ar_control.usb.AndroidHidTransport
import com.example.ar_control.usb.AndroidUsbPermissionGateway
import com.example.ar_control.usb.EyeUsbConfigurator
import com.example.ar_control.usb.HidTransport
import com.example.ar_control.usb.UsbPermissionGateway
import com.example.ar_control.xreal.GlassesSession
import com.example.ar_control.xreal.OneXrGlassesSession
import com.example.ar_control.xreal.OneXrFacade
import com.example.ar_control.xreal.ProductionOneXrFacade
import java.io.File

class DefaultAppContainer(
    application: Application,
    providedSessionLog: SessionLog? = null
) : AppContainer {

    private val appContext = application.applicationContext
    private val usbManager = checkNotNull(appContext.getSystemService(UsbManager::class.java))
    private val packageInfo by lazy {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    }
    override val sessionLog: SessionLog = providedSessionLog ?: PersistentSessionLog(
        file = File(appContext.filesDir, "diagnostics/session-log.txt")
    )
    override val diagnosticsReportBuilder: DiagnosticsReportBuilder by lazy {
        DiagnosticsReportBuilder(
            appVersionName = packageInfo.versionName ?: "unknown",
            appVersionCode = packageInfo.longVersionCode.toInt()
        )
    }

    private val oneXrFacade: OneXrFacade by lazy {
        ProductionOneXrFacade(appContext)
    }

    private val glassesSession: GlassesSession by lazy {
        OneXrGlassesSession(
            facade = oneXrFacade,
            sessionLog = sessionLog
        )
    }

    private val hidTransport: HidTransport by lazy {
        AndroidHidTransport(
            usbManager = usbManager,
            sessionLog = sessionLog
        )
    }

    private val eyeUsbConfigurator: EyeUsbConfigurator by lazy {
        AndroidEyeUsbConfigurator(
            hidTransport = hidTransport,
            sessionLog = sessionLog
        )
    }

    private val usbPermissionGateway: UsbPermissionGateway by lazy {
        AndroidUsbPermissionGateway(
            context = appContext,
            usbManager = usbManager,
            sessionLog = sessionLog
        )
    }

    private val cameraSource: CameraSource by lazy {
        UvcCameraSource(
            AndroidUvcLibraryAdapter(
                context = appContext,
                usbManager = usbManager,
                sessionLog = sessionLog
            )
        )
    }

    private val androidCameraSource: CameraSource by lazy {
        AndroidCameraSource(
            context = appContext,
            sessionLog = sessionLog
        )
    }

    private val recordingsDirectory: File by lazy {
        File(
            checkNotNull(appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)),
            "recordings"
        )
    }

    private val recordingPreferences: RecordingPreferences by lazy {
        SharedPreferencesRecordingPreferences(appContext)
    }

    private val cameraSourcePreferences: CameraSourcePreferences by lazy {
        SharedPreferencesCameraSourcePreferences(appContext)
    }

    private val detectionPreferences: DetectionPreferences by lazy {
        SharedPreferencesDetectionPreferences(appContext)
    }

    private val faceRecognitionPreferences: FaceRecognitionPreferences by lazy {
        SharedPreferencesFaceRecognitionPreferences(appContext)
    }

    private val gemmaSubtitlePreferences: GemmaSubtitlePreferences by lazy {
        SharedPreferencesGemmaSubtitlePreferences(appContext)
    }

    private val gemmaModelDownloadScheduler: GemmaModelDownloadScheduler by lazy {
        WorkManagerGemmaModelDownloadScheduler(
            workManager = WorkManager.getInstance(appContext)
        )
    }

    private val gemmaFrameCaptioner: GemmaFrameCaptioner by lazy {
        LiteRtGemmaFrameCaptioner(
            context = appContext,
            captionPromptProvider = gemmaSubtitlePreferences::getCaptionPrompt,
            sessionLog = sessionLog
        )
    }

    private val objectDetector: ObjectDetector by lazy {
        LiteRtYoloObjectDetector(
            context = appContext,
            sessionLog = sessionLog
        )
    }

    private val faceEmbeddingStore: FaceEmbeddingStore by lazy {
        SharedPreferencesFaceEmbeddingStore(appContext)
    }

    private val faceRecognizer: FaceRecognizer by lazy {
        MlKitFaceRecognizer(
            context = appContext,
            embeddingStore = faceEmbeddingStore,
            sessionLog = sessionLog
        )
    }

    private val recoveryManager: RecoveryManager by lazy {
        DefaultRecoveryManager(
            stateStore = FileRecoveryStateStore(
                File(appContext.filesDir, "recovery/state.properties")
            ),
            clipMetadataReader = AndroidClipMetadataReader(),
            sessionLog = sessionLog
        )
    }

    private val clipRepository: ClipRepository by lazy {
        JsonClipRepository(File(recordingsDirectory, "clips.json"))
    }

    private val videoRecorder: VideoRecorder by lazy {
        MediaCodecVideoRecorder(
            outputDirectory = recordingsDirectory,
            sessionLog = sessionLog
        )
    }

    private val detectionAnnotationSink: DetectionAnnotationSink by lazy {
        check(videoRecorder is DetectionAnnotationSink) {
            "Configured video recorder does not support detection annotations"
        }
        videoRecorder as DetectionAnnotationSink
    }

    override val clipFileSharer: ClipFileSharer by lazy {
        AndroidClipFileSharer(appContext)
    }

    override val previewViewModelFactory: PreviewViewModelFactory by lazy {
        PreviewViewModelFactory(
            glassesSession = glassesSession,
            eyeUsbConfigurator = eyeUsbConfigurator,
            usbPermissionGateway = usbPermissionGateway,
            cameraSource = cameraSource,
            androidCameraSource = androidCameraSource,
            cameraSourcePreferences = cameraSourcePreferences,
            recordingPreferences = recordingPreferences,
            detectionPreferences = detectionPreferences,
            faceRecognitionPreferences = faceRecognitionPreferences,
            faceEmbeddingStore = faceEmbeddingStore,
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
        )
    }
}
