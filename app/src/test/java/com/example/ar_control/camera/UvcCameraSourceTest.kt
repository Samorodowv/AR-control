package com.example.ar_control.camera

import com.example.ar_control.recording.RecordingInputTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UvcCameraSourceTest {

    @Test
    fun start_returnsStartedWhenAdapterStartsPreview() = runTest {
        val adapter = FakeUvcLibraryAdapter(
            UvcLibraryAdapter.OpenResult.Started(PreviewSize(width = 1920, height = 1080))
        )
        val source = UvcCameraSource(adapter)

        val result = source.start(FakeSurfaceToken)

        assertEquals(
            CameraSource.StartResult.Started(PreviewSize(width = 1920, height = 1080)),
            result
        )
        assertEquals(listOf(FakeSurfaceToken), adapter.openCalls)
    }

    @Test
    fun start_returnsMissingSurfaceFailureWhenAdapterCannotExtractSurface() = runTest {
        val source = UvcCameraSource(
            FakeUvcLibraryAdapter(UvcLibraryAdapter.OpenResult.MissingSurface)
        )

        val result = source.start(FakeSurfaceToken)

        assertEquals(CameraSource.StartResult.Failed("missing_surface"), result)
    }

    @Test
    fun start_returnsMissingDeviceFailureWhenNoCameraCandidateIsAvailable() = runTest {
        val source = UvcCameraSource(
            FakeUvcLibraryAdapter(UvcLibraryAdapter.OpenResult.MissingDevice)
        )

        val result = source.start(FakeSurfaceToken)

        assertEquals(CameraSource.StartResult.Failed("missing_device"), result)
    }

    @Test
    fun start_returnsMissingCameraPermissionWhenRuntimeCameraPermissionIsAbsent() = runTest {
        val source = UvcCameraSource(
            FakeUvcLibraryAdapter(UvcLibraryAdapter.OpenResult.MissingCameraPermission)
        )

        val result = source.start(FakeSurfaceToken)

        assertEquals(CameraSource.StartResult.Failed("missing_camera_permission"), result)
    }

    @Test
    fun start_returnsOpenFailureWhenCameraCannotBeOpened() = runTest {
        val source = UvcCameraSource(
            FakeUvcLibraryAdapter(UvcLibraryAdapter.OpenResult.OpenFailed)
        )

        val result = source.start(FakeSurfaceToken)

        assertEquals(CameraSource.StartResult.Failed("open_failed"), result)
    }

    @Test
    fun start_returnsPreviewStartFailureWhenPreviewCannotBegin() = runTest {
        val source = UvcCameraSource(
            FakeUvcLibraryAdapter(UvcLibraryAdapter.OpenResult.PreviewStartFailed)
        )

        val result = source.start(FakeSurfaceToken)

        assertEquals(CameraSource.StartResult.Failed("preview_start_failed"), result)
    }

    @Test
    fun stop_releasesAdapterResourcesSafely() = runTest {
        val adapter = FakeUvcLibraryAdapter(
            UvcLibraryAdapter.OpenResult.Started(PreviewSize(width = 640, height = 480))
        )
        val source = UvcCameraSource(adapter)

        source.stop()

        assertTrue(adapter.stopCalled)
    }

    @Test
    fun stop_rethrowsCancellationException() = runTest {
        val source = UvcCameraSource(
            FakeUvcLibraryAdapter(
                openResult = UvcLibraryAdapter.OpenResult.Started(PreviewSize(width = 640, height = 480)),
                stopError = CancellationException("cancel stop")
            )
        )

        try {
            source.stop()
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertEquals("cancel stop", expected.message)
        }
    }
}

private data object FakeSurfaceToken : CameraSource.SurfaceToken

private class FakeUvcLibraryAdapter(
    private val openResult: UvcLibraryAdapter.OpenResult,
    private val stopError: Throwable? = null
) : UvcLibraryAdapter {
    val openCalls = mutableListOf<CameraSource.SurfaceToken>()
    var stopCalled = false

    override suspend fun open(surfaceToken: CameraSource.SurfaceToken): UvcLibraryAdapter.OpenResult {
        openCalls += surfaceToken
        return openResult
    }

    override suspend fun stop() {
        stopCalled = true
        stopError?.let { throw it }
    }

    override suspend fun startRecording(target: RecordingInputTarget): UvcLibraryAdapter.RecordingResult {
        return UvcLibraryAdapter.RecordingResult.Failed
    }

    override suspend fun stopRecording() = Unit
}
