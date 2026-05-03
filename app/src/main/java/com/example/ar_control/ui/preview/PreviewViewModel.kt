package com.example.ar_control.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ar_control.camera.CameraSource
import com.example.ar_control.camera.CameraSourceKind
import com.example.ar_control.camera.CameraSourcePreferences
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.detection.DetectionPreferences
import com.example.ar_control.detection.NoOpObjectDetector
import com.example.ar_control.detection.ObjectDetectionSession
import com.example.ar_control.detection.ObjectDetector
import com.example.ar_control.diagnostics.SessionLog
import com.example.ar_control.gemma.DEFAULT_GEMMA_CAPTION_PROMPT
import com.example.ar_control.gemma.GemmaCaptionSession
import com.example.ar_control.gemma.GemmaFrameCaptioner
import com.example.ar_control.gemma.GemmaModelDownloadProgress
import com.example.ar_control.gemma.GemmaModelDownloadScheduler
import com.example.ar_control.gemma.GemmaModelDownloadWorkState
import com.example.ar_control.gemma.GemmaSubtitlePreferences
import com.example.ar_control.gemma.NoOpGemmaFrameCaptioner
import com.example.ar_control.recording.ClipRepository
import com.example.ar_control.recording.DetectionAnnotationSink
import com.example.ar_control.recording.FrameCallbackTargetFanOut
import com.example.ar_control.recording.NoOpDetectionAnnotationSink
import com.example.ar_control.recording.RecordedClip
import com.example.ar_control.recording.RecordingPreferences
import com.example.ar_control.recording.RecordingStatus
import com.example.ar_control.recording.RecordingInputTarget
import com.example.ar_control.recording.VideoRecorder
import com.example.ar_control.recovery.RecoveryManager
import com.example.ar_control.recovery.RecoverySnapshot
import com.example.ar_control.usb.EyeUsbConfigurator
import com.example.ar_control.usb.UsbPermissionGateway
import com.example.ar_control.xreal.GlassesSession
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PreviewViewModel(
    private val glassesSession: GlassesSession,
    private val eyeUsbConfigurator: EyeUsbConfigurator,
    private val usbPermissionGateway: UsbPermissionGateway,
    private val cameraSource: CameraSource,
    private val androidCameraSource: CameraSource = cameraSource,
    private val cameraSourcePreferences: CameraSourcePreferences = NoOpCameraSourcePreferences,
    private val recordingPreferences: RecordingPreferences,
    private val detectionPreferences: DetectionPreferences = object : DetectionPreferences {
        override fun isObjectDetectionEnabled(): Boolean = false

        override fun setObjectDetectionEnabled(enabled: Boolean) = Unit
    },
    private val objectDetector: ObjectDetector = NoOpObjectDetector,
    private val detectionAnnotationSink: DetectionAnnotationSink = NoOpDetectionAnnotationSink,
    private val gemmaSubtitlePreferences: GemmaSubtitlePreferences = NoOpGemmaSubtitlePreferences,
    private val gemmaModelDownloadScheduler: GemmaModelDownloadScheduler? = null,
    private val gemmaFrameCaptioner: GemmaFrameCaptioner = NoOpGemmaFrameCaptioner,
    private val clipRepository: ClipRepository,
    private val videoRecorder: VideoRecorder,
    private val recoveryManager: RecoveryManager,
    private val sessionLog: SessionLog,
    private val controlPermissionTimeoutMillis: Long = 45_000L,
    private val minZoomFactor: Float = 1.0f,
    private val maxZoomFactor: Float = 3.0f,
    private val zoomStep: Float = 0.15f,
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) : ViewModel() {

    private val _uiState = MutableStateFlow(PreviewUiState())
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()
    private var hasGlassesStartFailure = false
    private var previewGeneration = 0L
    private var stopPreviewInFlight = false
    private var recoverySnapshot = RecoverySnapshot()
    private var activeDetectionSession: ObjectDetectionSession? = null
    private var activeGemmaCaptionSession: GemmaCaptionSession? = null
    private var hasActiveFrameCallbackCapture = false
    private var activePreviewCameraSource: CameraSource? = null

    init {
        applyRecoverySnapshot(recoveryManager.snapshot())
        loadInitialRecordingState()
        startGlassesSession()
        observeGlassesState()
        observeGemmaModelDownloadState()
    }

    fun enableCamera() {
        viewModelScope.launch {
            if (recoverySnapshot.isSafeMode) {
                sessionLog.record("PreviewViewModel", "Enable camera ignored because safe mode is active")
                return@launch
            }
            if (!_uiState.value.canEnableCamera) {
                return@launch
            }
            if (_uiState.value.selectedCameraSource == CameraSourceKind.ANDROID) {
                sessionLog.record("PreviewViewModel", "Enable camera skipped for Android camera source")
                _uiState.value = androidCameraReadyState()
                return@launch
            }
            sessionLog.record("PreviewViewModel", "Enable camera tapped")

            _uiState.value = applyRecoveryState(_uiState.value.copy(
                canEnableCamera = false,
                canStartPreview = false,
                canStopPreview = false,
                isPreviewRunning = false,
                errorMessage = null
            ))

            val hasPermission = withTimeoutOrNull(controlPermissionTimeoutMillis) {
                usbPermissionGateway.ensureControlPermission()
            }
            if (hasPermission == null) {
                sessionLog.record("PreviewViewModel", "Control USB permission request timed out")
                _uiState.value = idleCameraState(errorMessage = "Запрос USB-разрешения истек")
                return@launch
            }

            if (!hasPermission) {
                sessionLog.record("PreviewViewModel", "Control USB permission denied")
                _uiState.value = idleCameraState(errorMessage = "USB-разрешение отклонено")
                return@launch
            }
            sessionLog.record("PreviewViewModel", "Control USB permission granted")

            when (val result = eyeUsbConfigurator.enableCamera()) {
                EyeUsbConfigurator.Result.Enabled -> {
                    sessionLog.record("PreviewViewModel", "Camera enable finished successfully")
                    _uiState.value = enabledCameraState()
                }

                is EyeUsbConfigurator.Result.Failed -> {
                    sessionLog.record("PreviewViewModel", "Camera enable failed: ${result.reason}")
                    _uiState.value = idleCameraState(errorMessage = result.reason)
                }
            }
        }
    }

    fun startPreview(surfaceToken: CameraSource.SurfaceToken) {
        viewModelScope.launch {
            if (recoverySnapshot.isSafeMode) {
                sessionLog.record("PreviewViewModel", "Start preview ignored because safe mode is active")
                return@launch
            }
            if (!_uiState.value.canStartPreview || stopPreviewInFlight) {
                return@launch
            }
            val generation = nextPreviewGeneration()
            sessionLog.record("PreviewViewModel", "Start preview tapped")

            _uiState.value = applyRecoveryState(_uiState.value.copy(
                canStartPreview = false,
                canStopPreview = false,
                isPreviewRunning = false,
                errorMessage = null,
                recordingStatus = RecordingStatus.Idle
            ))

            val selectedSource = selectedCameraSource()
            when (val result = selectedSource.start(surfaceToken)) {
                is CameraSource.StartResult.Started -> {
                    if (!isCurrentPreviewGeneration(generation)) {
                        sessionLog.record("PreviewViewModel", "Preview start finished after stop; ignoring stale session")
                        runCatching { selectedSource.stop() }
                        return@launch
                    }
                    sessionLog.record("PreviewViewModel", "Preview started successfully")
                    recoveryManager.markPreviewStarted()
                    activePreviewCameraSource = selectedSource
                    _uiState.value = previewRunningState(result.previewSize)
                    maybeStartFramePipeline(result.previewSize, generation)
                }

                is CameraSource.StartResult.Failed -> {
                    if (!isCurrentPreviewGeneration(generation)) {
                        sessionLog.record("PreviewViewModel", "Preview start failure arrived after stop; ignoring stale session")
                        return@launch
                    }
                    sessionLog.record("PreviewViewModel", "Preview start failed: ${result.reason}")
                    _uiState.value = cameraSourceReadyState(errorMessage = result.reason)
                }
            }
        }
    }

    fun stopPreview() {
        if (stopPreviewInFlight) {
            sessionLog.record("PreviewViewModel", "Stop preview ignored: stop already in progress")
            return
        }
        stopPreviewInFlight = true
        invalidatePreviewGeneration()
        viewModelScope.launch {
            try {
                stopPreviewAndFinalizeRecording()
            } finally {
                stopPreviewInFlight = false
            }
        }
    }

    fun setRecordVideoEnabled(enabled: Boolean) {
        if (recoverySnapshot.isSafeMode) {
            sessionLog.record("PreviewViewModel", "Record video preference change ignored because safe mode is active")
            return
        }
        recordingPreferences.setRecordingEnabled(enabled)
        sessionLog.record("PreviewViewModel", "Record video preference changed: $enabled")
        _uiState.value = applyRecoveryState(_uiState.value.copy(recordVideoEnabled = enabled))
    }

    fun setObjectDetectionEnabled(enabled: Boolean) {
        if (recoverySnapshot.isSafeMode) {
            sessionLog.record("PreviewViewModel", "Object detection preference change ignored because safe mode is active")
            return
        }
        detectionPreferences.setObjectDetectionEnabled(enabled)
        sessionLog.record("PreviewViewModel", "Object detection preference changed: $enabled")
        _uiState.value = applyRecoveryState(_uiState.value.copy(objectDetectionEnabled = enabled))
    }

    fun setTransparentHudEnabled(enabled: Boolean) {
        if (recoverySnapshot.isSafeMode) {
            sessionLog.record("PreviewViewModel", "Transparent HUD preference change ignored because safe mode is active")
            return
        }
        sessionLog.record("PreviewViewModel", "Transparent HUD preference changed: $enabled")
        _uiState.value = applyRecoveryState(_uiState.value.copy(transparentHudEnabled = enabled))
    }

    fun setGemmaSubtitlesEnabled(enabled: Boolean) {
        if (recoverySnapshot.isSafeMode) {
            sessionLog.record("PreviewViewModel", "Gemma subtitles preference change ignored because safe mode is active")
            return
        }
        gemmaSubtitlePreferences.setGemmaSubtitlesEnabled(enabled)
        sessionLog.record("PreviewViewModel", "Gemma subtitles preference changed: $enabled")
        _uiState.value = applyRecoveryState(_uiState.value.copy(gemmaSubtitlesEnabled = enabled))
    }

    fun setGemmaPrompt(prompt: String) {
        val storedPrompt = prompt.ifBlank { DEFAULT_GEMMA_CAPTION_PROMPT }
        gemmaSubtitlePreferences.setCaptionPrompt(storedPrompt)
        _uiState.value = applyRecoveryState(_uiState.value.copy(gemmaPrompt = storedPrompt))
    }

    fun setCameraSource(source: CameraSourceKind) {
        if (recoverySnapshot.isSafeMode) {
            sessionLog.record("PreviewViewModel", "Camera source change ignored because safe mode is active")
            return
        }
        val currentState = _uiState.value
        if (!currentState.canChangeCameraSource || currentState.isPreviewRunning) {
            sessionLog.record("PreviewViewModel", "Camera source change ignored while preview is active")
            return
        }
        cameraSourcePreferences.setSelectedCameraSource(source)
        sessionLog.record("PreviewViewModel", "Camera source changed: $source")
        _uiState.value = cameraSourceBaseState(source).copy(
            recordVideoEnabled = currentState.recordVideoEnabled,
            objectDetectionEnabled = currentState.objectDetectionEnabled,
            transparentHudEnabled = currentState.transparentHudEnabled,
            gemmaSubtitlesEnabled = currentState.gemmaSubtitlesEnabled,
            gemmaModelDisplayName = currentState.gemmaModelDisplayName,
            gemmaPrompt = currentState.gemmaPrompt,
            recordedClips = currentState.recordedClips,
            selectedClipId = currentState.selectedClipId,
            errorMessage = null
        )
    }

    fun downloadGemmaModel() {
        val scheduler = gemmaModelDownloadScheduler ?: return
        if (_uiState.value.isGemmaModelDownloadInProgress) {
            sessionLog.record("PreviewViewModel", "Gemma model download cancellation requested")
            scheduler.cancelDownload()
            _uiState.value = applyRecoveryState(_uiState.value.copy(
                isGemmaModelDownloadInProgress = false,
                gemmaModelDownloadProgressText = null,
                errorMessage = null
            ))
            return
        }
        sessionLog.record("PreviewViewModel", "Gemma model download requested")
        _uiState.value = applyRecoveryState(_uiState.value.copy(
            isGemmaModelDownloadInProgress = true,
            gemmaModelDownloadProgressText = GEMMA_MODEL_DOWNLOADING,
            errorMessage = null
        ))
        scheduler.enqueueDownload()
    }

    private fun observeGemmaModelDownloadState() {
        val scheduler = gemmaModelDownloadScheduler ?: return
        viewModelScope.launch {
            scheduler.downloadState.collectLatest { state ->
                applyGemmaModelDownloadState(state)
            }
        }
    }

    private fun applyGemmaModelDownloadState(state: GemmaModelDownloadWorkState) {
        when (state) {
            GemmaModelDownloadWorkState.Idle -> {
                if (_uiState.value.isGemmaModelDownloadInProgress) {
                    _uiState.value = applyRecoveryState(_uiState.value.copy(
                        isGemmaModelDownloadInProgress = false,
                        gemmaModelDownloadProgressText = null
                    ))
                }
            }

            is GemmaModelDownloadWorkState.Running -> {
                _uiState.value = applyRecoveryState(_uiState.value.copy(
                    isGemmaModelDownloadInProgress = true,
                    gemmaModelDownloadProgressText = state.progress?.toStatusText() ?: GEMMA_MODEL_DOWNLOADING,
                    errorMessage = null
                ))
            }

            is GemmaModelDownloadWorkState.Completed -> {
                sessionLog.record("PreviewViewModel", "Gemma model download finished: ${state.displayName}")
                _uiState.value = applyRecoveryState(_uiState.value.copy(
                    isGemmaModelDownloadInProgress = false,
                    gemmaModelDownloadProgressText = null,
                    gemmaModelDisplayName = state.displayName ?: gemmaSubtitlePreferences.getModelDisplayName(),
                    errorMessage = null
                ))
            }

            is GemmaModelDownloadWorkState.Failed -> {
                sessionLog.record("PreviewViewModel", "Gemma model download failed: ${state.reason}")
                _uiState.value = applyRecoveryState(_uiState.value.copy(
                    isGemmaModelDownloadInProgress = false,
                    gemmaModelDownloadProgressText = null,
                    errorMessage = state.reason
                ))
            }
        }
    }

    private fun GemmaModelDownloadProgress.toStatusText(): String {
        val total = totalBytes ?: return GEMMA_MODEL_DOWNLOADING
        if (total <= 0L) {
            return GEMMA_MODEL_DOWNLOADING
        }
        val percent = ((bytesDownloaded * 100L) / total).coerceIn(0L, 100L)
        return "Модель Gemma: загрузка $percent%"
    }

    fun confirmSafeModeExit() {
        viewModelScope.launch {
            sessionLog.record("PreviewViewModel", "Safe mode exit confirmed by user")
            recoveryManager.clearSafeMode()
            val selectedSource = cameraSourcePreferences.getSelectedCameraSource()
            applyRecoverySnapshot(
                recoveryManager.snapshot(),
                cameraSourceBaseState(selectedSource).copy(
                    recordVideoEnabled = recordingPreferences.isRecordingEnabled(),
                    objectDetectionEnabled = detectionPreferences.isObjectDetectionEnabled(),
                    transparentHudEnabled = false,
                    gemmaSubtitlesEnabled = gemmaSubtitlePreferences.isGemmaSubtitlesEnabled(),
                    gemmaModelDisplayName = gemmaSubtitlePreferences.getModelDisplayName(),
                    gemmaPrompt = gemmaSubtitlePreferences.getCaptionPrompt(),
                    recordedClips = _uiState.value.recordedClips,
                    selectedClipId = _uiState.value.selectedClipId,
                    gemmaSubtitleText = "",
                    recordingStatus = RecordingStatus.Idle,
                    errorMessage = null
                )
            )
        }
    }

    fun selectClip(clipId: String) {
        val clipExists = _uiState.value.recordedClips.any { it.id == clipId }
        _uiState.value = _uiState.value.copy(selectedClipId = clipId.takeIf { clipExists })
    }

    fun clearSelectedClip() {
        _uiState.value = _uiState.value.copy(selectedClipId = null)
    }

    fun deleteSelectedClip() {
        viewModelScope.launch {
            val selectedClip = _uiState.value.selectedClip ?: return@launch
            sessionLog.record("PreviewViewModel", "Deleting selected clip: ${selectedClip.id}")
            val deleted = runCatching { clipRepository.delete(selectedClip.id) }
                .getOrElse { error ->
                    val reason = error.message ?: "Failed to delete clip"
                    sessionLog.record("PreviewViewModel", "Delete selected clip failed: $reason")
                    _uiState.value = _uiState.value.copy(errorMessage = reason)
                    return@launch
                }
            if (!deleted) {
                sessionLog.record("PreviewViewModel", "Selected clip missing during delete: ${selectedClip.id}")
            }
            _uiState.value = applyRecoveryState(_uiState.value.copy(
                recordedClips = _uiState.value.recordedClips.filterNot { it.id == selectedClip.id },
                selectedClipId = null
            ))
            refreshRecordedClipsInternal()
        }
    }

    fun refreshRecordedClips() {
        viewModelScope.launch {
            refreshRecordedClipsInternal()
        }
    }

    fun zoomInPreview() {
        updatePreviewZoom(delta = zoomStep, logLabel = "preview_zoom_in")
    }

    fun zoomOutPreview() {
        updatePreviewZoom(delta = -zoomStep, logLabel = "preview_zoom_out")
    }

    fun onPreviewStartBlocked(reason: String) {
        sessionLog.record("PreviewViewModel", "Preview start blocked: $reason")
        _uiState.value = cameraSourceReadyState(errorMessage = reason)
    }

    fun onEnableCameraBlocked(reason: String) {
        sessionLog.record("PreviewViewModel", "Enable camera blocked: $reason")
        _uiState.value = idleCameraState(errorMessage = reason)
    }

    override fun onCleared() {
        cleanupScope.launch {
            sessionLog.record("PreviewViewModel", "Clearing preview resources")
            runCatching { activeDetectionSession?.close() }
            runCatching { closeGemmaCaptionSession(clearSubtitle = true) }
            runCatching { activeCameraSource().stopRecording() }
            runCatching { videoRecorder.cancel() }
            runCatching { stopCameraSources() }
            runCatching { glassesSession.stop() }
            runCatching { recoveryManager.markCameraIdle() }
        }
        super.onCleared()
    }

    private fun loadInitialRecordingState() {
        val recordVideoEnabled = recordingPreferences.isRecordingEnabled()
        val objectDetectionEnabled = detectionPreferences.isObjectDetectionEnabled()
        val gemmaSubtitlesEnabled = gemmaSubtitlePreferences.isGemmaSubtitlesEnabled()
        val gemmaModelDisplayName = gemmaSubtitlePreferences.getModelDisplayName()
        val gemmaPrompt = gemmaSubtitlePreferences.getCaptionPrompt()
        val selectedSource = cameraSourcePreferences.getSelectedCameraSource()
        _uiState.value = applyRecoveryState(cameraSourceBaseState(selectedSource).copy(
            recordVideoEnabled = recordVideoEnabled,
            objectDetectionEnabled = objectDetectionEnabled,
            gemmaSubtitlesEnabled = gemmaSubtitlesEnabled,
            gemmaModelDisplayName = gemmaModelDisplayName,
            gemmaPrompt = gemmaPrompt
        ))

        viewModelScope.launch {
            refreshRecordedClipsInternal()
        }
    }

    private fun startGlassesSession() {
        viewModelScope.launch {
            sessionLog.record("PreviewViewModel", "Starting glasses session")
            try {
                glassesSession.start()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                hasGlassesStartFailure = true
                sessionLog.record(
                    "PreviewViewModel",
                    "Glasses session start failed: ${error.message ?: "unknown_error"}"
                )
                _uiState.value = _uiState.value.copy(
                    glassesStatus = GlassesSession.State.Unavailable.toStatusLabel(),
                    errorMessage = error.message ?: "Failed to start glasses session"
                )
            }
        }
    }

    private fun observeGlassesState() {
        viewModelScope.launch {
            glassesSession.state.collectLatest { state ->
                if (hasGlassesStartFailure) {
                    return@collectLatest
                }
                sessionLog.record("PreviewViewModel", "Glasses state updated: ${state.toStatusLabel()}")
                _uiState.value = _uiState.value.copy(glassesStatus = state.toStatusLabel())
            }
        }
    }

    private suspend fun refreshRecordedClipsInternal(): String? {
        val clips = runCatching { clipRepository.load() }
            .getOrElse { error ->
                val reason = error.message ?: "Failed to load recorded clips"
                sessionLog.record("PreviewViewModel", "Recorded clip refresh failed: $reason")
                _uiState.value = _uiState.value.copy(errorMessage = reason)
                return reason
            }

        val selectedClipId = _uiState.value.selectedClipId
            ?.takeIf { id -> clips.any { it.id == id } }

        _uiState.value = applyRecoveryState(_uiState.value.copy(
            recordedClips = clips,
            selectedClipId = selectedClipId
        ))
        return null
    }

    private suspend fun maybeStartFramePipeline(previewSize: PreviewSize, generation: Long) {
        val shouldRunDetection = _uiState.value.objectDetectionEnabled
        val shouldRecord = _uiState.value.recordVideoEnabled
        val shouldRunGemma = _uiState.value.gemmaSubtitlesEnabled
        val gemmaModelPath = gemmaSubtitlePreferences.getModelPath()
        if (!shouldRunDetection) {
            detectionAnnotationSink.clearDetections()
        }
        if (shouldRunGemma && gemmaModelPath == null) {
            sessionLog.record("PreviewViewModel", "Gemma subtitles enabled without configured model")
            _uiState.value = applyRecoveryState(_uiState.value.copy(
                errorMessage = GEMMA_MODEL_NOT_CONFIGURED,
                gemmaSubtitleText = ""
            ))
        }
        if ((!shouldRunDetection && !shouldRecord && (gemmaModelPath == null || !shouldRunGemma)) ||
            !isCurrentPreviewGeneration(generation)
        ) {
            return
        }

        val detectionSession = if (shouldRunDetection) {
            objectDetector.start(
                previewSize = previewSize,
                onDetectionsUpdated = { detections ->
                    detectionAnnotationSink.updateDetections(previewSize, detections)
                    viewModelScope.launch {
                        if (_uiState.value.isPreviewRunning) {
                            _uiState.value = applyRecoveryState(_uiState.value.copy(
                                detectedObjects = detections
                            ))
                        }
                    }
                },
                onSessionStatsUpdated = { stats ->
                    viewModelScope.launch {
                        if (_uiState.value.isPreviewRunning) {
                            _uiState.value = applyRecoveryState(_uiState.value.copy(
                                inferenceFps = stats.inferenceFps,
                                inferenceBackendLabel = stats.backendLabel
                            ))
                        }
                    }
                }
            ).also { activeDetectionSession = it }
        } else {
            null
        }

        var startedGemmaCaptionSession: GemmaCaptionSession? = null
        val gemmaCaptionSession = if (shouldRunGemma && gemmaModelPath != null) {
            val session = gemmaFrameCaptioner.start(
                modelPath = gemmaModelPath,
                previewSize = previewSize,
                onCaptionUpdated = { caption ->
                    viewModelScope.launch {
                        if (
                            isCurrentPreviewGeneration(generation) &&
                            activeGemmaCaptionSession === startedGemmaCaptionSession &&
                            _uiState.value.isPreviewRunning
                        ) {
                            _uiState.value = applyRecoveryState(_uiState.value.copy(
                                gemmaSubtitleText = caption
                            ))
                        }
                    }
                },
                onError = { reason ->
                    viewModelScope.launch {
                        if (
                            isCurrentPreviewGeneration(generation) &&
                            activeGemmaCaptionSession === startedGemmaCaptionSession
                        ) {
                            closeGemmaCaptionSession(clearSubtitle = false)
                            _uiState.value = applyRecoveryState(_uiState.value.copy(
                                errorMessage = reason,
                                gemmaSubtitleText = gemmaUnavailableSubtitle(reason)
                            ))
                        }
                    }
                }
            )
            startedGemmaCaptionSession = session
            session.also { activeGemmaCaptionSession = it }
        } else {
            null
        }

        if (!isCurrentPreviewGeneration(generation)) {
            sessionLog.record("PreviewViewModel", "Frame pipeline prepared after preview stop; closing stale sessions")
            closeDetectionSession(clearDetections = true)
            closeGemmaCaptionSession(clearSubtitle = true)
            return
        }

        if (!shouldRecord) {
            val captureTarget = combineFrameTargets(
                recorderTarget = null,
                detectionSession = detectionSession,
                gemmaCaptionSession = gemmaCaptionSession
            ) ?: return
            startFrameCallbackCapture(
                target = captureTarget,
                generation = generation,
                onFailure = { reason ->
                    closeDetectionSession(clearDetections = true)
                    closeGemmaCaptionSession(clearSubtitle = true)
                    _uiState.value = applyRecoveryState(_uiState.value.copy(errorMessage = reason))
                }
            )
            return
        }

        _uiState.value = applyRecoveryState(_uiState.value.copy(
            recordingStatus = RecordingStatus.Starting,
            errorMessage = if (shouldRunGemma && gemmaModelPath == null) {
                GEMMA_MODEL_NOT_CONFIGURED
            } else {
                null
            }
        ))

        when (val recorderResult = videoRecorder.start(previewSize)) {
            is VideoRecorder.StartResult.Started -> {
                if (!isCurrentPreviewGeneration(generation)) {
                    sessionLog.record("PreviewViewModel", "Recorder prepared after preview stop; cancelling stale session")
                    closeDetectionSession(clearDetections = true)
                    closeGemmaCaptionSession(clearSubtitle = true)
                    cancelPreparedRecorder(reasonForLog = "Stale preview generation after recorder start")
                    return
                }
                recoveryManager.markRecordingStarted(
                    outputFilePath = recorderResult.outputFilePath,
                    width = previewSize.width,
                    height = previewSize.height,
                    mimeType = "video/mp4"
                )
                val captureTarget = combineFrameTargets(
                    recorderTarget = recorderResult.inputTarget,
                    detectionSession = detectionSession,
                    gemmaCaptionSession = gemmaCaptionSession
                )
                if (captureTarget == null) {
                    closeDetectionSession(clearDetections = true)
                    closeGemmaCaptionSession(clearSubtitle = true)
                    cancelPreparedRecorder(reasonForLog = "Unsupported detection and recording targets")
                    _uiState.value = applyRecoveryState(_uiState.value.copy(
                        recordingStatus = RecordingStatus.Failed("detection_recording_target_mismatch"),
                        errorMessage = "detection_recording_target_mismatch"
                    ))
                    return
                }

                when (val captureResult = startFrameCallbackCapture(captureTarget, generation)) {
                    CameraSource.RecordingStartResult.Started -> {
                        if (!isCurrentPreviewGeneration(generation)) {
                            sessionLog.record("PreviewViewModel", "Capture started after preview stop; cancelling stale session")
                            runCatching { activeCameraSource().stopRecording() }
                            hasActiveFrameCallbackCapture = false
                            closeDetectionSession(clearDetections = true)
                            closeGemmaCaptionSession(clearSubtitle = true)
                            cancelPreparedRecorder(reasonForLog = "Stale preview generation after capture start")
                            return
                        }
                        sessionLog.record("PreviewViewModel", "Recording started successfully")
                        _uiState.value = applyRecoveryState(_uiState.value.copy(
                            recordingStatus = RecordingStatus.Recording,
                            errorMessage = if (shouldRunGemma && gemmaModelPath == null) {
                                GEMMA_MODEL_NOT_CONFIGURED
                            } else {
                                null
                            }
                        ))
                    }

                    is CameraSource.RecordingStartResult.Failed -> {
                        if (!isCurrentPreviewGeneration(generation)) {
                            sessionLog.record("PreviewViewModel", "Capture failure arrived after preview stop; cancelling stale session")
                            closeDetectionSession(clearDetections = true)
                            closeGemmaCaptionSession(clearSubtitle = true)
                            cancelPreparedRecorder(reasonForLog = "Stale preview generation after capture failure")
                            return
                        }
                        sessionLog.record(
                            "PreviewViewModel",
                            "Recording capture failed: ${captureResult.reason}"
                        )
                        cancelPreparedRecorder(reasonForLog = "Capture start failed")
                        closeDetectionSession(clearDetections = true)
                        closeGemmaCaptionSession(clearSubtitle = true)
                        recoveryManager.markPreviewStarted()
                        _uiState.value = applyRecoveryState(_uiState.value.copy(
                            recordingStatus = RecordingStatus.Failed(captureResult.reason),
                            errorMessage = captureResult.reason
                        ))
                    }
                }
            }

            is VideoRecorder.StartResult.Failed -> {
                if (!isCurrentPreviewGeneration(generation)) {
                    sessionLog.record("PreviewViewModel", "Recorder failure arrived after preview stop; ignoring stale session")
                    return
                }
                closeDetectionSession(clearDetections = true)
                closeGemmaCaptionSession(clearSubtitle = true)
                sessionLog.record("PreviewViewModel", "Recorder start failed: ${recorderResult.reason}")
                _uiState.value = applyRecoveryState(_uiState.value.copy(
                    recordingStatus = RecordingStatus.Failed(recorderResult.reason),
                    errorMessage = recorderResult.reason
                ))
            }
        }
    }

    private suspend fun stopPreviewAndFinalizeRecording() {
        sessionLog.record("PreviewViewModel", "Stop preview tapped")

        var nextErrorMessage = _uiState.value.errorMessage
        var nextRecordingStatus: RecordingStatus = when (val current = _uiState.value.recordingStatus) {
            is RecordingStatus.Failed -> current
            else -> RecordingStatus.Idle
        }

        when (val currentRecordingStatus = _uiState.value.recordingStatus) {
            RecordingStatus.Recording -> {
                _uiState.value = _uiState.value.copy(recordingStatus = RecordingStatus.Finalizing)
                sessionLog.record("PreviewViewModel", "Stopping camera recording capture")
                val stopRecordingFailure = stopActiveFrameCallbackCapture()
                    .exceptionOrNull()
                    ?.message

                if (stopRecordingFailure != null) {
                    sessionLog.record(
                        "PreviewViewModel",
                        "Camera recording stop failed: $stopRecordingFailure"
                    )
                    sessionLog.record(
                        "PreviewViewModel",
                        "Skipping recorder finalize because capture stop did not complete cleanly"
                    )
                    nextErrorMessage = stopRecordingFailure
                    nextRecordingStatus = RecordingStatus.Failed(stopRecordingFailure)
                    cancelPreparedRecorder(reasonForLog = "Camera recording stop failed")
                } else {
                    sessionLog.record("PreviewViewModel", "Camera recording capture stopped")
                    sessionLog.record("PreviewViewModel", "Finalizing recorder session")
                    when (val stopResult = videoRecorder.stop()) {
                        is VideoRecorder.StopResult.Finished -> {
                            sessionLog.record(
                                "PreviewViewModel",
                                "Recording finalized successfully: ${stopResult.clip.id}"
                            )
                            val clipPersistFailure = insertFinishedClip(stopResult.clip)
                            if (clipPersistFailure == null) {
                                nextErrorMessage = null
                                nextRecordingStatus = RecordingStatus.Idle
                            } else {
                                nextErrorMessage = clipPersistFailure
                                nextRecordingStatus = RecordingStatus.Idle
                            }
                        }

                        is VideoRecorder.StopResult.Failed -> {
                            sessionLog.record(
                                "PreviewViewModel",
                                "Recording finalize failed: ${stopResult.reason}"
                            )
                            nextErrorMessage = stopResult.reason
                            nextRecordingStatus = RecordingStatus.Failed(stopResult.reason)
                        }
                    }
                }
            }

            RecordingStatus.Starting,
            RecordingStatus.Idle,
            RecordingStatus.Finalizing,
            is RecordingStatus.Failed -> {
                val stopCaptureFailure = stopActiveFrameCallbackCapture()
                    .exceptionOrNull()
                    ?.message
                if (stopCaptureFailure != null) {
                    nextErrorMessage = stopCaptureFailure
                    nextRecordingStatus = RecordingStatus.Failed(stopCaptureFailure)
                }
                val cancelFailure = cancelPreparedRecorder(reasonForLog = "Preview stop cleanup")
                if (cancelFailure != null) {
                    nextErrorMessage = cancelFailure
                    nextRecordingStatus = RecordingStatus.Failed(cancelFailure)
                } else if (stopCaptureFailure == null && currentRecordingStatus !is RecordingStatus.Failed) {
                    nextRecordingStatus = RecordingStatus.Idle
                }
            }
        }

        closeDetectionSession(clearDetections = true)
        closeGemmaCaptionSession(clearSubtitle = true)

        sessionLog.record("PreviewViewModel", "Stopping camera preview source")
        activeCameraSource().stop()
        activePreviewCameraSource = null
        recoveryManager.markCameraIdle()
        sessionLog.record("PreviewViewModel", "Camera preview source stopped")
        _uiState.value = cameraSourceReadyState(
            errorMessage = nextErrorMessage,
            recordingStatus = nextRecordingStatus
        )
    }

    private suspend fun insertFinishedClip(clip: RecordedClip): String? {
        val insertFailure = runCatching { clipRepository.insert(clip) }
            .exceptionOrNull()
            ?.message
        if (insertFailure != null) {
            sessionLog.record("PreviewViewModel", "Recorded clip insert failed: $insertFailure")
            _uiState.value = _uiState.value.copy(errorMessage = insertFailure)
            return insertFailure
        }

        return refreshRecordedClipsInternal()
    }

    private suspend fun cancelPreparedRecorder(reasonForLog: String): String? {
        return when (val cancelResult = videoRecorder.cancel()) {
            VideoRecorder.CancelResult.Cancelled -> {
                sessionLog.record("PreviewViewModel", "$reasonForLog: recorder cancelled")
                null
            }

            is VideoRecorder.CancelResult.Failed -> {
                if (cancelResult.reason == RECORDING_NOT_STARTED) {
                    null
                } else {
                    sessionLog.record(
                        "PreviewViewModel",
                        "$reasonForLog: recorder cancel failed: ${cancelResult.reason}"
                    )
                    cancelResult.reason
                }
            }
        }
    }

    private fun nextPreviewGeneration(): Long {
        previewGeneration += 1
        return previewGeneration
    }

    private fun invalidatePreviewGeneration() {
        previewGeneration += 1
    }

    private fun isCurrentPreviewGeneration(generation: Long): Boolean {
        return previewGeneration == generation
    }

    private fun updatePreviewZoom(delta: Float, logLabel: String) {
        val uiState = _uiState.value
        if (!uiState.isPreviewRunning) {
            return
        }
        val updatedZoom = (uiState.zoomFactor + delta).coerceIn(minZoomFactor, maxZoomFactor)
        if (updatedZoom == uiState.zoomFactor) {
            return
        }
        sessionLog.record(
            "PreviewViewModel",
            "$logLabel -> ${String.format(Locale.US, "%.2f", updatedZoom)}"
        )
        _uiState.value = applyRecoveryState(uiState.copy(zoomFactor = updatedZoom))
    }

    private fun applyRecoverySnapshot(
        snapshot: RecoverySnapshot,
        baseState: PreviewUiState = _uiState.value
    ) {
        recoverySnapshot = snapshot
        _uiState.value = applyRecoveryState(baseState, snapshot)
    }

    private fun applyRecoveryState(
        baseState: PreviewUiState,
        snapshot: RecoverySnapshot = recoverySnapshot
    ): PreviewUiState {
        return if (snapshot.isSafeMode) {
            baseState.copy(
                canEnableCamera = false,
                canStartPreview = false,
                canStopPreview = false,
                isPreviewRunning = false,
                previewSize = null,
                zoomFactor = minZoomFactor,
                recordVideoEnabled = false,
                objectDetectionEnabled = false,
                transparentHudEnabled = false,
                gemmaSubtitlesEnabled = false,
                gemmaSubtitleText = "",
                recordingStatus = RecordingStatus.Idle,
                isSafeMode = true,
                safeModeReason = snapshot.safeModeReason,
                brokenClipMetadata = snapshot.brokenClipMetadata,
                canChangeRecordVideo = false,
                canChangeObjectDetection = false,
                canChangeTransparentHud = false,
                canChangeGemmaSubtitles = false,
                canChangeCameraSource = false,
                inferenceFps = 0f,
                inferenceBackendLabel = null
            )
        } else {
            baseState.copy(
                isSafeMode = false,
                safeModeReason = snapshot.safeModeReason,
                brokenClipMetadata = snapshot.brokenClipMetadata,
                canChangeRecordVideo = true,
                canChangeObjectDetection = true,
                canChangeTransparentHud = true,
                canChangeGemmaSubtitles = true,
                canChangeCameraSource = !baseState.isPreviewRunning
            )
        }
    }

    private fun GlassesSession.State.toStatusLabel(): String {
        return when (this) {
            GlassesSession.State.Unavailable -> "Очки: недоступны"
            GlassesSession.State.Connecting -> "Очки: подключение"
            GlassesSession.State.Available -> "Очки: доступны"
        }
    }

    private fun enabledCameraState(
        errorMessage: String? = null,
        recordingStatus: RecordingStatus = RecordingStatus.Idle
    ): PreviewUiState {
        return xrealCameraReadyState(errorMessage, recordingStatus)
    }

    private fun idleCameraState(
        errorMessage: String? = null,
        recordingStatus: RecordingStatus = RecordingStatus.Idle
    ): PreviewUiState {
        return xrealCameraIdleState(errorMessage, recordingStatus)
    }

    private fun cameraSourceReadyState(
        errorMessage: String? = null,
        recordingStatus: RecordingStatus = RecordingStatus.Idle
    ): PreviewUiState {
        return when (_uiState.value.selectedCameraSource) {
            CameraSourceKind.XREAL -> xrealCameraReadyState(errorMessage, recordingStatus)
            CameraSourceKind.ANDROID -> androidCameraReadyState(errorMessage, recordingStatus)
        }
    }

    private fun xrealCameraReadyState(
        errorMessage: String? = null,
        recordingStatus: RecordingStatus = RecordingStatus.Idle
    ): PreviewUiState {
        return applyRecoveryState(_uiState.value.copy(
            selectedCameraSource = CameraSourceKind.XREAL,
            cameraStatus = "Камера включена",
            canEnableCamera = false,
            canStartPreview = true,
            canStopPreview = false,
            isPreviewRunning = false,
            previewSize = null,
            zoomFactor = minZoomFactor,
            recordingStatus = recordingStatus,
            detectedObjects = emptyList(),
            inferenceFps = 0f,
            inferenceBackendLabel = null,
            errorMessage = errorMessage
        ))
    }

    private fun xrealCameraIdleState(
        errorMessage: String? = null,
        recordingStatus: RecordingStatus = RecordingStatus.Idle
    ): PreviewUiState {
        return applyRecoveryState(_uiState.value.copy(
            selectedCameraSource = CameraSourceKind.XREAL,
            cameraStatus = "Камера: ожидание",
            canEnableCamera = true,
            canStartPreview = false,
            canStopPreview = false,
            isPreviewRunning = false,
            previewSize = null,
            zoomFactor = minZoomFactor,
            recordingStatus = recordingStatus,
            detectedObjects = emptyList(),
            inferenceFps = 0f,
            inferenceBackendLabel = null,
            errorMessage = errorMessage
        ))
    }

    private fun androidCameraReadyState(
        errorMessage: String? = null,
        recordingStatus: RecordingStatus = RecordingStatus.Idle
    ): PreviewUiState {
        return applyRecoveryState(_uiState.value.copy(
            selectedCameraSource = CameraSourceKind.ANDROID,
            cameraStatus = "Камера телефона готова",
            canEnableCamera = false,
            canStartPreview = true,
            canStopPreview = false,
            isPreviewRunning = false,
            previewSize = null,
            zoomFactor = minZoomFactor,
            recordingStatus = recordingStatus,
            detectedObjects = emptyList(),
            errorMessage = errorMessage
        ))
    }

    private fun cameraSourceBaseState(source: CameraSourceKind): PreviewUiState {
        return when (source) {
            CameraSourceKind.XREAL -> _uiState.value.copy(
                selectedCameraSource = CameraSourceKind.XREAL,
                cameraStatus = "Камера: ожидание",
                canEnableCamera = true,
                canStartPreview = false,
                canStopPreview = false,
                isPreviewRunning = false,
                previewSize = null,
                zoomFactor = minZoomFactor,
                recordingStatus = RecordingStatus.Idle,
                detectedObjects = emptyList(),
                gemmaSubtitleText = "",
                errorMessage = null
            )
            CameraSourceKind.ANDROID -> _uiState.value.copy(
                selectedCameraSource = CameraSourceKind.ANDROID,
                cameraStatus = "Камера телефона готова",
                canEnableCamera = false,
                canStartPreview = true,
                canStopPreview = false,
                isPreviewRunning = false,
                previewSize = null,
                zoomFactor = minZoomFactor,
                recordingStatus = RecordingStatus.Idle,
                detectedObjects = emptyList(),
                gemmaSubtitleText = "",
                errorMessage = null
            )
        }
    }

    private fun previewRunningState(previewSize: PreviewSize): PreviewUiState {
        return applyRecoveryState(_uiState.value.copy(
            cameraStatus = "Просмотр запущен",
            canEnableCamera = false,
            canStartPreview = false,
            canStopPreview = true,
            isPreviewRunning = true,
            previewSize = previewSize,
            zoomFactor = minZoomFactor,
            recordingStatus = RecordingStatus.Idle,
            detectedObjects = emptyList(),
            inferenceFps = 0f,
            inferenceBackendLabel = null,
            errorMessage = null
        ))
    }

    private fun selectedCameraSource(): CameraSource {
        return when (_uiState.value.selectedCameraSource) {
            CameraSourceKind.XREAL -> cameraSource
            CameraSourceKind.ANDROID -> androidCameraSource
        }
    }

    private fun activeCameraSource(): CameraSource {
        return activePreviewCameraSource ?: selectedCameraSource()
    }

    private suspend fun stopCameraSources() {
        cameraSource.stop()
        if (androidCameraSource !== cameraSource) {
            androidCameraSource.stop()
        }
        activePreviewCameraSource = null
    }

    private suspend fun startFrameCallbackCapture(
        target: RecordingInputTarget,
        generation: Long,
        onFailure: ((String) -> Unit)? = null
    ): CameraSource.RecordingStartResult {
        val result = activeCameraSource().startRecording(target)
        return when (result) {
            CameraSource.RecordingStartResult.Started -> {
                if (isCurrentPreviewGeneration(generation)) {
                    hasActiveFrameCallbackCapture = true
                }
                result
            }
            is CameraSource.RecordingStartResult.Failed -> {
                hasActiveFrameCallbackCapture = false
                onFailure?.invoke(result.reason)
                result
            }
        }
    }

    private suspend fun stopActiveFrameCallbackCapture(): Result<Unit> {
        if (!hasActiveFrameCallbackCapture) {
            return Result.success(Unit)
        }
        return runCatching {
            activeCameraSource().stopRecording()
            hasActiveFrameCallbackCapture = false
        }
    }

    private fun combineFrameTargets(
        recorderTarget: RecordingInputTarget?,
        detectionSession: ObjectDetectionSession?,
        gemmaCaptionSession: GemmaCaptionSession?
    ): RecordingInputTarget? {
        val requiresFrameCallbacks = detectionSession != null || gemmaCaptionSession != null
        if (recorderTarget != null && !requiresFrameCallbacks) {
            return recorderTarget
        }
        val callbackTargets = buildList {
            if (recorderTarget != null) {
                val recordingCallbackTarget = recorderTarget as? RecordingInputTarget.FrameCallbackTarget
                    ?: return null
                add(recordingCallbackTarget)
            }
            detectionSession?.inputTarget?.let { add(it) }
            gemmaCaptionSession?.inputTarget?.let { add(it) }
        }
        if (callbackTargets.isEmpty()) {
            return recorderTarget
        }
        return FrameCallbackTargetFanOut.combine(
            callbackTargets
        )
    }

    private fun closeDetectionSession(clearDetections: Boolean) {
        activeDetectionSession?.close()
        activeDetectionSession = null
        if (clearDetections) {
            detectionAnnotationSink.clearDetections()
            _uiState.value = applyRecoveryState(_uiState.value.copy(
                detectedObjects = emptyList(),
                inferenceFps = 0f,
                inferenceBackendLabel = null
            ))
        }
    }

    private fun closeGemmaCaptionSession(clearSubtitle: Boolean) {
        activeGemmaCaptionSession?.close()
        activeGemmaCaptionSession = null
        if (clearSubtitle) {
            _uiState.value = applyRecoveryState(_uiState.value.copy(gemmaSubtitleText = ""))
        }
    }

    private companion object {
        const val RECORDING_NOT_STARTED = "recording_not_started"
        const val GEMMA_MODEL_NOT_CONFIGURED = "Модель Gemma не настроена"
        const val GEMMA_MODEL_DOWNLOADING = "Модель Gemma: загрузка..."
    }
}

internal object NoOpGemmaSubtitlePreferences : GemmaSubtitlePreferences {
    override fun isGemmaSubtitlesEnabled(): Boolean = false

    override fun setGemmaSubtitlesEnabled(enabled: Boolean) = Unit

    override fun getModelPath(): String? = null

    override fun getModelDisplayName(): String? = null

    override fun getCaptionPrompt(): String = DEFAULT_GEMMA_CAPTION_PROMPT

    override fun setCaptionPrompt(prompt: String) = Unit

    override fun setModel(path: String, displayName: String?) = Unit

    override fun clearModel() = Unit
}

internal object NoOpCameraSourcePreferences : CameraSourcePreferences {
    override fun getSelectedCameraSource(): CameraSourceKind = CameraSourceKind.XREAL

    override fun setSelectedCameraSource(source: CameraSourceKind) = Unit
}

internal fun gemmaUnavailableSubtitle(reason: String): String {
    val firstLine = reason.lineSequence()
        .firstOrNull()
        ?.trim()
        .orEmpty()
    if (firstLine.isBlank()) {
        return GEMMA_UNAVAILABLE
    }
    val shortReason = when {
        firstLine.startsWith("Failed to create engine", ignoreCase = true) -> "не удалось создать движок"
        firstLine.length > GEMMA_UNAVAILABLE_REASON_MAX_LENGTH -> {
            firstLine.take(GEMMA_UNAVAILABLE_REASON_MAX_LENGTH - 3).trimEnd() + "..."
        }
        else -> firstLine.replaceFirstChar { character -> character.lowercase() }
    }
    return "$GEMMA_UNAVAILABLE: $shortReason"
}

private const val GEMMA_UNAVAILABLE = "Gemma недоступна"
private const val GEMMA_UNAVAILABLE_REASON_MAX_LENGTH = 64
