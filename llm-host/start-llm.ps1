#requires -Version 7.0
<#
    start-llm.ps1 – lokales LLM für den Baumeister-NPC starten

    ZWECK
      Startet llama-server auf diesem PC (nur 127.0.0.1) und baut einen SSH-Rückwärtstunnel
      zum Server: Dort erreicht das Plugin das LLM unter http://127.0.0.1:18080. Bricht der Tunnel
      ab, wird er nach 10 s neu aufgebaut. Strg+C beendet Tunnel und llama-server.

    WICHTIG
      - WOZU der Tunnel: dev01 (Celeron J4125) hat kein AVX – ein LLM dort ist unbrauchbar langsam.
        Der Tunnel bindet auf dev01 nur an 127.0.0.1 (GatewayPorts no), hier lauscht llama-server
        nur an 127.0.0.1. Kein offener Port im LAN, keine Firewall-Regel nötig.
      - WOZU CPU statt GPU: gemessen 2026-10-28, Qwen2.5-VL-7B Q4_K_M, Core Ultra 7 155U:
        CPU 8,3 Token/s Ausgabe / 37 Token/s Prompt, Arc-iGPU (Vulkan) 6,8 / 29. -Gpu schaltet um.
      - FALLE: llama-server erlaubt ohne Angabe CORS "*" – jede Webseite im Browser dieses PCs
        könnte das LLM nutzen und die Antworten lesen. --cors-origins mit einer nie vorkommenden
        Origin sperrt das; das Plugin schickt keine Origin und ist nicht betroffen.
      - FALLE: plink nimmt den Schlüssel aus Pageant. Ohne geladenen Schlüssel scheitert der
        Tunnel sofort mit "No supported authentication methods".

    VORAUSSETZUNGEN
      - llama-server b10900 (Vulkan-Build) in C:\swb\llama-server-b10900-vk\bin\Release
      - GGUF-Modell (Standard: Qwen2.5-VL-7B-Instruct-Q4_K_M, 4,4 GB)
      - PuTTY (plink.exe) im PATH, Pageant mit dem Schlüssel für den Server (-Server root@<server>)

    AUFRUF
      pwsh -File llm-host\start-llm.ps1
      pwsh -File llm-host\start-llm.ps1 -Gpu
      pwsh -File llm-host\start-llm.ps1 -OhneTunnel          # nur LLM, z. B. für den lokalen Testserver
      pwsh -File llm-host\start-llm.ps1 -Modell D:\modelle\anderes.gguf

    WARUM MIT BOM
      Windows PowerShell 5.1 liest Dateien ohne BOM als ANSI und zerlegt die Umlaute in den
      Meldungen. Mit BOM lesen 5.1 und 7 die Datei gleich.
#>
[CmdletBinding()]
param(
    # GGUF-Modell, das llama-server lädt.
    [string]$Modell = 'C:\swb\voice-probe\vision\Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf',

    # Ordner mit llama-server.exe und den ggml-DLLs.
    [string]$LlamaDir = 'C:\swb\llama-server-b10900-vk\bin\Release',

    # Lokaler Port von llama-server (nur 127.0.0.1).
    [int]$Port = 8080,

    # SSH-Ziel für den Tunnel.
    # Ohne festen Vorgabewert, damit der Tunnel nie an einen fremden Rechner geht (z. B. root@192.0.2.10).
    [string]$Server = '',

    # Port auf dem Server (127.0.0.1), den das Plugin anspricht – muss zu llm.url in config.yml passen.
    [int]$TunnelPort = 18080,

    # Kontextlänge. System-Prompt ~1700 Token + Plan bis 3000 + eine Reparaturrunde.
    [int]$Kontext = 8192,

    # Auf der Arc-iGPU (Vulkan) statt auf der CPU rechnen.
    [switch]$Gpu,

    # Nur llama-server starten, keinen Tunnel.
    [switch]$OhneTunnel
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

function Schritt([string]$Text) { Write-Host "==> $Text" -ForegroundColor Cyan }
function Hinweis([string]$Text) { Write-Host "    $Text" -ForegroundColor Gray }
function Warnen([string]$Text) { Write-Host "!!  $Text" -ForegroundColor Yellow }
function Fehler([string]$Text) { Write-Host "XX  $Text" -ForegroundColor Red }

$exe = Join-Path $LlamaDir 'llama-server.exe'
if (-not (Test-Path $exe)) { Fehler "llama-server.exe fehlt: $exe"; exit 1 }
if (-not (Test-Path $Modell)) { Fehler "Modell fehlt: $Modell"; exit 1 }
if (-not $OhneTunnel -and -not $Server) {
    Fehler 'Für den Tunnel -Server root@<server> angeben (oder -OhneTunnel).'
    exit 1
}
if (-not $OhneTunnel -and -not (Get-Command plink.exe -ErrorAction SilentlyContinue)) {
    Fehler 'plink.exe nicht im PATH (PuTTY installieren oder -OhneTunnel).'
    exit 1
}

$llamaArgs = @(
    '-m', $Modell,
    '--host', '127.0.0.1', '--port', "$Port",
    '-c', "$Kontext", '-np', '1',
    '--cors-origins', 'http://baumeister.invalid', '--no-cors-credentials'
)
if ($Gpu) {
    $llamaArgs += @('-ngl', '99')
} else {
    $llamaArgs += @('-ngl', '0', '--device', 'none')
}

Schritt "Starte llama-server ($([IO.Path]::GetFileName($Modell)), $(if ($Gpu) { 'GPU' } else { 'CPU' }))"
$logDatei = Join-Path $PSScriptRoot 'llama-server.log'
$llama = Start-Process -FilePath $exe -ArgumentList $llamaArgs -PassThru -NoNewWindow `
    -RedirectStandardError $logDatei -RedirectStandardOutput (Join-Path $PSScriptRoot 'llama-server.out.log')

$tunnel = $null
try {
    $bereit = $false
    for ($i = 0; $i -lt 180; $i++) {
        if ($llama.HasExited) { break }
        try {
            $h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2
            if ($h.status -eq 'ok') { $bereit = $true; break }
        } catch {
            # FALLE: Während des Ladens antwortet /health mit 503 oder gar nicht – das ist kein Fehler.
            Start-Sleep -Seconds 1
        }
    }
    if (-not $bereit) {
        Fehler "llama-server ist nicht hochgekommen. Log: $logDatei"
        exit 1
    }
    Hinweis "LLM bereit auf http://127.0.0.1:$Port (Log: $logDatei)"

    if ($OhneTunnel) {
        Schritt 'Ohne Tunnel – Strg+C beendet llama-server.'
        Wait-Process -Id $llama.Id
        exit 0
    }

    Schritt "Tunnel $Server : 127.0.0.1:$TunnelPort -> 127.0.0.1:$Port"
    while (-not $llama.HasExited) {
        # -N: keine Shell, -batch: nie interaktiv nachfragen (Hostkey muss bekannt sein).
        $tunnel = Start-Process -FilePath 'plink.exe' -PassThru -NoNewWindow -ArgumentList @(
            '-batch', '-N', '-R', "127.0.0.1:${TunnelPort}:127.0.0.1:$Port", $Server)
        Hinweis 'Tunnel steht. Im Spiel: "Baumeister, bau mir …"  ·  Strg+C beendet alles.'
        Wait-Process -Id $tunnel.Id
        if ($llama.HasExited) { break }
        Warnen "Tunnel abgebrochen (Exit $($tunnel.ExitCode)) – neuer Versuch in 10 s."
        Start-Sleep -Seconds 10
    }
    Fehler "llama-server hat sich beendet. Log: $logDatei"
    exit 1
} finally {
    if ($null -ne $tunnel -and -not $tunnel.HasExited) { Stop-Process -Id $tunnel.Id -Force }
    if (-not $llama.HasExited) { Stop-Process -Id $llama.Id -Force }
    Hinweis 'llama-server und Tunnel beendet.'
}
