package com.qianshi.woltool.wol

import android.content.Context
import com.qianshi.woltool.data.Device
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends Wake-on-LAN magic packets as UDP broadcasts.
 */
class WolSender(private val appContext: Context) {

    data class SendResult(
        val success: Boolean,
        val packetsSent: Int,
        val message: String,
    )

    suspend fun wake(device: Device): SendResult = withContext(Dispatchers.IO) {
        send(
            mac = device.mac,
            broadcastAddress = device.broadcastAddress,
            port = device.port,
            packetCount = device.packetCount,
        )
    }

    fun send(
        mac: String,
        broadcastAddress: String,
        port: Int,
        packetCount: Int = 3,
    ): SendResult {
        val macBytes = WolPacket.parseMac(mac)
            ?: return SendResult(false, 0, "MAC 地址格式不正确")
        if (!NetworkUtils.isUsableBroadcastAddress(broadcastAddress)) {
            return SendResult(false, 0, "广播地址不可用，请填写局域网子网广播地址")
        }
        if (port !in 1..65535) {
            return SendResult(false, 0, "端口需在 1–65535 之间")
        }
        val repeat = packetCount.coerceIn(1, 10)
        val packet = WolPacket.magicPacket(macBytes)

        return try {
            DatagramSocket().use { socket ->
                // Keep the magic packet on the physical LAN even while Tailscale
                // (or another VPN/exit node) is the default route.
                runCatching { NetworkUtils.lanNetwork(appContext)?.bindSocket(socket) }
                socket.broadcast = true
                val address = InetAddress.getByName(broadcastAddress)
                repeat(repeat) { index ->
                    val datagram = DatagramPacket(packet, packet.size, address, port)
                    socket.send(datagram)
                    if (index < repeat - 1) {
                        Thread.sleep(PACKET_INTERVAL_MILLIS)
                    }
                }
            }
            SendResult(
                success = true,
                packetsSent = repeat,
                message = "已发送 $repeat 个魔术包",
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            SendResult(
                success = false,
                packetsSent = 0,
                message = exception.message ?: exception.javaClass.simpleName,
            )
        }
    }

    private companion object {
        const val PACKET_INTERVAL_MILLIS = 80L
    }
}
