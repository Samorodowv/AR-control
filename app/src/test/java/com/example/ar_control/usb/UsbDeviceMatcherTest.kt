package com.example.ar_control.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbDeviceMatcherTest {

    @Test
    fun findControlCandidate_returnsMatchingXrealHidDevice() {
        val devices = listOf(
            UsbDeviceMatcher.DeviceDescriptor(
                deviceId = "other-hid",
                vendorId = 0x1234,
                productId = 0x5678,
                interfaceClasses = listOf(UsbDeviceMatcher.InterfaceDescriptor(hardwareClass = 3))
            ),
            UsbDeviceMatcher.DeviceDescriptor(
                deviceId = "xreal-video-only",
                vendorId = 0x3318,
                productId = 0x0435,
                interfaceClasses = listOf(UsbDeviceMatcher.InterfaceDescriptor(hardwareClass = 14))
            ),
            UsbDeviceMatcher.DeviceDescriptor(
                deviceId = "xreal-hid",
                vendorId = 0x3318,
                productId = 0x0437,
                interfaceClasses = listOf(
                    UsbDeviceMatcher.InterfaceDescriptor(hardwareClass = 255),
                    UsbDeviceMatcher.InterfaceDescriptor(hardwareClass = 3)
                )
            )
        )

        val match = UsbDeviceMatcher.findControlCandidate(devices)

        assertEquals("xreal-hid", match?.deviceId)
    }

    @Test
    fun findControlCandidate_returnsNullWhenNoMatchingHidDeviceExists() {
        val devices = listOf(
            UsbDeviceMatcher.DeviceDescriptor(
                deviceId = "xreal-video-only",
                vendorId = 0x3318,
                productId = 0x0436,
                interfaceClasses = listOf(UsbDeviceMatcher.InterfaceDescriptor(hardwareClass = 14))
            ),
            UsbDeviceMatcher.DeviceDescriptor(
                deviceId = "non-xreal-hid",
                vendorId = 0x9999,
                productId = 0x0437,
                interfaceClasses = listOf(UsbDeviceMatcher.InterfaceDescriptor(hardwareClass = 3))
            )
        )

        val match = UsbDeviceMatcher.findControlCandidate(devices)

        assertNull(match)
    }

    @Test
    fun findControlCandidate_prefersDeterministicOrderAcrossMatchingDevices() {
        val devices = listOf(
            UsbDeviceMatcher.DeviceDescriptor(
                deviceId = "z-device",
                vendorId = 0x3318,
                productId = 0x0438,
                interfaceClasses = listOf(UsbDeviceMatcher.InterfaceDescriptor(hardwareClass = 3))
            ),
            UsbDeviceMatcher.DeviceDescriptor(
                deviceId = "a-device",
                vendorId = 0x3318,
                productId = 0x0435,
                interfaceClasses = listOf(UsbDeviceMatcher.InterfaceDescriptor(hardwareClass = 3))
            ),
            UsbDeviceMatcher.DeviceDescriptor(
                deviceId = "m-device",
                vendorId = 0x3318,
                productId = 0x0435,
                interfaceClasses = listOf(UsbDeviceMatcher.InterfaceDescriptor(hardwareClass = 3))
            )
        )

        val match = UsbDeviceMatcher.findControlCandidate(devices)

        assertEquals("a-device", match?.deviceId)
    }
}
