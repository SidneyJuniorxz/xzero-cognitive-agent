@echo off
REM ==============================================================================
REM X0 / X-Zero (Windows 10/11 Edition) — Inicializador Rápido
REM ==============================================================================
cd /d "%~dp0"
if "%PORT%"=="" set PORT=3032

echo [X0 / X-Zero] Iniciando servidor na porta %PORT%...
start "X0 / X-Zero Server" /min python xzero_windows_server.py
timeout /t 2 /nobreak >nul
start "" "http://127.0.0.1:%PORT%"
echo [X0 / X-Zero] Control Center aberto em http://127.0.0.1:%PORT%
