package com.example.ar_control.usb

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidEyeUsbConfiguratorTest {

    @Test
    fun enableCamera_returnsEnabledWhenCameraDeviceAppears() = runTest {
        val transport = FakeHidTransport(cameraPresenceSequence = ArrayDeque(listOf(false, true)))
        val configurator = AndroidEyeUsbConfigurator(
            hidTransport = transport,
            pollIntervalMillis = 10,
            timeoutMillis = 100,
            delayStrategy = {}
        )

        val result = configurator.enableCamera()

        assertEquals(EyeUsbConfigurator.Result.Enabled, result)
        assertTrue(transport.enableRequestSent)
        assertEquals(2, transport.cameraChecks)
    }

    @Test
    fun enableCamera_returnsFailureWhenCameraDeviceNeverAppears() = runTest {
        val transport = FakeHidTransport(cameraPresenceSequence = ArrayDeque(listOf(false, false, false)))
        val configurator = AndroidEyeUsbConfigurator(
            hidTransport = transport,
            pollIntervalMillis = 10,
            timeoutMillis = 25,
            delayStrategy = {}
        )

        val result = configurator.enableCamera()

        assertEquals(
            EyeUsbConfigurator.Result.Failed("Timed out waiting for XREAL Eye camera device"),
            result
        )
        assertTrue(transport.enableRequestSent)
    }

    @Test
    fun enableCamera_propagatesCancellation() = runTest {
        val transport = FakeHidTransport(
            cameraPresenceSequence = ArrayDeque(listOf(false)),
            onSend = { throw CancellationException("cancelled") }
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val configurator = AndroidEyeUsbConfigurator(
            hidTransport = transport,
            blockingDispatcher = dispatcher,
            pollIntervalMillis = 10,
            timeoutMillis = 100,
            delayStrategy = { _ -> }
        )

        try {
            configurator.enableCamera()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            assertTrue(transport.enableRequestSent)
        }
    }
}

private class FakeHidTransport(
    private val cameraPresenceSequence: ArrayDeque<Boolean>,
    private val onSend: suspend () -> Unit = {}
) : HidTransport {
    var enableRequestSent = false
    var cameraChecks = 0

    override suspend fun sendEnableUvcRequest() {
        enableRequestSent = true
        onSend()
    }

    override fun isCameraDevicePresent(): Boolean {
        cameraChecks += 1
        return cameraPresenceSequence.removeFirstOrNull() ?: false
    }
}
