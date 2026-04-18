package com.example.ar_control.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidHidTransportProtocolTest {

    @Test
    fun buildEnableUvcPacket_matchesExpectedHeaderLayout() {
        val packet = XrealHidProtocol.buildEnableUvcPacket()
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(26, packet.size)
        assertEquals(0xFD, buffer.get().toInt() and 0xFF)
        assertEquals(crc32(packet, 5, 21).toInt(), buffer.int)
        assertEquals(21, buffer.short.toInt() and 0xFFFF)
        assertEquals(0, buffer.int)
        assertEquals(0, buffer.int)
        assertArrayEquals(byteArrayOf(0xD3.toByte(), 0x00), byteArrayOf(buffer.get(), buffer.get()))
        assertArrayEquals(ByteArray(5), ByteArray(5) { buffer.get() })
        assertArrayEquals(byteArrayOf(0x00, 0x10, 0x01, 0x00), ByteArray(4) { buffer.get() })
    }

    @Test
    fun validateSuccessResponse_acceptsFdMagicResponse() {
        val response = buildSuccessResponse(command = byteArrayOf(0xD3.toByte(), 0x00))

        XrealHidProtocol.validateSuccessResponse(
            response = response,
            expectedCommand = byteArrayOf(0xD3.toByte(), 0x00)
        )
    }

    private fun buildSuccessResponse(command: ByteArray): ByteArray {
        val packet = ByteArray(23)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0xFD.toByte())
        buffer.putInt(0)
        buffer.putShort(18)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.put(command)
        buffer.put(ByteArray(5))
        buffer.put(0)

        val checksum = crc32(packet, 5, 18).toInt()
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(1)
            putInt(checksum)
        }
        return packet
    }

    private fun crc32(bytes: ByteArray, offset: Int, length: Int): Long {
        val crc32 = CRC32()
        crc32.update(bytes, offset, length)
        return crc32.value
    }
}
