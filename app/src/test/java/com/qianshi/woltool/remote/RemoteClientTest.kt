package com.qianshi.woltool.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteClientTest {

    private val client = RemoteClient()

    @Test
    fun `adds http scheme when missing`() {
        assertEquals(
            "http://100.64.0.2:8787",
            client.normalizeBaseUrl("100.64.0.2:8787"),
        )
        assertEquals(
            "http://100.64.0.2:8787",
            client.normalizeBaseUrl(" http://100.64.0.2:8787/ "),
        )
    }

    @Test
    fun `keeps https and strips trailing slash`() {
        assertEquals(
            "https://relay.example.ts.net:8787",
            client.normalizeBaseUrl("https://relay.example.ts.net:8787/"),
        )
    }

    @Test
    fun `rejects empty and non-http urls`() {
        assertNull(client.normalizeBaseUrl(""))
        assertNull(client.normalizeBaseUrl("ftp://100.64.0.2"))
        assertNull(client.normalizeBaseUrl("http://"))
    }
}
