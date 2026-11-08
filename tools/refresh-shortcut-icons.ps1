[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = 'D:\AI代码\agent test\27(手机端AI自制小软件)'
$icon = Join-Path $root 'tools\pocket.ico'
$desk = [Environment]::GetFolderPath('Desktop')
$sh = New-Object -ComObject WScript.Shell
$changed = 0
Get-ChildItem $desk -Filter *.lnk | ForEach-Object {
    $l = $sh.CreateShortcut($_.FullName)
    $target = [string]$l.TargetPath
    $args = [string]$l.Arguments
    if ($target -match 'pocket' -or $args -match 'start-pocket|stop-pocket') {
        $l.IconLocation = $icon + ',0'
        $l.Save()
        $changed += 1
        Write-Output ('updated: ' + $_.Name)
    }
}
Write-Output ('changed=' + $changed)
try { & ie4uinit.exe -show } catch {}
