@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
py -3 -B "%~dp0scripts\start_local.py" %*
if errorlevel 1 (
    echo.
    pause
    exit /b 1
)
