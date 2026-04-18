package com.example.ar_control

import com.example.ar_control.ui.preview.PreviewUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSurfaceControllerTest {

    @Test
    fun clickStartWithoutSurface_queuesStartUntilSurfaceArrives() {
        val controller = PreviewSurfaceController()
        val enabledState = PreviewUiState(canEnableCamera = false, canStartPreview = true)

        val clickAction = controller.onStartPreviewClicked(enabledState)

        assertNull(clickAction)
        assertFalse(controller.isStartButtonEnabled(enabledState))

        val availableAction = controller.onSurfaceAvailable(enabledState)

        assertEquals(PreviewSurfaceController.Action.StartPreview, availableAction)
        assertTrue(controller.isStartButtonEnabled(enabledState))
    }

    @Test
    fun surfaceDestroyedWhilePreviewRunning_requestsStopAndDisablesStart() {
        val controller = PreviewSurfaceController()
        val runningState = PreviewUiState(
            canEnableCamera = false,
            canStartPreview = false,
            canStopPreview = true,
            isPreviewRunning = true
        )

        controller.onSurfaceAvailable(runningState)
        val destroyAction = controller.onSurfaceDestroyed(runningState)

        assertEquals(PreviewSurfaceController.Action.StopPreview, destroyAction)
        assertFalse(controller.isStartButtonEnabled(runningState))
    }

    @Test
    fun uiStateWithoutStartCapability_clearsQueuedStart() {
        val controller = PreviewSurfaceController()
        val enabledState = PreviewUiState(canEnableCamera = false, canStartPreview = true)
        val failedState = PreviewUiState(
            canEnableCamera = true,
            canStartPreview = false,
            errorMessage = "enable_failed"
        )

        controller.onStartPreviewClicked(enabledState)
        controller.onUiStateChanged(failedState)
        val availableAction = controller.onSurfaceAvailable(failedState)

        assertNull(availableAction)
        assertFalse(controller.isStartButtonEnabled(failedState))
    }
}
