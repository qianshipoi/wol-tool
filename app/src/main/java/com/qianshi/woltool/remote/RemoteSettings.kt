package com.qianshi.woltool.remote

import android.content.Context
import java.security.SecureRandom
import java.util.Base64

/**
 * Persists the relay server and remote client settings.
 *
 * The relay token is generated locally on first use. Both phones in the
 * Tailscale tailnet must use the same token to talk to each other.
 */
object RemoteSettings {

    private const val PREFERENCES_NAME = "wol_remote"
    private const val KEY_RELAY_PORT = "relay_port"
    private const val KEY_RELAY_TOKEN = "relay_token"
    private const val KEY_REMOTE_URL = "remote_url"
    private const val KEY_REMOTE_TOKEN = "remote_token"

    const val DEFAULT_RELAY_PORT = 8787

    data class RelayConfig(
        val port: Int,
        val token: String,
    )

    data class RemoteConfig(
        val url: String,
        val token: String,
    )

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun relayConfig(context: Context): RelayConfig {
        val prefs = preferences(context)
        var token = prefs.getString(KEY_RELAY_TOKEN, null).orEmpty()
        if (token.isBlank()) {
            token = generateToken()
            prefs.edit().putString(KEY_RELAY_TOKEN, token).apply()
        }
        val port = prefs.getInt(KEY_RELAY_PORT, DEFAULT_RELAY_PORT)
            .takeIf { it in 1024..65535 }
            ?: DEFAULT_RELAY_PORT
        return RelayConfig(port = port, token = token)
    }

    fun setRelayConfig(context: Context, port: Int, token: String) {
        preferences(context).edit()
            .putInt(KEY_RELAY_PORT, port)
            .putString(KEY_RELAY_TOKEN, token)
            .apply()
    }

    fun remoteConfig(context: Context): RemoteConfig {
        val prefs = preferences(context)
        return RemoteConfig(
            url = prefs.getString(KEY_REMOTE_URL, null).orEmpty(),
            token = prefs.getString(KEY_REMOTE_TOKEN, null).orEmpty(),
        )
    }

    fun setRemoteConfig(context: Context, url: String, token: String) {
        preferences(context).edit()
            .putString(KEY_REMOTE_URL, url)
            .putString(KEY_REMOTE_TOKEN, token)
            .apply()
    }

    fun generateToken(): String {
        val random = ByteArray(24)
        SecureRandom().nextBytes(random)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random)
    }
}
