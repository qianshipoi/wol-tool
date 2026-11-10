package com.qianshi.woltool.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.qianshi.woltool.R
import com.qianshi.woltool.data.DeviceRepository
import com.qianshi.woltool.wol.WolSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Home screen widget that sends a magic packet directly from its click handler,
 * without launching the app.
 */
class WolWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { appWidgetId ->
            clearStalePending(context, appWidgetId)
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = widgetPreferences(context).edit()
        appWidgetIds.forEach { appWidgetId ->
            editor.remove(deviceKey(appWidgetId))
            editor.remove(lastWakeAtKey(appWidgetId))
            editor.remove(lastWakeOkKey(appWidgetId))
            editor.remove(pendingAtKey(appWidgetId))
        }
        editor.apply()
    }

    /**
     * Widget ids are assigned by the launcher, so restored preferences cannot be
     * trusted on a new device/launcher. Clear bindings and show the config prompt.
     */
    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        widgetPreferences(context).edit().clear().apply()
        newWidgetIds.forEach { appWidgetId -> updateWidget(context, appWidgetId) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_WAKE) return

        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        // The persisted binding is authoritative; the PendingIntent extra is a fallback.
        val deviceId = readDeviceId(context, appWidgetId)
            ?: intent.getStringExtra(EXTRA_DEVICE_ID)
        val pendingResult = goAsync()
        wakeAsync(context.applicationContext, pendingResult, appWidgetId, deviceId)
    }

    private fun wakeAsync(
        appContext: Context,
        pendingResult: BroadcastReceiver.PendingResult,
        appWidgetId: Int,
        deviceId: String?,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(GO_ASYNC_TIMEOUT_MILLIS) {
                    performWake(appContext, appWidgetId, deviceId)
                }
            } catch (exception: Exception) {
                // Never let a widget tap crash the host process; report failure instead.
                runCatching { markFailed(appContext, appWidgetId) }
                runCatching { updateWidget(appContext, appWidgetId) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun performWake(
        appContext: Context,
        appWidgetId: Int,
        deviceId: String?,
    ) {
        val repository = DeviceRepository.get(appContext)
        val device = deviceId?.let(repository::device)
        if (device == null) {
            updateWidget(appContext, appWidgetId)
            return
        }

        widgetPreferences(appContext).edit()
            .putLong(pendingAtKey(appWidgetId), System.currentTimeMillis())
            .apply()
        updateWidget(appContext, appWidgetId)

        val result = WolSender(appContext).wake(device)
        repository.recordWake(device.id, result.success)

        widgetPreferences(appContext).edit()
            .remove(pendingAtKey(appWidgetId))
            .putLong(lastWakeAtKey(appWidgetId), System.currentTimeMillis())
            .putBoolean(lastWakeOkKey(appWidgetId), result.success)
            .apply()
        updateWidget(appContext, appWidgetId)
    }

    private fun markFailed(context: Context, appWidgetId: Int) {
        widgetPreferences(context).edit()
            .remove(pendingAtKey(appWidgetId))
            .putLong(lastWakeAtKey(appWidgetId), System.currentTimeMillis())
            .putBoolean(lastWakeOkKey(appWidgetId), false)
            .apply()
    }

    companion object {
        const val ACTION_WAKE = "com.qianshi.woltool.action.WAKE"
        const val EXTRA_DEVICE_ID = "com.qianshi.woltool.extra.DEVICE_ID"

        private const val WIDGET_PREFERENCES = "wol_widgets"
        private const val CONFIG_REQUEST_OFFSET = 100_000
        private const val GO_ASYNC_TIMEOUT_MILLIS = 8_000L
        private const val PENDING_STALE_MILLIS = 45_000L
        private const val PENDING_INTENT_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private fun widgetPreferences(context: Context) =
            context.getSharedPreferences(WIDGET_PREFERENCES, Context.MODE_PRIVATE)

        private fun deviceKey(appWidgetId: Int) = "device_$appWidgetId"
        private fun lastWakeAtKey(appWidgetId: Int) = "last_at_$appWidgetId"
        private fun lastWakeOkKey(appWidgetId: Int) = "last_ok_$appWidgetId"
        private fun pendingAtKey(appWidgetId: Int) = "pending_at_$appWidgetId"

        fun readDeviceId(context: Context, appWidgetId: Int): String? =
            widgetPreferences(context).getString(deviceKey(appWidgetId), null)

        /** Associates a widget instance with a device and clears stale status. */
        fun bindWidget(context: Context, appWidgetId: Int, deviceId: String) {
            widgetPreferences(context).edit()
                .putString(deviceKey(appWidgetId), deviceId)
                .remove(lastWakeAtKey(appWidgetId))
                .remove(lastWakeOkKey(appWidgetId))
                .remove(pendingAtKey(appWidgetId))
                .apply()
        }

        fun updateWidget(context: Context, appWidgetId: Int) {
            AppWidgetManager.getInstance(context)
                .updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, WolWidgetProvider::class.java),
            )
            ids.forEach { appWidgetId ->
                clearStalePending(context, appWidgetId)
                manager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
            }
        }

        private fun clearStalePending(context: Context, appWidgetId: Int) {
            val preferences = widgetPreferences(context)
            val pendingAt = preferences.getLong(pendingAtKey(appWidgetId), 0L)
            if (pendingAt > 0L && System.currentTimeMillis() - pendingAt > PENDING_STALE_MILLIS) {
                preferences.edit().remove(pendingAtKey(appWidgetId)).apply()
            }
        }

        private fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.wol_widget)
            val deviceId = readDeviceId(context, appWidgetId)
            val device = deviceId?.let { DeviceRepository.get(context).device(it) }

            if (device == null) {
                val title = if (deviceId == null) {
                    context.getString(R.string.app_name)
                } else {
                    "设备已删除"
                }
                val status = if (deviceId == null) "点按配置" else "点按重选"
                views.setTextViewText(R.id.widget_name, title)
                views.setTextViewText(R.id.widget_status, status)
                views.setContentDescription(
                    R.id.widget_root,
                    if (deviceId == null) {
                        "${context.getString(R.string.app_name)}，点按配置设备"
                    } else {
                        "设备已删除，点按重新选择设备"
                    },
                )
                views.setOnClickPendingIntent(
                    R.id.widget_root,
                    configurePendingIntent(context, appWidgetId),
                )
            } else {
                val status = statusLabel(context, appWidgetId)
                views.setTextViewText(R.id.widget_name, device.name)
                views.setTextViewText(R.id.widget_status, status)
                views.setContentDescription(
                    R.id.widget_root,
                    "${device.name}，$status，点按唤醒",
                )
                views.setOnClickPendingIntent(
                    R.id.widget_root,
                    wakePendingIntent(context, appWidgetId, device.id),
                )
            }
            return views
        }

        private fun statusLabel(context: Context, appWidgetId: Int): String {
            val preferences = widgetPreferences(context)
            val pendingAt = preferences.getLong(pendingAtKey(appWidgetId), 0L)
            if (pendingAt > 0L &&
                System.currentTimeMillis() - pendingAt < PENDING_STALE_MILLIS
            ) {
                return "正在发送…"
            }
            val lastWakeAt = preferences.getLong(lastWakeAtKey(appWidgetId), 0L)
            if (lastWakeAt <= 0L) return "点按唤醒"
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastWakeAt))
            return if (preferences.getBoolean(lastWakeOkKey(appWidgetId), false)) {
                "已发送 · $time"
            } else {
                "发送失败 · $time"
            }
        }

        private fun wakePendingIntent(
            context: Context,
            appWidgetId: Int,
            deviceId: String,
        ): PendingIntent {
            val intent = Intent(context, WolWidgetProvider::class.java).apply {
                action = ACTION_WAKE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(EXTRA_DEVICE_ID, deviceId)
            }
            return PendingIntent.getBroadcast(
                context,
                appWidgetId,
                intent,
                PENDING_INTENT_FLAGS,
            )
        }

        private fun configurePendingIntent(
            context: Context,
            appWidgetId: Int,
        ): PendingIntent {
            val intent = Intent(context, WidgetConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                appWidgetId + CONFIG_REQUEST_OFFSET,
                intent,
                PENDING_INTENT_FLAGS,
            )
        }
    }
}
