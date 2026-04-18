package com.example.ar_control.di

import com.example.ar_control.diagnostics.DiagnosticsReportBuilder
import com.example.ar_control.diagnostics.SessionLog
import com.example.ar_control.recording.ClipFileSharer
import com.example.ar_control.ui.preview.PreviewViewModelFactory

interface AppContainer {
    val clipFileSharer: ClipFileSharer
    val diagnosticsReportBuilder: DiagnosticsReportBuilder
    val previewViewModelFactory: PreviewViewModelFactory
    val sessionLog: SessionLog
}
