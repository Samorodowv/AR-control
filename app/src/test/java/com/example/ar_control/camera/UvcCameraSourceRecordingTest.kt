package com.example.ar_control.camera

import android.graphics.SurfaceTexture
import android.view.Surface
import com.example.ar_control.ArControlApp
import com.example.ar_control.recording.RecordingInputTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(application = ArControlApp::class)
@RunWith(RobolectricTestRunner::class)
class UvcCameraSourceRecordingTest {

    @Test
    fun startRecording_mapsAdapterStartedResult() = runTest {
        val adapter = FakeRecordingUvcLibraryAdapter(
            recordingResult = UvcLibraryAdapter.RecordingResult.Started
        )
        val source = UvcCameraSource(adapter)
        val captureSurface = TestCaptureSurface.create()

        try {
            val target = RecordingInputTarget.SurfaceTarget(captureSurface.surface)
            val result = source.startRecording(target)

            assertEquals(CameraSource.RecordingStartResult.Started, result)
            assertSame(target, adapter.recordingTargets.single())
        } finally {
            captureSurface.close()
        }
    }

    @Test
    fun startRecording_mapsNotOpenFailure() = runTest {
        val source = UvcCameraSource(
            FakeRecordingUvcLibraryAdapter(
                recordingResult = UvcLibraryAdapter.RecordingResult.NotOpen
            )
        )
        val captureSurface = TestCaptureSurface.create()

        try {
            val result = source.startRecording(RecordingInputTarget.SurfaceTarget(captureSurface.surface))

            assertEquals(
                CameraSource.RecordingStartResult.Failed("recording_camera_not_open"),
                result
            )
        } finally {
            captureSurface.close()
        }
    }

    @Test
    fun startRecording_mapsAlreadyRecordingFailure() = runTest {
        val source = UvcCameraSource(
            FakeRecordingUvcLibraryAdapter(
                recordingResult = UvcLibraryAdapter.RecordingResult.AlreadyRecording
            )
        )
        val captureSurface = TestCaptureSurface.create()

        try {
            val result = source.startRecording(RecordingInputTarget.SurfaceTarget(captureSurface.surface))

            assertEquals(
                CameraSource.RecordingStartResult.Failed("recording_already_running"),
                result
            )
        } finally {
            captureSurface.close()
        }
    }

    @Test
    fun startRecording_mapsGenericFailureWhenAdapterFailsOrThrows() = runTest {
        val captureSurface = TestCaptureSurface.create()

        try {
            val failedSource = UvcCameraSource(
                FakeRecordingUvcLibraryAdapter(
                    recordingResult = UvcLibraryAdapter.RecordingResult.Failed
                )
            )
            assertEquals(
                CameraSource.RecordingStartResult.Failed("recording_start_failed"),
                failedSource.startRecording(RecordingInputTarget.SurfaceTarget(captureSurface.surface))
            )

            val throwingSource = UvcCameraSource(
                FakeRecordingUvcLibraryAdapter(
                    recordingResult = UvcLibraryAdapter.RecordingResult.Started,
                    startRecordingError = IllegalStateException("boom")
                )
            )
            assertEquals(
                CameraSource.RecordingStartResult.Failed("recording_start_failed"),
                throwingSource.startRecording(RecordingInputTarget.SurfaceTarget(captureSurface.surface))
            )
        } finally {
            captureSurface.close()
        }
    }

    @Test
    fun stopRecording_delegatesSafely() = runTest {
        val adapter = FakeRecordingUvcLibraryAdapter(
            recordingResult = UvcLibraryAdapter.RecordingResult.Started,
            stopRecordingError = IllegalStateException("stop boom")
        )
        val source = UvcCameraSource(adapter)

        source.stopRecording()

        assertTrue(adapter.stopRecordingCalled)
    }

    @Test
    fun stopRecording_rethrowsCancellationException() = runTest {
        val source = UvcCameraSource(
            FakeRecordingUvcLibraryAdapter(
                recordingResult = UvcLibraryAdapter.RecordingResult.Started,
                stopRecordingError = CancellationException("cancel stop recording")
            )
        )

        try {
            source.stopRecording()
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertEquals("cancel stop recording", expected.message)
        }
    }
}

private class FakeRecordingUvcLibraryAdapter(
    private val openResult: UvcLibraryAdapter.OpenResult =
        UvcLibraryAdapter.OpenResult.Started(PreviewSize(width = 640, height = 480)),
    private val recordingResult: UvcLibraryAdapter.RecordingResult,
    private val startRecordingError: Throwable? = null,
    private val stopRecordingError: Throwable? = null
) : UvcLibraryAdapter {
    val recordingTargets = mutableListOf<RecordingInputTarget>()
    var stopCalled = false
    var stopRecordingCalled = false

    override suspend fun open(surfaceToken: CameraSource.SurfaceToken): UvcLibraryAdapter.OpenResult {
        return openResult
    }

    override suspend fun stop() {
        stopCalled = true
    }

    override suspend fun startRecording(target: RecordingInputTarget): UvcLibraryAdapter.RecordingResult {
        recordingTargets += target
        startRecordingError?.let { throw it }
        return recordingResult
    }

    override suspend fun stopRecording() {
        stopRecordingCalled = true
        stopRecordingError?.let { throw it }
    }
}

private class TestCaptureSurface private constructor(
    private val surfaceTexture: SurfaceTexture,
    val surface: Surface
) : AutoCloseable {
    override fun close() {
        surface.release()
        surfaceTexture.release()
    }

    companion object {
        fun create(): TestCaptureSurface {
            val surfaceTexture = SurfaceTexture(0)
            return TestCaptureSurface(surfaceTexture, Surface(surfaceTexture))
        }
    }
}
