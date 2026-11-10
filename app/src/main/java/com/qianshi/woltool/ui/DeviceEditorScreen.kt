package com.qianshi.woltool.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.qianshi.woltool.data.Device
import com.qianshi.woltool.wol.NetworkUtils
import com.qianshi.woltool.wol.WolPacket
import com.qianshi.woltool.wol.WolSender
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeviceEditorScreen(
    initial: Device?,
    onBack: () -> Unit,
    onSave: (Device) -> Unit,
    onDelete: (Device) -> Unit,
) {
    val context = LocalContext.current
    val sender = remember { WolSender(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val motionScheme = MaterialTheme.motionScheme

    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var macField by rememberSaveable(initial?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial?.mac.orEmpty()))
    }
    var broadcast by rememberSaveable(initial?.id) {
        mutableStateOf(
            initial?.broadcastAddress
                ?: NetworkUtils.current(context).broadcastAddress
                ?: "255.255.255.255",
        )
    }
    var port by rememberSaveable(initial?.id) { mutableStateOf((initial?.port ?: 9).toString()) }
    var packetCount by rememberSaveable(initial?.id) {
        mutableIntStateOf((initial?.packetCount ?: 3).coerceIn(1, 10))
    }

    // The state-based Slider is controlled: the callback has to write the value back into the
    // state, otherwise the thumb/track stay where they were while only our value changes.
    val packetSliderState = rememberSliderState(
        value = packetCount.toFloat(),
        steps = 8,
        trackRange = 1f..10f,
    )

    var localNetwork by remember { mutableStateOf(NetworkUtils.current(context)) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<WolSender.SendResult?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val macDigitCount = macField.text.count(Char::isMacHex)
    val macValid = macDigitCount == 12 && WolPacket.parseMac(macField.text) != null
    val broadcastValid = NetworkUtils.isUsableBroadcastAddress(broadcast)
    val portValue = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val nameValid = name.trim().isNotEmpty()
    val canSave = nameValid && macValid && broadcastValid && portValue != null
    val canTest = macValid && broadcastValid && portValue != null

    fun buildDevice(): Device? {
        val macValue = WolPacket.normalizeMac(macField.text) ?: return null
        val portNumber = portValue ?: return null
        return Device(
            id = initial?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            mac = macValue,
            broadcastAddress = broadcast.trim(),
            port = portNumber,
            packetCount = packetCount,
            createdAt = initial?.createdAt ?: System.currentTimeMillis(),
            lastWakeAt = initial?.lastWakeAt,
            lastWakeSuccess = initial?.lastWakeSuccess,
        )
    }

    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(if (initial == null) "添加设备" else "编辑设备")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    if (initial != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = "删除设备",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Button(
                        onClick = { buildDevice()?.let(onSave) },
                        enabled = canSave,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(if (initial == null) "保存设备" else "保存修改")
                    }
                }
            }
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FormSection(title = "基本信息") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("设备名称") },
                    placeholder = { Text("例如：书房电脑") },
                    singleLine = true,
                    isError = name.isNotEmpty() && name.isBlank(),
                    supportingText = {
                        Text(if (name.isNotEmpty() && name.isBlank()) "名称不能为空" else "用于在列表和桌面小组件中识别设备")
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = macField,
                    onValueChange = { newValue -> macField = applyMacMask(macField, newValue) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("MAC 地址") },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    supportingText = {
                        Text(
                            when {
                                macDigitCount == 0 -> "输入两位十六进制后自动添加冒号，可直接粘贴完整 MAC"
                                macDigitCount < 12 -> "已输入 $macDigitCount/12 位，继续输入会自动补冒号"
                                else -> "MAC 地址有效"
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
                )
            }

            FormSection(title = "网络参数") {
                OutlinedTextField(
                    value = broadcast,
                    onValueChange = { broadcast = it.trim().take(15) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("广播地址") },
                    placeholder = { Text("192.168.1.255") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    isError = broadcast.isNotEmpty() && !broadcastValid,
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                val fresh = NetworkUtils.current(context)
                                localNetwork = fresh
                                fresh.broadcastAddress?.let { broadcast = it }
                            },
                        ) {
                            Text("自动")
                        }
                    },
                    supportingText = {
                        val networkHint = if (localNetwork.ipAddress != null) {
                            "当前 ${localNetwork.transportLabel} ${localNetwork.ipAddress}/${localNetwork.prefixLength}，建议 ${localNetwork.broadcastAddress ?: "255.255.255.255"}"
                        } else {
                            "未检测到局域网 IPv4 地址，可填写 255.255.255.255 或子网广播地址"
                        }
                        Text(
                            if (broadcast.isNotEmpty() && !broadcastValid) {
                                "请输入可用的 IPv4 广播地址"
                            } else {
                                networkHint
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("端口") },
                    singleLine = true,
                    isError = port.isNotEmpty() && portValue == null,
                    supportingText = {
                        Text(if (port.isNotEmpty() && portValue == null) "1–65535" else "常用 9 或 7")
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "发送次数",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.weight(1f))
                    AnimatedContent(
                        targetState = packetCount,
                        transitionSpec = {
                            (slideInVertically(motionScheme.fastSpatialSpec()) { height -> height / 2 } +
                                fadeIn(motionScheme.fastEffectsSpec())) togetherWith
                                (slideOutVertically(motionScheme.fastSpatialSpec()) { height -> -height / 2 } +
                                    fadeOut(motionScheme.fastEffectsSpec()))
                        },
                        label = "packetCount",
                    ) { count ->
                        Text(
                            text = "$count 次",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Slider(
                    state = packetSliderState,
                    onValueChange = { value ->
                        packetSliderState.value = value
                        packetCount = value.roundToInt().coerceIn(1, 10)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "左右拖动调整，1–10 次；次数越多，唤醒成功率越高。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Wake-on-LAN 数据会以 UDP 广播发送到上述地址和端口，连续发送多个魔术包可提高成功率。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("先测试一下", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "无需保存即可发送一次魔术包，验证当前网络和广播地址是否可达。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            val device = buildDevice() ?: return@OutlinedButton
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = sender.wake(device)
                                testing = false
                            }
                        },
                        enabled = canTest && !testing,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        if (testing) {
                            LoadingIndicator(
                                modifier = Modifier.size(18.dp),
                                color = LocalContentColor.current,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("发送中…")
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.PowerSettingsNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("发送测试魔术包")
                        }
                    }
                    testResult?.let { result ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (result.success) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                },
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = if (result.success) {
                                    "测试成功：${result.message}"
                                } else {
                                    "测试失败：${result.message}"
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.success) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                },
                            )
                        }
                    }
                }
            }

            Text(
                text = "提示：目标设备需在 BIOS/UEFI 和网卡驱动中启用“网络唤醒 / Wake on Magic Packet”，并使用有线网络连接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (showDeleteDialog && initial != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("删除设备？") },
            text = {
                Text("将删除「${initial.name}」的配置。已添加到桌面的小组件会提示设备已删除。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete(initial)
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * Applies the MAC mask while keeping the caret sensible for edits in the middle
 * of the value. Backspacing directly after a separator moves the caret before
 * the separator instead of silently re-inserting it, so the next backspace
 * removes the preceding digit.
 */
private fun applyMacMask(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    val oldCursor = old.selection.start
    val newCursor = new.selection.start
    val separatorDeleted = old.selection.collapsed &&
        new.selection.collapsed &&
        new.text.length == old.text.length - 1 &&
        oldCursor in 1..old.text.length &&
        old.text[oldCursor - 1] == ':' &&
        new.text.count(Char::isMacHex) == old.text.count(Char::isMacHex)
    if (separatorDeleted) {
        return old.copy(selection = TextRange(oldCursor - 1))
    }

    val digitsBeforeCursor = new.text
        .take(newCursor.coerceIn(0, new.text.length))
        .count(Char::isMacHex)
    val formatted = WolPacket.maskMac(new.text)
    val cursor = if (digitsBeforeCursor <= 0) {
        0
    } else {
        digitsBeforeCursor + (digitsBeforeCursor - 1) / 2
    }
    return TextFieldValue(
        text = formatted,
        selection = TextRange(cursor.coerceIn(0, formatted.length)),
    )
}

private fun Char.isMacHex(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

@Composable
private fun FormSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
