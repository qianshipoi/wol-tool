package com.qianshi.woltool.wol

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Reads the active IPv4 network and derives the subnet broadcast address.
 */
object NetworkUtils {

    data class LocalNetwork(
        val ipAddress: String? = null,
        val prefixLength: Int? = null,
        val broadcastAddress: String? = null,
        val transportLabel: String = "未连接",
        val interfaceName: String? = null,
    ) {
        val hasIpv4: Boolean get() = ipAddress != null
    }

    fun current(context: Context): LocalNetwork {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return LocalNetwork()
        // Prefer the Wi-Fi/Ethernet network over an active VPN (e.g. Tailscale),
        // because Wake-on-LAN must be broadcast on the physical LAN.
        val network = lanNetwork(context) ?: manager.activeNetwork ?: return LocalNetwork()
        val capabilities = manager.getNetworkCapabilities(network)
        val linkProperties = manager.getLinkProperties(network)
        val transportLabel = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "有线网络"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "移动网络"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            else -> "其他网络"
        }
        val ipv4 = linkProperties?.primaryIpv4Address()
        val ip = ipv4?.address?.hostAddress
        val prefix = ipv4?.prefixLength
        return LocalNetwork(
            ipAddress = ip,
            prefixLength = prefix,
            broadcastAddress = if (ip != null && prefix != null) broadcastAddress(ip, prefix) else null,
            transportLabel = transportLabel,
            interfaceName = linkProperties?.interfaceName,
        )
    }

    private fun LinkProperties.primaryIpv4Address(): android.net.LinkAddress? =
        linkAddresses.firstOrNull { linkAddress ->
            val address: InetAddress = linkAddress.address
            address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress
        }

    /**
     * Returns the physical Wi-Fi/Ethernet network with an IPv4 address, or null.
     * Used to bind WOL sockets so a Tailscale/VPN route or exit node cannot
     * swallow the broadcast.
     */
    fun lanNetwork(context: Context): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return null
        return manager.allNetworks.firstOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
            val isLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            isLan && manager.getLinkProperties(network)?.primaryIpv4Address() != null
        }
    }

    /**
     * Returns the directed broadcast address for an IPv4 address and prefix length.
     * Example: 192.168.1.23/24 -> 192.168.1.255.
     */
    fun broadcastAddress(ip: String, prefixLength: Int): String? {
        if (prefixLength !in 0..32 || !isValidIpv4(ip)) return null
        val octets = ip.trim().split(".").map { it.toLong() }
        var address = 0L
        octets.forEach { octet -> address = (address shl 8) or octet }
        val mask = if (prefixLength == 0) 0L else 0xFFFFFFFFL shl (32 - prefixLength)
        val broadcast = (address or mask.inv()) and 0xFFFFFFFFL
        return listOf(
            (broadcast ushr 24) and 0xFF,
            (broadcast ushr 16) and 0xFF,
            (broadcast ushr 8) and 0xFF,
            broadcast and 0xFF,
        ).joinToString(".")
    }

    fun isValidIpv4(value: String): Boolean {
        val parts = value.trim().split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                part.length <= 3 &&
                part.all { it in '0'..'9' } &&
                (part.toIntOrNull() ?: -1) in 0..255
        }
    }

    /**
     * Finds the phone's Tailscale IPv4 address (CGNAT range 100.64.0.0/10).
     * Returns null when Tailscale is disconnected or has not assigned an address.
     */
    fun tailscaleIpv4(): String? {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .flatMap { networkInterface ->
                    Collections.list(networkInterface.inetAddresses).asSequence()
                }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { address -> isTailscaleIpv4(address.hostAddress.orEmpty()) }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    fun isTailscaleIpv4(value: String): Boolean {
        if (!isValidIpv4(value)) return false
        val octets = value.trim().split(".").map { it.toInt() }
        return octets[0] == 100 && octets[1] in 64..127
    }

    /**
     * Broadcast targets that can plausibly carry a Wake-on-LAN packet from a phone:
     * the limited broadcast 255.255.255.255 or a unicast subnet's directed broadcast.
     * Loopback, unspecified, link-local and multicast/reserved ranges are rejected.
     */
    fun isUsableBroadcastAddress(value: String): Boolean {
        if (!isValidIpv4(value)) return false
        val trimmed = value.trim()
        if (trimmed == "255.255.255.255") return true
        val octets = trimmed.split(".").map { it.toInt() }
        if (octets.all { it == 0 }) return false
        return when (octets[0]) {
            0, 127 -> false
            169 -> octets[1] != 254
            in 224..255 -> false
            else -> true
        }
    }
}
