package com.example.ar_control.usb

import com.example.ar_control.diagnostics.NoOpSessionLog
import com.example.ar_control.diagnostics.SessionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AndroidEyeUsbConfigurator(
    private val hidTransport: HidTransport,
    private val sessionLog: SessionLog = NoOpSessionLog,
    private val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pollIntervalMillis: Long = 250L,
    private val timeoutMillis: Long = 5_000L,
    private val delayStrategy: suspend (Long) -> Unit = { delay(it) }
) : EyeUsbConfigurator {

    override suspend fun enableCamera(): EyeUsbConfigurator.Result {
        sessionLog.record("AndroidEyeUsbConfigurator", "Starting XREAL Eye camera enable flow")
        return try {
            withContext(blockingDispatcher) {
                hidTransport.sendEnableUvcRequest()
            }
            sessionLog.record("AndroidEyeUsbConfigurator", "Waiting for XREAL Eye camera device")
            waitForCameraDevice()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val reason = error.message ?: "Failed to enable XREAL Eye camera"
            sessionLog.record("AndroidEyeUsbConfigurator", "Camera enable failed: $reason")
            EyeUsbConfigurator.Result.Failed(reason)
        }
    }

    private suspend fun waitForCameraDevice(): EyeUsbConfigurator.Result {
        var elapsedMillis = 0L
        while (elapsedMillis <= timeoutMillis) {
            if (hidTransport.isCameraDevicePresent()) {
                sessionLog.record("AndroidEyeUsbConfigurator", "XREAL Eye camera device detected")
                return EyeUsbConfigurator.Result.Enabled
            }
            if (elapsedMillis >= timeoutMillis) {
                break
            }
            delayStrategy(pollIntervalMillis)
            elapsedMillis += pollIntervalMillis
        }
        val reason = "Timed out waiting for XREAL Eye camera device"
        sessionLog.record("AndroidEyeUsbConfigurator", reason)
        return EyeUsbConfigurator.Result.Failed(reason)
    }
}

interface HidTransport {
    suspend fun sendEnableUvcRequest()
    fun isCameraDevicePresent(): Boolean
}
