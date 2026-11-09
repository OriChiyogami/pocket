@echo off
for %%I in ("%~dp0..") do set ROOT=%%~fI
set PACKED=%ROOT%\dist\desktop\Pocket-win32-x64\Pocket.exe
set ELECTRON=%ROOT%\desktop\node_modules\electron\dist\electron.exe
if exist "%PACKED%" (
  start "" /normal "%PACKED%"
  exit /b 0
)
if exist "%ELECTRON%" (
  start "" /normal "%ELECTRON%" "%ROOT%\desktop"
  exit /b 0
)
echo Pocket desktop app not found.
exit /b 1
