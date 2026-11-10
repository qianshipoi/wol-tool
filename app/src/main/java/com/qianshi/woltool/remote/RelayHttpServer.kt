package com.qianshi.woltool.remote

import android.content.Context
import android.util.Log
import com.qianshi.woltool.data.DeviceRepository
import com.qianshi.woltool.widget.WolWidgetProvider
import com.qianshi.woltool.wol.NetworkUtils
import com.qianshi.woltool.wol.WolSender
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny HTTP server used by the relay phone.
 *
 * It only exposes a few endpoints, requires the shared relay token for every
 * /api/ call, and sends WOL packets through the existing [WolSender].
 */
class RelayHttpServer(
    private val appContext: Context,
    val port: Int,
    private val token: String,
) {

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class Response(
        val status: Int,
        val body: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false

    fun start(): Result<Unit> = runCatching {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        running = true
        scope.launch { acceptLoop(socket) }
        Log.i(TAG, "Relay HTTP server listening on 0.0.0.0:$port")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
        Log.i(TAG, "Relay HTTP server stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (exception: Exception) {
                if (running) Log.w(TAG, "accept failed", exception)
                break
            }
            scope.launch {
                client.use {
                    runCatching { handleClient(it) }
                        .onFailure { error -> Log.w(TAG, "request failed", error) }
                }
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        client.soTimeout = SOCKET_TIMEOUT_MILLIS
        val request = try {
            readRequest(client)
        } catch (_: Exception) {
            null
        } ?: run {
            writeResponse(client, Response(400, errorJson("无法解析 HTTP 请求")))
            return
        }

        val response = try {
            dispatch(request)
        } catch (exception: Exception) {
            Log.e(TAG, "dispatch failed", exception)
            Response(500, errorJson(exception.message ?: "服务器内部错误"))
        }
        writeResponse(client, response)
    }

    private suspend fun dispatch(request: Request): Response {
        if (request.method == "GET" && request.path == "/health") {
            return Response(200, healthJson())
        }

        if (!request.path.startsWith("/api/")) {
            return Response(404, errorJson("接口不存在"))
        }
        if (!isAuthorized(request)) {
            return Response(401, errorJson("令牌无效"))
        }

        return when {
            request.path == "/api/devices" && request.method == "GET" -> devicesResponse()
            request.path == "/api/wake" &&
                (request.method == "POST" || request.method == "GET") -> wakeResponse(request)
            else -> Response(404, errorJson("接口不存在"))
        }
    }

    private fun healthJson(): String {
        val json = JSONObject()
            .put("ok", true)
            .put("service", "wol-relay")
            .put("port", port)
            .put("tailscaleIp", NetworkUtils.tailscaleIpv4() ?: JSONObject.NULL)
        return json.toString()
    }

    private fun devicesResponse(): Response {
        val devices = DeviceRepository.get(appContext).devices.value
        val array = JSONArray()
        devices.forEach { device ->
            array.put(
                JSONObject()
                    .put("id", device.id)
                    .put("name", device.name)
                    .put("mac", device.mac),
            )
        }
        return Response(200, JSONObject().put("ok", true).put("devices", array).toString())
    }

    private suspend fun wakeResponse(request: Request): Response {
        val deviceId = when (request.method) {
            "GET" -> request.query["deviceId"]
            else -> parseDeviceId(request.body)
        }
        if (deviceId.isNullOrBlank()) {
            return Response(400, errorJson("缺少 deviceId"))
        }

        val repository = DeviceRepository.get(appContext)
        val device = repository.device(deviceId)
            ?: return Response(404, errorJson("设备不存在"))

        val result = WolSender(appContext).wake(device)
        repository.recordWake(device.id, result.success)
        WolWidgetProvider.updateAll(appContext)

        if (!result.success) {
            return Response(
                502,
                JSONObject()
                    .put("ok", false)
                    .put("error", result.message)
                    .toString(),
            )
        }
        return Response(
            200,
            JSONObject()
                .put("ok", true)
                .put("deviceId", device.id)
                .put("deviceName", device.name)
                .put("packetsSent", result.packetsSent)
                .put("message", result.message)
                .toString(),
        )
    }

    private fun isAuthorized(request: Request): Boolean {
        if (token.isBlank()) return false
        val provided = request.headers["authorization"]
            ?.let { value ->
                if (value.startsWith("Bearer ", ignoreCase = true)) {
                    value.substringAfter(' ').trim()
                } else {
                    value.trim()
                }
            }
            ?: request.headers["x-relay-token"]
            ?: return false
        return MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            provided.toByteArray(Charsets.UTF_8),
        )
    }

    private fun parseDeviceId(body: String): String? {
        return runCatching {
            JSONObject(body).optString("deviceId").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun readRequest(client: Socket): Request? {
        val input = BufferedInputStream(client.getInputStream())
        val headerBuffer = ByteArrayOutputStream()
        var matched = 0
        while (headerBuffer.size() < MAX_HEADER_BYTES) {
            val byte = input.read()
            if (byte == -1) return null
            headerBuffer.write(byte)
            if (byte == '\r'.code) {
                matched = if (matched == 2) 3 else 1
            } else if (byte == '\n'.code) {
                if (matched == 3) break
                matched = if (matched == 1) 2 else 0
            } else {
                matched = 0
            }
        }

        val headerText = headerBuffer.toString(Charsets.UTF_8.name())
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null

        val method = parts[0].uppercase(Locale.US)
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val headers = mutableMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                    line.substring(separator + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength !in 0..MAX_BODY_BYTES) return null
        val bodyBytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val count = input.read(bodyBytes, read, contentLength - read)
            if (count < 0) break
            read += count
        }
        return Request(
            method = method,
            path = path,
            query = query,
            headers = headers,
            body = String(bodyBytes, 0, read, Charsets.UTF_8),
        )
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }.toMap()
    }

    private fun writeResponse(client: Socket, response: Response) {
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        val reason = when (response.status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 ${response.status} $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        client.getOutputStream().use { output ->
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        }
    }

    private fun errorJson(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    private companion object {
        const val TAG = "WolRelay"
        const val SOCKET_TIMEOUT_MILLIS = 10_000
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 16 * 1024
    }
}
