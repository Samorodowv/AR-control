package com.example.ar_control.camera

import kotlinx.coroutines.CancellationException
import com.example.ar_control.recording.RecordingInputTarget

class UvcCameraSource(
    private val adapter: UvcLibraryAdapter
) : CameraSource {

    override suspend fun start(surfaceToken: CameraSource.SurfaceToken): CameraSource.StartResult {
        val result = try {
            adapter.open(surfaceToken)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return CameraSource.StartResult.Failed("open_failed")
        }

        return when (result) {
            is UvcLibraryAdapter.OpenResult.Started ->
                CameraSource.StartResult.Started(result.previewSize)
            UvcLibraryAdapter.OpenResult.MissingSurface -> CameraSource.StartResult.Failed("missing_surface")
            UvcLibraryAdapter.OpenResult.MissingDevice -> CameraSource.StartResult.Failed("missing_device")
            UvcLibraryAdapter.OpenResult.MissingCameraPermission ->
                CameraSource.StartResult.Failed("missing_camera_permission")
            UvcLibraryAdapter.OpenResult.OpenFailed -> CameraSource.StartResult.Failed("open_failed")
            UvcLibraryAdapter.OpenResult.PreviewStartFailed ->
                CameraSource.StartResult.Failed("preview_start_failed")
        }
    }

    override suspend fun stop() {
        try {
            adapter.stop()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    override suspend fun startRecording(target: RecordingInputTarget): CameraSource.RecordingStartResult {
        val result = try {
            adapter.startRecording(target)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return CameraSource.RecordingStartResult.Failed("recording_start_failed")
        }

        return when (result) {
            UvcLibraryAdapter.RecordingResult.Started -> CameraSource.RecordingStartResult.Started
            UvcLibraryAdapter.RecordingResult.NotOpen ->
                CameraSource.RecordingStartResult.Failed("recording_camera_not_open")
            UvcLibraryAdapter.RecordingResult.AlreadyRecording ->
                CameraSource.RecordingStartResult.Failed("recording_already_running")
            UvcLibraryAdapter.RecordingResult.Failed ->
                CameraSource.RecordingStartResult.Failed("recording_start_failed")
        }
    }

    override suspend fun stopRecording() {
        try {
            adapter.stopRecording()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }
}
