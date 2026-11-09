package com.qianshi.woltool.wol

import java.util.Locale

/**
 * Builds and parses Wake-on-LAN magic packets.
 *
 * A magic packet consists of six 0xFF bytes followed by the target MAC address
 * repeated 16 times (102 bytes in total).
 */
object WolPacket {

    const val MAC_BYTE_LENGTH = 6
    const val MAGIC_PACKET_LENGTH = 6 + 16 * MAC_BYTE_LENGTH

    /** Parses a MAC address in `AA:BB:CC:DD:EE:FF`, `AA-BB-...`, `AABB...` or spaced form. */
    fun parseMac(input: String): ByteArray? {
        val cleaned = input.trim()
            .replace(":", "")
            .replace("-", "")
            .replace(".", "")
            .replace(" ", "")
        if (cleaned.length != 12) return null
        if (!cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(MAC_BYTE_LENGTH) { index ->
            val high = cleaned[index * 2].digitToInt(16)
            val low = cleaned[index * 2 + 1].digitToInt(16)
            ((high shl 4) or low).toByte()
        }
    }

    /** Returns the MAC as upper-case colon-separated text, or null when invalid. */
    fun normalizeMac(input: String): String? = parseMac(input)?.toMacString()

    fun ByteArray.toMacString(): String = joinToString(":") { byte ->
        String.format(Locale.US, "%02X", byte.toInt() and 0xFF)
    }

    /**
     * Formats raw input as an upper-case MAC mask: a separator is inserted
     * after every two hexadecimal digits, and extra input is ignored.
     */
    fun maskMac(raw: String): String {
        val hex = raw.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            .take(12)
            .uppercase(Locale.US)
        return hex.chunked(2).joinToString(":")
    }

    /** Builds the 102 byte magic packet for an already parsed MAC. */
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == MAC_BYTE_LENGTH) { "MAC must contain exactly 6 bytes" }
        val packet = ByteArray(MAGIC_PACKET_LENGTH)
        for (index in 0 until MAC_BYTE_LENGTH) {
            packet[index] = 0xFF.toByte()
        }
        for (repetition in 0 until 16) {
            mac.copyInto(
                destination = packet,
                destinationOffset = MAC_BYTE_LENGTH + repetition * MAC_BYTE_LENGTH,
            )
        }
        return packet
    }

    /** Builds the magic packet from a textual MAC address, or null when invalid. */
    fun magicPacket(mac: String): ByteArray? = parseMac(mac)?.let(::magicPacket)
}
