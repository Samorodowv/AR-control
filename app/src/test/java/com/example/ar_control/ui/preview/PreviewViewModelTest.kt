package com.example.ar_control.ui.preview

import android.graphics.SurfaceTexture
import android.view.Surface
import com.example.ar_control.ArControlApp
import com.example.ar_control.camera.CameraSource
import com.example.ar_control.camera.CameraSourceKind
import com.example.ar_control.camera.CameraSourcePreferences
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.detection.DetectionPreferences
import com.example.ar_control.detection.DetectedObject
import com.example.ar_control.detection.DetectionBoundingBox
import com.example.ar_control.detection.DetectionSessionStats
import com.example.ar_control.detection.ObjectDetectionSession
import com.example.ar_control.detection.ObjectDetector
import com.example.ar_control.diagnostics.InMemorySessionLog
import com.example.ar_control.gemma.GemmaCaptionSession
import com.example.ar_control.gemma.GemmaFrameCaptioner
import com.example.ar_control.gemma.GemmaModelDownloadProgress
import com.example.ar_control.gemma.GemmaModelDownloadScheduler
import com.example.ar_control.gemma.GemmaModelDownloadWorkState
import com.example.ar_control.gemma.GemmaSubtitlePreferences
import com.example.ar_control.gemma.DEFAULT_GEMMA_CAPTION_PROMPT
import com.example.ar_control.recording.ClipRepository
import com.example.ar_control.recording.DetectionAnnotationSink
import com.example.ar_control.recording.RecordingInputTarget
import com.example.ar_control.recording.NoOpDetectionAnnotationSink
import com.example.ar_control.recording.RecordedClip
import com.example.ar_control.recording.RecordingPreferences
import com.example.ar_control.recording.RecordingStatus
import com.example.ar_control.recording.VideoFrameConsumer
import com.example.ar_control.recording.VideoFramePixelFormat
import com.example.ar_control.recording.VideoRecorder
import com.example.ar_control.recovery.RecoveryManager
import com.example.ar_control.recovery.RecoverySnapshot
import java.io.Closeable
import com.example.ar_control.usb.EyeUsbConfigurator
import com.example.ar_control.usb.UsbPermissionGateway
import com.example.ar_control.xreal.GlassesSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@Config(application = ArControlApp::class)
@RunWith(RobolectricTestRunner::class)
class PreviewViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var cleanupScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        cleanupScope = CoroutineScope(dispatcher + Job())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun enableCameraSuccess_updatesUiState() = runTest {
        val usbPermissionGateway = FakeUsbPermissionGateway(true)
        val eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled)
        val viewModel = PreviewViewModel(
            glassesSession = FakeGlassesSession(),
            eyeUsbConfigurator = eyeUsbConfigurator,
            usbPermissionGateway = usbPermissionGateway,
            cameraSource = FakeCameraSource(),
            recordingPreferences = FakeRecordingPreferences(),
            clipRepository = FakeClipRepository(),
            videoRecorder = FakeVideoRecorder(),
            recoveryManager = FakeRecoveryManager(),
            sessionLog = InMemorySessionLog(),
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, usbPermissionGateway.ensureControlPermissionCalls)
        assertEquals(1, eyeUsbConfigurator.enableCameraCalls)
        assertEquals("Камера включена", viewModel.uiState.value.cameraStatus)
        assertFalse(viewModel.uiState.value.canEnableCamera)
        assertTrue(viewModel.uiState.value.canStartPreview)
        assertFalse(viewModel.uiState.value.canStopPreview)
    }

    @Test
    fun enableCameraPermissionDenied_setsErrorWithoutCallingConfigurator() = runTest {
        val usbPermissionGateway = FakeUsbPermissionGateway(false)
        val eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled)
        val viewModel = PreviewViewModel(
            glassesSession = FakeGlassesSession(),
            eyeUsbConfigurator = eyeUsbConfigurator,
            usbPermissionGateway = usbPermissionGateway,
            cameraSource = FakeCameraSource(),
            recordingPreferences = FakeRecordingPreferences(),
            clipRepository = FakeClipRepository(),
            videoRecorder = FakeVideoRecorder(),
            recoveryManager = FakeRecoveryManager(),
            sessionLog = InMemorySessionLog(),
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("USB-разрешение отклонено", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isPreviewRunning)
        assertTrue(viewModel.uiState.value.canEnableCamera)
        assertFalse(viewModel.uiState.value.canStartPreview)
        assertEquals(1, usbPermissionGateway.ensureControlPermissionCalls)
        assertEquals(0, eyeUsbConfigurator.enableCameraCalls)
    }

    @Test
    fun enableCameraFailure_restoresEnableAndClearsPreviewActions() = runTest {
        val eyeUsbConfigurator = FakeEyeUsbConfigurator(
            EyeUsbConfigurator.Result.Failed("enable_failed")
        )
        val viewModel = PreviewViewModel(
            glassesSession = FakeGlassesSession(),
            eyeUsbConfigurator = eyeUsbConfigurator,
            usbPermissionGateway = FakeUsbPermissionGateway(true),
            cameraSource = FakeCameraSource(),
            recordingPreferences = FakeRecordingPreferences(),
            clipRepository = FakeClipRepository(),
            videoRecorder = FakeVideoRecorder(),
            recoveryManager = FakeRecoveryManager(),
            sessionLog = InMemorySessionLog(),
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("enable_failed", viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.canEnableCamera)
        assertFalse(viewModel.uiState.value.canStartPreview)
        assertFalse(viewModel.uiState.value.canStopPreview)
        assertFalse(viewModel.uiState.value.isPreviewRunning)
        assertEquals(1, eyeUsbConfigurator.enableCameraCalls)
    }

    @Test
    fun init_loadsRecordingPreferenceAndRecordedClips() = runTest {
        val olderClip = createClip(id = "older", createdAtEpochMillis = 1_000L)
        val newerClip = createClip(id = "newer", createdAtEpochMillis = 2_000L)
        val clipRepository = FakeClipRepository(mutableListOf(olderClip, newerClip))
        val viewModel = buildViewModel(
            recordingPreferences = FakeRecordingPreferences(enabled = true),
            detectionPreferences = FakeDetectionPreferences(enabled = true),
            clipRepository = clipRepository,
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.recordVideoEnabled)
        assertTrue(viewModel.uiState.value.objectDetectionEnabled)
        assertEquals(listOf("older", "newer"), viewModel.uiState.value.recordedClips.map { it.id })
        assertEquals(1, clipRepository.loadCalls)
        assertEquals(RecordingStatus.Idle, viewModel.uiState.value.recordingStatus)
        assertFalse(viewModel.uiState.value.canOpenSelectedClip)
        assertFalse(viewModel.uiState.value.canShareSelectedClip)
        assertFalse(viewModel.uiState.value.canDeleteSelectedClip)
    }

    @Test
    fun init_loadsGemmaSubtitlePreferenceAndModelName() = runTest {
        val gemmaSubtitlePreferences = FakeGemmaSubtitlePreferences(
            enabled = true,
            modelPath = "/models/gemma.litertlm",
            modelDisplayName = "Gemma subtitles"
        )
        val viewModel = buildViewModel(
            gemmaSubtitlePreferences = gemmaSubtitlePreferences,
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.gemmaSubtitlesEnabled)
        assertEquals("Gemma subtitles", viewModel.uiState.value.gemmaModelDisplayName)
        assertEquals("", viewModel.uiState.value.gemmaSubtitleText)
        assertEquals(DEFAULT_GEMMA_CAPTION_PROMPT, viewModel.uiState.value.gemmaPrompt)
    }

    @Test
    fun init_withAndroidCameraSourceSelected_startsInAndroidCameraReadyState() = runTest {
        val cameraSourcePreferences = FakeCameraSourcePreferences(CameraSourceKind.ANDROID)
        val viewModel = buildViewModel(
            cameraSourcePreferences = cameraSourcePreferences,
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(CameraSourceKind.ANDROID, viewModel.uiState.value.selectedCameraSource)
        assertEquals("Камера телефона готова", viewModel.uiState.value.cameraStatus)
        assertFalse(viewModel.uiState.value.canEnableCamera)
        assertTrue(viewModel.uiState.value.canStartPreview)
    }

    @Test
    fun setRecordVideoEnabled_updatesUiStateAndPreferenceStore() = runTest {
        val recordingPreferences = FakeRecordingPreferences(enabled = false)
        val viewModel = buildViewModel(
            recordingPreferences = recordingPreferences,
            cleanupScope = cleanupScope
        )

        viewModel.setRecordVideoEnabled(true)

        assertTrue(viewModel.uiState.value.recordVideoEnabled)
        assertTrue(recordingPreferences.isRecordingEnabled())
        assertEquals(1, recordingPreferences.setCalls)
    }

    @Test
    fun setObjectDetectionEnabled_updatesUiStateAndPreferenceStore() = runTest {
        val detectionPreferences = FakeDetectionPreferences(enabled = false)
        val viewModel = buildViewModel(
            detectionPreferences = detectionPreferences,
            cleanupScope = cleanupScope
        )

        viewModel.setObjectDetectionEnabled(true)

        assertTrue(viewModel.uiState.value.objectDetectionEnabled)
        assertTrue(detectionPreferences.isObjectDetectionEnabled())
        assertEquals(1, detectionPreferences.setCalls)
    }

    @Test
    fun setTransparentHudEnabled_updatesUiState() = runTest {
        val viewModel = buildViewModel(cleanupScope = cleanupScope)

        viewModel.setTransparentHudEnabled(true)

        assertTrue(viewModel.uiState.value.transparentHudEnabled)
    }

    @Test
    fun setGemmaSubtitlesEnabled_updatesUiStateAndPreferenceStore() = runTest {
        val gemmaSubtitlePreferences = FakeGemmaSubtitlePreferences(enabled = false)
        val viewModel = buildViewModel(
            gemmaSubtitlePreferences = gemmaSubtitlePreferences,
            cleanupScope = cleanupScope
        )

        viewModel.setGemmaSubtitlesEnabled(true)

        assertTrue(viewModel.uiState.value.gemmaSubtitlesEnabled)
        assertTrue(gemmaSubtitlePreferences.isGemmaSubtitlesEnabled())
        assertEquals(1, gemmaSubtitlePreferences.setEnabledCalls)
    }

    @Test
    fun setGemmaPrompt_updatesUiStateAndPreferenceStore() = runTest {
        val gemmaSubtitlePreferences = FakeGemmaSubtitlePreferences()
        val viewModel = buildViewModel(
            gemmaSubtitlePreferences = gemmaSubtitlePreferences,
            cleanupScope = cleanupScope
        )

        viewModel.setGemmaPrompt("Опиши людей и предметы.")

        assertEquals("Опиши людей и предметы.", viewModel.uiState.value.gemmaPrompt)
        assertEquals("Опиши людей и предметы.", gemmaSubtitlePreferences.getCaptionPrompt())
        assertEquals(1, gemmaSubtitlePreferences.setCaptionPromptCalls)
    }

    @Test
    fun setCameraSourceToAndroid_persistsSelectionAndSkipsXrealEnable() = runTest {
        val cameraSourcePreferences = FakeCameraSourcePreferences(CameraSourceKind.XREAL)
        val usbPermissionGateway = FakeUsbPermissionGateway(true)
        val eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled)
        val viewModel = buildViewModel(
            usbPermissionGateway = usbPermissionGateway,
            eyeUsbConfigurator = eyeUsbConfigurator,
            cameraSourcePreferences = cameraSourcePreferences,
            cleanupScope = cleanupScope
        )

        viewModel.setCameraSource(CameraSourceKind.ANDROID)
        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(CameraSourceKind.ANDROID, viewModel.uiState.value.selectedCameraSource)
        assertEquals(CameraSourceKind.ANDROID, cameraSourcePreferences.selected)
        assertEquals("Камера телефона готова", viewModel.uiState.value.cameraStatus)
        assertFalse(viewModel.uiState.value.canEnableCamera)
        assertTrue(viewModel.uiState.value.canStartPreview)
        assertEquals(0, usbPermissionGateway.ensureControlPermissionCalls)
        assertEquals(0, eyeUsbConfigurator.enableCameraCalls)
    }

    @Test
    fun startPreviewWithAndroidCameraSelected_usesAndroidCameraSource() = runTest {
        val xrealCameraSource = FakeCameraSource()
        val androidCameraSource = FakeCameraSource(
            startResult = CameraSource.StartResult.Started(PreviewSize(width = 1280, height = 720))
        )
        val viewModel = buildViewModel(
            cameraSource = xrealCameraSource,
            androidCameraSource = androidCameraSource,
            cameraSourcePreferences = FakeCameraSourcePreferences(CameraSourceKind.ANDROID),
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()
        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, xrealCameraSource.lastStartSurfaceToken)
        assertEquals(FakeCameraSurfaceToken, androidCameraSource.lastStartSurfaceToken)
        assertTrue(viewModel.uiState.value.isPreviewRunning)
        assertEquals(PreviewSize(width = 1280, height = 720), viewModel.uiState.value.previewSize)
    }

    @Test
    fun downloadGemmaModel_enqueuesSchedulerAndShowsImmediateProgress() = runTest {
        val scheduler = FakeGemmaModelDownloadScheduler()
        val viewModel = buildViewModel(
            gemmaModelDownloadScheduler = scheduler,
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()
        viewModel.downloadGemmaModel()

        assertEquals(1, scheduler.enqueueCalls)
        assertTrue(viewModel.uiState.value.isGemmaModelDownloadInProgress)
        assertEquals("Модель Gemma: загрузка...", viewModel.uiState.value.gemmaModelDownloadProgressText)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun observeGemmaModelDownloadProgress_updatesProgressText() = runTest {
        val scheduler = FakeGemmaModelDownloadScheduler()
        val viewModel = buildViewModel(
            gemmaModelDownloadScheduler = scheduler,
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()
        scheduler.publish(
            GemmaModelDownloadWorkState.Running(
                GemmaModelDownloadProgress(bytesDownloaded = 50L, totalBytes = 100L)
            )
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isGemmaModelDownloadInProgress)
        assertEquals("Модель Gemma: загрузка 50%", viewModel.uiState.value.gemmaModelDownloadProgressText)
    }

    @Test
    fun observeGemmaModelDownloadSuccess_setsModelNameAndClearsProgress() = runTest {
        val scheduler = FakeGemmaModelDownloadScheduler()
        val viewModel = buildViewModel(
            gemmaModelDownloadScheduler = scheduler,
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()
        scheduler.publish(
            GemmaModelDownloadWorkState.Completed(displayName = "gemma-4-E2B-it.litertlm")
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isGemmaModelDownloadInProgress)
        assertEquals("gemma-4-E2B-it.litertlm", viewModel.uiState.value.gemmaModelDisplayName)
        assertEquals(null, viewModel.uiState.value.gemmaModelDownloadProgressText)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun downloadGemmaModel_whenAlreadyInProgress_cancelsDownload() = runTest {
        val scheduler = FakeGemmaModelDownloadScheduler()
        val viewModel = buildViewModel(
            gemmaModelDownloadScheduler = scheduler,
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()
        viewModel.downloadGemmaModel()
        viewModel.downloadGemmaModel()

        assertEquals(1, scheduler.enqueueCalls)
        assertEquals(1, scheduler.cancelCalls)
        assertFalse(viewModel.uiState.value.isGemmaModelDownloadInProgress)
        assertEquals(null, viewModel.uiState.value.gemmaModelDownloadProgressText)
    }

    @Test
    fun downloadGemmaModelFailure_setsErrorAndLeavesModelNameEmpty() = runTest {
        val scheduler = FakeGemmaModelDownloadScheduler()
        val viewModel = buildViewModel(
            gemmaModelDownloadScheduler = scheduler,
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()
        scheduler.publish(
            GemmaModelDownloadWorkState.Failed("Could not download Gemma model")
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isGemmaModelDownloadInProgress)
        assertEquals("Could not download Gemma model", viewModel.uiState.value.errorMessage)
        assertEquals(null, viewModel.uiState.value.gemmaModelDisplayName)
    }

    @Test
    fun init_whenSafeModeActive_disablesCameraAndMasksRecordingPreference() = runTest {
        val recoveryManager = FakeRecoveryManager(
            snapshot = RecoverySnapshot(
                isSafeMode = true,
                safeModeReason = "Recovered after abnormal termination during recording"
            )
        )
        val viewModel = buildViewModel(
            recordingPreferences = FakeRecordingPreferences(enabled = true),
            detectionPreferences = FakeDetectionPreferences(enabled = true),
            gemmaSubtitlePreferences = FakeGemmaSubtitlePreferences(
                enabled = true,
                modelPath = "/models/gemma.litertlm",
                modelDisplayName = "Gemma subtitles"
            ),
            recoveryManager = recoveryManager,
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSafeMode)
        assertFalse(viewModel.uiState.value.canEnableCamera)
        assertFalse(viewModel.uiState.value.canStartPreview)
        assertFalse(viewModel.uiState.value.recordVideoEnabled)
        assertFalse(viewModel.uiState.value.objectDetectionEnabled)
        assertFalse(viewModel.uiState.value.gemmaSubtitlesEnabled)
        assertEquals("", viewModel.uiState.value.gemmaSubtitleText)
        assertFalse(viewModel.uiState.value.canChangeRecordVideo)
        assertFalse(viewModel.uiState.value.canChangeObjectDetection)
        assertFalse(viewModel.uiState.value.canChangeGemmaSubtitles)
        assertEquals(
            "Recovered after abnormal termination during recording",
            viewModel.uiState.value.safeModeReason
        )
    }

    @Test
    fun confirmSafeModeExit_restoresIdleControlsAndRecordingPreference() = runTest {
        val recoveryManager = FakeRecoveryManager(
            snapshot = RecoverySnapshot(
                isSafeMode = true,
                safeModeReason = "Recovered after abnormal termination during preview"
            )
        )
        val recordingPreferences = FakeRecordingPreferences(enabled = true)
        val detectionPreferences = FakeDetectionPreferences(enabled = true)
        val gemmaSubtitlePreferences = FakeGemmaSubtitlePreferences(
            enabled = true,
            modelPath = "/models/gemma.litertlm",
            modelDisplayName = "Gemma subtitles"
        )
        val viewModel = buildViewModel(
            recordingPreferences = recordingPreferences,
            detectionPreferences = detectionPreferences,
            gemmaSubtitlePreferences = gemmaSubtitlePreferences,
            recoveryManager = recoveryManager,
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()
        viewModel.confirmSafeModeExit()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSafeMode)
        assertTrue(viewModel.uiState.value.canEnableCamera)
        assertFalse(viewModel.uiState.value.canStartPreview)
        assertTrue(viewModel.uiState.value.recordVideoEnabled)
        assertTrue(viewModel.uiState.value.objectDetectionEnabled)
        assertTrue(viewModel.uiState.value.gemmaSubtitlesEnabled)
        assertEquals("Gemma subtitles", viewModel.uiState.value.gemmaModelDisplayName)
        assertTrue(viewModel.uiState.value.canChangeRecordVideo)
        assertTrue(viewModel.uiState.value.canChangeObjectDetection)
        assertTrue(viewModel.uiState.value.canChangeGemmaSubtitles)
        assertEquals(1, recoveryManager.clearSafeModeCalls)
    }

    @Test
    fun startPreviewSuccess_updatesUiStateToRunningWithoutControlPermissionRequest() = runTest {
        val cameraSource = FakeCameraSource(
            startResult = CameraSource.StartResult.Started(
                com.example.ar_control.camera.PreviewSize(width = 1920, height = 1080)
            )
        )
        val usbPermissionGateway = FakeUsbPermissionGateway(true)
        val viewModel = PreviewViewModel(
            glassesSession = FakeGlassesSession(),
            eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled),
            usbPermissionGateway = usbPermissionGateway,
            cameraSource = cameraSource,
            recordingPreferences = FakeRecordingPreferences(),
            clipRepository = FakeClipRepository(),
            videoRecorder = FakeVideoRecorder(),
            recoveryManager = FakeRecoveryManager(),
            sessionLog = InMemorySessionLog(),
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, usbPermissionGateway.ensureControlPermissionCalls)
        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, usbPermissionGateway.ensureControlPermissionCalls)
        assertTrue(viewModel.uiState.value.isPreviewRunning)
        assertFalse(viewModel.uiState.value.canEnableCamera)
        assertTrue(viewModel.uiState.value.canStopPreview)
        assertFalse(viewModel.uiState.value.canStartPreview)
        assertEquals("Просмотр запущен", viewModel.uiState.value.cameraStatus)
        assertEquals(1920, viewModel.uiState.value.previewSize?.width)
        assertEquals(1080, viewModel.uiState.value.previewSize?.height)
        assertEquals(FakeCameraSurfaceToken, cameraSource.lastStartSurfaceToken)
    }

    @Test
    fun startPreview_withRecordingEnabled_startsRecorderAfterPreview() = runTest {
        val captureSurface = TestSurface.create()
        captureSurface.use { testSurface ->
            val cameraSource = FakeCameraSource(
                startResult = CameraSource.StartResult.Started(PreviewSize(width = 1920, height = 1080)),
                recordingStartResult = CameraSource.RecordingStartResult.Started
            )
            val videoRecorder = FakeVideoRecorder(
                startResult = VideoRecorder.StartResult.Started(
                    inputTarget = RecordingInputTarget.SurfaceTarget(testSurface.surface),
                    outputFilePath = "/clips/clip-1.mp4",
                    startedAtEpochMillis = 4_000L
                ),
                stopResult = VideoRecorder.StopResult.Finished(
                    createClip(id = "clip-1", createdAtEpochMillis = 4_000L)
                )
            )
            val viewModel = buildViewModel(
                cameraSource = cameraSource,
                recordingPreferences = FakeRecordingPreferences(enabled = true),
                videoRecorder = videoRecorder,
                cleanupScope = cleanupScope
            )

            viewModel.enableCamera()
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.startPreview(FakeCameraSurfaceToken)
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isPreviewRunning)
            assertEquals(RecordingStatus.Recording, viewModel.uiState.value.recordingStatus)
            assertEquals(1, videoRecorder.startCalls)
            assertEquals(PreviewSize(width = 1920, height = 1080), videoRecorder.lastStartPreviewSize)
            assertEquals(1, cameraSource.startRecordingCalls)
            assertEquals(testSurface.surface, cameraSource.lastCaptureSurface)
        }
    }

    @Test
    fun startPreview_withObjectDetectionEnabled_startsSharedFrameCallbackStream() = runTest {
        val cameraSource = FakeCameraSource(
            startResult = CameraSource.StartResult.Started(PreviewSize(width = 1920, height = 1080)),
            recordingStartResult = CameraSource.RecordingStartResult.Started
        )
        val objectDetector = FakeObjectDetector()
        val viewModel = buildViewModel(
            cameraSource = cameraSource,
            detectionPreferences = FakeDetectionPreferences(enabled = true),
            objectDetector = objectDetector,
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isPreviewRunning)
        assertEquals(1, objectDetector.startCalls)
        assertEquals(1, cameraSource.startRecordingCalls)
        assertTrue(cameraSource.lastRecordingTarget is RecordingInputTarget.FrameCallbackTarget)
    }

    @Test
    fun startPreviewWithGemmaEnabledButNoModel_keepsPreviewRunningAndShowsError() = runTest {
        val cameraSource = FakeCameraSource(
            startResult = CameraSource.StartResult.Started(PreviewSize(width = 1920, height = 1080)),
            recordingStartResult = CameraSource.RecordingStartResult.Started
        )
        val gemmaFrameCaptioner = FakeGemmaFrameCaptioner()
        val viewModel = buildViewModel(
            cameraSource = cameraSource,
            gemmaSubtitlePreferences = FakeGemmaSubtitlePreferences(enabled = true),
            gemmaFrameCaptioner = gemmaFrameCaptioner,
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isPreviewRunning)
        assertEquals("Модель Gemma не настроена", viewModel.uiState.value.errorMessage)
        assertEquals("", viewModel.uiState.value.gemmaSubtitleText)
        assertEquals(0, gemmaFrameCaptioner.startCalls)
        assertEquals(0, cameraSource.startRecordingCalls)
    }

    @Test
    fun startPreviewWithGemmaEnabled_startsCaptionSessionAndUpdatesSubtitle() = runTest {
        val cameraSource = FakeCameraSource(
            startResult = CameraSource.StartResult.Started(PreviewSize(width = 1920, height = 1080)),
            recordingStartResult = CameraSource.RecordingStartResult.Started
        )
        val gemmaFrameCaptioner = FakeGemmaFrameCaptioner()
        val viewModel = buildViewModel(
            cameraSource = cameraSource,
            gemmaSubtitlePreferences = FakeGemmaSubtitlePreferences(
                enabled = true,
                modelPath = "/models/gemma.litertlm",
                modelDisplayName = "Gemma subtitles"
            ),
            gemmaFrameCaptioner = gemmaFrameCaptioner,
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isPreviewRunning)
        assertEquals(1, gemmaFrameCaptioner.startCalls)
        assertEquals("/models/gemma.litertlm", gemmaFrameCaptioner.lastModelPath)
        assertEquals(PreviewSize(width = 1920, height = 1080), gemmaFrameCaptioner.lastPreviewSize)
        assertEquals(1, cameraSource.startRecordingCalls)
        assertTrue(cameraSource.lastRecordingTarget is RecordingInputTarget.FrameCallbackTarget)

        gemmaFrameCaptioner.lastSession?.publish("a red mug on a desk")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("a red mug on a desk", viewModel.uiState.value.gemmaSubtitleText)
    }

    @Test
    fun gemmaCaptionFailure_showsUnavailableSubtitleOverRunningPreview() = runTest {
        val cameraSource = FakeCameraSource(
            startResult = CameraSource.StartResult.Started(PreviewSize(width = 1920, height = 1080)),
            recordingStartResult = CameraSource.RecordingStartResult.Started
        )
        val gemmaFrameCaptioner = FakeGemmaFrameCaptioner()
        val viewModel = buildViewModel(
            cameraSource = cameraSource,
            gemmaSubtitlePreferences = FakeGemmaSubtitlePreferences(
                enabled = true,
                modelPath = "/models/gemma.litertlm",
                modelDisplayName = "Gemma subtitles"
            ),
            gemmaFrameCaptioner = gemmaFrameCaptioner,
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()
        gemmaFrameCaptioner.lastSession?.fail(
            "Failed to create engine: INTERNAL: native compiled model error"
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isPreviewRunning)
        assertTrue(gemmaFrameCaptioner.lastSession?.closed == true)
        assertEquals(
            "Gemma недоступна: не удалось создать движок",
            viewModel.uiState.value.gemmaSubtitleText
        )
        assertEquals(
            "Failed to create engine: INTERNAL: native compiled model error",
            viewModel.uiState.value.errorMessage
        )
    }

    @Test
    fun stopPreview_closesGemmaSessionAndClearsSubtitle() = runTest {
        val cameraSource = FakeCameraSource(
            recordingStartResult = CameraSource.RecordingStartResult.Started
        )
        val gemmaFrameCaptioner = FakeGemmaFrameCaptioner()
        val viewModel = buildViewModel(
            cameraSource = cameraSource,
            gemmaSubtitlePreferences = FakeGemmaSubtitlePreferences(
                enabled = true,
                modelPath = "/models/gemma.litertlm"
            ),
            gemmaFrameCaptioner = gemmaFrameCaptioner,
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()
        gemmaFrameCaptioner.lastSession?.publish("a blue box")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.stopPreview()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(gemmaFrameCaptioner.lastSession?.closed == true)
        assertEquals("", viewModel.uiState.value.gemmaSubtitleText)
        assertFalse(viewModel.uiState.value.isPreviewRunning)
    }

    @Test
    fun staleGemmaCaptionFromPreviousPreview_doesNotOverwriteCurrentSubtitle() = runTest {
        val cameraSource = FakeCameraSource(
            recordingStartResult = CameraSource.RecordingStartResult.Started
        )
        val gemmaFrameCaptioner = FakeGemmaFrameCaptioner()
        val viewModel = buildViewModel(
            cameraSource = cameraSource,
            gemmaSubtitlePreferences = FakeGemmaSubtitlePreferences(
                enabled = true,
                modelPath = "/models/gemma.litertlm"
            ),
            gemmaFrameCaptioner = gemmaFrameCaptioner,
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()
        val staleSession = gemmaFrameCaptioner.sessions.single()
        viewModel.stopPreview()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()
        val activeSession = gemmaFrameCaptioner.sessions.last()
        activeSession.publish("current caption")
        dispatcher.scheduler.advanceUntilIdle()

        staleSession.publish("stale caption")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("current caption", viewModel.uiState.value.gemmaSubtitleText)
        assertFalse(activeSession.closed)
    }

    @Test
    fun staleGemmaErrorFromPreviousPreview_doesNotCloseCurrentSession() = runTest {
        val cameraSource = FakeCameraSource(
            recordingStartResult = CameraSource.RecordingStartResult.Started
        )
        val gemmaFrameCaptioner = FakeGemmaFrameCaptioner()
        val viewModel = buildViewModel(
            cameraSource = cameraSource,
            gemmaSubtitlePreferences = FakeGemmaSubtitlePreferences(
                enabled = true,
                modelPath = "/models/gemma.litertlm"
            ),
            gemmaFrameCaptioner = gemmaFrameCaptioner,
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()
        val staleSession = gemmaFrameCaptioner.sessions.single()
        viewModel.stopPreview()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()
        val activeSession = gemmaFrameCaptioner.sessions.last()
        activeSession.publish("current caption")
        dispatcher.scheduler.advanceUntilIdle()

        staleSession.fail("stale failure")
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(activeSession.closed)
        assertEquals("current caption", viewModel.uiState.value.gemmaSubtitleText)
        assertEquals("Просмотр запущен", viewModel.uiState.value.cameraStatus)
    }

    @Test
    fun detectionResults_updateUiStateAndAreClearedWhenPreviewStops() = runTest {
        val cameraSource = FakeCameraSource(
            recordingStartResult = CameraSource.RecordingStartResult.Started
        )
        val objectDetector = FakeObjectDetector()
        val detectionAnnotationSink = FakeDetectionAnnotationSink()
        val viewModel = buildViewModel(
            cameraSource = cameraSource,
            detectionPreferences = FakeDetectionPreferences(enabled = true),
            objectDetector = objectDetector,
            detectionAnnotationSink = detectionAnnotationSink,
            cleanupScope = cleanupScope
        )
        val expectedDetection = DetectedObject(
            labelIndex = 1,
            label = "car",
            confidence = 0.82f,
            boundingBox = DetectionBoundingBox(10f, 20f, 30f, 40f)
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()

        objectDetector.lastSession?.publish(listOf(expectedDetection))
        objectDetector.lastSession?.publishStats(
            DetectionSessionStats(
                backendLabel = "AUTO GPU->CPU",
                inferenceFps = 6.5f
            )
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(expectedDetection), viewModel.uiState.value.detectedObjects)
        assertEquals("AUTO GPU->CPU", viewModel.uiState.value.inferenceBackendLabel)
        assertEquals(6.5f, viewModel.uiState.value.inferenceFps, 0.0001f)
        assertEquals(1, detectionAnnotationSink.updates.size)
        assertEquals(listOf(expectedDetection), detectionAnnotationSink.updates.single().detections)

        viewModel.stopPreview()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(objectDetector.lastSession?.closed == true)
        assertTrue(viewModel.uiState.value.detectedObjects.isEmpty())
        assertEquals(null, viewModel.uiState.value.inferenceBackendLabel)
        assertEquals(0f, viewModel.uiState.value.inferenceFps, 0.0001f)
        assertEquals(1, detectionAnnotationSink.clearCalls)
    }

    @Test
    fun stopPreview_whileRecordingStillStarting_doesNotAllowStaleStartToResumeRecording() = runTest {
        val captureSurface = TestSurface.create()
        captureSurface.use { testSurface ->
            val startGate = CompletableDeferred<VideoRecorder.StartResult>()
            val cameraSource = FakeCameraSource(
                recordingStartResult = CameraSource.RecordingStartResult.Started
            )
            val videoRecorder = DeferredStartVideoRecorder(
                startGate = startGate,
                cancelResult = VideoRecorder.CancelResult.Cancelled
            )
            val viewModel = buildViewModel(
                cameraSource = cameraSource,
                recordingPreferences = FakeRecordingPreferences(enabled = true),
                videoRecorder = videoRecorder,
                cleanupScope = cleanupScope
            )

            viewModel.enableCamera()
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.startPreview(FakeCameraSurfaceToken)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(RecordingStatus.Starting, viewModel.uiState.value.recordingStatus)

            viewModel.stopPreview()
            dispatcher.scheduler.advanceUntilIdle()

            startGate.complete(
                VideoRecorder.StartResult.Started(
                    inputTarget = RecordingInputTarget.SurfaceTarget(testSurface.surface),
                    outputFilePath = "/clips/stale.mp4",
                    startedAtEpochMillis = 6_000L
                )
            )
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("Камера включена", viewModel.uiState.value.cameraStatus)
            assertEquals(RecordingStatus.Idle, viewModel.uiState.value.recordingStatus)
            assertFalse(viewModel.uiState.value.isPreviewRunning)
            assertTrue(videoRecorder.cancelCalls >= 1)
            assertEquals(0, cameraSource.startRecordingCalls)
        }
    }

    @Test
    fun startPreviewFailure_keepsPreviewStoppedAndAllowsRetry() = runTest {
        val cameraSource = FakeCameraSource(
            startResult = CameraSource.StartResult.Failed("missing_surface")
        )
        val viewModel = PreviewViewModel(
            glassesSession = FakeGlassesSession(),
            eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled),
            usbPermissionGateway = FakeUsbPermissionGateway(true),
            cameraSource = cameraSource,
            recordingPreferences = FakeRecordingPreferences(),
            clipRepository = FakeClipRepository(),
            videoRecorder = FakeVideoRecorder(),
            recoveryManager = FakeRecoveryManager(),
            sessionLog = InMemorySessionLog(),
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("missing_surface", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isPreviewRunning)
        assertFalse(viewModel.uiState.value.canEnableCamera)
        assertTrue(viewModel.uiState.value.canStartPreview)
        assertFalse(viewModel.uiState.value.canStopPreview)
    }

    @Test
    fun stopPreview_whileRecording_finalizesClipAndRefreshesHistory() = runTest {
        val finishedClip = createClip(id = "clip-final", createdAtEpochMillis = 5_000L)
        val captureSurface = TestSurface.create()
        captureSurface.use { testSurface ->
            val clipRepository = FakeClipRepository()
            val cameraSource = FakeCameraSource(
                recordingStartResult = CameraSource.RecordingStartResult.Started
            )
            val videoRecorder = FakeVideoRecorder(
                startResult = VideoRecorder.StartResult.Started(
                    inputTarget = RecordingInputTarget.SurfaceTarget(testSurface.surface),
                    outputFilePath = finishedClip.filePath,
                    startedAtEpochMillis = finishedClip.createdAtEpochMillis
                ),
                stopResult = VideoRecorder.StopResult.Finished(finishedClip)
            )
            val viewModel = buildViewModel(
                cameraSource = cameraSource,
                recordingPreferences = FakeRecordingPreferences(enabled = true),
                clipRepository = clipRepository,
                videoRecorder = videoRecorder,
                cleanupScope = cleanupScope
            )

            viewModel.enableCamera()
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.startPreview(FakeCameraSurfaceToken)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.stopPreview()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, cameraSource.stopRecordingCalls)
            assertEquals(1, videoRecorder.stopCalls)
            assertEquals(1, clipRepository.insertCalls)
            assertEquals(listOf(finishedClip), viewModel.uiState.value.recordedClips)
            assertEquals(RecordingStatus.Idle, viewModel.uiState.value.recordingStatus)
            assertFalse(viewModel.uiState.value.isPreviewRunning)
            assertEquals("Камера включена", viewModel.uiState.value.cameraStatus)
        }
    }

    @Test
    fun stopPreview_whileFinalizing_ignoresSecondStopCall() = runTest {
        val finishedClip = createClip(id = "clip-final", createdAtEpochMillis = 7_000L)
        val captureSurface = TestSurface.create()
        captureSurface.use { testSurface ->
            val stopGate = CompletableDeferred<VideoRecorder.StopResult>()
            val clipRepository = FakeClipRepository()
            val cameraSource = FakeCameraSource(
                recordingStartResult = CameraSource.RecordingStartResult.Started
            )
            val videoRecorder = DeferredStopVideoRecorder(
                startResult = VideoRecorder.StartResult.Started(
                    inputTarget = RecordingInputTarget.SurfaceTarget(testSurface.surface),
                    outputFilePath = finishedClip.filePath,
                    startedAtEpochMillis = finishedClip.createdAtEpochMillis
                ),
                stopGate = stopGate,
                cancelResult = VideoRecorder.CancelResult.Cancelled
            )
            val viewModel = buildViewModel(
                cameraSource = cameraSource,
                recordingPreferences = FakeRecordingPreferences(enabled = true),
                clipRepository = clipRepository,
                videoRecorder = videoRecorder,
                cleanupScope = cleanupScope
            )

            viewModel.enableCamera()
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.startPreview(FakeCameraSurfaceToken)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.stopPreview()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(RecordingStatus.Finalizing, viewModel.uiState.value.recordingStatus)

            viewModel.stopPreview()
            dispatcher.scheduler.advanceUntilIdle()

            stopGate.complete(VideoRecorder.StopResult.Finished(finishedClip))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, videoRecorder.stopCalls)
            assertEquals(0, videoRecorder.cancelCalls)
            assertEquals(1, cameraSource.stopRecordingCalls)
            assertEquals(listOf(finishedClip), viewModel.uiState.value.recordedClips)
            assertEquals(RecordingStatus.Idle, viewModel.uiState.value.recordingStatus)
        }
    }

    @Test
    fun stopPreview_whenCameraRecordingStopFails_cancelsRecorderInsteadOfFinalizing() = runTest {
        val captureSurface = TestSurface.create()
        captureSurface.use { testSurface ->
            val clipRepository = FakeClipRepository()
            val cameraSource = FakeCameraSource(
                recordingStartResult = CameraSource.RecordingStartResult.Started,
                stopRecordingFailure = IllegalStateException("uvc_stop_failed")
            )
            val videoRecorder = FakeVideoRecorder(
                startResult = VideoRecorder.StartResult.Started(
                    inputTarget = RecordingInputTarget.SurfaceTarget(testSurface.surface),
                    outputFilePath = "/clips/clip-stop-fail.mp4",
                    startedAtEpochMillis = 8_000L
                ),
                stopResult = VideoRecorder.StopResult.Finished(
                    createClip(id = "clip-stop-fail", createdAtEpochMillis = 8_000L)
                ),
                cancelResult = VideoRecorder.CancelResult.Cancelled
            )
            val viewModel = buildViewModel(
                cameraSource = cameraSource,
                recordingPreferences = FakeRecordingPreferences(enabled = true),
                clipRepository = clipRepository,
                videoRecorder = videoRecorder,
                cleanupScope = cleanupScope
            )

            viewModel.enableCamera()
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.startPreview(FakeCameraSurfaceToken)
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.stopPreview()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, cameraSource.stopRecordingCalls)
            assertEquals(0, videoRecorder.stopCalls)
            assertEquals(1, videoRecorder.cancelCalls)
            assertEquals(0, clipRepository.insertCalls)
            assertEquals("uvc_stop_failed", viewModel.uiState.value.errorMessage)
            assertTrue(viewModel.uiState.value.recordingStatus is RecordingStatus.Failed)
            assertFalse(viewModel.uiState.value.isPreviewRunning)
            assertEquals("Камера включена", viewModel.uiState.value.cameraStatus)
        }
    }

    @Test
    fun stopPreview_resetsUiStateToCameraEnabled() = runTest {
        val viewModel = PreviewViewModel(
            glassesSession = FakeGlassesSession(),
            eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled),
            usbPermissionGateway = FakeUsbPermissionGateway(true),
            cameraSource = FakeCameraSource(),
            recordingPreferences = FakeRecordingPreferences(),
            clipRepository = FakeClipRepository(),
            videoRecorder = FakeVideoRecorder(),
            recoveryManager = FakeRecoveryManager(),
            sessionLog = InMemorySessionLog(),
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.stopPreview()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isPreviewRunning)
        assertFalse(viewModel.uiState.value.canEnableCamera)
        assertFalse(viewModel.uiState.value.canStopPreview)
        assertTrue(viewModel.uiState.value.canStartPreview)
        assertEquals("Камера включена", viewModel.uiState.value.cameraStatus)
    }

    @Test
    fun selectClip_clearSelection_andDeleteSelectedClip_updateClipActions() = runTest {
        val clipOne = createClip(id = "clip-1", createdAtEpochMillis = 1_000L)
        val clipTwo = createClip(id = "clip-2", createdAtEpochMillis = 2_000L)
        val clipRepository = FakeClipRepository(mutableListOf(clipOne, clipTwo))
        val viewModel = buildViewModel(
            clipRepository = clipRepository,
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectClip(clipTwo.id)

        assertEquals(clipTwo.id, viewModel.uiState.value.selectedClipId)
        assertEquals(clipTwo, viewModel.uiState.value.selectedClip)
        assertTrue(viewModel.uiState.value.canOpenSelectedClip)
        assertTrue(viewModel.uiState.value.canShareSelectedClip)
        assertTrue(viewModel.uiState.value.canDeleteSelectedClip)

        viewModel.clearSelectedClip()

        assertEquals(null, viewModel.uiState.value.selectedClipId)
        assertFalse(viewModel.uiState.value.canOpenSelectedClip)

        viewModel.selectClip(clipOne.id)
        viewModel.deleteSelectedClip()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(clipTwo), viewModel.uiState.value.recordedClips)
        assertEquals(1, clipRepository.deleteCalls)
        assertEquals(null, viewModel.uiState.value.selectedClipId)
        assertFalse(viewModel.uiState.value.canDeleteSelectedClip)
    }

    @Test
    fun deleteSelectedClip_keepsDeletedClipOutOfUiWhenReloadFails() = runTest {
        val clipOne = createClip(id = "clip-1", createdAtEpochMillis = 1_000L)
        val clipTwo = createClip(id = "clip-2", createdAtEpochMillis = 2_000L)
        val clipRepository = FakeClipRepository(
            clips = mutableListOf(clipOne, clipTwo),
            failLoadsAfterDelete = true
        )
        val viewModel = buildViewModel(
            clipRepository = clipRepository,
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectClip(clipOne.id)
        viewModel.deleteSelectedClip()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(clipTwo), viewModel.uiState.value.recordedClips)
        assertEquals(null, viewModel.uiState.value.selectedClipId)
        assertFalse(viewModel.uiState.value.canOpenSelectedClip)
        assertFalse(viewModel.uiState.value.canShareSelectedClip)
        assertFalse(viewModel.uiState.value.canDeleteSelectedClip)
        assertEquals("Failed to load recorded clips", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun zoomPreview_clampsWithinRangeWhileRunning() = runTest {
        val sessionLog = InMemorySessionLog()
        val viewModel = PreviewViewModel(
            glassesSession = FakeGlassesSession(),
            eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled),
            usbPermissionGateway = FakeUsbPermissionGateway(true),
            cameraSource = FakeCameraSource(),
            recordingPreferences = FakeRecordingPreferences(),
            clipRepository = FakeClipRepository(),
            videoRecorder = FakeVideoRecorder(),
            recoveryManager = FakeRecoveryManager(),
            sessionLog = sessionLog,
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()

        repeat(20) { viewModel.zoomInPreview() }
        repeat(40) { viewModel.zoomOutPreview() }

        assertEquals(1.0f, viewModel.uiState.value.zoomFactor)
        repeat(20) { viewModel.zoomInPreview() }
        assertEquals(3.0f, viewModel.uiState.value.zoomFactor)
        val messages = sessionLog.snapshot().map { "${it.source}|${it.message}" }
        assertTrue(messages.any { it.contains("PreviewViewModel|preview_zoom_in") })
        assertTrue(messages.any { it.contains("PreviewViewModel|preview_zoom_out") })
    }

    @Test
    fun stopPreview_resetsZoomFactorToDefault() = runTest {
        val viewModel = PreviewViewModel(
            glassesSession = FakeGlassesSession(),
            eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled),
            usbPermissionGateway = FakeUsbPermissionGateway(true),
            cameraSource = FakeCameraSource(),
            recordingPreferences = FakeRecordingPreferences(),
            clipRepository = FakeClipRepository(),
            videoRecorder = FakeVideoRecorder(),
            recoveryManager = FakeRecoveryManager(),
            sessionLog = InMemorySessionLog(),
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.startPreview(FakeCameraSurfaceToken)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.zoomInPreview()
        viewModel.zoomInPreview()
        viewModel.stopPreview()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1.0f, viewModel.uiState.value.zoomFactor)
        assertFalse(viewModel.uiState.value.isPreviewRunning)
    }

    @Test
    fun glassesSessionStartFailure_setsUnavailableStatusAndError() = runTest {
        val viewModel = PreviewViewModel(
            glassesSession = FakeGlassesSession(
                initialState = GlassesSession.State.Connecting,
                startFailure = IllegalStateException("session_failed")
            ),
            eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled),
            usbPermissionGateway = FakeUsbPermissionGateway(true),
            cameraSource = FakeCameraSource(),
            recordingPreferences = FakeRecordingPreferences(),
            clipRepository = FakeClipRepository(),
            videoRecorder = FakeVideoRecorder(),
            recoveryManager = FakeRecoveryManager(),
            sessionLog = InMemorySessionLog(),
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Очки: недоступны", viewModel.uiState.value.glassesStatus)
        assertEquals("session_failed", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun glassesStateCollection_mapsStatusesToLabels() = runTest {
        val glassesSession = FakeGlassesSession(GlassesSession.State.Unavailable)
        val viewModel = PreviewViewModel(
            glassesSession = glassesSession,
            eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled),
            usbPermissionGateway = FakeUsbPermissionGateway(true),
            cameraSource = FakeCameraSource(),
            recordingPreferences = FakeRecordingPreferences(),
            clipRepository = FakeClipRepository(),
            videoRecorder = FakeVideoRecorder(),
            recoveryManager = FakeRecoveryManager(),
            sessionLog = InMemorySessionLog(),
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Очки: недоступны", viewModel.uiState.value.glassesStatus)

        glassesSession.state.value = GlassesSession.State.Connecting
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Очки: подключение", viewModel.uiState.value.glassesStatus)

        glassesSession.state.value = GlassesSession.State.Available
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Очки: доступны", viewModel.uiState.value.glassesStatus)
    }

    @Test
    fun onCleared_stopsGlassesSessionAndCameraSource() = runTest {
        val glassesSession = FakeGlassesSession()
        val cameraSource = FakeCameraSource()
        val viewModel = PreviewViewModel(
            glassesSession = glassesSession,
            eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled),
            usbPermissionGateway = FakeUsbPermissionGateway(true),
            cameraSource = cameraSource,
            recordingPreferences = FakeRecordingPreferences(),
            clipRepository = FakeClipRepository(),
            videoRecorder = FakeVideoRecorder(),
            recoveryManager = FakeRecoveryManager(),
            sessionLog = InMemorySessionLog(),
            cleanupScope = cleanupScope
        )

        invokeOnCleared(viewModel)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(glassesSession.stopCalled)
        assertTrue(cameraSource.stopCalled)
    }

    @Test
    fun enableCamera_recordsShareableLogEntries() = runTest {
        val sessionLog = InMemorySessionLog()
        val viewModel = PreviewViewModel(
            glassesSession = FakeGlassesSession(),
            eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled),
            usbPermissionGateway = FakeUsbPermissionGateway(true),
            cameraSource = FakeCameraSource(),
            recordingPreferences = FakeRecordingPreferences(),
            clipRepository = FakeClipRepository(),
            videoRecorder = FakeVideoRecorder(),
            recoveryManager = FakeRecoveryManager(),
            sessionLog = sessionLog,
            cleanupScope = cleanupScope
        )

        dispatcher.scheduler.advanceUntilIdle()
        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()

        val messages = sessionLog.snapshot().map { "${it.source}|${it.message}" }
        assertTrue(messages.any { it.contains("PreviewViewModel|Enable camera tapped") })
        assertTrue(messages.any { it.contains("PreviewViewModel|Control USB permission granted") })
        assertTrue(messages.any { it.contains("PreviewViewModel|Camera enable finished successfully") })
    }

    @Test
    fun enableCameraPermissionTimeout_restoresIdleStateAndButtons() = runTest {
        val sessionLog = InMemorySessionLog()
        val viewModel = PreviewViewModel(
            glassesSession = FakeGlassesSession(),
            eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled),
            usbPermissionGateway = HangingUsbPermissionGateway(),
            cameraSource = FakeCameraSource(),
            recordingPreferences = FakeRecordingPreferences(),
            clipRepository = FakeClipRepository(),
            videoRecorder = FakeVideoRecorder(),
            recoveryManager = FakeRecoveryManager(),
            sessionLog = sessionLog,
            controlPermissionTimeoutMillis = 1,
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Запрос USB-разрешения истек", viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.canEnableCamera)
        assertFalse(viewModel.uiState.value.canStartPreview)
        assertFalse(viewModel.uiState.value.canStopPreview)
        val messages = sessionLog.snapshot().map { entry -> "${entry.source}|${entry.message}" }
        assertTrue(messages.any { message ->
            message.contains("PreviewViewModel|Control USB permission request timed out")
        })
    }

    @Test
    fun onPreviewStartBlocked_keepsCameraEnabledAndShowsReason() = runTest {
        val viewModel = PreviewViewModel(
            glassesSession = FakeGlassesSession(),
            eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled),
            usbPermissionGateway = FakeUsbPermissionGateway(true),
            cameraSource = FakeCameraSource(),
            recordingPreferences = FakeRecordingPreferences(),
            clipRepository = FakeClipRepository(),
            videoRecorder = FakeVideoRecorder(),
            recoveryManager = FakeRecoveryManager(),
            sessionLog = InMemorySessionLog(),
            cleanupScope = cleanupScope
        )

        viewModel.enableCamera()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.onPreviewStartBlocked("Camera permission denied")

        assertEquals("Camera permission denied", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.canEnableCamera)
        assertTrue(viewModel.uiState.value.canStartPreview)
        assertFalse(viewModel.uiState.value.canStopPreview)
    }

    @Test
    fun onEnableCameraBlocked_restoresIdleStateAndShowsReason() = runTest {
        val viewModel = PreviewViewModel(
            glassesSession = FakeGlassesSession(),
            eyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled),
            usbPermissionGateway = FakeUsbPermissionGateway(true),
            cameraSource = FakeCameraSource(),
            recordingPreferences = FakeRecordingPreferences(),
            clipRepository = FakeClipRepository(),
            videoRecorder = FakeVideoRecorder(),
            recoveryManager = FakeRecoveryManager(),
            sessionLog = InMemorySessionLog(),
            cleanupScope = cleanupScope
        )

        viewModel.onEnableCameraBlocked("Camera permission denied")

        assertEquals("Camera permission denied", viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.canEnableCamera)
        assertFalse(viewModel.uiState.value.canStartPreview)
        assertFalse(viewModel.uiState.value.canStopPreview)
    }
}

private object FakeCameraSurfaceToken : CameraSource.SurfaceToken

private fun invokeOnCleared(viewModel: PreviewViewModel) {
    val method = viewModel.javaClass.superclass.getDeclaredMethod("onCleared")
    method.isAccessible = true
    method.invoke(viewModel)
}

private fun buildViewModel(
    glassesSession: GlassesSession = FakeGlassesSession(),
    eyeUsbConfigurator: EyeUsbConfigurator = FakeEyeUsbConfigurator(EyeUsbConfigurator.Result.Enabled),
    usbPermissionGateway: UsbPermissionGateway = FakeUsbPermissionGateway(true),
    cameraSource: CameraSource = FakeCameraSource(),
    androidCameraSource: CameraSource = cameraSource,
    cameraSourcePreferences: CameraSourcePreferences = FakeCameraSourcePreferences(),
    recordingPreferences: RecordingPreferences = FakeRecordingPreferences(),
    detectionPreferences: DetectionPreferences = FakeDetectionPreferences(),
    objectDetector: ObjectDetector = FakeObjectDetector(),
    detectionAnnotationSink: DetectionAnnotationSink = NoOpDetectionAnnotationSink,
    gemmaSubtitlePreferences: GemmaSubtitlePreferences = FakeGemmaSubtitlePreferences(),
    gemmaModelDownloadScheduler: GemmaModelDownloadScheduler? = null,
    gemmaFrameCaptioner: GemmaFrameCaptioner = FakeGemmaFrameCaptioner(),
    clipRepository: ClipRepository = FakeClipRepository(),
    videoRecorder: VideoRecorder = FakeVideoRecorder(),
    recoveryManager: RecoveryManager = FakeRecoveryManager(),
    sessionLog: InMemorySessionLog = InMemorySessionLog(),
    controlPermissionTimeoutMillis: Long = 45_000L,
    cleanupScope: CoroutineScope
): PreviewViewModel {
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
        clipRepository = clipRepository,
        videoRecorder = videoRecorder,
        recoveryManager = recoveryManager,
        sessionLog = sessionLog,
        controlPermissionTimeoutMillis = controlPermissionTimeoutMillis,
        cleanupScope = cleanupScope
    )
}

private fun createClip(
    id: String,
    createdAtEpochMillis: Long
): RecordedClip {
    return RecordedClip(
        id = id,
        filePath = "/clips/$id.mp4",
        createdAtEpochMillis = createdAtEpochMillis,
        durationMillis = 2_000L,
        width = 1920,
        height = 1080,
        fileSizeBytes = 42L,
        mimeType = "video/mp4"
    )
}

private class FakeGlassesSession(
    initialState: GlassesSession.State = GlassesSession.State.Available,
    private val startFailure: Throwable? = null
) : GlassesSession {
    override val state = MutableStateFlow(initialState)
    var stopCalled = false

    override suspend fun start() {
        startFailure?.let { throw it }
    }

    override suspend fun stop() {
        stopCalled = true
    }
}

private class FakeEyeUsbConfigurator(
    private val result: EyeUsbConfigurator.Result
) : EyeUsbConfigurator {
    var enableCameraCalls = 0

    override suspend fun enableCamera(): EyeUsbConfigurator.Result {
        enableCameraCalls += 1
        return result
    }
}

private class FakeUsbPermissionGateway(
    private val grant: Boolean
) : UsbPermissionGateway {
    var ensureControlPermissionCalls = 0

    override suspend fun ensureControlPermission(): Boolean {
        ensureControlPermissionCalls += 1
        return grant
    }
}

private class HangingUsbPermissionGateway : UsbPermissionGateway {
    override suspend fun ensureControlPermission(): Boolean {
        return suspendCancellableCoroutine { }
    }
}

private class FakeCameraSource : CameraSource {
    constructor(
        startResult: CameraSource.StartResult = CameraSource.StartResult.Started(
            com.example.ar_control.camera.PreviewSize(width = 640, height = 480)
        ),
        recordingStartResult: CameraSource.RecordingStartResult =
            CameraSource.RecordingStartResult.Failed("recording_not_supported"),
        stopRecordingFailure: Throwable? = null
    ) {
        this.startResult = startResult
        this.recordingStartResult = recordingStartResult
        this.stopRecordingFailure = stopRecordingFailure
    }

    private val startResult: CameraSource.StartResult
    private val recordingStartResult: CameraSource.RecordingStartResult
    private val stopRecordingFailure: Throwable?
    var lastStartSurfaceToken: CameraSource.SurfaceToken? = null
    var lastCaptureSurface: Surface? = null
    var lastRecordingTarget: RecordingInputTarget? = null
    var stopCalled = false
    var startRecordingCalls = 0
    var stopRecordingCalls = 0

    override suspend fun start(surfaceToken: CameraSource.SurfaceToken): CameraSource.StartResult {
        lastStartSurfaceToken = surfaceToken
        return startResult
    }

    override suspend fun stop() {
        stopCalled = true
    }

    override suspend fun startRecording(
        target: RecordingInputTarget
    ): CameraSource.RecordingStartResult {
        startRecordingCalls += 1
        lastRecordingTarget = target
        lastCaptureSurface = (target as? RecordingInputTarget.SurfaceTarget)?.surface
        return recordingStartResult
    }

    override suspend fun stopRecording() {
        stopRecordingCalls += 1
        stopRecordingFailure?.let { throw it }
    }
}

private class FakeCameraSourcePreferences(
    var selected: CameraSourceKind = CameraSourceKind.XREAL
) : CameraSourcePreferences {
    var setCalls = 0

    override fun getSelectedCameraSource(): CameraSourceKind = selected

    override fun setSelectedCameraSource(source: CameraSourceKind) {
        selected = source
        setCalls += 1
    }
}

private class FakeRecordingPreferences(
    private var enabled: Boolean = false
) : RecordingPreferences {
    var setCalls = 0

    override fun isRecordingEnabled(): Boolean = enabled

    override fun setRecordingEnabled(enabled: Boolean) {
        this.enabled = enabled
        setCalls += 1
    }
}

private class FakeDetectionPreferences(
    private var enabled: Boolean = false
) : DetectionPreferences {
    var setCalls = 0

    override fun isObjectDetectionEnabled(): Boolean = enabled

    override fun setObjectDetectionEnabled(enabled: Boolean) {
        this.enabled = enabled
        setCalls += 1
    }
}

private class FakeGemmaSubtitlePreferences(
    private var enabled: Boolean = false,
    private var modelPath: String? = null,
    private var modelDisplayName: String? = null,
    private var captionPrompt: String = DEFAULT_GEMMA_CAPTION_PROMPT
) : GemmaSubtitlePreferences {
    var setEnabledCalls = 0
    var setCaptionPromptCalls = 0

    override fun isGemmaSubtitlesEnabled(): Boolean = enabled

    override fun setGemmaSubtitlesEnabled(enabled: Boolean) {
        this.enabled = enabled
        setEnabledCalls += 1
    }

    override fun getModelPath(): String? = modelPath

    override fun getModelDisplayName(): String? = modelDisplayName

    override fun getCaptionPrompt(): String = captionPrompt

    override fun setCaptionPrompt(prompt: String) {
        captionPrompt = prompt
        setCaptionPromptCalls += 1
    }

    override fun setModel(path: String, displayName: String?) {
        modelPath = path
        modelDisplayName = displayName
    }

    override fun clearModel() {
        modelPath = null
        modelDisplayName = null
    }
}

private class FakeGemmaFrameCaptioner : GemmaFrameCaptioner {
    var startCalls = 0
    var lastModelPath: String? = null
    var lastPreviewSize: PreviewSize? = null
    var lastSession: FakeGemmaCaptionSession? = null
    val sessions = mutableListOf<FakeGemmaCaptionSession>()

    override fun start(
        modelPath: String,
        previewSize: PreviewSize,
        onCaptionUpdated: (String) -> Unit,
        onError: (String) -> Unit
    ): GemmaCaptionSession {
        startCalls += 1
        lastModelPath = modelPath
        lastPreviewSize = previewSize
        return FakeGemmaCaptionSession(onCaptionUpdated, onError).also {
            lastSession = it
            sessions += it
        }
    }
}

private class FakeGemmaModelDownloadScheduler : GemmaModelDownloadScheduler {
    private val mutableState = MutableStateFlow<GemmaModelDownloadWorkState>(
        GemmaModelDownloadWorkState.Idle
    )
    override val downloadState = mutableState
    var enqueueCalls = 0
    var cancelCalls = 0

    override fun enqueueDownload() {
        enqueueCalls += 1
    }

    override fun cancelDownload() {
        cancelCalls += 1
    }

    fun publish(state: GemmaModelDownloadWorkState) {
        mutableState.value = state
    }
}

private class FakeGemmaCaptionSession(
    private val onCaptionUpdated: (String) -> Unit,
    private val onError: (String) -> Unit
) : GemmaCaptionSession {
    var closed = false
        private set

    override val inputTarget: RecordingInputTarget.FrameCallbackTarget =
        RecordingInputTarget.FrameCallbackTarget(
            pixelFormat = VideoFramePixelFormat.YUV420SP,
            frameConsumer = VideoFrameConsumer { _, _ -> }
        )

    fun publish(caption: String) {
        onCaptionUpdated(caption)
    }

    fun fail(reason: String) {
        onError(reason)
    }

    override fun close() {
        closed = true
    }
}

private class FakeObjectDetector : ObjectDetector {
    var startCalls = 0
    var lastSession: FakeObjectDetectionSession? = null

    override fun start(
        previewSize: PreviewSize,
        onDetectionsUpdated: (List<DetectedObject>) -> Unit,
        onSessionStatsUpdated: (DetectionSessionStats) -> Unit
    ): ObjectDetectionSession {
        startCalls += 1
        return FakeObjectDetectionSession(onDetectionsUpdated, onSessionStatsUpdated).also {
            lastSession = it
        }
    }
}

private class FakeObjectDetectionSession(
    private val onDetectionsUpdated: (List<DetectedObject>) -> Unit,
    private val onSessionStatsUpdated: (DetectionSessionStats) -> Unit
) : ObjectDetectionSession {
    var closed = false
        private set

    override val inputTarget: RecordingInputTarget.FrameCallbackTarget =
        RecordingInputTarget.FrameCallbackTarget(
            pixelFormat = VideoFramePixelFormat.YUV420SP,
            frameConsumer = VideoFrameConsumer { _, _ -> }
        )

    fun publish(detections: List<DetectedObject>) {
        onDetectionsUpdated(detections)
    }

    fun publishStats(stats: DetectionSessionStats) {
        onSessionStatsUpdated(stats)
    }

    override fun close() {
        closed = true
    }
}

private class FakeDetectionAnnotationSink : DetectionAnnotationSink {
    val updates = mutableListOf<com.example.ar_control.recording.DetectionAnnotationSnapshot>()
    var clearCalls = 0

    override fun updateDetections(
        previewSize: PreviewSize,
        detections: List<DetectedObject>
    ) {
        updates += com.example.ar_control.recording.DetectionAnnotationSnapshot(
            previewSize = previewSize,
            detections = detections
        )
    }

    override fun clearDetections() {
        clearCalls += 1
    }
}

private class FakeRecoveryManager(
    private var snapshot: RecoverySnapshot = RecoverySnapshot()
) : RecoveryManager {
    var clearSafeModeCalls = 0
    var markPreviewStartedCalls = 0
    var markRecordingStartedPaths = mutableListOf<String>()
    var markCameraIdleCalls = 0

    override fun snapshot(): RecoverySnapshot = snapshot

    override fun markPreviewStarted() {
        markPreviewStartedCalls += 1
    }

    override fun markRecordingStarted(
        outputFilePath: String,
        width: Int?,
        height: Int?,
        mimeType: String?
    ) {
        markRecordingStartedPaths += outputFilePath
    }

    override fun markCameraIdle() {
        markCameraIdleCalls += 1
    }

    override fun clearSafeMode() {
        clearSafeModeCalls += 1
        snapshot = snapshot.copy(isSafeMode = false, safeModeReason = null)
    }
}

private class FakeClipRepository(
    private val clips: MutableList<RecordedClip> = mutableListOf(),
    private val failLoadsAfterDelete: Boolean = false
) : ClipRepository {
    var loadCalls = 0
    var insertCalls = 0
    var deleteCalls = 0
    private var deletedSinceLastLoad = false

    override suspend fun load(): List<RecordedClip> {
        loadCalls += 1
        if (failLoadsAfterDelete && deletedSinceLastLoad) {
            throw IllegalStateException("Failed to load recorded clips")
        }
        return clips.toList()
    }

    override suspend fun insert(clip: RecordedClip) {
        insertCalls += 1
        clips.removeAll { it.id == clip.id }
        clips.add(0, clip)
    }

    override suspend fun delete(clipId: String): Boolean {
        deleteCalls += 1
        val deleted = clips.removeAll { it.id == clipId }
        if (deleted) {
            deletedSinceLastLoad = true
        }
        return deleted
    }
}

private class FakeVideoRecorder(
    private val startResult: VideoRecorder.StartResult =
        VideoRecorder.StartResult.Failed("recorder_not_configured"),
    private val stopResult: VideoRecorder.StopResult =
        VideoRecorder.StopResult.Failed("recording_not_started"),
    private val cancelResult: VideoRecorder.CancelResult =
        VideoRecorder.CancelResult.Failed("recording_not_started")
) : VideoRecorder {
    var startCalls = 0
    var stopCalls = 0
    var cancelCalls = 0
    var lastStartPreviewSize: PreviewSize? = null

    override suspend fun start(previewSize: PreviewSize): VideoRecorder.StartResult {
        startCalls += 1
        lastStartPreviewSize = previewSize
        return startResult
    }

    override suspend fun stop(): VideoRecorder.StopResult {
        stopCalls += 1
        return stopResult
    }

    override suspend fun cancel(): VideoRecorder.CancelResult {
        cancelCalls += 1
        return cancelResult
    }
}

private class DeferredStartVideoRecorder(
    private val startGate: CompletableDeferred<VideoRecorder.StartResult>,
    private val cancelResult: VideoRecorder.CancelResult
) : VideoRecorder {
    var startCalls = 0
    var stopCalls = 0
    var cancelCalls = 0

    override suspend fun start(previewSize: PreviewSize): VideoRecorder.StartResult {
        startCalls += 1
        return startGate.await()
    }

    override suspend fun stop(): VideoRecorder.StopResult {
        stopCalls += 1
        return VideoRecorder.StopResult.Failed("recording_not_started")
    }

    override suspend fun cancel(): VideoRecorder.CancelResult {
        cancelCalls += 1
        return cancelResult
    }
}

private class DeferredStopVideoRecorder(
    private val startResult: VideoRecorder.StartResult,
    private val stopGate: CompletableDeferred<VideoRecorder.StopResult>,
    private val cancelResult: VideoRecorder.CancelResult
) : VideoRecorder {
    var startCalls = 0
    var stopCalls = 0
    var cancelCalls = 0

    override suspend fun start(previewSize: PreviewSize): VideoRecorder.StartResult {
        startCalls += 1
        return startResult
    }

    override suspend fun stop(): VideoRecorder.StopResult {
        stopCalls += 1
        return stopGate.await()
    }

    override suspend fun cancel(): VideoRecorder.CancelResult {
        cancelCalls += 1
        return cancelResult
    }
}

private class TestSurface private constructor(
    private val texture: SurfaceTexture,
    val surface: Surface
) : Closeable {

    private var closed = false

    override fun close() {
        if (!closed) {
            closed = true
            surface.release()
            texture.release()
        }
    }

    companion object {
        fun create(): TestSurface {
            val texture = SurfaceTexture(0)
            return TestSurface(texture, Surface(texture))
        }
    }
}
