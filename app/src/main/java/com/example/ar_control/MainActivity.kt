package com.example.ar_control

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.camera.TextureViewSurfaceToken
import com.example.ar_control.databinding.ActivityMainBinding
import com.example.ar_control.di.AppContainer
import com.example.ar_control.performance.FramesPerSecondTracker
import com.example.ar_control.recording.RecordedClip
import com.example.ar_control.recording.RecordingStatus
import com.example.ar_control.ui.clips.RecordedClipAdapter
import com.example.ar_control.ui.clips.RecordedClipListItem
import com.example.ar_control.ui.preview.PreviewUiState
import com.example.ar_control.ui.preview.PreviewViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private enum class CameraPermissionRequestAction {
        EnableCamera,
        StartPreview
    }

    private lateinit var binding: ActivityMainBinding
    private val appContainer: AppContainer by lazy { (application as ArControlApp).appContainer }
    private val surfaceController = PreviewSurfaceController()
    private var latestUiState = PreviewUiState()
    private var pendingCameraPermissionAction: CameraPermissionRequestAction? = null
    private var isImmersivePreviewEnabled = false
    private var lastAppliedPreviewSize: PreviewSize? = null
    private var lastAppliedZoomFactor: Float = 1.0f
    private val previewFpsTracker = FramesPerSecondTracker()
    private var lastPreviewFps: Float? = null
    private lateinit var previewBackPressedCallback: OnBackPressedCallback
    private lateinit var recordedClipAdapter: RecordedClipAdapter
    private lateinit var recordVideoCheckedChangeListener: CompoundButton.OnCheckedChangeListener
    private lateinit var objectDetectionCheckedChangeListener: CompoundButton.OnCheckedChangeListener
    private val previewBackButtonBaseMargin by lazy {
        resources.getDimensionPixelSize(R.dimen.preview_back_button_margin)
    }
    private val previewStatusBaseMargin by lazy {
        resources.getDimensionPixelSize(R.dimen.preview_status_margin_top)
    }
    private val previewViewModel: PreviewViewModel by viewModels {
        appContainer.previewViewModelFactory
    }
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingCameraPermissionAction
        pendingCameraPermissionAction = null
        appContainer.sessionLog.record(
            "MainActivity",
            "Runtime CAMERA permission ${if (granted) "granted" else "denied"} for ${action?.toLogLabel() ?: "unknown_action"}"
        )
        when (action) {
            CameraPermissionRequestAction.EnableCamera -> {
                if (granted) {
                    previewViewModel.enableCamera()
                } else {
                    previewViewModel.onEnableCameraBlocked("Camera permission denied")
                }
            }

            CameraPermissionRequestAction.StartPreview -> {
                if (granted) {
                    startPreviewFromUi()
                } else {
                    previewViewModel.onPreviewStartBlocked("Camera permission denied")
                }
            }

            null -> {
                appContainer.sessionLog.record(
                    "MainActivity",
                    "Ignoring CAMERA permission callback without a pending action"
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        appContainer.sessionLog.record(
            "MainActivity",
            "Initial runtime CAMERA permission: ${if (hasCameraPermission()) "granted" else "denied"}"
        )

        recordedClipAdapter = RecordedClipAdapter(previewViewModel::selectClip)
        recordVideoCheckedChangeListener =
            CompoundButton.OnCheckedChangeListener { _, isChecked ->
                appContainer.sessionLog.record(
                    "MainActivity",
                    "Record video checkbox changed: $isChecked"
                )
                previewViewModel.setRecordVideoEnabled(isChecked)
            }
        objectDetectionCheckedChangeListener =
            CompoundButton.OnCheckedChangeListener { _, isChecked ->
                appContainer.sessionLog.record(
                    "MainActivity",
                    "Object detection checkbox changed: $isChecked"
                )
                previewViewModel.setObjectDetectionEnabled(isChecked)
            }

        previewBackPressedCallback = onBackPressedDispatcher.addCallback(this, false) {
            appContainer.sessionLog.record("MainActivity", "Hardware back pressed in fullscreen preview")
            exitFullscreenPreview("hardware_back")
        }

        configurePreviewInsets()
        configureRecordedClipList()

        binding.enableCameraButton.setOnClickListener {
            requestCameraPermissionThenEnableCamera()
        }
        binding.startPreviewButton.setOnClickListener {
            requestCameraPermissionThenStartPreview()
        }
        binding.stopPreviewButton.setOnClickListener {
            previewViewModel.stopPreview()
        }
        binding.previewBackButton.setOnClickListener {
            appContainer.sessionLog.record("MainActivity", "Fullscreen preview back button tapped")
            exitFullscreenPreview("preview_back_button")
        }
        binding.shareLogsButton.setOnClickListener {
            shareLogs()
        }
        binding.recordVideoCheckbox.setOnCheckedChangeListener(recordVideoCheckedChangeListener)
        binding.objectDetectionCheckbox.setOnCheckedChangeListener(objectDetectionCheckedChangeListener)
        binding.openClipButton.setOnClickListener {
            latestUiState.selectedClip?.let { clip ->
                openClipSafely(clip)
            }
        }
        binding.shareClipButton.setOnClickListener {
            latestUiState.selectedClip?.let { clip ->
                val shareIntent = appContainer.clipFileSharer.buildShareIntent(clip)
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_clip)))
            }
        }
        binding.deleteClipButton.setOnClickListener {
            showDeleteConfirmationForSelectedClip()
        }
        binding.reEnableCameraTestingButton.setOnClickListener {
            showSafeModeExitConfirmation()
        }
        binding.previewTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                appContainer.sessionLog.record(
                    "MainActivity",
                    "Preview surface available: ${width}x$height"
                )
                handleSurfaceAction(surfaceController.onSurfaceAvailable(latestUiState))
                render(latestUiState)
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                appContainer.sessionLog.record("MainActivity", "Preview surface destroyed")
                handleSurfaceAction(surfaceController.onSurfaceDestroyed(latestUiState))
                render(latestUiState)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (!latestUiState.isPreviewRunning) {
                    return
                }
                lastPreviewFps = previewFpsTracker.recordFrame(System.nanoTime())
                renderDetectionHud(latestUiState)
            }
        }
        if (binding.previewTextureView.isAvailable) {
            appContainer.sessionLog.record("MainActivity", "Preview surface already available")
            handleSurfaceAction(surfaceController.onSurfaceAvailable(latestUiState))
        }
        render(latestUiState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                previewViewModel.uiState.collect(::render)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (latestUiState.isPreviewRunning) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        previewViewModel.zoomInPreview()
                    }
                    return true
                }

                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        previewViewModel.zoomOutPreview()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun render(uiState: PreviewUiState) {
        latestUiState = uiState
        surfaceController.onUiStateChanged(uiState)
        binding.glassesStatusText.text = uiState.glassesStatus
        binding.cameraStatusText.text = uiState.errorMessage ?: uiState.cameraStatus
        binding.enableCameraButton.isEnabled = uiState.canEnableCamera
        binding.startPreviewButton.isEnabled = surfaceController.isStartButtonEnabled(uiState)
        binding.stopPreviewButton.isEnabled = uiState.canStopPreview
        renderRecordVideoCheckbox(uiState.recordVideoEnabled)
        renderObjectDetectionCheckbox(uiState.objectDetectionEnabled)
        binding.recordVideoCheckbox.isEnabled = uiState.canChangeRecordVideo
        binding.objectDetectionCheckbox.isEnabled = uiState.canChangeObjectDetection
        binding.openClipButton.isEnabled = uiState.canOpenSelectedClip
        binding.shareClipButton.isEnabled = uiState.canShareSelectedClip
        binding.deleteClipButton.isEnabled = uiState.canDeleteSelectedClip
        binding.emptyRecordedClipsText.visibility =
            if (uiState.recordedClips.isEmpty()) View.VISIBLE else View.GONE
        recordedClipAdapter.submitList(uiState.recordedClips.map(::toRecordedClipListItem))
        renderRecoveryState(uiState)
        renderPreviewRecordingStatus(uiState)
        previewBackPressedCallback.isEnabled = uiState.isPreviewRunning
        binding.controlsContainer.visibility = if (uiState.isPreviewRunning) View.GONE else View.VISIBLE
        binding.previewContainer.alpha = if (uiState.isPreviewRunning) 1f else 0f
        binding.previewContainer.isClickable = uiState.isPreviewRunning
        binding.previewContainer.isFocusable = uiState.isPreviewRunning
        binding.detectionOverlayView.visibility = if (uiState.isPreviewRunning) View.VISIBLE else View.INVISIBLE
        binding.detectionOverlayView.setDetections(uiState.detectedObjects)
        renderDetectionHud(uiState)
        binding.previewBackButton.visibility = if (uiState.isPreviewRunning) View.VISIBLE else View.INVISIBLE
        binding.previewBackButton.isEnabled = uiState.isPreviewRunning

        updatePreviewAspectRatio(uiState.previewSize)
        updatePreviewZoom(uiState.zoomFactor)
        updateImmersivePreview(uiState.isPreviewRunning)
    }

    private fun handleSurfaceAction(action: PreviewSurfaceController.Action?) {
        when (action) {
            PreviewSurfaceController.Action.StartPreview -> {
                previewViewModel.startPreview(TextureViewSurfaceToken(binding.previewTextureView))
            }

            PreviewSurfaceController.Action.StopPreview -> {
                previewViewModel.stopPreview()
            }

            null -> Unit
        }
    }

    private fun requestCameraPermissionThenStartPreview() {
        if (hasCameraPermission()) {
            appContainer.sessionLog.record(
                "MainActivity",
                "Runtime CAMERA permission already granted for start preview"
            )
            startPreviewFromUi()
            return
        }
        pendingCameraPermissionAction = CameraPermissionRequestAction.StartPreview
        appContainer.sessionLog.record(
            "MainActivity",
            "Requesting runtime CAMERA permission for start preview"
        )
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun requestCameraPermissionThenEnableCamera() {
        if (hasCameraPermission()) {
            appContainer.sessionLog.record(
                "MainActivity",
                "Runtime CAMERA permission already granted for enable camera"
            )
            previewViewModel.enableCamera()
            return
        }
        pendingCameraPermissionAction = CameraPermissionRequestAction.EnableCamera
        appContainer.sessionLog.record(
            "MainActivity",
            "Requesting runtime CAMERA permission for enable camera"
        )
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun configureRecordedClipList() {
        binding.recordedClipsRecyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.recordedClipsRecyclerView.adapter = recordedClipAdapter
    }

    private fun renderRecordVideoCheckbox(recordVideoEnabled: Boolean) {
        if (binding.recordVideoCheckbox.isChecked == recordVideoEnabled) {
            return
        }
        binding.recordVideoCheckbox.setOnCheckedChangeListener(null)
        binding.recordVideoCheckbox.isChecked = recordVideoEnabled
        binding.recordVideoCheckbox.setOnCheckedChangeListener(recordVideoCheckedChangeListener)
    }

    private fun renderObjectDetectionCheckbox(objectDetectionEnabled: Boolean) {
        if (binding.objectDetectionCheckbox.isChecked == objectDetectionEnabled) {
            return
        }
        binding.objectDetectionCheckbox.setOnCheckedChangeListener(null)
        binding.objectDetectionCheckbox.isChecked = objectDetectionEnabled
        binding.objectDetectionCheckbox.setOnCheckedChangeListener(objectDetectionCheckedChangeListener)
    }

    private fun renderRecoveryState(uiState: PreviewUiState) {
        binding.recoveryCard.visibility = if (uiState.isSafeMode) View.VISIBLE else View.GONE
        if (!uiState.isSafeMode) {
            binding.recoveryClipMetadataText.visibility = View.GONE
            binding.recoveryClipMetadataText.text = ""
            binding.recoveryMessageText.text = getString(R.string.recovery_mode_message)
            return
        }

        val recoveryMessage = buildString {
            append(getString(R.string.recovery_mode_message))
            uiState.safeModeReason?.let { reason ->
                append('\n')
                append(reason)
            }
        }
        binding.recoveryMessageText.text = recoveryMessage
        val brokenClipSummary = uiState.brokenClipMetadata?.toSummaryString()
        if (brokenClipSummary.isNullOrBlank()) {
            binding.recoveryClipMetadataText.visibility = View.GONE
            binding.recoveryClipMetadataText.text = ""
        } else {
            binding.recoveryClipMetadataText.visibility = View.VISIBLE
            binding.recoveryClipMetadataText.text =
                getString(R.string.broken_clip_metadata, brokenClipSummary)
        }
    }

    private fun startPreviewFromUi() {
        handleSurfaceAction(surfaceController.onStartPreviewClicked(latestUiState))
        render(latestUiState)
    }

    private fun exitFullscreenPreview(source: String) {
        appContainer.sessionLog.record("MainActivity", "Exit fullscreen preview requested via $source")
        previewViewModel.stopPreview()
    }

    private fun updatePreviewAspectRatio(previewSize: PreviewSize?) {
        if (previewSize == null) {
            if (lastAppliedPreviewSize != null) {
                appContainer.sessionLog.record("MainActivity", "Clearing preview aspect ratio")
                lastAppliedPreviewSize = null
            }
            binding.previewTextureView.clearContentAspectRatio()
            binding.detectionOverlayView.clearContentAspectRatio()
            return
        }
        if (lastAppliedPreviewSize == previewSize) {
            return
        }
        lastAppliedPreviewSize = previewSize
        binding.previewTextureView.setContentAspectRatio(previewSize.width, previewSize.height)
        binding.detectionOverlayView.setContentAspectRatio(previewSize.width, previewSize.height)
        appContainer.sessionLog.record(
            "MainActivity",
            "Applying fit-center preview aspect ratio ${previewSize.width}x${previewSize.height}"
        )
    }

    private fun renderDetectionHud(uiState: PreviewUiState) {
        if (!uiState.isPreviewRunning) {
            previewFpsTracker.reset()
            lastPreviewFps = null
        }
        binding.detectionOverlayView.setHud(
            previewFps = lastPreviewFps,
            inferenceFps = uiState.inferenceFps,
            inferenceBackendLabel = uiState.inferenceBackendLabel
        )
    }

    private fun updatePreviewZoom(zoomFactor: Float) {
        if (lastAppliedZoomFactor == zoomFactor) {
            return
        }
        lastAppliedZoomFactor = zoomFactor
        binding.previewTextureView.setZoomFactor(zoomFactor)
        binding.detectionOverlayView.setZoomFactor(zoomFactor)
        appContainer.sessionLog.record(
            "MainActivity",
            "Applying preview zoom scale ${String.format(Locale.US, "%.2f", zoomFactor)}"
        )
    }

    private fun updateImmersivePreview(enabled: Boolean) {
        if (isImmersivePreviewEnabled == enabled) {
            return
        }
        isImmersivePreviewEnabled = enabled

        WindowCompat.setDecorFitsSystemWindows(window, !enabled)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (enabled) {
            appContainer.sessionLog.record("MainActivity", "enter_fullscreen_preview")
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            appContainer.sessionLog.record("MainActivity", "exit_fullscreen_preview")
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun configurePreviewInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.previewBackButton) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updateLayoutParams<FrameLayout.LayoutParams> {
                leftMargin = previewBackButtonBaseMargin + insets.left
                topMargin = previewBackButtonBaseMargin + insets.top
            }
            windowInsets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.previewRecordingStatusText) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = previewStatusBaseMargin + insets.top
            }
            windowInsets
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun CameraPermissionRequestAction.toLogLabel(): String {
        return when (this) {
            CameraPermissionRequestAction.EnableCamera -> "enable_camera"
            CameraPermissionRequestAction.StartPreview -> "start_preview"
        }
    }

    private fun shareLogs() {
        appContainer.sessionLog.record("MainActivity", "Share logs tapped")
        val report = appContainer.diagnosticsReportBuilder.build(
            uiState = latestUiState,
            entries = appContainer.sessionLog.snapshot()
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_logs_subject))
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_logs)))
    }

    private fun showDeleteConfirmationForSelectedClip() {
        val clip = latestUiState.selectedClip ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_clip)
            .setMessage(getString(R.string.delete_clip_confirmation, clip.id))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_clip) { _, _ ->
                previewViewModel.deleteSelectedClip()
            }
            .show()
    }

    private fun showSafeModeExitConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.re_enable_camera_testing_title)
            .setMessage(R.string.re_enable_camera_testing_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.re_enable_camera_testing) { _, _ ->
                previewViewModel.confirmSafeModeExit()
            }
            .show()
    }

    private fun renderPreviewRecordingStatus(uiState: PreviewUiState) {
        val message = uiState.previewRecordingStatusMessage(this)
        binding.previewRecordingStatusText.text = message.orEmpty()
        binding.previewRecordingStatusText.visibility =
            if (message == null) View.GONE else View.VISIBLE
    }

    private fun openClipSafely(clip: RecordedClip) {
        val openIntent = appContainer.clipFileSharer.buildOpenIntent(clip)
        if (!canLaunchIntent(packageManager, openIntent)) {
            appContainer.sessionLog.record(
                "MainActivity",
                "Open clip blocked: no activity available for ${clip.id}"
            )
            Toast.makeText(this, R.string.open_clip_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(openIntent)
        } catch (_: ActivityNotFoundException) {
            appContainer.sessionLog.record(
                "MainActivity",
                "Open clip failed after resolve: ${clip.id}"
            )
            Toast.makeText(this, R.string.open_clip_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toRecordedClipListItem(clip: RecordedClip): RecordedClipListItem {
        val title = DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT
        ).format(Date(clip.createdAtEpochMillis))
        val subtitle = getString(
            R.string.clip_details,
            formatDuration(clip.durationMillis),
            clip.width,
            clip.height
        )
        return RecordedClipListItem(
            id = clip.id,
            title = title,
            subtitle = subtitle,
            isSelected = clip.id == latestUiState.selectedClipId
        )
    }

    private fun formatDuration(durationMillis: Long): String {
        val totalSeconds = durationMillis / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

internal fun PreviewUiState.previewRecordingStatusMessage(context: Context): String? {
    if (!isPreviewRunning) {
        return null
    }
    return when (val status = recordingStatus) {
        RecordingStatus.Idle -> null
        RecordingStatus.Starting -> context.getString(R.string.preview_recording_starting)
        RecordingStatus.Recording -> context.getString(R.string.preview_recording_active)
        RecordingStatus.Finalizing -> context.getString(R.string.preview_recording_finalizing)
        is RecordingStatus.Failed -> {
            context.getString(R.string.preview_recording_failed, status.reason)
        }
    }
}

internal fun canLaunchIntent(packageManager: PackageManager, intent: Intent): Boolean {
    return intent.resolveActivity(packageManager) != null
}
