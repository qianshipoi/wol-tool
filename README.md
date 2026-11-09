# WOL 唤醒（Wake-on-LAN）

一个 Android 局域网唤醒工具：配置目标设备的 MAC 地址和广播地址后，可在应用内一键唤醒，也可以把设备添加到桌面小组件，**点按小组件即可直接发送魔术包，无需先打开 App**。

## 功能

- **设备配置**：设备名称、MAC 地址、广播地址、UDP 端口、发送次数（1–10 次拖拽条）。
- **MAC 掩码输入**：每输入两位十六进制自动补冒号，支持直接粘贴完整 MAC，并实时显示已输入位数。
- **一键唤醒**：主界面按设备展示，点按「唤醒」立即发送 Wake-on-LAN 魔术包。
- **Material 3 Expressive 界面**：页面进入 / 退出使用官方 `MotionScheme.expressive()` 动效规格；列表用会随滚动折叠的 `LargeFlexibleTopAppBar`；发送次数是官方 M3 拖拽条（刻度吸附 + 手柄按压拉伸）；发送时用变形 `LoadingIndicator` 与波浪 `LinearWavyProgressIndicator`；列表增删有过渡动画。
- **智能填充**：自动读取当前 Wi-Fi / 有线网络的 IPv4 地址和前缀，推导子网广播地址（如 `192.168.1.255`）。
- **发送前测试**：在编辑页直接发送测试魔术包，无需保存即可验证配置。
- **桌面小组件**：2×1 尺寸，可在添加时选择设备；之后点按小组件直接唤醒，并显示发送结果与时间。
- **无广告、无需登录、无需目标设备安装软件**。

## 截图

| 空状态 | 设备列表 |
| --- | --- |
| ![空状态](docs/screenshots/01-empty.png) | ![设备列表](docs/screenshots/04-list.png) |

| 添加 / 编辑设备 | 唤醒结果 |
| --- | --- |
| ![编辑设备](docs/screenshots/03-editor.png) | ![唤醒结果](docs/screenshots/05-wake.png) |

| MAC 自动分隔 | 输入完成 |
| --- | --- |
| ![MAC 输入中](docs/screenshots/20-mac-mask-partial.png) | ![MAC 输入完成](docs/screenshots/21-mac-mask-full.png) |

| 发送次数拖拽条 | 发送中的变形加载与波浪进度 |
| --- | --- |
| ![发送次数](docs/screenshots/22-packet-slider.png) | ![发送中](docs/screenshots/23-wake-progress.png) |

| 桌面小组件 | 点按小组件直接唤醒 |
| --- | --- |
| ![桌面小组件](docs/screenshots/11-home-widget.png) | ![小组件唤醒](docs/screenshots/12-widget-wake.png) |

> 截图来自 Android 14（API 34）模拟器，2×1 小组件的点击发送已实际验证（无需打开 App）。

## 环境要求

- Android 8.0（API 26）及以上
- 手机与目标设备处于同一局域网
- 目标设备：
  - 主板 BIOS/UEFI 已开启 Wake-on-LAN / Power On By PCI-E
  - 网卡驱动已启用 “Wake on Magic Packet”
  - 使用有线网络连接
  - 关机后网卡保持供电（Windows 建议关闭「快速启动」）

## 构建

需要 JDK 17 和 Android SDK（compileSdk 37，即 Android 17）。项目自带 Gradle Wrapper（9.3.1，指向国内镜像）。首次构建前先装平台包：

```bash
sdkmanager "platforms;android-37.0" "build-tools;37.0.0"
```


```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。本地也可以复制到 `dist/WolTool-debug.apk` 方便安装；`dist/`、APK 和构建日志已在 `.gitignore` 中排除，Git 仓库只提交源码与文档。

安装到已连接的设备 / 模拟器：

```bash
adb install -r dist/WolTool-debug.apk
```

运行单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

技术栈：Kotlin 2.4.21（AGP 9 内置 Kotlin 支持）、Jetpack Compose 1.12（BOM 2026.09.00）、Material 3 1.5.0-rc01、AGP 9.1.1、Gradle 9.3.1；`compileSdk 37`（Android 17）/ `minSdk 26` / `targetSdk 36`。

## 使用

1. 打开 App，点按「添加设备」。
2. 填写设备名称与 MAC 地址（支持 `AA:BB:CC:DD:EE:FF`、`AA-BB-...`、`AABB...` 等格式）。
3. 广播地址通常点击「自动」即可填入当前子网广播地址；端口保持默认 `9`。
4. 左右拖动「发送次数」拖拽条选择 1–10 次（默认 3 次，次数越多成功率越高）。
5. 点按「发送测试魔术包」验证配置，成功后保存。
6. 在主界面点按设备卡片上的「唤醒」发送唤醒指令。

### 添加桌面小组件

1. 长按桌面空白处 →「小组件」。
2. 找到「WOL 唤醒」，把 2×1 小组件拖到桌面。
3. 在配置页选择设备（也可现场添加），点按「添加到桌面」。
4. 以后直接点按桌面上的小组件即可发送魔术包，无需打开 App。

> 长按已添加的小组件可重新配置；删除设备后，小组件会显示「设备已删除」，点按可重新选择设备。

## 验证广播是否真的发出

### 方式一：电脑端监听脚本（无需安装软件）

电脑与手机连在同一个局域网，在项目目录运行：

```powershell
.\tools\wol-listen.ps1 -Port 9 -TimeoutSeconds 120
```

然后在手机上点一次「唤醒」。脚本会打印收到的 UDP 包并校验魔术包结构：

```text
[16:13:03.095] 192.168.153.146:58912  102 字节  有效 WOL 魔术包 MAC=00:D8:61:F8:A5:F9
  FF-FF-FF-FF-FF-FF-00-D8-61-F8-A5-F9-...(MAC 重复 16 次)
```

看到 `102 字节` 且 `有效 WOL 魔术包`，说明 App 确实通过局域网发出了标准的 Wake-on-LAN 广播包。

> 收不到时先检查：App 中配置的端口是否与脚本一致（默认 9）、电脑和手机是否在同一子网、Windows 防火墙是否允许入站 UDP。添加防火墙规则（管理员 PowerShell）：
>
> ```powershell
> New-NetFirewallRule -DisplayName "WOL UDP 9" -Direction Inbound -Protocol UDP -LocalPort 9 -Action Allow
> ```

### 方式二：Wireshark 图形化抓包

1. 安装 [Wireshark](https://www.wireshark.org/)（安装时一并安装 Npcap）。
2. 选择与手机同一局域网的网卡（如「以太网」），开始抓包。
3. 在过滤栏输入 `udp.port == 9`，然后在手机上点「唤醒」。
4. 应看到：源地址为手机 IP、目的地址为子网广播地址（如 `192.168.153.255`）、长度为 102 字节的 UDP 包；负载为 6 个 `FF` + MAC 重复 16 次。

## 实现说明

- 魔术包格式：6 字节 `0xFF` + 目标 MAC 重复 16 次，共 102 字节。
- 通过 `DatagramSocket` 以 UDP 广播发送到配置的广播地址与端口，默认连续发送 3 次以提高成功率。
- 小组件通过 `PendingIntent` → `BroadcastReceiver` 在后台直接发送；`goAsync()` + 超时保护保证进程在发送完成前不被回收，异常也不会导致宿主进程崩溃。
- 设备配置保存在 `SharedPreferences`（JSON），不依赖数据库；读取失败时保留原始数据备份，避免静默丢失。
- 小组件绑定按实例 ID 隔离，支持多个设备小组件；删除设备后小组件降级为「设备已删除」并提供点按重选入口。
- 小组件绑定信息不参与云备份，并在系统恢复后自动清空，避免 launcher 重新分配 ID 后唤醒错设备。
- 界面使用 Material 3 Expressive：`MaterialExpressiveTheme` + `MotionScheme.expressive()` 提供官方弹性按压、形变与过渡曲线；页面切换用 `AnimatedContent` 套用 `MaterialTheme.motionScheme` 的 `defaultSpatialSpec` / `fastSpatialSpec` 与对应 effects spec；发送次数直接用官方 `Slider`（`rememberSliderState` + 9 个刻度），手柄与轨道动效由组件自带。
- 其余组件同样走官方 Expressive 动效：列表标题用 `LargeFlexibleTopAppBar` + `exitUntilCollapsedScrollBehavior`（随滚动收缩），发送中的按钮用 `LoadingIndicator`（多边形形变），设备卡片里叠加 `LinearWavyProgressIndicator`（波浪进度）。
- 注意：material3 1.5 的 `Slider(state = ...)` 是**受控**组件，`onValueChange` 里必须把值写回 `state.value`（组件自己不会改 state），否则会出现「数字变了但手柄/进度不动」的现象。

## 权限

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 发送 UDP 魔术包 |
| `ACCESS_NETWORK_STATE` | 读取当前网络类型与 IPv4 信息，计算子网广播地址 |

两项均为普通权限，安装时无需手动授权。App 不申请位置、存储、相机等敏感权限，也不包含任何分析或广告 SDK。

## 项目结构

```
app/src/main/java/com/qianshi/woltool/
├── MainActivity.kt              # 入口与页面切换
├── WolViewModel.kt              # 唤醒状态与消息
├── data/
│   ├── Device.kt                # 设备模型
│   └── DeviceRepository.kt      # SharedPreferences JSON 存储
├── wol/
│   ├── WolPacket.kt             # MAC 解析与魔术包构造
│   ├── WolSender.kt             # UDP 广播发送
│   └── NetworkUtils.kt          # 本机 IPv4 / 子网广播地址计算
├── ui/
│   ├── DeviceListScreen.kt      # 设备列表
│   ├── DeviceEditorScreen.kt    # 添加 / 编辑 / 测试
│   ├── WidgetConfigScreen.kt    # 小组件配置页
│   ├── WakeHelpDialog.kt        # 使用条件与排查
│   └── theme/                   # Material 3 Expressive 主题
└── widget/
    ├── WolWidgetProvider.kt     # 小组件点击发送与状态刷新
    └── WidgetConfigActivity.kt  # 小组件选择设备
```

资源文件：`res/xml/wol_widget_info.xml`（小组件声明）、`res/layout/wol_widget.xml`（2×1 布局）、`res/xml/backup_rules.xml` 与 `data_extraction_rules.xml`（排除小组件绑定）。

## 常见问题

**发送成功但没有唤醒？**
协议只负责把魔术包发到局域网，能否唤醒取决于目标设备。依次检查：BIOS 网络唤醒选项、网卡「Wake on Magic Packet」、是否使用有线网络、Windows 快速启动、路由器 AP 隔离与 UDP 广播策略。

**子网广播地址填什么？**
例如手机 IP 为 `192.168.1.23/24`，广播地址填 `192.168.1.255`。编辑页点「自动」会按当前网络计算。部分路由器会拦截 `255.255.255.255`，建议使用子网定向广播地址。

**小组件点按没反应？**
确认小组件已绑定设备（显示设备名称而不是「点按配置」），并检查是否被系统省电策略限制后台广播。可在系统设置中允许本应用后台运行。

**换了一个 Wi-Fi / 子网后唤醒失败？**
广播地址是配置时保存的。编辑设备，点「自动」按当前网络重新生成子网广播地址；手机与目标设备必须处于同一局域网，VPN 可能会改变路由。

## 验证情况

- `./gradlew :app:testDebugUnitTest`：12 个单元测试全部通过（魔术包结构、MAC 解析/掩码、IPv4/广播地址校验）。
- `./gradlew :app:assembleDebug`：构建成功，产物约 19 MB（debug 包包含 Compose 调试工具）。
- Android 14（API 34）模拟器实测：添加 / 编辑 / 删除设备、主界面唤醒、小组件添加与选择设备、**强制停止 App 后点按小组件冷启动发送**、删除设备后小组件的降级提示与重新配置。
- Material 3 Expressive 改版同样在模拟器实测：拖拽发送次数 3 → 8 后保存 / 唤醒正常；页面进入与退出的位移动画（`MotionScheme` 规格）在 Android 14（API 34）与 Android 16（API 36）上均正常。
- Expressive 组件实测：拖动滑块时手柄与进度同步移动（3 → 9 保存为 `packetCount: 9`）；折叠标题栏在列表滚动时收缩；发送过程中抓到 `LoadingIndicator` 多边形形变与卡片底部波浪进度条；编辑页「发送测试魔术包」的发送中状态同样显示变形加载图标。
- 工具链已升级到 AGP 9.1.1 + Gradle 9.3.1 + compileSdk 37（Android 17）：`compileSdk 37` 只影响编译，`minSdk 26` / `targetSdk 36` 未变，Android 8.0–16 的机型仍可正常安装运行，Android 16 上已实测安装、启动、编辑、保存与唤醒全流程。
