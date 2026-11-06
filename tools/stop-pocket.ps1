param([switch]$Quiet)

$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root 'dist\.pocket-server.json'
$targets = @()

if (Test-Path $pidFile) {
    try {
        $meta = Get-Content $pidFile -Raw | ConvertFrom-Json
        if ($meta.pid) { $targets += [int]$meta.pid }
    } catch {}
}

# 端口兜底：即使 PID 文件丢失，也能找到监听 8570 的 node
try {
    $conns = Get-NetTCPConnection -LocalPort 8570 -State Listen -ErrorAction SilentlyContinue
    foreach ($c in $conns) {
        if ($c.OwningProcess) { $targets += [int]$c.OwningProcess }
    }
} catch {}

$stopped = 0
foreach ($id in ($targets | Sort-Object -Unique)) {
    try {
        $proc = Get-Process -Id $id -ErrorAction Stop
        if ($proc.ProcessName -eq 'node') {
            Stop-Process -Id $id -Force
            $stopped++
        }
    } catch {}
}

Remove-Item $pidFile -Force -ErrorAction SilentlyContinue

if (-not $Quiet) {
    if ($stopped -gt 0) {
        Write-Host "已关闭口袋电脑端服务（进程 $stopped 个）。"
    } else {
        Write-Host "没有发现正在运行的口袋服务。"
    }
    Start-Sleep -Milliseconds 900
}
