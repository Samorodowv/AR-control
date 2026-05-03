package com.example.ar_control

import android.content.Context
import android.content.Intent
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
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
            "Запись запускается",
            PreviewUiState(
                isPreviewRunning = true,
                recordingStatus = RecordingStatus.Starting
            ).previewRecordingStatusMessage(context)
        )
        assertEquals(
            "Идет видеозапись",
            PreviewUiState(
                isPreviewRunning = true,
                recordingStatus = RecordingStatus.Recording
            ).previewRecordingStatusMessage(context)
        )
        assertEquals(
            "Ошибка записи: muxer_failed",
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
    fun mainLayout_containsDownloadGemmaModelButton() {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AR_Control)
        val view = LayoutInflater.from(themedContext).inflate(
            R.layout.activity_main,
            FrameLayout(themedContext),
            false
        )

        assertNotNull(view.findViewById<View>(R.id.downloadGemmaModelButton))
    }

    @Test
    fun mainActivityLayout_containsTransparentHudCheckbox() {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AR_Control)
        val view = LayoutInflater.from(themedContext).inflate(
            R.layout.activity_main,
            FrameLayout(themedContext),
            false
        )

        assertNotNull(view.findViewById<View>(R.id.transparentHudCheckbox))
    }

    @Test
    fun mainLayout_containsFaceRecognitionStatusOverlay() {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AR_Control)
        val view = LayoutInflater.from(themedContext).inflate(
            R.layout.activity_main,
            FrameLayout(themedContext),
            false
        )

        assertNotNull(view.findViewById<View>(R.id.faceRecognitionStatusText))
    }

    @Test
    fun previewTextureAlpha_hidesPreviewOnlyWhenHudModeIsRunning() {
        assertEquals(1f, previewTextureAlpha(isPreviewRunning = false, transparentHudEnabled = false))
        assertEquals(1f, previewTextureAlpha(isPreviewRunning = false, transparentHudEnabled = true))
        assertEquals(1f, previewTextureAlpha(isPreviewRunning = true, transparentHudEnabled = false))
        assertEquals(0f, previewTextureAlpha(isPreviewRunning = true, transparentHudEnabled = true))
    }

    @Test
    fun mainLayout_containsEditableGemmaPromptField() {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AR_Control)
        val view = LayoutInflater.from(themedContext).inflate(
            R.layout.activity_main,
            FrameLayout(themedContext),
            false
        )

        assertNotNull(view.findViewById<View>(R.id.gemmaPromptEditText))
    }

    @Test
    fun mainLayout_containsApplyGemmaPromptButton() {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AR_Control)
        val view = LayoutInflater.from(themedContext).inflate(
            R.layout.activity_main,
            FrameLayout(themedContext),
            false
        )

        assertNotNull(view.findViewById<View>(R.id.applyGemmaPromptButton))
    }

    @Test
    fun mainLayout_containsCameraSourceSelector() {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AR_Control)
        val view = LayoutInflater.from(themedContext).inflate(
            R.layout.activity_main,
            FrameLayout(themedContext),
            false
        )

        assertNotNull(view.findViewById<View>(R.id.cameraSourceRadioGroup))
        assertNotNull(view.findViewById<View>(R.id.xrealCameraSourceRadio))
        assertNotNull(view.findViewById<View>(R.id.androidCameraSourceRadio))
    }

    @Test
    fun gemmaModelStatusMessage_ignoresDownloadProgress() {
        assertEquals(
            "Модель Gemma: gemma-4-E2B-it.litertlm",
            PreviewUiState(
                gemmaModelDisplayName = "gemma-4-E2B-it.litertlm",
                gemmaModelDownloadProgressText = "Модель Gemma: загрузка 50%"
            ).gemmaModelStatusMessage(context)
        )
    }

    @Test
    fun gemmaModelDownloadButtonText_showsCancelActionWithProgress() {
        assertEquals(
            "Отменить загрузку Gemma: 50%",
            PreviewUiState(
                gemmaModelDisplayName = "gemma-4-E2B-it.litertlm",
                isGemmaModelDownloadInProgress = true,
                gemmaModelDownloadProgressText = "Модель Gemma: загрузка 50%"
            ).gemmaModelDownloadButtonText(context)
        )
    }

    @Test
    fun gemmaModelDownloadButtonText_usesDefaultWhenNotDownloading() {
        assertEquals(
            "Скачать модель Gemma",
            PreviewUiState(
                gemmaModelDisplayName = "gemma-4-E2B-it.litertlm"
            ).gemmaModelDownloadButtonText(context)
        )
    }

    @Test
    fun mainLayout_usesModerateGemmaSubtitleText() {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AR_Control)
        val view = LayoutInflater.from(themedContext).inflate(
            R.layout.activity_main,
            FrameLayout(themedContext),
            false
        )
        val subtitle = view.findViewById<TextView>(R.id.gemmaSubtitleText)
        val expectedTextSizePx = 16f * themedContext.resources.displayMetrics.scaledDensity

        assertEquals(expectedTextSizePx, subtitle.textSize, 0.5f)
    }

    @Test
    fun mainLayout_allowsLongerGemmaSubtitles() {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AR_Control)
        val view = LayoutInflater.from(themedContext).inflate(
            R.layout.activity_main,
            FrameLayout(themedContext),
            false
        )
        val subtitle = view.findViewById<TextView>(R.id.gemmaSubtitleText)

        assertEquals(8, subtitle.maxLines)
    }
}
