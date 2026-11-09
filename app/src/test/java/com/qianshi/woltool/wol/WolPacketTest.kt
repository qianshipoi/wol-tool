package com.qianshi.woltool.wol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WolPacketTest {

    private val macBytes = byteArrayOf(
        0xAA.toByte(),
        0xBB.toByte(),
        0xCC.toByte(),
        0xDD.toByte(),
        0xEE.toByte(),
        0xFF.toByte(),
    )

    @Test
    fun `parses colon separated mac`() {
        assertArrayEquals(macBytes, WolPacket.parseMac("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `parses dash separated and plain mac`() {
        assertArrayEquals(macBytes, WolPacket.parseMac("aa-bb-cc-dd-ee-ff"))
        assertArrayEquals(macBytes, WolPacket.parseMac("AABBCCDDEEFF"))
        assertArrayEquals(macBytes, WolPacket.parseMac("aa bb cc dd ee ff"))
    }

    @Test
    fun `rejects malformed mac addresses`() {
        assertNull(WolPacket.parseMac(""))
        assertNull(WolPacket.parseMac("AA:BB:CC:DD:EE"))
        assertNull(WolPacket.parseMac("AA:BB:CC:DD:EE:GG"))
        assertNull(WolPacket.parseMac("AA:BB:CC:DD:EE:FF:00"))
        assertNull(WolPacket.parseMac("你好"))
    }

    @Test
    fun `builds a 102 byte magic packet`() {
        val packet = WolPacket.magicPacket(macBytes)
        assertEquals(WolPacket.MAGIC_PACKET_LENGTH, packet.size)
        assertEquals(102, packet.size)

        for (index in 0 until 6) {
            assertEquals(0xFF, packet[index].toInt() and 0xFF)
        }
        for (repetition in 0 until 16) {
            val offset = 6 + repetition * 6
            assertArrayEquals(
                macBytes,
                packet.copyOfRange(offset, offset + 6),
            )
        }
    }

    @Test
    fun `builds magic packet from string`() {
        assertArrayEquals(macBytes, WolPacket.magicPacket("AA:BB:CC:DD:EE:FF")!!.copyOfRange(6, 12))
    }

    @Test
    fun `masks mac input as pairs with separators`() {
        assertEquals("A", WolPacket.maskMac("a"))
        assertEquals("AA", WolPacket.maskMac("aa"))
        assertEquals("AA:B", WolPacket.maskMac("aa-b"))
        assertEquals("AA:BB", WolPacket.maskMac("aabb"))
        assertEquals("AA:BB:CC:DD:EE:FF", WolPacket.maskMac("aa bb cc dd ee ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", WolPacket.maskMac("aabbccddeeff00"))
        assertEquals("", WolPacket.maskMac("zz"))
    }

    @Test
    fun `normalizes mac text`() {
        assertEquals("AA:BB:CC:DD:EE:FF", WolPacket.normalizeMac("aabbccddeeff"))
        assertEquals("01:23:45:67:89:AB", WolPacket.normalizeMac("01-23-45-67-89-ab"))
        assertNull(WolPacket.normalizeMac("not a mac"))
    }

}
