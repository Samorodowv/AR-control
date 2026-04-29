package com.example.ar_control

import android.content.Context
import android.content.Intent
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.google.android.material.checkbox.MaterialCheckBox
import com.example.ar_control.recording.RecordingStatus
import com.example.ar_control.ui.preview.PreviewUiState
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@Config(application = ArControlApp::class)
@RunWith(RobolectricTestRunner::class)
class MainActivityUiLogicTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun previewRecordingStatusMessage_returnsFullscreenRecordingLabels() {
        assertEquals(
            "Recording starting",
            PreviewUiState(
                isPreviewRunning = true,
                recordingStatus = RecordingStatus.Starting
            ).previewRecordingStatusMessage(context)
        )
        assertEquals(
            "Recording video",
            PreviewUiState(
                isPreviewRunning = true,
                recordingStatus = RecordingStatus.Recording
            ).previewRecordingStatusMessage(context)
        )
        assertEquals(
            "Recording failed: muxer_failed",
            PreviewUiState(
                isPreviewRunning = true,
                recordingStatus = RecordingStatus.Failed("muxer_failed")
            ).previewRecordingStatusMessage(context)
        )
    }

    @Test
    fun previewRecordingStatusMessage_hidesWhenPreviewNotRunningOrIdle() {
        assertNull(PreviewUiState().previewRecordingStatusMessage(context))
        assertNull(
            PreviewUiState(
                isPreviewRunning = false,
                recordingStatus = RecordingStatus.Recording
            ).previewRecordingStatusMessage(context)
        )
    }

    @Test
    fun previewTextureAlpha_hidesPreviewOnlyWhenHudModeIsRunning() {
        assertEquals(0f, previewTextureAlpha(isPreviewRunning = true, transparentHudEnabled = true))
        assertEquals(1f, previewTextureAlpha(isPreviewRunning = true, transparentHudEnabled = false))
        assertEquals(1f, previewTextureAlpha(isPreviewRunning = false, transparentHudEnabled = true))
    }

    @Test
    fun canLaunchIntent_detectsWhetherAnActivityCanHandleTheIntent() {
        assertTrue(
            canLaunchIntent(
                context.packageManager,
                Intent(context, MainActivity::class.java)
            )
        )
        assertFalse(
            canLaunchIntent(
                context.packageManager,
                Intent("com.example.ar_control.NO_HANDLER")
            )
        )
    }

    @Test
    fun recordedClipItemLayout_inflatesUnderAppTheme() {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AR_Control)
        val view = LayoutInflater.from(themedContext).inflate(
            R.layout.item_recorded_clip,
            FrameLayout(themedContext),
            false
        )

        assertNotNull(view)
    }

    @Test
    fun mainActivityLayout_containsTransparentHudCheckbox() {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AR_Control)
        val view = LayoutInflater.from(themedContext).inflate(
            R.layout.activity_main,
            FrameLayout(themedContext),
            false
        )

        assertNotNull(view.findViewById<MaterialCheckBox>(R.id.transparentHudCheckbox))
    }
}
