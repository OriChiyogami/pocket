param([switch]$NoBrowser)

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$node = (Get-Command node -ErrorAction SilentlyContinue).Source
if (-not $node) {
    Add-Type -AssemblyName PresentationFramework
    [System.Windows.MessageBox]::Show('未找到 Node.js，请先安装 Node 并确保 node 在 PATH 中。', '口袋电脑端') | Out-Null
    exit 1
}

# 先收掉已经在跑的实例，避免端口冲突
& (Join-Path $PSScriptRoot 'stop-pocket.ps1') -Quiet

$dist = Join-Path $root 'dist'
$outLog = Join-Path $dist '.pocket-server.out.log'
$errLog = Join-Path $dist '.pocket-server.err.log'
$serve = Join-Path $root 'cli\bin\pocket.js'

if (-not (Test-Path $dist)) { New-Item -ItemType Directory -Path $dist | Out-Null }

# 隐藏窗口 + 独立进程启动：关掉任何窗口、注销当前会话以外的情况都不会带走服务
# 注意：路径含中文和空格，参数必须显式加引号，否则会被拆成多段
$argLine = '"{0}" serve --dir "{1}" --port 8570 --name "{2}"' -f $serve, $dist, $env:COMPUTERNAME
Start-Process -FilePath $node `
    -ArgumentList $argLine `
    -WorkingDirectory $root -WindowStyle Hidden `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog | Out-Null

if (-not $NoBrowser) {
    for ($i = 0; $i -lt 25; $i++) {
        try {
            $r = Invoke-WebRequest -Uri 'http://127.0.0.1:8570/api/health' -UseBasicParsing -TimeoutSec 1
            if ($r.StatusCode -eq 200) { break }
        } catch {}
        Start-Sleep -Milliseconds 300
    }
    Start-Process 'http://127.0.0.1:8570/'
}
