package com.example.ar_control.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice as AndroidUsbDevice

object UsbDeviceMatcher {
    const val xrealVendorId: Int = 0x3318

    private val supportedProductIds = setOf(
        0x0435,
        0x0436,
        0x0437,
        0x0438
    )

    data class InterfaceDescriptor(
        val hardwareClass: Int
    )

    data class DeviceDescriptor(
        val deviceId: String,
        val vendorId: Int,
        val productId: Int,
        val interfaceClasses: List<InterfaceDescriptor>
    )

    fun findControlCandidate(devices: Iterable<DeviceDescriptor>): DeviceDescriptor? {
        return devices
            .filter { descriptor ->
                isSupportedXrealProduct(descriptor.vendorId, descriptor.productId) &&
                    descriptor.interfaceClasses.any { it.hardwareClass == UsbConstants.USB_CLASS_HID }
            }
            .sortedWith(compareBy<DeviceDescriptor>({ it.vendorId }, { it.productId }, { it.deviceId }))
            .firstOrNull()
    }

    fun findControlCandidate(devices: Iterable<AndroidUsbDevice>): AndroidUsbDevice? {
        return findControlCandidates(devices).firstOrNull()
    }

    fun findControlCandidates(devices: Iterable<AndroidUsbDevice>): List<AndroidUsbDevice> {
        return devices
            .filter(::isControlCandidate)
            .sortedWith(compareBy<AndroidUsbDevice>({ it.vendorId }, { it.productId }, { it.deviceName }))
    }

    fun isCameraCandidate(device: AndroidUsbDevice): Boolean {
        if (!isSupportedXrealProduct(device.vendorId, device.productId)) {
            return false
        }
        return (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VIDEO
        }
    }

    private fun isControlCandidate(device: AndroidUsbDevice): Boolean {
        if (!isSupportedXrealProduct(device.vendorId, device.productId)) {
            return false
        }
        return (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_HID
        }
    }

    private fun isSupportedXrealProduct(vendorId: Int, productId: Int): Boolean {
        return vendorId == xrealVendorId && productId in supportedProductIds
    }
}
