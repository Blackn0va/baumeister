#requires -Version 7.0
<#
.SYNOPSIS
    Wandelt das feingetunte Baumeister-Modell (merged/ aus train_qlora.py) in eine GGUF für die Host-App und
    schreibt den Katalogeintrag für host/deps.lock.json.

.DESCRIPTION
    Zweck: Aus dem zusammengeführten Hugging-Face-Modell wird mit convert_hf_to_gguf.py aus DERSELBEN llama.cpp-Fassung
    wie die Host-App (b10900, host/deps.lock.json → fetchcontent) eine GGUF. Danach Größe und SHA-256 messen und
    den Eintrag als modell-eintrag.json ablegen – im Format der übrigen Modelle.

    WICHTIG:
    - Standard ist Q8_0 direkt aus convert_hf_to_gguf.py. WOZU: Das braucht kein llama-quantize. Für 1.5B sind das
      ~1,6 GB, für 12 GB VRAM klein genug. Q4_K_M nur mit -Quantize <llama-quantize.exe>.
    - Der Eintrag geht NICHT von selbst in deps.lock.json. FALLE: Die App lädt Katalogmodelle von Hugging Face
      (repo + revision). Ohne hochgeladenes Modell gibt es diese URL nicht – bis dahin in der App „Eigene
      GGUF-Datei“ wählen. Hochladen ist eine Veröffentlichung und bleibt Entscheidung des Nutzers.
    - Grundmodell-Lizenz: Qwen2.5-Coder 1.5B Apache-2.0; 3B nur nicht kommerziell (Qwen-Research).

    Voraussetzungen: Python mit torch, transformers, safetensors, numpy, sentencepiece, gguf
    (tools/train/requirements-*.txt); llama.cpp-Quellen b10900 (enthält convert_hf_to_gguf.py und gguf-py).

    Warum die Datei ein BOM hat: Windows PowerShell 5.1 liest Dateien ohne BOM als ANSI, und die Umlaute in den
    Meldungen würden verstümmelt. Mit BOM lesen 5.1 und 7 sie gleich.

.EXAMPLE
    pwsh -File tools\train\export-gguf.ps1 -Merged out\baumeister-coder-1.5b\merged -LlamaCpp C:\swb\bh-r6\_deps\llama-src
.EXAMPLE
    pwsh -File tools\train\export-gguf.ps1 -Merged out\probe\merged -LlamaCpp C:\swb\bh-r6\_deps\llama-src -Name probe -Typ f16
#>
[CmdletBinding()]
param(
    # Ordner merged/ aus train_qlora.py
    [Parameter(Mandatory)] [string] $Merged,
    # llama.cpp-Quellbaum (b10900) mit convert_hf_to_gguf.py
    [Parameter(Mandatory)] [string] $LlamaCpp,
    # Dateiname ohne Endung
    [string] $Name = 'Baumeister-Coder-1.5B',
    # q8_0 (Standard), f16 oder bf16 direkt; Q4_K_M nur mit -Quantize
    [ValidateSet('q8_0', 'f16', 'bf16', 'Q4_K_M')] [string] $Typ = 'q8_0',
    # Pfad zu llama-quantize.exe (nur für Q4_K_M)
    [string] $Quantize = '',
    # Python-Programm (Standard: python im PATH)
    [string] $Python = 'python',
    # Ausgabeordner (Standard: neben merged/)
    [string] $Ziel = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

function Schritt([string] $t) { Write-Host "==> $t" -ForegroundColor Cyan }
function Hinweis([string] $t) { Write-Host "    $t" -ForegroundColor Gray }
function Warnen([string] $t) { Write-Host "WARNUNG: $t" -ForegroundColor Yellow }
function Fehler([string] $t) { Write-Host "FEHLER: $t" -ForegroundColor Red; exit 1 }

function Invoke-Native([string] $exe, [string[]] $argv) {
    & $exe @argv
    # FALLE: $LASTEXITCODE existiert unter StrictMode in einer frischen Konsole nicht – deshalb abgesichert lesen.
    $code = (Get-Variable LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue)?.Value
    if ($code -ne 0) { Fehler "$exe endete mit Exitcode $code" }
}

$mergedDir = (Resolve-Path $Merged).Path
$convert = Join-Path $LlamaCpp 'convert_hf_to_gguf.py'
if (-not (Test-Path $convert)) { Fehler "convert_hf_to_gguf.py fehlt in $LlamaCpp" }
if (-not (Test-Path (Join-Path $mergedDir 'config.json'))) { Fehler "Kein Hugging-Face-Modell in $mergedDir" }
if ($Typ -eq 'Q4_K_M' -and -not (Test-Path $Quantize)) { Fehler 'Q4_K_M braucht -Quantize <llama-quantize.exe>' }
$outDir = if ($Ziel) { $Ziel } else { Split-Path $mergedDir -Parent }
New-Item -ItemType Directory -Force $outDir | Out-Null

$direct = if ($Typ -eq 'Q4_K_M') { 'f16' } else { $Typ }
$directFile = Join-Path $outDir "$Name-$($direct.ToUpper()).gguf"
Schritt "GGUF ($direct) aus $mergedDir"
Invoke-Native $Python @($convert, $mergedDir, '--outfile', $directFile, '--outtype', $direct)
$final = $directFile
if ($Typ -eq 'Q4_K_M') {
    $final = Join-Path $outDir "$Name-Q4_K_M.gguf"
    Schritt "Quantisieren nach Q4_K_M"
    Invoke-Native $Quantize @($directFile, $final, 'Q4_K_M')
}

Schritt 'Größe und SHA-256 messen'
$item = Get-Item $final
$sha = (Get-FileHash -Algorithm SHA256 $final).Hash.ToLowerInvariant()
Hinweis "$($item.Name): $($item.Length) Byte, SHA-256 $sha"
# Eintrag im Format von host/deps.lock.json → models. repo/revision bleiben leer, bis das Modell hochgeladen ist
# (siehe WICHTIG oben); kv_kib_per_token = 2 × Schichten × KV-Köpfe × Kopfbreite × 2 B aus config.json.
$cfg = Get-Content (Join-Path $mergedDir 'config.json') -Raw | ConvertFrom-Json
$headDim = [int]($cfg.hidden_size / $cfg.num_attention_heads)
$kv = [int](2 * $cfg.num_hidden_layers * $cfg.num_key_value_heads * $headDim * 2 / 1024)
$entry = [ordered]@{
    title            = "Baumeister Coder ($Name, eigenes Modell)"
    repo             = ''
    revision         = ''
    file             = $item.Name
    size             = $item.Length
    sha256           = $sha
    license          = 'Apache-2.0 (Grundmodell Qwen2.5-Coder-1.5B-Instruct prüfen)'
    min_vram_mb      = [int][math]::Ceiling(($item.Length / 1MB + 600) / 512) * 512
    n_ctx            = 32768
    kv_kib_per_token = $kv
    kv_q8            = $false
}
$entryFile = Join-Path $outDir 'modell-eintrag.json'
$entry | ConvertTo-Json | Set-Content -Encoding utf8NoBOM $entryFile
Hinweis "Eintrag: $entryFile"
Warnen 'Nicht in deps.lock.json übernehmen, solange repo/revision leer sind – bis dahin in der App „Eigene GGUF-Datei“ wählen.'
exit 0
