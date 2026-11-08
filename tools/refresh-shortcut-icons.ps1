[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
# Keep this file ASCII-only. PowerShell 5.1 reads UTF-8 without BOM as ANSI/GBK;
# Chinese characters can turn into a trailing backtick and swallow the next line.
$scriptPath = $MyInvocation.MyCommand.Path
if (-not $scriptPath) { $scriptPath = $PSCommandPath }
$scriptDir = Split-Path -Parent $scriptPath
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
$root = Split-Path -Parent $scriptDir
$srcIcon = Join-Path $root 'tools\pocket.ico'
$desk = [Environment]::GetFolderPath('Desktop')
$sh = New-Object -ComObject WScript.Shell
$changed = 0

if (-not (Test-Path $srcIcon)) {
    Write-Output ('icon missing: ' + $srcIcon)
    exit 1
}

# Windows caches icons by path; copying to a new timestamped file busts the cache.
$stamp = [string](Get-Date).Ticks
$icon = Join-Path $root ('tools\pocket-' + $stamp + '.ico')
Copy-Item $srcIcon $icon -Force
Get-ChildItem (Join-Path $root 'tools') -Filter 'pocket-*.ico' |
    Where-Object { $_.FullName -ne $icon } |
    Remove-Item -Force -ErrorAction SilentlyContinue

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

Write-Output ('icon=' + $icon)
Write-Output ('changed=' + $changed)
try { & ie4uinit.exe -show } catch {}
try { & ie4uinit.exe -ClearIconCache } catch {}
