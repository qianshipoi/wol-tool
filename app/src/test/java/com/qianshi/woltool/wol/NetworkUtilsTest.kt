package com.qianshi.woltool.wol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun `computes directed broadcast for common prefixes`() {
        assertEquals("192.168.1.255", NetworkUtils.broadcastAddress("192.168.1.23", 24))
        assertEquals("192.168.255.255", NetworkUtils.broadcastAddress("192.168.1.23", 16))
        assertEquals("10.255.255.255", NetworkUtils.broadcastAddress("10.1.2.3", 8))
        assertEquals("172.16.1.255", NetworkUtils.broadcastAddress("172.16.0.5", 23))
        assertEquals("192.168.1.42", NetworkUtils.broadcastAddress("192.168.1.42", 32))
        assertEquals("255.255.255.255", NetworkUtils.broadcastAddress("0.0.0.0", 0))
    }

    @Test
    fun `rejects invalid prefix length`() {
        assertNull(NetworkUtils.broadcastAddress("192.168.1.23", -1))
        assertNull(NetworkUtils.broadcastAddress("192.168.1.23", 33))
    }

    @Test
    fun `rejects invalid ipv4 strings`() {
        assertNull(NetworkUtils.broadcastAddress("192.168.1", 24))
        assertNull(NetworkUtils.broadcastAddress("999.1.1.1", 24))
        assertNull(NetworkUtils.broadcastAddress("192.168.1.a", 24))
    }

    @Test
    fun `validates ipv4 literals`() {
        assertTrue(NetworkUtils.isValidIpv4("192.168.1.255"))
        assertTrue(NetworkUtils.isValidIpv4("255.255.255.255"))
        assertTrue(NetworkUtils.isValidIpv4("0.0.0.0"))
        assertFalse(NetworkUtils.isValidIpv4("256.0.0.1"))
        assertFalse(NetworkUtils.isValidIpv4("1.2.3"))
        assertFalse(NetworkUtils.isValidIpv4("1.2.3.4.5"))
        assertFalse(NetworkUtils.isValidIpv4(""))
        assertFalse(NetworkUtils.isValidIpv4("abc.def.ghi.jkl"))
    }

    @Test
    fun `accepts only usable broadcast targets`() {
        assertTrue(NetworkUtils.isUsableBroadcastAddress("192.168.1.255"))
        assertTrue(NetworkUtils.isUsableBroadcastAddress("10.0.2.255"))
        assertTrue(NetworkUtils.isUsableBroadcastAddress("255.255.255.255"))

        assertFalse(NetworkUtils.isUsableBroadcastAddress("0.0.0.0"))
        assertFalse(NetworkUtils.isUsableBroadcastAddress("127.0.0.1"))
        assertFalse(NetworkUtils.isUsableBroadcastAddress("169.254.1.255"))
        assertFalse(NetworkUtils.isUsableBroadcastAddress("224.0.0.1"))
        assertFalse(NetworkUtils.isUsableBroadcastAddress("not-an-ip"))
    }
}
