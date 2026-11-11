package com.qianshi.woltool.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qianshi.woltool.data.Device
import com.qianshi.woltool.remote.RelayManager
import com.qianshi.woltool.remote.RelayService
import com.qianshi.woltool.remote.RelayShare
import com.qianshi.woltool.remote.RemoteClient
import com.qianshi.woltool.remote.RemoteSettings
import com.qianshi.woltool.wol.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Two roles in one screen:
 *  - Relay: host an HTTP endpoint on the home LAN and wake local devices.
 *  - Remote: call a relay over Tailscale and ask it to wake a device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    devices: List<Device>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val relayState by RelayManager.state.collectAsStateWithLifecycle()
    val client = remember { RemoteClient() }

    val initialRelay = remember { RemoteSettings.relayConfig(context) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var portText by rememberSaveable { mutableStateOf(initialRelay.port.toString()) }
    var relayToken by rememberSaveable { mutableStateOf(initialRelay.token) }
    val initialRemote = remember { RemoteSettings.remoteConfig(context) }
    var relayUrl by rememberSaveable { mutableStateOf(initialRemote.url) }
    var remoteToken by rememberSaveable { mutableStateOf(initialRemote.token) }

    var tailscaleIp by remember { mutableStateOf(NetworkUtils.tailscaleIpv4()) }
    var remoteDevices by remember { mutableStateOf<List<RemoteClient.RemoteDevice>>(emptyList()) }
    var selectedRemoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var remoteLoading by remember { mutableStateOf(false) }
    var remoteMessage by remember { mutableStateOf<String?>(null) }
    var remoteIsError by remember { mutableStateOf(false) }

    // 中继侧生成的分享码；只在分享对话框打开期间持有。
    var sharePayload by remember { mutableStateOf<RelayShare?>(null) }
    var showShareDialog by rememberSaveable { mutableStateOf(false) }
    var showScanner by rememberSaveable { mutableStateOf(false) }

    fun showMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    LaunchedEffect(Unit) {
        tailscaleIp = NetworkUtils.tailscaleIpv4()
        RelayManager.refreshTailscaleIp()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Even when the user denies the notification permission, the server can
        // still run; the notification is simply not visible in the shade.
        RelayService.start(context)
    }

    fun startRelay() {
        val port = portText.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            showMessage("端口需在 1024–65535 之间")
            return
        }
        if (relayToken.length < 8) {
            showMessage("令牌至少需要 8 个字符")
            return
        }
        RemoteSettings.setRelayConfig(context, port, relayToken)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            RelayService.start(context)
        }
    }

    fun stopRelay() {
        RelayService.stop(context)
    }

    fun saveRelayConfig() {
        val port = portText.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            showMessage("端口需在 1024–65535 之间")
            return
        }
        if (relayToken.length < 8) {
            showMessage("令牌至少需要 8 个字符")
            return
        }
        RemoteSettings.setRelayConfig(context, port, relayToken)
        if (relayState.running) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { RelayManager.start(context) }
                if (result.isFailure) {
                    snackbarHostState.showSnackbar(
                        result.exceptionOrNull()?.message ?: "中继重启失败",
                    )
                } else {
                    tailscaleIp = NetworkUtils.tailscaleIpv4()
                    snackbarHostState.showSnackbar("中继配置已更新")
                }
            }
        } else {
            showMessage("中继配置已保存")
        }
    }

    fun copyRelayToken() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("WOL relay token", relayToken))
        showMessage("令牌已复制")
    }

    /**
     * Builds the shareable relay snapshot and opens the QR dialog.
     *
     * The payload must describe the *saved* configuration, otherwise the remote
     * phone would scan an address/port/token that the running relay does not use.
     */
    fun openShareDialog() {
        val port = portText.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            showMessage("端口需在 1024–65535 之间")
            return
        }
        if (relayToken.length < 8) {
            showMessage("令牌至少需要 8 个字符")
            return
        }
        val saved = RemoteSettings.relayConfig(context)
        if (saved.port != port || saved.token != relayToken) {
            showMessage("请先保存中继配置，再生成二维码")
            return
        }
        var host = tailscaleIp
        if (host == null) {
            tailscaleIp = NetworkUtils.tailscaleIpv4()
            host = tailscaleIp
        }
        // Tailscale is the intended path, but a LAN-only setup still works: fall
        // back to the Wi-Fi/Ethernet IPv4 address so the QR is never empty.
        val address = host ?: NetworkUtils.current(context).ipAddress
        if (address == null) {
            showMessage("未检测到 Tailscale 或局域网地址，无法生成二维码")
            return
        }
        sharePayload = RelayShare(url = "http://$address:$port", token = relayToken)
        showShareDialog = true
    }

    fun openBatterySettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { showMessage("无法打开电池设置，请手动在系统设置中配置") }
    }

    fun fetchRemoteDevices(baseUrl: String = relayUrl, token: String = remoteToken) {
        if (remoteLoading) return
        val base = client.normalizeBaseUrl(baseUrl)
        if (base == null) {
            remoteIsError = true
            remoteMessage = "中继地址格式不正确"
            return
        }
        if (token.isBlank()) {
            remoteIsError = true
            remoteMessage = "请填写中继令牌"
            return
        }
        remoteLoading = true
        remoteMessage = null
        scope.launch {
            val result = client.listDevices(base, token.trim())
            remoteLoading = false
            if (result.success) {
                val list = result.value.orEmpty()
                remoteDevices = list
                selectedRemoteId = list.firstOrNull()?.id
                remoteIsError = false
                remoteMessage = if (list.isEmpty()) {
                    "中继连接成功，但还没有配置设备"
                } else {
                    "已连接中继，读取到 ${list.size} 台设备"
                }
                RemoteSettings.setRemoteConfig(context, base, token.trim())
            } else {
                remoteDevices = emptyList()
                selectedRemoteId = null
                remoteIsError = true
                remoteMessage = result.error
            }
        }
    }

    fun wakeRemoteDevice() {
        val deviceId = selectedRemoteId
        if (deviceId == null) {
            remoteIsError = true
            remoteMessage = "请先选择设备"
            return
        }
        remoteLoading = true
        remoteMessage = null
        scope.launch {
            val result = client.wake(relayUrl, remoteToken.trim(), deviceId)
            remoteLoading = false
            if (result.success) {
                val wake = result.value
                remoteIsError = false
                remoteMessage = buildString {
                    append("中继已发送唤醒指令")
                    wake?.let { value ->
                        if (value.deviceName.isNotBlank()) append("：${value.deviceName}")
                        if (value.packetsSent > 0) append("（${value.packetsSent} 个魔术包）")
                    }
                }
            } else {
                remoteIsError = true
                remoteMessage = result.error ?: "远程唤醒失败"
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("远程唤醒") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IntroCard()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == 0,
                    onClick = { mode = 0 },
                    label = { Text("中继服务") },
                )
                FilterChip(
                    selected = mode == 1,
                    onClick = { mode = 1 },
                    label = { Text("远程发送") },
                )
            }

            if (mode == 0) {
                RelayModeContent(
                    state = relayState,
                    deviceCount = devices.size,
                    tailscaleIp = tailscaleIp,
                    portText = portText,
                    relayToken = relayToken,
                    onPortChange = { portText = it.filter(Char::isDigit).take(5) },
                    onTokenChange = { relayToken = it },
                    onToggle = { enabled -> if (enabled) startRelay() else stopRelay() },
                    onSave = ::saveRelayConfig,
                    onRefreshIp = {
                        tailscaleIp = NetworkUtils.tailscaleIpv4()
                        RelayManager.refreshTailscaleIp()
                    },
                    onCopyToken = ::copyRelayToken,
                    onRegenerateToken = { relayToken = RemoteSettings.generateToken() },
                    onOpenBatterySettings = ::openBatterySettings,
                    onShareQr = ::openShareDialog,
                )
            } else {
                RemoteModeContent(
                    relayUrl = relayUrl,
                    remoteToken = remoteToken,
                    devices = remoteDevices,
                    selectedId = selectedRemoteId,
                    loading = remoteLoading,
                    message = remoteMessage,
                    isError = remoteIsError,
                    onUrlChange = { relayUrl = it },
                    onTokenChange = { remoteToken = it },
                    onFetch = ::fetchRemoteDevices,
                    onWake = ::wakeRemoteDevice,
                    onSelect = { selectedRemoteId = it },
                    onScanQr = { showScanner = true },
                    onSave = {
                        RemoteSettings.setRemoteConfig(
                            context,
                            client.normalizeBaseUrl(relayUrl) ?: relayUrl.trim(),
                            remoteToken.trim(),
                        )
                        showMessage("远程配置已保存")
                    },
                )
            }

            Text(
                text = "提示：中继手机必须与目标电脑处于同一局域网；两台手机登录同一个 Tailscale 网络即可跨网络访问中继。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            Spacer(Modifier.size(24.dp))
        }
    }

    val share = sharePayload
    if (showShareDialog && share != null) {
        RelayShareDialog(
            share = share,
            deviceCount = devices.size,
            onDismiss = { showShareDialog = false },
        )
    }

    if (showScanner) {
        QrScannerScreen(
            onResult = { raw ->
                showScanner = false
                RelayShare.decode(raw)
                    .onSuccess { decoded ->
                        relayUrl = decoded.url
                        remoteToken = decoded.token
                        showMessage("已从二维码填入中继地址与令牌")
                        // Fill first, then immediately try to read the devices so
                        // the scanned code is trusted end-to-end.
                        fetchRemoteDevices(decoded.url, decoded.token)
                    }
                    .onFailure { error ->
                        remoteIsError = true
                        remoteMessage = error.message ?: "二维码内容无法识别"
                    }
            },
            onDismiss = { showScanner = false },
        )
    }
}

@Composable
private fun IntroCard() {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("异地唤醒", style = MaterialTheme.typography.titleMedium)
                Text(
                    "中继手机负责局域网广播；远程手机通过 Tailscale 给它发指令。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RelayModeContent(
    state: RelayManager.RelayState,
    deviceCount: Int,
    tailscaleIp: String?,
    portText: String,
    relayToken: String,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onToggle: (Boolean) -> Unit,
    onSave: () -> Unit,
    onRefreshIp: () -> Unit,
    onCopyToken: () -> Unit,
    onRegenerateToken: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onShareQr: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.running) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Cloud,
                contentDescription = null,
                tint = if (state.running) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (state.running) "中继服务运行中" else "中继服务未启动",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (state.running) {
                        "监听端口 ${state.port} · 已配置 $deviceCount 台设备"
                    } else {
                        "开启后，远程手机可以通过 Tailscale 唤醒这里配置的设备"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.running, onCheckedChange = onToggle)
        }
        state.error?.let { error ->
            HorizontalDivider(modifier = Modifier.padding(horizontal = 18.dp))
            Text(
                text = error,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("中继设置", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tailscale 地址",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = tailscaleIp ?: "未检测到 Tailscale 地址",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                IconButton(onClick = onRefreshIp) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新 Tailscale 地址")
                }
            }
            if (tailscaleIp != null) {
                Text(
                    text = "远程手机会访问 http://$tailscaleIp:$portText",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            OutlinedTextField(
                value = portText,
                onValueChange = onPortChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("监听端口") },
                singleLine = true,
                supportingText = { Text("1024–65535，默认 8787") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = relayToken,
                onValueChange = onTokenChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("中继令牌") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                supportingText = { Text("远程手机必须填写相同令牌") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCopyToken) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("复制令牌")
                }
                TextButton(onClick = onRegenerateToken) {
                    Text("重新生成")
                }
                Spacer(Modifier.weight(1f))
                Text("${deviceCount} 台设备", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = onShareQr,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.QrCode2,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("生成分享二维码")
            }
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(if (state.running) "保存并重启中继" else "保存中继配置")
            }
        }
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("保活与网络", style = MaterialTheme.typography.titleMedium)
            Text(
                "中继手机需要保持充电和联网。建议把本应用与 Tailscale 的省电策略设为“无限制”，并允许 Tailscale 常驻后台。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpenBatterySettings) {
                    Text("电池设置")
                }
                TextButton(onClick = onRefreshIp) {
                    Text("刷新 Tailscale 地址")
                }
            }
        }
    }
}

@Composable
private fun RemoteModeContent(
    relayUrl: String,
    remoteToken: String,
    devices: List<RemoteClient.RemoteDevice>,
    selectedId: String?,
    loading: Boolean,
    message: String?,
    isError: Boolean,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onFetch: () -> Unit,
    onWake: () -> Unit,
    onSelect: (String) -> Unit,
    onScanQr: () -> Unit,
    onSave: () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "远程连接",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onScanQr) {
                    Icon(
                        imageVector = Icons.Rounded.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("扫描二维码")
                }
            }
            OutlinedTextField(
                value = relayUrl,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("中继地址") },
                placeholder = { Text("http://100.x.x.x:8787") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                supportingText = { Text("填写中继手机的 Tailscale 地址和端口") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = remoteToken,
                onValueChange = onTokenChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("中继令牌") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onFetch,
                    enabled = !loading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("读取设备")
                }
                OutlinedButton(
                    onClick = onSave,
                    modifier = Modifier.weight(0.7f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("保存")
                }
            }
        }
    }

    if (devices.isNotEmpty()) {
        Text(
            text = "选择要唤醒的设备",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
        devices.forEach { device ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(device.id) },
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (device.id == selectedId) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = device.id == selectedId,
                        onClick = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        if (device.mac.isNotBlank()) {
                            Text(
                                device.mac,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = onWake,
            enabled = !loading && selectedId != null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text("通过中继唤醒")
        }
    }

    message?.let {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ),
        ) {
            Text(
                text = it,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
        }
    }
}


