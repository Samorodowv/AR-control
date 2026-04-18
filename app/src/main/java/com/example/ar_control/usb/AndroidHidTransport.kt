package com.example.ar_control.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.example.ar_control.diagnostics.NoOpSessionLog
import com.example.ar_control.diagnostics.SessionLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class AndroidHidTransport(
    private val usbManager: UsbManager,
    private val sessionLog: SessionLog = NoOpSessionLog
) : HidTransport {

    override suspend fun sendEnableUvcRequest() {
        try {
            val device = UsbDeviceMatcher.findControlCandidate(usbManager.deviceList.values)
                ?: throw IllegalStateException("No matching XREAL HID control device found")
            val usbInterface = device.findHidInterface()
                ?: throw IllegalStateException("No HID interface found on XREAL control device")
            val outEndpoint = usbInterface.findEndpoint(UsbConstants.USB_DIR_OUT)
                ?: throw IllegalStateException("No OUT endpoint found on XREAL HID control interface")
            val inEndpoint = usbInterface.findEndpoint(UsbConstants.USB_DIR_IN)
                ?: throw IllegalStateException("No IN endpoint found on XREAL HID control interface")
            val connection = usbManager.openDevice(device)
                ?: throw IllegalStateException("Unable to open XREAL HID control device")

            sessionLog.record(
                "AndroidHidTransport",
                "Sending HID enable UVC request to ${device.summary()}"
            )
            try {
                if (!connection.claimInterface(usbInterface, true)) {
                    throw IllegalStateException("Unable to claim XREAL HID control interface")
                }
                val request = XrealHidProtocol.buildEnableUvcPacket()
                transferOut(connection, outEndpoint, request)
                val response = transferIn(connection, inEndpoint)
                XrealHidProtocol.validateSuccessResponse(
                    response = response,
                    expectedCommand = XrealHidProtocol.SET_USB_CONFIG_COMMAND
                )
                sessionLog.record("AndroidHidTransport", "HID enable UVC request acknowledged")
            } finally {
                runCatching { connection.releaseInterface(usbInterface) }
                connection.close()
            }
        } catch (error: Exception) {
            sessionLog.record(
                "AndroidHidTransport",
                "HID enable UVC request failed: ${error.message ?: "unknown_error"}"
            )
            throw error
        }
    }

    override fun isCameraDevicePresent(): Boolean {
        return usbManager.deviceList.values.any(UsbDeviceMatcher::isCameraCandidate)
    }

    private fun transferOut(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        packet: ByteArray
    ) {
        val written = connection.bulkTransfer(endpoint, packet, packet.size, IO_TIMEOUT_MILLIS)
        if (written != packet.size) {
            throw IllegalStateException("Failed to write full HID packet: wrote $written of ${packet.size} bytes")
        }
    }

    private fun transferIn(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint
    ): ByteArray {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        val read = connection.bulkTransfer(endpoint, buffer, buffer.size, IO_TIMEOUT_MILLIS)
        if (read < HEADER_SIZE + 1) {
            throw IllegalStateException("Short HID response: $read bytes")
        }
        return buffer.copyOf(read)
    }

    private fun UsbDevice.findHidInterface(): UsbInterface? {
        return (0 until interfaceCount)
            .map(::getInterface)
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
    }

    private fun UsbInterface.findEndpoint(direction: Int): UsbEndpoint? {
        return (0 until endpointCount)
            .map(::getEndpoint)
            .firstOrNull { endpoint ->
                endpoint.direction == direction &&
                    (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT ||
                        endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK)
            }
    }

    private fun UsbDevice.summary(): String {
        return "vid=0x${vendorId.toString(16)}, pid=0x${productId.toString(16)}, name=$deviceName"
    }

    private companion object {
        const val MAX_PACKET_SIZE = 1024
        const val IO_TIMEOUT_MILLIS = 2_000
        const val HEADER_SIZE = XrealHidProtocol.HEADER_SIZE
    }
}

internal object XrealHidProtocol {
    const val MAGIC: Byte = 0xFD.toByte()
    val SET_USB_CONFIG_COMMAND = byteArrayOf(0xD3.toByte(), 0x00)
    const val REQUEST_ID = 0
    const val TIMESTAMP = 0
    const val CHECKSUM_OFFSET = 1
    const val HEADER_FIELDS_OFFSET = 5
    const val LENGTH_OFFSET = HEADER_FIELDS_OFFSET
    const val REQUEST_ID_OFFSET = LENGTH_OFFSET + 2
    const val TIMESTAMP_OFFSET = REQUEST_ID_OFFSET + 4
    const val COMMAND_OFFSET = TIMESTAMP_OFFSET + 4
    const val UNKNOWN_SIZE = 5
    const val UNKNOWN_OFFSET = COMMAND_OFFSET + 2
    const val STATUS_OFFSET = UNKNOWN_OFFSET + UNKNOWN_SIZE
    const val HEADER_FIELDS_SIZE = 17
    const val HEADER_SIZE = 22
    const val USB_CONFIG_PAYLOAD_SIZE = 4
    private const val FIELD_WIDTH_BITS = 2
    private const val UVC0_FIELD_INDEX = 6
    private const val ENABLE_FIELD_INDEX = 8

    fun buildEnableUvcPacket(): ByteArray {
        val payload = buildEnableUvcPayload()
        val packet = ByteArray(HEADER_SIZE + payload.size)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC)
        buffer.putInt(0)
        buffer.putShort((HEADER_FIELDS_SIZE + payload.size).toShort())
        buffer.putInt(REQUEST_ID)
        buffer.putInt(TIMESTAMP)
        buffer.put(SET_USB_CONFIG_COMMAND)
        buffer.put(ByteArray(UNKNOWN_SIZE))
        buffer.put(payload)

        val checksum = crc32(packet, HEADER_FIELDS_OFFSET, HEADER_FIELDS_SIZE + payload.size)
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(CHECKSUM_OFFSET)
            putInt(checksum.toInt())
        }
        return packet
    }

    fun validateSuccessResponse(
        response: ByteArray,
        expectedCommand: ByteArray
    ) {
        if (response.size < HEADER_SIZE + 1) {
            throw IllegalStateException("Short HID response: ${response.size} bytes")
        }

        val header = parseHeader(response)
        if (header.magic != (MAGIC.toInt() and 0xFF)) {
            throw IllegalStateException("Unexpected HID response magic: ${header.magic}")
        }
        if (!header.command.contentEquals(expectedCommand)) {
            throw IllegalStateException("Unexpected HID response command")
        }
        if (header.requestId != REQUEST_ID) {
            throw IllegalStateException("Unexpected HID response request id: ${header.requestId}")
        }

        val checksumEnd = HEADER_FIELDS_OFFSET + header.length
        if (checksumEnd > response.size) {
            throw IllegalStateException("HID response length exceeds packet size")
        }

        val expectedChecksum = crc32(response, HEADER_FIELDS_OFFSET, header.length)
        if (expectedChecksum != header.checksum) {
            throw IllegalStateException("Invalid HID response checksum")
        }

        val status = response[STATUS_OFFSET].toInt() and 0xFF
        if (status != 0) {
            throw IllegalStateException("XREAL HID request failed with status $status")
        }
    }

    private fun buildEnableUvcPayload(): ByteArray {
        val configBits = (1 shl (UVC0_FIELD_INDEX * FIELD_WIDTH_BITS)) or
            (1 shl (ENABLE_FIELD_INDEX * FIELD_WIDTH_BITS))
        return ByteBuffer.allocate(USB_CONFIG_PAYLOAD_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(configBits)
            .array()
    }

    private fun parseHeader(response: ByteArray): ParsedHeader {
        val buffer = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN)
        return ParsedHeader(
            magic = buffer.get().toInt() and 0xFF,
            checksum = buffer.int.toUInt(),
            length = buffer.short.toInt() and 0xFFFF,
            requestId = buffer.int,
            timestamp = buffer.int,
            command = byteArrayOf(buffer.get(), buffer.get())
        )
    }

    private fun crc32(bytes: ByteArray, offset: Int, length: Int): UInt {
        val crc32 = CRC32()
        crc32.update(bytes, offset, length)
        return crc32.value.toUInt()
    }

    private data class ParsedHeader(
        val magic: Int,
        val checksum: UInt,
        val length: Int,
        val requestId: Int,
        val timestamp: Int,
        val command: ByteArray
    )
}
