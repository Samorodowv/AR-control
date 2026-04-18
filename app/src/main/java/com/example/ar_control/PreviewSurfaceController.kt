package com.example.ar_control

import com.example.ar_control.ui.preview.PreviewUiState

class PreviewSurfaceController {

    private var isSurfaceAvailable = false
    private var hasQueuedStart = false

    fun onUiStateChanged(uiState: PreviewUiState) {
        if (!uiState.canStartPreview || uiState.isPreviewRunning) {
            hasQueuedStart = false
        }
    }

    fun onStartPreviewClicked(uiState: PreviewUiState): Action? {
        if (!uiState.canStartPreview || uiState.isPreviewRunning) {
            return null
        }

        return if (isSurfaceAvailable) {
            Action.StartPreview
        } else {
            hasQueuedStart = true
            null
        }
    }

    fun onSurfaceAvailable(uiState: PreviewUiState): Action? {
        isSurfaceAvailable = true
        return if (hasQueuedStart && uiState.canStartPreview && !uiState.isPreviewRunning) {
            hasQueuedStart = false
            Action.StartPreview
        } else {
            null
        }
    }

    fun onSurfaceDestroyed(uiState: PreviewUiState): Action? {
        isSurfaceAvailable = false
        hasQueuedStart = false
        return if (uiState.isPreviewRunning) {
            Action.StopPreview
        } else {
            null
        }
    }

    fun isStartButtonEnabled(uiState: PreviewUiState): Boolean {
        return uiState.canStartPreview && isSurfaceAvailable && !hasQueuedStart
    }

    sealed interface Action {
        data object StartPreview : Action
        data object StopPreview : Action
    }
}
