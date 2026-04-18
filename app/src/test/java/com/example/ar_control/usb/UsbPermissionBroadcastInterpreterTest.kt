package com.example.ar_control.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbPermissionBroadcastInterpreterTest {

    @Test
    fun resolve_returnsGrantedWhenBroadcastMatchesTargetDevice() {
        val result = UsbPermissionBroadcastInterpreter.resolve(
            targetDeviceName = "/dev/bus/usb/002/003",
            grantedDeviceName = "/dev/bus/usb/002/003",
            permissionGrantedExtra = true,
            permissionGrantedNow = true
        )

        assertEquals(UsbPermissionBroadcastResult.Granted, result)
    }

    @Test
    fun resolve_returnsGrantedWhenDeviceExtraMissingButPermissionNowGranted() {
        val result = UsbPermissionBroadcastInterpreter.resolve(
            targetDeviceName = "/dev/bus/usb/002/003",
            grantedDeviceName = null,
            permissionGrantedExtra = false,
            permissionGrantedNow = true
        )

        assertEquals(UsbPermissionBroadcastResult.Granted, result)
    }

    @Test
    fun resolve_returnsDeniedWhenTargetDeviceBroadcastDenied() {
        val result = UsbPermissionBroadcastInterpreter.resolve(
            targetDeviceName = "/dev/bus/usb/002/003",
            grantedDeviceName = "/dev/bus/usb/002/003",
            permissionGrantedExtra = false,
            permissionGrantedNow = false
        )

        assertEquals(UsbPermissionBroadcastResult.Denied, result)
    }

    @Test
    fun resolve_ignoresBroadcastForDifferentDeviceWithoutPermissionFallback() {
        val result = UsbPermissionBroadcastInterpreter.resolve(
            targetDeviceName = "/dev/bus/usb/002/003",
            grantedDeviceName = "/dev/bus/usb/002/004",
            permissionGrantedExtra = true,
            permissionGrantedNow = false
        )

        assertNull(result)
    }
}
