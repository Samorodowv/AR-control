package com.example.ar_control.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ar_control.camera.CameraSource
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.diagnostics.SessionLog
import com.example.ar_control.recording.ClipRepository
import com.example.ar_control.recording.RecordedClip
import com.example.ar_control.recording.RecordingPreferences
import com.example.ar_control.recording.RecordingStatus
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
    private val recordingPreferences: RecordingPreferences,
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

    init {
        applyRecoverySnapshot(recoveryManager.snapshot())
        loadInitialRecordingState()
        startGlassesSession()
        observeGlassesState()
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
                _uiState.value = idleCameraState(errorMessage = "USB permission request timed out")
                return@launch
            }

            if (!hasPermission) {
                sessionLog.record("PreviewViewModel", "Control USB permission denied")
                _uiState.value = idleCameraState(errorMessage = "USB permission denied")
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

            when (val result = cameraSource.start(surfaceToken)) {
                is CameraSource.StartResult.Started -> {
                    if (!isCurrentPreviewGeneration(generation)) {
                        sessionLog.record("PreviewViewModel", "Preview start finished after stop; ignoring stale session")
                        runCatching { cameraSource.stop() }
                        return@launch
                    }
                    sessionLog.record("PreviewViewModel", "Preview started successfully")
                    recoveryManager.markPreviewStarted()
                    _uiState.value = previewRunningState(result.previewSize)
                    maybeStartRecording(result.previewSize, generation)
                }

                is CameraSource.StartResult.Failed -> {
                    if (!isCurrentPreviewGeneration(generation)) {
                        sessionLog.record("PreviewViewModel", "Preview start failure arrived after stop; ignoring stale session")
                        return@launch
                    }
                    sessionLog.record("PreviewViewModel", "Preview start failed: ${result.reason}")
                    _uiState.value = enabledCameraState(errorMessage = result.reason)
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

    fun confirmSafeModeExit() {
        viewModelScope.launch {
            sessionLog.record("PreviewViewModel", "Safe mode exit confirmed by user")
            recoveryManager.clearSafeMode()
            applyRecoverySnapshot(
                recoveryManager.snapshot(),
                _uiState.value.copy(
                    cameraStatus = "Camera: idle",
                    canEnableCamera = true,
                    canStartPreview = false,
                    canStopPreview = false,
                    isPreviewRunning = false,
                    previewSize = null,
                    zoomFactor = minZoomFactor,
                    recordVideoEnabled = recordingPreferences.isRecordingEnabled(),
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
        _uiState.value = enabledCameraState(errorMessage = reason)
    }

    fun onEnableCameraBlocked(reason: String) {
        sessionLog.record("PreviewViewModel", "Enable camera blocked: $reason")
        _uiState.value = idleCameraState(errorMessage = reason)
    }

    override fun onCleared() {
        cleanupScope.launch {
            sessionLog.record("PreviewViewModel", "Clearing preview resources")
            runCatching { cameraSource.stopRecording() }
            runCatching { videoRecorder.cancel() }
            runCatching { cameraSource.stop() }
            runCatching { glassesSession.stop() }
            runCatching { recoveryManager.markCameraIdle() }
        }
        super.onCleared()
    }

    private fun loadInitialRecordingState() {
        val recordVideoEnabled = recordingPreferences.isRecordingEnabled()
        _uiState.value = applyRecoveryState(_uiState.value.copy(recordVideoEnabled = recordVideoEnabled))

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

    private suspend fun maybeStartRecording(previewSize: PreviewSize, generation: Long) {
        if (!_uiState.value.recordVideoEnabled || !isCurrentPreviewGeneration(generation)) {
            return
        }

        _uiState.value = applyRecoveryState(_uiState.value.copy(
            recordingStatus = RecordingStatus.Starting,
            errorMessage = null
        ))

        when (val recorderResult = videoRecorder.start(previewSize)) {
            is VideoRecorder.StartResult.Started -> {
                if (!isCurrentPreviewGeneration(generation)) {
                    sessionLog.record("PreviewViewModel", "Recorder prepared after preview stop; cancelling stale session")
                    cancelPreparedRecorder(reasonForLog = "Stale preview generation after recorder start")
                    return
                }
                recoveryManager.markRecordingStarted(
                    outputFilePath = recorderResult.outputFilePath,
                    width = previewSize.width,
                    height = previewSize.height,
                    mimeType = "video/mp4"
                )
                when (val captureResult = cameraSource.startRecording(recorderResult.inputTarget)) {
                    CameraSource.RecordingStartResult.Started -> {
                        if (!isCurrentPreviewGeneration(generation)) {
                            sessionLog.record("PreviewViewModel", "Capture started after preview stop; cancelling stale session")
                            runCatching { cameraSource.stopRecording() }
                            cancelPreparedRecorder(reasonForLog = "Stale preview generation after capture start")
                            return
                        }
                        sessionLog.record("PreviewViewModel", "Recording started successfully")
                        _uiState.value = applyRecoveryState(_uiState.value.copy(
                            recordingStatus = RecordingStatus.Recording,
                            errorMessage = null
                        ))
                    }

                    is CameraSource.RecordingStartResult.Failed -> {
                        if (!isCurrentPreviewGeneration(generation)) {
                            sessionLog.record("PreviewViewModel", "Capture failure arrived after preview stop; cancelling stale session")
                            cancelPreparedRecorder(reasonForLog = "Stale preview generation after capture failure")
                            return
                        }
                        sessionLog.record(
                            "PreviewViewModel",
                            "Recording capture failed: ${captureResult.reason}"
                        )
                        cancelPreparedRecorder(reasonForLog = "Capture start failed")
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
                val stopRecordingFailure = runCatching { cameraSource.stopRecording() }
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
                val cancelFailure = cancelPreparedRecorder(reasonForLog = "Preview stop cleanup")
                if (cancelFailure != null) {
                    nextErrorMessage = cancelFailure
                    nextRecordingStatus = RecordingStatus.Failed(cancelFailure)
                } else if (currentRecordingStatus !is RecordingStatus.Failed) {
                    nextRecordingStatus = RecordingStatus.Idle
                }
            }
        }

        sessionLog.record("PreviewViewModel", "Stopping camera preview source")
        cameraSource.stop()
        recoveryManager.markCameraIdle()
        sessionLog.record("PreviewViewModel", "Camera preview source stopped")
        _uiState.value = enabledCameraState(
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
                recordingStatus = RecordingStatus.Idle,
                isSafeMode = true,
                safeModeReason = snapshot.safeModeReason,
                brokenClipMetadata = snapshot.brokenClipMetadata,
                canChangeRecordVideo = false
            )
        } else {
            baseState.copy(
                isSafeMode = false,
                safeModeReason = snapshot.safeModeReason,
                brokenClipMetadata = snapshot.brokenClipMetadata,
                canChangeRecordVideo = true
            )
        }
    }

    private fun GlassesSession.State.toStatusLabel(): String {
        return when (this) {
            GlassesSession.State.Unavailable -> "Glasses: unavailable"
            GlassesSession.State.Connecting -> "Glasses: connecting"
            GlassesSession.State.Available -> "Glasses: available"
        }
    }

    private fun enabledCameraState(
        errorMessage: String? = null,
        recordingStatus: RecordingStatus = RecordingStatus.Idle
    ): PreviewUiState {
        return applyRecoveryState(_uiState.value.copy(
            cameraStatus = "Camera enabled",
            canEnableCamera = false,
            canStartPreview = true,
            canStopPreview = false,
            isPreviewRunning = false,
            previewSize = null,
            zoomFactor = minZoomFactor,
            recordingStatus = recordingStatus,
            errorMessage = errorMessage
        ))
    }

    private fun idleCameraState(
        errorMessage: String? = null,
        recordingStatus: RecordingStatus = RecordingStatus.Idle
    ): PreviewUiState {
        return applyRecoveryState(_uiState.value.copy(
            cameraStatus = "Camera: idle",
            canEnableCamera = true,
            canStartPreview = false,
            canStopPreview = false,
            isPreviewRunning = false,
            previewSize = null,
            zoomFactor = minZoomFactor,
            recordingStatus = recordingStatus,
            errorMessage = errorMessage
        ))
    }

    private fun previewRunningState(previewSize: PreviewSize): PreviewUiState {
        return applyRecoveryState(_uiState.value.copy(
            cameraStatus = "Preview running",
            canEnableCamera = false,
            canStartPreview = false,
            canStopPreview = true,
            isPreviewRunning = true,
            previewSize = previewSize,
            zoomFactor = minZoomFactor,
            recordingStatus = RecordingStatus.Idle,
            errorMessage = null
        ))
    }
    private companion object {
        const val RECORDING_NOT_STARTED = "recording_not_started"
    }
}
