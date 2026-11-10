package com.qianshi.woltool

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qianshi.woltool.data.Device
import com.qianshi.woltool.data.DeviceRepository
import com.qianshi.woltool.widget.WolWidgetProvider
import com.qianshi.woltool.wol.WolSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WolViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DeviceRepository.get(application)
    private val sender = WolSender(application)

    val devices: StateFlow<List<Device>> = repository.devices

    private val _wakingIds = MutableStateFlow<Set<String>>(emptySet())
    val wakingIds: StateFlow<Set<String>> = _wakingIds.asStateFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun save(device: Device) {
        repository.save(device)
        refreshWidgets()
        _messages.tryEmit("已保存「${device.name}」")
    }

    fun delete(device: Device) {
        repository.delete(device.id)
        refreshWidgets()
        _messages.tryEmit("已删除「${device.name}」")
    }

    fun wake(device: Device) {
        if (device.id in _wakingIds.value) return
        _wakingIds.update { it + device.id }
        viewModelScope.launch {
            try {
                val result = sender.wake(device)
                repository.recordWake(device.id, result.success)
                refreshWidgets()
                val message = if (result.success) {
                    "「${device.name}」${result.message}"
                } else {
                    "「${device.name}」发送失败：${result.message}"
                }
                _messages.emit(message)
            } finally {
                _wakingIds.update { it - device.id }
            }
        }
    }

    private fun refreshWidgets() {
        viewModelScope.launch(Dispatchers.IO) {
            WolWidgetProvider.updateAll(getApplication())
        }
    }
}
