package com.qianshi.woltool.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.qianshi.woltool.wol.WolPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small JSON backed store for configured devices.
 *
 * The data volume is tiny, so SharedPreferences + a StateFlow keeps the app
 * dependency free and lets both the UI and the widget read consistent data.
 */
class DeviceRepository private constructor(context: Context) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    private val _devices = MutableStateFlow(readDevices())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    fun device(id: String): Device? = _devices.value.firstOrNull { it.id == id }

    fun save(device: Device) {
        mutate { devices ->
            val existingIndex = devices.indexOfFirst { it.id == device.id }
            if (existingIndex >= 0) {
                devices[existingIndex] = device
            } else {
                devices.add(device)
            }
        }
    }

    fun delete(id: String) {
        mutate { devices -> devices.removeAll { it.id == id } }
    }

    fun recordWake(id: String, success: Boolean, atMillis: Long = System.currentTimeMillis()) {
        mutate { devices ->
            val index = devices.indexOfFirst { it.id == id }
            if (index >= 0) {
                devices[index] = devices[index].copy(
                    lastWakeAt = atMillis,
                    lastWakeSuccess = success,
                )
            }
        }
    }

    private fun mutate(block: (MutableList<Device>) -> Unit) {
        synchronized(lock) {
            val updated = _devices.value.toMutableList()
            block(updated)
            _devices.value = updated
            preferences.edit().putString(KEY_DEVICES, encode(updated)).apply()
        }
    }

    private fun readDevices(): List<Device> {
        val raw = preferences.getString(KEY_DEVICES, null) ?: return emptyList()
        return try {
            decode(raw)
        } catch (exception: Exception) {
            // Keep the unreadable payload around instead of silently destroying it
            // on the next save, and leave a trail for bug reports.
            Log.e(TAG, "Unable to parse stored devices; keeping a backup copy", exception)
            preferences.edit().putString(KEY_BACKUP, raw).apply()
            emptyList()
        }
    }

    private fun encode(devices: List<Device>): String {
        val array = JSONArray()
        devices.forEach { device ->
            val json = JSONObject()
                .put(FIELD_ID, device.id)
                .put(FIELD_NAME, device.name)
                .put(FIELD_MAC, device.mac)
                .put(FIELD_BROADCAST, device.broadcastAddress)
                .put(FIELD_PORT, device.port)
                .put(FIELD_PACKET_COUNT, device.packetCount)
                .put(FIELD_CREATED_AT, device.createdAt)
            device.lastWakeAt?.let { json.put(FIELD_LAST_WAKE_AT, it) }
            device.lastWakeSuccess?.let { json.put(FIELD_LAST_WAKE_SUCCESS, it) }
            array.put(json)
        }
        return array.toString()
    }

    private fun decode(raw: String): List<Device> {
        val array = JSONArray(raw)
        val result = mutableListOf<Device>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val id = json.optString(FIELD_ID).takeIf { it.isNotBlank() } ?: continue
            val mac = WolPacket.normalizeMac(json.optString(FIELD_MAC)) ?: continue
            val name = json.optString(FIELD_NAME).ifBlank { "未命名设备" }
            val broadcast = json.optString(FIELD_BROADCAST).ifBlank { "255.255.255.255" }
            result.add(
                Device(
                    id = id,
                    name = name,
                    mac = mac,
                    broadcastAddress = broadcast,
                    port = json.optInt(FIELD_PORT, 9).coerceIn(1, 65535),
                    packetCount = json.optInt(FIELD_PACKET_COUNT, 3).coerceIn(1, 10),
                    createdAt = json.optLong(FIELD_CREATED_AT, 0L),
                    lastWakeAt = if (json.has(FIELD_LAST_WAKE_AT)) json.optLong(FIELD_LAST_WAKE_AT) else null,
                    lastWakeSuccess = if (json.has(FIELD_LAST_WAKE_SUCCESS)) json.optBoolean(FIELD_LAST_WAKE_SUCCESS) else null,
                ),
            )
        }
        return result
    }

    companion object {
        private const val TAG = "DeviceRepository"
        private const val PREFERENCES_NAME = "wol_devices"
        private const val KEY_DEVICES = "devices"
        private const val KEY_BACKUP = "devices_backup"

        private const val FIELD_ID = "id"
        private const val FIELD_NAME = "name"
        private const val FIELD_MAC = "mac"
        private const val FIELD_BROADCAST = "broadcast"
        private const val FIELD_PORT = "port"
        private const val FIELD_PACKET_COUNT = "packetCount"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_LAST_WAKE_AT = "lastWakeAt"
        private const val FIELD_LAST_WAKE_SUCCESS = "lastWakeSuccess"

        @Volatile
        private var instance: DeviceRepository? = null

        fun get(context: Context): DeviceRepository {
            return instance ?: synchronized(this) {
                instance ?: DeviceRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
