package com.example.ar_control.gemma

import kotlinx.coroutines.flow.Flow

interface GemmaModelDownloadScheduler {
    val downloadState: Flow<GemmaModelDownloadWorkState>

    fun enqueueDownload()

    fun cancelDownload()
}

sealed interface GemmaModelDownloadWorkState {
    data object Idle : GemmaModelDownloadWorkState
    data class Running(
        val progress: GemmaModelDownloadProgress? = null
    ) : GemmaModelDownloadWorkState
    data class Completed(
        val displayName: String?
    ) : GemmaModelDownloadWorkState
    data class Failed(
        val reason: String
    ) : GemmaModelDownloadWorkState
}
