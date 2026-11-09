package com.qianshi.woltool.data

/**
 * A device that can be woken with a Wake-on-LAN magic packet.
 */
data class Device(
    val id: String,
    val name: String,
    val mac: String,
    val broadcastAddress: String,
    val port: Int = 9,
    val packetCount: Int = 3,
    val createdAt: Long = System.currentTimeMillis(),
    val lastWakeAt: Long? = null,
    val lastWakeSuccess: Boolean? = null,
)
