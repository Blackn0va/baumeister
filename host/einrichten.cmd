@echo off
rem einrichten.cmd - ein Doppelklick fuer den GPU-PC: PowerShell 7 sicherstellen, dann einrichten.ps1
rem (Werkzeuge, Bau, Setup). ASCII und CRLF: cmd liest die Datei in der OEM-Codepage.
setlocal
where pwsh >nul 2>nul
if errorlevel 1 (
    echo PowerShell 7 fehlt - installiere es ueber winget ...
    winget install --id Microsoft.PowerShell --exact --source winget --accept-package-agreements --accept-source-agreements
    if errorlevel 1 (
        echo winget fehlgeschlagen. Bitte PowerShell 7 von https://aka.ms/powershell installieren.
        pause
        exit /b 1
    )
    set "PATH=%ProgramFiles%\PowerShell\7;%PATH%"
)
pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0einrichten.ps1" %*
set RC=%errorlevel%
echo.
echo Fertig mit Exitcode %RC%.
pause
exit /b %RC%
