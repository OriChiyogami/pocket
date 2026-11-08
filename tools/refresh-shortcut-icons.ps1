[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
# 注意：本文件里不要出现中文路径字面量。
# PowerShell 5.1 默认按 ANSI/GBK 读取无 BOM 的 UTF-8 脚本，中文字面量会变成乱码。
$scriptPath = $MyInvocation.MyCommand.Path
if (-not $scriptPath) { $scriptPath = $PSCommandPath }
$scriptDir = Split-Path -Parent $scriptPath
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
$root = Split-Path -Parent $scriptDir
$icon = Join-Path $root 'tools\pocket.ico'
$desk = [Environment]::GetFolderPath('Desktop')
$sh = New-Object -ComObject WScript.Shell
$changed = 0

if (-not (Test-Path $icon)) {
    Write-Output ('icon missing: ' + $icon)
    exit 1
}

Get-ChildItem $desk -Filter *.lnk | ForEach-Object {
    $l = $sh.CreateShortcut($_.FullName)
    $target = [string]$l.TargetPath
    $argLine = [string]$l.Arguments
    if ($target -match 'pocket' -or $argLine -match 'start-pocket|stop-pocket') {
        $l.IconLocation = $icon + ',0'
        $l.Save()
        $changed += 1
        Write-Output ('updated: ' + $_.Name)
    }
}

Write-Output ('changed=' + $changed)
try { & ie4uinit.exe -show } catch {}
try { & ie4uinit.exe -ClearIconCache } catch {}
