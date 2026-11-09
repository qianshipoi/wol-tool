<#
.SYNOPSIS
  在 Windows 电脑上监听 Wake-on-LAN UDP 广播，验证手机 App 是否真的发出了魔术包。

.DESCRIPTION
  绑定 0.0.0.0:<Port>（默认 9），打印收到的每个 UDP 包，并识别 102 字节的 WOL 魔术包。
  电脑需要和手机在同一局域网，且 App 中配置的端口与此处一致。

.EXAMPLE
  .\tools\wol-listen.ps1
  .\tools\wol-listen.ps1 -Port 9 -TimeoutSeconds 120

.NOTES
  如果收不到包，先确认 Windows 防火墙允许入站 UDP 9（需要管理员权限）：
    New-NetFirewallRule -DisplayName "WOL UDP 9" -Direction Inbound -Protocol UDP -LocalPort 9 -Action Allow
  也可用 Wireshark 抓包，过滤器：udp.port == 9
#>
param(
    [int]$Port = 9,
    [int]$TimeoutSeconds = 60
)

$listener = [System.Net.Sockets.UdpClient]::new()
try {
    $listener.Client.SetSocketOption(
        [System.Net.Sockets.SocketOptionLevel]::Socket,
        [System.Net.Sockets.SocketOptionName]::ReuseAddress,
        $true
    )
    $listener.Client.Bind([System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, $Port))
    Write-Host "正在监听 UDP $Port ...（$TimeoutSeconds 秒后自动退出，Ctrl+C 可停止）"

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($listener.Available -le 0) {
            Start-Sleep -Milliseconds 100
            continue
        }

        $remote = [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Any, 0)
        $data = $listener.Receive([ref]$remote)
        $hex = [BitConverter]::ToString($data)

        $isMagic = $data.Length -eq 102
        if ($isMagic) {
            for ($i = 0; $i -lt 6; $i++) {
                if ($data[$i] -ne 0xFF) { $isMagic = $false; break }
            }
        }
        if ($isMagic) {
            $mac = ($data[6..11] | ForEach-Object { $_.ToString("X2") }) -join ":"
            $repeated = $true
            for ($repeat = 1; $repeat -lt 16; $repeat++) {
                $offset = 6 + $repeat * 6
                for ($i = 0; $i -lt 6; $i++) {
                    if ($data[$offset + $i] -ne $data[6 + $i]) {
                        $repeated = $false
                        break
                    }
                }
                if (-not $repeated) { break }
            }
            $verdict = if ($repeated) { "有效 WOL 魔术包 MAC=$mac" } else { "102 字节，但 MAC 重复结构不正确" }
        } else {
            $verdict = "非 WOL 魔术包"
        }

        Write-Host ("[{0}] {1}:{2}  {3} 字节  {4}" -f `
            (Get-Date -Format "HH:mm:ss.fff"), $remote.Address, $remote.Port, $data.Length, $verdict)
        if ($hex.Length -le 400) {
            Write-Host "  $hex"
        }
    }
} finally {
    $listener.Dispose()
}
