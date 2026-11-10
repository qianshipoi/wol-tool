package com.qianshi.woltool.remote

import android.content.Context
import com.qianshi.woltool.wol.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the lifecycle of the relay HTTP server. The foreground service calls
 * [start] and [stop]; the UI observes [state].
 */
object RelayManager {

    data class RelayState(
        val running: Boolean = false,
        val port: Int = RemoteSettings.DEFAULT_RELAY_PORT,
        val tailscaleIp: String? = null,
        val error: String? = null,
    )

    private val lock = Any()
    private var server: RelayHttpServer? = null

    private val _state = MutableStateFlow(RelayState())
    val state: StateFlow<RelayState> = _state.asStateFlow()

    fun start(context: Context): Result<Unit> {
        synchronized(lock) {
            server?.stop()
            server = null
            val config = RemoteSettings.relayConfig(context)
            val newServer = RelayHttpServer(
                appContext = context.applicationContext,
                port = config.port,
                token = config.token,
            )
            val result = newServer.start()
            return if (result.isSuccess) {
                server = newServer
                _state.value = RelayState(
                    running = true,
                    port = config.port,
                    tailscaleIp = NetworkUtils.tailscaleIpv4(),
                    error = null,
                )
                Result.success(Unit)
            } else {
                _state.value = RelayState(
                    running = false,
                    port = config.port,
                    tailscaleIp = NetworkUtils.tailscaleIpv4(),
                    error = result.exceptionOrNull()?.message ?: "中继服务启动失败",
                )
                Result.failure(result.exceptionOrNull() ?: IllegalStateException("中继服务启动失败"))
            }
        }
    }

    fun stop(preserveError: Boolean = false) {
        synchronized(lock) {
            server?.stop()
            server = null
            _state.value = RelayState(
                running = false,
                port = _state.value.port,
                tailscaleIp = _state.value.tailscaleIp,
                error = if (preserveError) _state.value.error else null,
            )
        }
    }

    fun refreshTailscaleIp() {
        synchronized(lock) {
            _state.value = _state.value.copy(tailscaleIp = NetworkUtils.tailscaleIpv4())
        }
    }
}
