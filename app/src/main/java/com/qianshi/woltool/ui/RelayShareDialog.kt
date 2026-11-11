package com.qianshi.woltool.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.qianshi.woltool.remote.RelayShare
import kotlinx.coroutines.delay

/** “已复制”提示的停留时长。 */
private const val COPIED_HINT_MILLIS = 2_000L

/**
 * 中继侧分享二维码的对话框。
 *
 * 内容全部来自参数：不读全局状态、不处理业务逻辑，方便 Lead 在 RemoteScreen 里直接调用。
 */
@Composable
fun RelayShareDialog(
    share: RelayShare,
    deviceCount: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val shareCode = remember(share) { share.encode() }
    var copied by remember(shareCode) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_HINT_MILLIS)
            copied = false
        }
    }

    val copyShareCode: () -> Unit = {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("WOL 中继分享码", shareCode))
        copied = true
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 内容可滚动、操作按钮固定在底部，避免小屏上「关闭」被挤出可视区域。
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "分享中继二维码",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "远程手机打开「远程唤醒 → 远程发送」，点「扫描二维码」即可自动填入地址和令牌。",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    // 二维码固定用纯白底 + 纯黑码：不随深浅主题反转，保证相机一定能识别。
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        QrCodeImage(
                            content = shareCode,
                            modifier = Modifier
                                .padding(12.dp)
                                .size(228.dp),
                            darkColor = Color.Black,
                            lightColor = Color.White,
                        )
                    }

                    Text(
                        text = share.url,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "已配置 $deviceCount 台设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "二维码内含中继令牌",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = "请勿公开分享二维码：拿到它的手机可以唤醒你这台设备所在局域网里的电脑。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 24.dp, top = 4.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = copyShareCode) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (copied) "已复制" else "复制分享码")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}
