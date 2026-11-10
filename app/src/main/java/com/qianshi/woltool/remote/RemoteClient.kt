package com.qianshi.woltool.remote

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * HTTP client used by the remote phone to talk to the relay phone over Tailscale.
 */
class RemoteClient {

    data class RemoteDevice(
        val id: String,
        val name: String,
        val mac: String,
    )

    data class WakeResult(
        val deviceName: String,
        val packetsSent: Int,
        val message: String,
    )

    data class Result<T>(
        val success: Boolean,
        val value: T? = null,
        val error: String? = null,
    )

    suspend fun listDevices(baseUrl: String, token: String): Result<List<RemoteDevice>> =
        withContext(Dispatchers.IO) {
            val base = normalizeBaseUrl(baseUrl)
                ?: return@withContext Result(false, error = "中继地址格式不正确")
            if (token.isBlank()) {
                return@withContext Result(false, error = "请填写中继令牌")
            }
            when (val response = request("$base/api/devices", "GET", token, null)) {
                is HttpResult.Error -> Result(false, error = response.message)
                is HttpResult.Success -> {
                    try {
                        val array = JSONObject(response.body).optJSONArray("devices") ?: JSONArray()
                        val devices = buildList {
                            for (index in 0 until array.length()) {
                                val item = array.optJSONObject(index) ?: continue
                                val id = item.optString("id")
                                if (id.isBlank()) continue
                                add(
                                    RemoteDevice(
                                        id = id,
                                        name = item.optString("name").ifBlank { "未命名设备" },
                                        mac = item.optString("mac"),
                                    ),
                                )
                            }
                        }
                        Result(true, devices)
                    } catch (exception: Exception) {
                        Result(false, error = "中继返回的数据无法解析")
                    }
                }
            }
        }

    suspend fun wake(
        baseUrl: String,
        token: String,
        deviceId: String,
    ): Result<WakeResult> = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(baseUrl)
            ?: return@withContext Result(false, error = "中继地址格式不正确")
        if (token.isBlank()) {
            return@withContext Result(false, error = "请填写中继令牌")
        }
        if (deviceId.isBlank()) {
            return@withContext Result(false, error = "请选择要唤醒的设备")
        }
        val body = JSONObject().put("deviceId", deviceId).toString()
        when (val response = request("$base/api/wake", "POST", token, body)) {
            is HttpResult.Error -> Result(false, error = response.message)
            is HttpResult.Success -> {
                try {
                    val json = JSONObject(response.body)
                    if (!json.optBoolean("ok", false)) {
                        Result(false, error = json.optString("error").ifBlank { "中继发送失败" })
                    } else {
                        Result(
                            true,
                            WakeResult(
                                deviceName = json.optString("deviceName"),
                                packetsSent = json.optInt("packetsSent"),
                                message = json.optString("message"),
                            ),
                        )
                    }
                } catch (exception: Exception) {
                    Result(false, error = "中继返回的数据无法解析")
                }
            }
        }
    }

    fun normalizeBaseUrl(input: String): String? {
        var value = input.trim()
        if (value.isBlank()) return null
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            // Reject other explicit schemes instead of turning them into
            // http://ftp://host.
            if (value.contains("://")) return null
            value = "http://$value"
        }
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        return value.trimEnd('/')
    }

    private fun request(
        url: String,
        method: String,
        token: String,
        body: String?,
    ): HttpResult {
        var connection: HttpURLConnection? = null
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            connection = conn
            if (body != null) {
                conn.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val status = conn.responseCode
            val text = (if (status in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream
            })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(text).optString("error")
                }.getOrNull().orEmpty().ifBlank {
                    "中继返回 HTTP $status"
                }
                HttpResult.Error(message)
            } else {
                HttpResult.Success(text)
            }
        } catch (exception: UnknownHostException) {
            HttpResult.Error("无法解析中继地址，请检查 Tailscale 是否连接")
        } catch (exception: ConnectException) {
            HttpResult.Error("无法连接中继，请检查中继服务、端口和 Tailscale")
        } catch (exception: SocketTimeoutException) {
            HttpResult.Error("连接中继超时")
        } catch (exception: IOException) {
            HttpResult.Error(exception.message ?: "网络请求失败")
        } catch (exception: Exception) {
            HttpResult.Error(exception.message ?: "网络请求失败")
        } finally {
            connection?.disconnect()
        }
    }

    private sealed interface HttpResult {
        data class Success(val body: String) : HttpResult
        data class Error(val message: String) : HttpResult
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 20_000
    }
}
