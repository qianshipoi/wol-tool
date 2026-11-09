package com.qianshi.woltool.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qianshi.woltool.data.DeviceRepository
import com.qianshi.woltool.ui.DeviceEditorScreen
import com.qianshi.woltool.ui.WidgetConfigScreen
import com.qianshi.woltool.ui.theme.WolToolTheme

/**
 * Launcher entry point shown when a widget instance is created or reconfigured.
 * It lets the user pick (or create) the device bound to this widget.
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val widgetInfo = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)
        if (widgetInfo?.provider != ComponentName(this, WolWidgetProvider::class.java)) {
            finish()
            return
        }

        enableEdgeToEdge()
        val repository = DeviceRepository.get(applicationContext)
        val initiallySelectedId = WolWidgetProvider.readDeviceId(this, appWidgetId)

        setContent {
            WolToolTheme {
                val devices by repository.devices.collectAsStateWithLifecycle()
                var selectedId by rememberSaveable { mutableStateOf(initiallySelectedId) }
                var editorOpen by rememberSaveable { mutableStateOf(false) }

                BackHandler(enabled = !editorOpen) {
                    finish()
                }

                if (editorOpen) {
                    DeviceEditorScreen(
                        initial = null,
                        onBack = { editorOpen = false },
                        onSave = { device ->
                            repository.save(device)
                            selectedId = device.id
                            editorOpen = false
                            WolWidgetProvider.updateAll(this@WidgetConfigActivity)
                        },
                        onDelete = { /* not reachable for a new device */ },
                    )
                } else {
                    WidgetConfigScreen(
                        devices = devices,
                        selectedId = selectedId,
                        onSelect = { selectedId = it },
                        onAddDevice = { editorOpen = true },
                        onConfirm = { deviceId -> confirmSelection(deviceId) },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    private fun confirmSelection(deviceId: String) {
        WolWidgetProvider.bindWidget(this, appWidgetId, deviceId)
        WolWidgetProvider.updateWidget(this, appWidgetId)
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )
        finish()
    }
}
