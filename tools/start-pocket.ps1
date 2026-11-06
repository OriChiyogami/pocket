$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$Host.UI.RawUI.WindowTitle = '口袋电脑端'

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Host '未找到 Node.js，请先安装 Node 并确保 node 在 PATH 中。'
    Read-Host '按回车关闭'
    exit 1
}

& (Join-Path $PSScriptRoot 'stop-pocket.ps1') -Quiet

# 服务就绪后自动打开电脑端管理页（独立隐藏进程，不影响服务生命周期）
Start-Process powershell.exe -WindowStyle Hidden -ArgumentList @(
    '-NoProfile',
    '-Command',
    'Start-Sleep -Seconds 2; Start-Process "http://127.0.0.1:8570/"'
) | Out-Null

Write-Host ''
Write-Host '  口袋 · 电脑端服务启动中…'
Write-Host '  手机：口袋 → + → 从电脑导入'
Write-Host '  关闭：直接关闭本窗口，或点管理页右上角「关闭服务」'
Write-Host ''

try {
    node (Join-Path $root 'cli\bin\pocket.js') serve --dir (Join-Path $root 'dist') --port 8570 --name $env:COMPUTERNAME
} finally {
    Write-Host ''
    Write-Host '口袋电脑端已停止，可以关闭窗口。'
    Read-Host
}
