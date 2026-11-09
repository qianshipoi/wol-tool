package com.qianshi.woltool.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WakeHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("使用条件与排查") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                HelpItem(
                    title = "目标设备支持 WOL",
                    body = "在 BIOS/UEFI 中开启“Wake on LAN / Power On By PCI-E”，并在网卡属性 → 高级中启用“Wake on Magic Packet”。",
                )
                HelpItem(
                    title = "使用有线网络",
                    body = "目标设备需通过网线连接到路由器或交换机。手机与目标设备应处于同一局域网，关闭路由器的 AP 隔离。",
                )
                HelpItem(
                    title = "关机后网卡仍供电",
                    body = "Windows 关闭“快速启动”，避免 S5 关机时网卡断电；部分主板还需开启 ErP 相关的网络唤醒选项。",
                )
                HelpItem(
                    title = "广播地址与端口",
                    body = "优先填写子网广播地址，例如 192.168.1.255；若路由器拦截广播，可尝试 255.255.255.255。端口通常为 9，少数设备使用 7。",
                )
                HelpItem(
                    title = "桌面小组件",
                    body = "长按桌面空白处 → 小组件 → 找到“WOL 唤醒” → 拖到桌面并选择设备。之后点按小组件即可直接发送魔术包，无需打开 App。",
                )
                HelpItem(
                    title = "仍然无法唤醒",
                    body = "检查目标设备 MAC 是否填写正确；确认手机当前网络能访问该子网；尝试增加发送次数或在路由器中允许 UDP 广播。",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        },
    )
}

@Composable
private fun HelpItem(title: String, body: String) {
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
