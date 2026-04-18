package com.example.ar_control.usb

import android.app.PendingIntent
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.example.ar_control.diagnostics.NoOpSessionLog
import com.example.ar_control.diagnostics.SessionLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

interface UsbPermissionGateway {
    suspend fun ensureControlPermission(): Boolean
}

internal enum class UsbPermissionBroadcastResult {
    Granted,
    Denied
}

internal object UsbPermissionBroadcastInterpreter {
    fun resolve(
        targetDeviceName: String,
        grantedDeviceName: String?,
        permissionGrantedExtra: Boolean,
        permissionGrantedNow: Boolean
    ): UsbPermissionBroadcastResult? {
        if (grantedDeviceName == targetDeviceName) {
            return if (permissionGrantedExtra || permissionGrantedNow) {
                UsbPermissionBroadcastResult.Granted
            } else {
                UsbPermissionBroadcastResult.Denied
            }
        }
        if (grantedDeviceName == null && permissionGrantedNow) {
            return UsbPermissionBroadcastResult.Granted
        }
        return null
    }
}

class AndroidUsbPermissionGateway(
    private val context: Context,
    private val usbManager: UsbManager,
    private val sessionLog: SessionLog = NoOpSessionLog,
    private val pollIntervalMillis: Long = 250L
) : UsbPermissionGateway {

    override suspend fun ensureControlPermission(): Boolean {
        val device = UsbDeviceMatcher.findControlCandidate(usbManager.deviceList.values)
        if (device == null) {
            sessionLog.record("AndroidUsbPermissionGateway", "No XREAL HID control device found")
            return false
        }
        if (usbManager.hasPermission(device)) {
            sessionLog.record(
                "AndroidUsbPermissionGateway",
                "Control USB permission already granted for ${device.summary()}"
            )
            return true
        }

        val action = "${context.packageName}.USB_PERMISSION"
        var broadcastResult: UsbPermissionBroadcastResult? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != action) {
                    return
                }
                val grantedExtra = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val grantedDevice = intent.readUsbDeviceExtra()
                val grantedNow = usbManager.hasPermission(device)
                sessionLog.record(
                    "AndroidUsbPermissionGateway",
                    "Control USB permission broadcast received: " +
                        "device=${grantedDevice?.deviceName ?: "null"}, " +
                        "grantedExtra=$grantedExtra, grantedNow=$grantedNow"
                )
                when (
                    UsbPermissionBroadcastInterpreter.resolve(
                        targetDeviceName = device.deviceName,
                        grantedDeviceName = grantedDevice?.deviceName,
                        permissionGrantedExtra = grantedExtra,
                        permissionGrantedNow = grantedNow
                    )
                ) {
                    null -> {
                        sessionLog.record(
                            "AndroidUsbPermissionGateway",
                            "Ignoring USB permission broadcast for non-target device"
                        )
                    }
                    UsbPermissionBroadcastResult.Granted -> {
                        sessionLog.record(
                            "AndroidUsbPermissionGateway",
                            "Control USB permission granted for ${device.summary()} via broadcast"
                        )
                        broadcastResult = UsbPermissionBroadcastResult.Granted
                    }
                    UsbPermissionBroadcastResult.Denied -> {
                        sessionLog.record(
                            "AndroidUsbPermissionGateway",
                            "Control USB permission denied for ${device.summary()} via broadcast"
                        )
                        broadcastResult = UsbPermissionBroadcastResult.Denied
                    }
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            // USB permission callbacks may be delivered by a privileged system component.
            ContextCompat.RECEIVER_EXPORTED
        )

        try {
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            sessionLog.record(
                "AndroidUsbPermissionGateway",
                "Requesting control USB permission for ${device.summary()}"
            )
            sessionLog.record(
                "AndroidUsbPermissionGateway",
                "USB device snapshot before request: ${describeUsbDevices()}"
            )
            runCatching {
                usbManager.requestPermission(device, permissionIntent)
            }.onFailure {
                sessionLog.record(
                    "AndroidUsbPermissionGateway",
                    "Control USB permission request failed: ${it.message ?: "unknown_error"}"
                )
                return false
            }

            sessionLog.record(
                "AndroidUsbPermissionGateway",
                "Waiting for control USB permission result for ${device.summary()}"
            )
            var lastSnapshot = describeUsbDevices()
            while (currentCoroutineContext().isActive) {
                when (broadcastResult) {
                    UsbPermissionBroadcastResult.Granted -> return true
                    UsbPermissionBroadcastResult.Denied -> return false
                    null -> Unit
                }

                val refreshedCandidate = findPermittedControlCandidate()
                if (refreshedCandidate != null) {
                    sessionLog.record(
                        "AndroidUsbPermissionGateway",
                        "Control USB permission granted for ${refreshedCandidate.summary()} via device-list polling fallback"
                    )
                    return true
                }

                if (usbManager.hasPermission(device)) {
                    sessionLog.record(
                        "AndroidUsbPermissionGateway",
                        "Control USB permission granted for ${device.summary()} via polling fallback"
                    )
                    return true
                }

                val currentSnapshot = describeUsbDevices()
                if (currentSnapshot != lastSnapshot) {
                    sessionLog.record(
                        "AndroidUsbPermissionGateway",
                        "USB device snapshot changed while waiting: $currentSnapshot"
                    )
                    lastSnapshot = currentSnapshot
                }

                delay(pollIntervalMillis)
            }
            return false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun findPermittedControlCandidate(): UsbDevice? {
        return UsbDeviceMatcher.findControlCandidates(usbManager.deviceList.values)
            .firstOrNull(usbManager::hasPermission)
    }

    private fun UsbDevice.summary(): String {
        return "vid=0x${vendorId.toString(16)}, pid=0x${productId.toString(16)}, name=$deviceName, interfaces=${
            (0 until interfaceCount).joinToString(separator = ",") { index ->
                getInterface(index).interfaceClass.toString()
            }
        }"
    }

    private fun Intent.readUsbDeviceExtra(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
        }
    }

    private fun describeUsbDevices(): String {
        val devices = usbManager.deviceList.values.sortedWith(
            compareBy<UsbDevice>({ it.vendorId }, { it.productId }, { it.deviceName })
        )
        if (devices.isEmpty()) {
            return "none"
        }
        return devices.joinToString(separator = "; ") { device ->
            "${device.summary()}, hasPermission=${usbManager.hasPermission(device)}"
        }
    }
}
