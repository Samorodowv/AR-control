package com.example.ar_control.camera

import android.graphics.SurfaceTexture
import android.view.Surface
import com.example.ar_control.ArControlApp
import com.example.ar_control.diagnostics.NoOpSessionLog
import com.example.ar_control.recording.RecordingInputTarget
import com.example.ar_control.recording.VideoFrameConsumer
import com.example.ar_control.recording.VideoFramePixelFormat
import kotlinx.coroutines.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import com.serenegiant.usb.UVCCamera

@Config(application = ArControlApp::class)
@RunWith(RobolectricTestRunner::class)
class AndroidUvcLibraryAdapterRecordingTest {

    @Test
    fun startRecording_returnsAlreadyRecordingWhileFirstStartIsInProgress() = runTest {
        val recordingSession = BlockingRecordingSession()
        val adapter = createAdapter(recordingSession)
        val captureSurface = AdapterTestCaptureSurface.create()

        try {
            val firstCall = async(Dispatchers.Default) {
                adapter.startRecording(RecordingInputTarget.SurfaceTarget(captureSurface.surface))
            }
            assertTrue(recordingSession.started.await(2, TimeUnit.SECONDS))

            val secondCall = withContext(Dispatchers.Default) {
                adapter.startRecording(RecordingInputTarget.SurfaceTarget(captureSurface.surface))
            }

            assertEquals(UvcLibraryAdapter.RecordingResult.AlreadyRecording, secondCall)

            recordingSession.allowStartToFinish.countDown()

            assertEquals(UvcLibraryAdapter.RecordingResult.Started, firstCall.await())
            assertEquals(1, recordingSession.startSurfaceCalls.get())
        } finally {
            captureSurface.close()
        }
    }

    @Test
    fun stopRecording_throwsWhenStopCaptureFailsAndPreservesRunningState() = runTest {
        val recordingSession = FakeRecordingSession(
            stopError = IllegalStateException("stop failed")
        )
        val adapter = createAdapter(recordingSession)
        val captureSurface = AdapterTestCaptureSurface.create()

        try {
            assertEquals(
                UvcLibraryAdapter.RecordingResult.Started,
                adapter.startRecording(RecordingInputTarget.SurfaceTarget(captureSurface.surface))
            )
            try {
                adapter.stopRecording()
                fail("Expected IllegalStateException")
            } catch (expected: IllegalStateException) {
                assertEquals("stop failed", expected.message)
            }
            val restartAttempt = adapter.startRecording(RecordingInputTarget.SurfaceTarget(captureSurface.surface))

            assertEquals(UvcLibraryAdapter.RecordingResult.AlreadyRecording, restartAttempt)
            assertEquals(1, recordingSession.startSurfaceCalls.get())
            assertEquals(1, recordingSession.stopSurfaceCalls.get())
        } finally {
            captureSurface.close()
        }
    }

    @Test
    fun stopRecording_rethrowsCancellationException() = runTest {
        val recordingSession = FakeRecordingSession(
            stopError = CancellationException("stop cancelled")
        )
        val adapter = createAdapter(recordingSession)
        val captureSurface = AdapterTestCaptureSurface.create()

        try {
            assertEquals(
                UvcLibraryAdapter.RecordingResult.Started,
                adapter.startRecording(RecordingInputTarget.SurfaceTarget(captureSurface.surface))
            )
            adapter.stopRecording()
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertEquals("stop cancelled", expected.message)
        } finally {
            captureSurface.close()
        }
    }

    @Test
    fun startRecording_frameCallbackUsesRequestedUvcPixelFormat() = runTest {
        val recordingSession = FakeRecordingSession()
        val adapter = createAdapter(recordingSession)

        assertEquals(
            UvcLibraryAdapter.RecordingResult.Started,
            adapter.startRecording(
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.YUV420SP,
                    frameConsumer = VideoFrameConsumer { _, _ -> }
                )
            )
        )

        assertEquals(1, recordingSession.startFrameCallbackCalls.get())
        assertEquals(UVCCamera.PIXEL_FORMAT_YUV420SP, recordingSession.lastFramePixelFormat)
    }
}

private fun createAdapter(recordingSession: UvcRecordingSession): AndroidUvcLibraryAdapter {
    val context = RuntimeEnvironment.getApplication() as ArControlApp
    val adapter = AndroidUvcLibraryAdapter(
        context = context,
        sessionLog = NoOpSessionLog
    )
    setRecordingSession(adapter, recordingSession)
    return adapter
}

private fun setRecordingSession(adapter: AndroidUvcLibraryAdapter, recordingSession: UvcRecordingSession) {
    adapter.javaClass.getDeclaredField("recordingSession").apply {
        isAccessible = true
        set(adapter, recordingSession)
    }
}

private open class FakeRecordingSession(
    private val stopError: Throwable? = null
) : UvcRecordingSession {
    val startSurfaceCalls = AtomicInteger(0)
    val stopSurfaceCalls = AtomicInteger(0)
    val startFrameCallbackCalls = AtomicInteger(0)
    val stopFrameCallbackCalls = AtomicInteger(0)
    var lastFramePixelFormat: Int? = null

    override fun startSurfaceCapture(surface: Surface) {
        startSurfaceCalls.incrementAndGet()
    }

    override fun stopSurfaceCapture() {
        stopSurfaceCalls.incrementAndGet()
        stopError?.let { throw it }
    }

    override fun startFrameCapture(callback: com.serenegiant.usb.IFrameCallback, pixelFormat: Int) {
        startFrameCallbackCalls.incrementAndGet()
        lastFramePixelFormat = pixelFormat
    }

    override fun stopFrameCapture() {
        stopFrameCallbackCalls.incrementAndGet()
        stopError?.let { throw it }
    }
}

private class BlockingRecordingSession : FakeRecordingSession() {
    val started = CountDownLatch(1)
    val allowStartToFinish = CountDownLatch(1)

    override fun startSurfaceCapture(surface: Surface) {
        startSurfaceCalls.incrementAndGet()
        started.countDown()
        check(allowStartToFinish.await(2, TimeUnit.SECONDS)) { "Timed out waiting to finish startSurfaceCapture" }
    }
}

private class AdapterTestCaptureSurface private constructor(
    private val surfaceTexture: SurfaceTexture,
    val surface: Surface
) : AutoCloseable {
    override fun close() {
        surface.release()
        surfaceTexture.release()
    }

    companion object {
        fun create(): AdapterTestCaptureSurface {
            val surfaceTexture = SurfaceTexture(0)
            return AdapterTestCaptureSurface(surfaceTexture, Surface(surfaceTexture))
        }
    }
}
