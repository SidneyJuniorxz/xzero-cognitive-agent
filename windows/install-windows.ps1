# ==============================================================================
# X0 / X-Zero (Windows 10/11 Edition) — PowerShell Installer
# ==============================================================================
$ErrorActionPreference = "Stop"
$Dir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "🚀 Instalando X0 / X-Zero (Windows Desktop Edition) em: $Dir" -ForegroundColor Cyan

New-Item -ItemType Directory -Force -Path "$Dir\data\trajectories" | Out-Null

# Cria atalho na Área de Trabalho do Windows (opcional)
$DesktopPath = [Environment]::GetFolderPath("Desktop")
$ShortcutFile = Join-Path $DesktopPath "X0 - X-Zero Control Center.lnk"
try {
    $WScriptShell = New-Object -ComObject WScript.Shell
    $Shortcut = $WScriptShell.CreateShortcut($ShortcutFile)
    $Shortcut.TargetPath = "$Dir\start-windows.bat"
    $Shortcut.WorkingDirectory = $Dir
    $Shortcut.Description = "X0 / X-Zero Autonomous Agent & Control Center (Windows)"
    $Shortcut.Save()
    Write-Host "✅ Atalho criado na Área de Trabalho: $ShortcutFile" -ForegroundColor Green
} catch {
    Write-Host "ℹ️ Atalho na área de trabalho ignorado." -ForegroundColor Yellow
}

Write-Host "✅ Instalação concluída!" -ForegroundColor Green
Write-Host "👉 Execute .\start-windows.bat para iniciar o X0 / X-Zero e abrir o Control Center em http://127.0.0.1:3032" -ForegroundColor Cyan
