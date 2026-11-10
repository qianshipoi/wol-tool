package com.qianshi.woltool.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.qianshi.woltool.MainActivity
import com.qianshi.woltool.R
import com.qianshi.woltool.wol.NetworkUtils

/**
 * Keeps the relay HTTP server alive while the phone is on the home LAN.
 */
class RelayService : Service() {

    private var startedSuccessfully = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }

        val result = RelayManager.start(this)
        startedSuccessfully = result.isSuccess
        if (result.isFailure) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        RelayManager.stop(preserveError = !startedSuccessfully)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "远程唤醒中继",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持远程唤醒中继服务运行"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopRelay = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val port = RemoteSettings.relayConfig(this).port
        val tailscaleIp = NetworkUtils.tailscaleIpv4()
        val detail = if (tailscaleIp != null) {
            "$tailscaleIp:$port · 点击查看"
        } else {
            "端口 $port · 未检测到 Tailscale 地址"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_power)
            .setContentTitle("远程唤醒中继运行中")
            .setContentText(detail)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "停止", stopRelay)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "wol_relay"
        private const val NOTIFICATION_ID = 4101
        private const val REQUEST_OPEN_APP = 4102
        private const val REQUEST_STOP = 4103
        private const val ACTION_STOP = "com.qianshi.woltool.remote.STOP_RELAY"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RelayService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
}
