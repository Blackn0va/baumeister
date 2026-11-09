#Requires -Version 7
<#
.SYNOPSIS
  Baut StreamWizard Baumeister-Host: konfigurieren, bauen, testen, bereitstellen, Setup.

.DESCRIPTION
  Stages (Vorgabe: alle, in dieser Reihenfolge):
    configure  CMake mit Ninja in einer vcvars64-Umgebung (llama.cpp per FetchContent,
               Commit aus deps.lock.json; Qt aus SWBH_QT_DIR, DLL-Hashes geprüft)
    build      alle Ziele
    test       ctest -L unit (ohne Netz, ohne Modell, ohne GPU) – nur mit -Tests oder ausdrücklich -Stage test
    stage      cmake --install (app + runtime) nach out\dist, Lizenzbeilage, Prüfung aller
               PE-Abhängigkeiten gegen die Windows-Baseline
    installer  Inno Setup → out\installer\StreamWizard-Baumeister-Host-Setup-<ver>.exe (+ SHA-256)

  Übernommen aus kraken\build.ps1 (dort gemessen und begründet), ohne Discord-Abhängigkeiten,
  Update-Schlüssel und Symbolablage. Ninja statt Visual-Studio-Generator – llama.cpp mit
  Vulkan-Shadern baut unter MSBuild in localAI 52 min, mit Ninja knapp 3 min (dort gemessen).

  Frischer Rechner (Werkzeuge, Qt einrichten): einrichten.ps1 – ruft danach dieses Skript.

.EXAMPLE
  pwsh -File build.ps1
  pwsh -File build.ps1 -Tests
  pwsh -File build.ps1 -Stage build,test
  pwsh -File build.ps1 -GpuTestModel C:\modelle\Qwen2.5-7B-Instruct-Q4_K_M.gguf
#>
[CmdletBinding()]
param(
    # string[] statt ValidateSet: mit pwsh -File kommt "build,test" als EIN String an.
    [string[]]$Stage = @('all'),
    [ValidateSet('Release', 'RelWithDebInfo', 'Debug')] [string]$Config = 'Release',
    [string]$BuildDir = '',
    [string]$DistDir = '',
    [string]$InstallerDir = '',
    [string]$QtDir = '',
    [switch]$Clean,
    # Tests mitbauen und laufen lassen. Vorgabe AUS (Wunsch 2026-10-28): Auf dem GPU-PC sperrt Smart App Control frisch
    # gebaute Test-EXEs (ctest: 0xC0E90002 bzw. „Not Run“).
    # Ohne -Tests entstehen gar keine Test-EXEs (SWBH_BUILD_TESTS=OFF). Wer das zurückdreht, lässt den Bau dort
    # wieder an gesperrten Tests scheitern. Auf dem Entwicklungsrechner vor jeder Abgabe mit -Tests bauen.
    [switch]$Tests,
    # GPU-Probe mit echtem Modell (ctest -L gpu): Pfad zu einer GGUF-Datei.
    [string]$GpuTestModel = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# stderr nativer Werkzeuge ist kein Abbruchgrund; entscheidend ist der Exit-Code.
$PSNativeCommandUseErrorActionPreference = $false

# Immer in einem EIGENEN Prozess bauen. Mit „.\build.ps1“ lief das Skript im Prozess des Terminals,
# und die vcvars64-Übernahme (unten) schrieb PATH, INCLUDE, LIB … dort hinein: Jeder Lauf verlängerte
# den PATH des Terminals um ~1600 Zeichen (gemessen 1527 → 3146 → 4765). Nach einigen Läufen stand
# das CMake 3.31 aus Visual Studio vorne, dann fehlten git („llama.cpp steht auf ''“) und cmd.exe.
# Wer das zurückdreht, verseucht wieder das Terminal des Benutzers.
if (-not $env:SWBH_BUILD_ISOLATED) {
    $childArgs = [System.Collections.Generic.List[string]]::new()
    $childArgs.AddRange([string[]]@('-NoProfile', '-NonInteractive', '-File', $PSCommandPath))
    foreach ($kv in $PSBoundParameters.GetEnumerator()) {
        if ($kv.Value -is [System.Management.Automation.SwitchParameter]) {
            if ($kv.Value.IsPresent) { $childArgs.Add("-$($kv.Key)") }
        } else {
            $childArgs.Add("-$($kv.Key)")
            $childArgs.Add(([string[]]$kv.Value) -join ',')  # Stage-Listen kommen als „a,b“ an (siehe param)
        }
    }
    $env:SWBH_BUILD_ISOLATED = '1'
    try {
        & (Get-Process -Id $PID).Path @childArgs
        $childExit = if (Test-Path variable:global:LASTEXITCODE) { $global:LASTEXITCODE } else { 1 }
    } finally {
        Remove-Item Env:\SWBH_BUILD_ISOLATED -ErrorAction SilentlyContinue
    }
    exit $childExit
}

$Root = $PSScriptRoot
$LockFile = Join-Path $Root 'deps.lock.json'
. (Join-Path $Root 'installer\package-dependencies.ps1')

function Write-Step([string]$Text) { Write-Host "==> $Text" -ForegroundColor Cyan }
function Write-Info([string]$Text) { Write-Host "    $Text" }
function Stop-Build([string]$Text) { Write-Host "FEHLER: $Text" -ForegroundColor Red; exit 1 }

# $LASTEXITCODE existiert in einer frischen Sitzung unter StrictMode nicht.
function Get-ExitCode {
    if (Test-Path variable:global:LASTEXITCODE) { return $global:LASTEXITCODE }
    return 0
}
function Invoke-Checked([string]$Exe, [string[]]$ArgList, [string]$What) {
    & $Exe @ArgList
    $code = Get-ExitCode
    if ($code -ne 0) { Stop-Build "$What fehlgeschlagen (Exit $code)." }
}
function Write-Utf8NoBom([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false))
}

# --- Stages ---------------------------------------------------------------------
$AllStages = @('configure', 'build', 'test', 'stage', 'installer')
$requested = @($Stage | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim().ToLowerInvariant() } | Where-Object { $_ })
$unknown = @($requested | Where-Object { $_ -notin ($AllStages + 'all') })
if ($unknown.Count -gt 0) { Stop-Build "Unbekannte Stage: $($unknown -join ', ') (erlaubt: $($AllStages -join ', '), all)" }
$Stages = if ($requested -contains 'all') { $AllStages } else { @($AllStages | Where-Object { $requested -contains $_ }) }
$WithTests = $Tests -or ($requested -contains 'test') -or [bool]$GpuTestModel
if (-not $WithTests) { $Stages = @($Stages | Where-Object { $_ -ne 'test' }) }

$Lock = Get-Content $LockFile -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
$BuildDirGiven = [bool]$BuildDir
if (-not $BuildDir) { $BuildDir = Join-Path $Root 'out\build' }
if (-not $DistDir) { $DistDir = Join-Path $Root 'out\dist' }
if (-not $InstallerDir) { $InstallerDir = Join-Path $Root 'out\installer' }
if (-not $QtDir) { $QtDir = $Lock.qt.dir }
foreach ($name in 'BuildDir', 'DistDir', 'InstallerDir') {
    $v = Get-Variable $name -ValueOnly
    if (-not [System.IO.Path]::IsPathRooted($v)) { Set-Variable $name (Join-Path $Root $v) }
}
# FALLE: llama.cpp baut vulkan-shaders-gen als eigenes Unterprojekt, und dessen CMake-Probe liegt rund 165 Zeichen tief
# unter dem Bauordner. Mit „C:\Users\…\Downloads\baumeister-main (1)\baumeister-main\host\out\build“ (75 Zeichen) kam
# MSVC über die 260-Zeichen-Grenze: „C1083 … Invalid argument“, CMake meldete 240 von höchstens 250 Zeichen (GPU-PC,
# 2026-10-28). Deshalb ab 60 Zeichen ein kurzer Bauordner unter %LOCALAPPDATA%\BaumeisterBau\<8 Hex des Quellpfads>.
# Programm und Setup bleiben in out\dist bzw. out\installer. Wer die Grenze anhebt, bricht den Bau in tiefen Ordnern.
$MaxBuildDirLength = 60
if ($BuildDir.Length -gt $MaxBuildDirLength) {
    if ($BuildDirGiven) {
        Stop-Build "Bauordner zu lang ($($BuildDir.Length) Zeichen, höchstens $MaxBuildDirLength – sonst scheitert MSVC an der Pfadgrenze): $BuildDir"
    }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digest = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Root.ToLowerInvariant()))
    } finally { $sha.Dispose() }
    $kurz = Join-Path $env:LOCALAPPDATA ('BaumeisterBau\' + (-join ($digest[0..3] | ForEach-Object { $_.ToString('x2') })))
    Write-Host "    Quellordner liegt tief ($($BuildDir.Length) Zeichen bis out\build) – baue in $kurz" -ForegroundColor DarkGray
    $BuildDir = $kurz
}
# --- Werkzeuge (alle Probleme sammeln, dann einmal abbrechen) -------------------
Write-Step 'Werkzeuge prüfen'
$problems = [System.Collections.Generic.List[string]]::new()
# Nicht einfach das erste cmake im PATH: In der Developer PowerShell von Visual Studio steht dessen
# CMake 3.31 vorne, in einer normalen Konsole 4.4 – derselbe Befehl scheiterte so je nach Terminal
# (2026-10-04). Alle Fundstellen prüfen; die Fassung aus deps.lock.json zuerst, sonst irgendeine 4.x.
$cmakeCandidates = @(Get-Command cmake -All -CommandType Application -ErrorAction SilentlyContinue |
        ForEach-Object Source) + @(Join-Path $env:ProgramFiles 'CMake\bin\cmake.exe') |
    Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique
$cmakeFound = foreach ($c in $cmakeCandidates) {
    $v = ((& $c --version) | Select-Object -First 1) -replace '^cmake version\s+', ''
    [pscustomobject]@{ Path = $c; Version = $v }
}
$cmakePick = @($cmakeFound | Where-Object { $_.Version -eq $Lock.toolchain.cmake }) +
             @($cmakeFound | Where-Object { $_.Version.StartsWith('4.') }) | Select-Object -First 1
$cmake = if ($cmakePick) { $cmakePick.Path } else { 'C:\Program Files\CMake\bin\cmake.exe' }
if (-not $cmakeFound) { $problems.Add('CMake fehlt') }
elseif (-not $cmakePick) {
    $problems.Add('CMake 4.x erwartet, gefunden: ' + (($cmakeFound | ForEach-Object { "$($_.Version) ($($_.Path))" }) -join ', '))
} else {
    Write-Info "CMake $($cmakePick.Version) ($cmake)"
}
$ctest = Join-Path (Split-Path $cmake) 'ctest.exe'

$vsDir = [string]$Lock.toolchain.vs_instance
if (-not (Test-Path $vsDir)) {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (Test-Path $vswhere) {
        $vsDir = (& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath | Select-Object -First 1)
    }
}
$vcvars = if ($vsDir) { Join-Path $vsDir 'VC\Auxiliary\Build\vcvars64.bat' } else { '' }
if (-not $vcvars -or -not (Test-Path $vcvars)) { $problems.Add('MSVC (vcvars64.bat) nicht gefunden') }
$ninja = if ($vsDir) { Join-Path $vsDir ([string]$Lock.toolchain.ninja) } else { '' }
if (-not $ninja -or -not (Test-Path $ninja)) { $problems.Add("Ninja fehlt: $ninja") }

if ($Stages -contains 'configure') {
    if (-not (Test-Path (Join-Path $QtDir 'bin\Qt6Core.dll'))) { $problems.Add("Qt fehlt: $QtDir") }
    $vk = [Environment]::GetEnvironmentVariable('VULKAN_SDK')
    if (-not $vk -or -not (Test-Path (Join-Path $vk 'Bin\glslc.exe'))) {
        $problems.Add('Vulkan-SDK mit glslc fehlt (VULKAN_SDK) – nötig für ggml-vulkan.dll (einrichten.ps1)')
    } elseif (-not $vk.EndsWith([string]$Lock.toolchain.vulkan_sdk)) {
        $problems.Add("Vulkan-SDK $([string]$Lock.toolchain.vulkan_sdk) erwartet, gefunden $vk")
    }
}
# WOZU: deps.lock.json nennt den Pfad mit %LOCALAPPDATA% statt mit Benutzernamen (Repo öffentlich, 2026-11-09) –
# Inno Setup liegt pro Benutzer unter dessen eigenem Profil (einrichten.ps1).
$iscc = [Environment]::ExpandEnvironmentVariables([string]$Lock.toolchain.iscc)
if (-not (Test-Path $iscc)) { $iscc = Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe' }
if ($Stages -contains 'installer' -and -not (Test-Path $iscc)) { $problems.Add("Inno Setup fehlt: $iscc") }
if ($problems.Count -gt 0) { Stop-Build ("`n  - " + ($problems -join "`n  - ")) }

# vcvars64 in diesen Prozess übernehmen (cl, link, rc, mt im PATH; INCLUDE/LIB gesetzt).
if ($Stages | Where-Object { $_ -in 'configure', 'build', 'test' }) {
    $tmp = Join-Path $Root 'out\vcvars.cmd'
    New-Item -ItemType Directory -Force (Split-Path $tmp) | Out-Null
    Write-Utf8NoBom $tmp "@call `"$vcvars`" >nul`r`n@set`r`n"
    # Voller Pfad: Ein beschädigter PATH (siehe oben) ließ „cmd.exe“ unauffindbar werden.
    $cmdExe = if ($env:ComSpec -and (Test-Path $env:ComSpec)) { $env:ComSpec } else { Join-Path ([Environment]::SystemDirectory) 'cmd.exe' }
    foreach ($line in (& $cmdExe /d /c $tmp)) {
        if ($line -match '^([^=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
    }
    Remove-Item $tmp -Force
    $env:PATH = (Split-Path $ninja) + ';' + $env:PATH
}

function Get-AppVersion {
    $text = Get-Content (Join-Path $Root 'CMakeLists.txt') -Raw -Encoding utf8
    if ($text -notmatch 'project\(BaumeisterHost\s+VERSION\s+(\d+\.\d+\.\d+)') { Stop-Build 'Version nicht in CMakeLists.txt gefunden' }
    return $Matches[1]
}

# --- configure ------------------------------------------------------------------
function Invoke-ConfigureStage {
    Write-Step "Konfigurieren ($BuildDir)"
    # WOZU: Wird host\ samt out\ kopiert (anderer Rechner, anderer Ordner), steht im CMakeCache.txt noch der alte
    # Quellpfad, und CMake bricht mit „CMakeCache.txt directory … is different“ ab (gemeldet 2026-10-28). Ein fremder
    # Bauordner ist unbrauchbar – dann neu anlegen, wie mit -Clean.
    $cache = Join-Path $BuildDir 'CMakeCache.txt'
    $fremd = $false
    if (Test-Path -LiteralPath $cache) {
        $zeile = Select-String -LiteralPath $cache -Pattern '^CMAKE_HOME_DIRECTORY:INTERNAL=(.*)$' | Select-Object -First 1
        if ($zeile) {
            $alt = [System.IO.Path]::GetFullPath(($zeile.Matches[0].Groups[1].Value -replace '/', '\')).TrimEnd('\')
            $fremd = -not [string]::Equals($alt, $Root.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)
            if ($fremd) { Write-Info "Bauordner stammt aus $alt – lege ihn neu an (erster Bau dauert wieder länger)" }
        }
    }
    if (($Clean -or $fremd) -and (Test-Path $BuildDir)) {
        # FALLE: .build.lock hält dieser Lauf offen (unten) – alles außer ihr löschen, sonst scheitert Remove-Item.
        Get-ChildItem -LiteralPath $BuildDir -Force | Where-Object Name -NE '.build.lock' |
            Remove-Item -Recurse -Force
    }
    Invoke-Checked $cmake @('-S', $Root, '-B', $BuildDir, '-G', 'Ninja', "-DCMAKE_BUILD_TYPE=$Config",
        "-DCMAKE_MAKE_PROGRAM=$($ninja -replace '\\', '/')", "-DSWBH_QT_DIR=$($QtDir -replace '\\', '/')",
        "-DSWBH_BUILD_TESTS=$(if ($WithTests) { 'ON' } else { 'OFF' })") 'CMake-Konfiguration'
}

# --- Smart App Control ----------------------------------------------------------
# WOZU: Mit Smart App Control („On“) sperrt Windows frisch gelinkte, unsignierte Dateien nach Zufall. Gemessen
# 2026-10-24 (C:\swb\sac-probe, 20 Mini-EXEs je Runde): 7, 7, 1 und 5 von 20 gesperrt. Eine gesperrte Datei
# bleibt gesperrt (7/7 beim zweiten Start), neu gelinkt ist sie eine neue Datei mit neuem Urteil. Betroffen
# (Betreiber-Logs 2026-10-24): vulkan-shaders-gen (Bau bricht nach ~540 von 1577 Schritten ab), Test-EXEs
# (ctest BAD_COMMAND) und Dateien, die ein Test zur Laufzeit lädt (Exit 0xC0E90002 =
# STATUS_SYSTEM_INTEGRITY_POLICY_VIOLATION, 3 von 33 Tests). Deshalb: gesperrte Dateien im Bauordner löschen und
# neu bauen, höchstens $SacRetries Mal. Wer das zurückdreht, lässt einen frischen Klon auf solchen Rechnern
# zufällig scheitern.
# FALLE: Nur löschen, was unter $BuildDir liegt – Protokoll und Meldung nennen auch fremde Dateien.
$SacRetries = 3

function Get-BuildRoot { return [System.IO.Path]::GetFullPath($BuildDir).TrimEnd('\') + '\' }

function Test-UnderBuildDir([string]$Path) {
    return $Path.StartsWith((Get-BuildRoot), [System.StringComparison]::OrdinalIgnoreCase)
}

# Pfade gesperrter EXEs aus der Ausgabe („'…\x.exe' wurde durch die Device Guard-Richtlinie … blockiert“).
function Get-BlockedFromOutput([string[]]$Lines) {
    $found = foreach ($l in $Lines) {
        if ($l -match "'(?<exe>[^']+\.exe)' (wurde durch|was blocked by)") {
            $p = [System.IO.Path]::GetFullPath($Matches.exe)
            if (Test-UnderBuildDir $p) { $p }
        }
    }
    return @($found | Sort-Object -Unique)
}

# NT-Pfad aus dem Protokoll (\Device\HarddiskVolume3\Users\…) → Laufwerk des Bauordners, sonst leer.
function Convert-NtPath([string]$Nt) {
    if ($Nt -notmatch '^\\Device\\HarddiskVolume\d+(?<rest>\\.+)$') { return '' }
    return [System.IO.Path]::GetPathRoot((Get-BuildRoot)).TrimEnd('\') + $Matches.rest
}

# Sperren seit $Since aus dem CodeIntegrity-Protokoll (Ereignis 3077 = durchgesetzte Sperre; lesbar ohne
# Adminrechte, gemessen 2026-10-24): gesperrte Datei (nur unter dem Bauordner), der Prozess, der sie laden wollte,
# und ihr SHA-256 („SHA256 Flat Hash“ = Get-FileHash, gemessen 2026-10-25). Nur so findet man eine DLL, die ein Test
# lädt – ctest meldet dann 0xC0E90002 (fester Import) oder schlicht „Failed“ (LoadLibrary im Test, swa_test_accel:
# „Fehler 4551“, Betreiber-Log 2026-10-24). Die Datei muss nicht mehr da sein: swa_test_win_dll kopiert seine
# Plant-DLL unter anderem Namen in einen Wegwerf-Ordner (swa_plant_a.dll …) – der Hash führt zum gebauten Original.
# Ist das Protokoll nicht lesbar, kommt nichts zurück, und der Aufrufer bricht ab (fail-closed).
function Get-BlockedFromEventLog([datetime]$Since) {
    $filter = @{ LogName = 'Microsoft-Windows-CodeIntegrity/Operational'; Id = 3077; StartTime = $Since }
    return @(foreach ($e in @(Get-WinEvent -FilterHashtable $filter -ErrorAction SilentlyContinue)) {
            $data = @{}
            # FALLE: leere Felder haben unter StrictMode kein '#text' – InnerText gibt es immer.
            foreach ($d in ([xml]$e.ToXml()).Event.EventData.Data) { $data[$d.Name] = $d.InnerText }
            $file = Convert-NtPath $data['File Name']
            if ($file -and (Test-UnderBuildDir $file)) {
                [pscustomobject]@{
                    File    = $file
                    Process = Convert-NtPath $data['Process Name']
                    Hash    = "$($data['SHA256 Flat Hash'])".ToUpperInvariant()
                }
            }
        })
}

# Gesperrte Dateien im Bauordner löschen – über den SHA-256, nicht über den Namen: Bei ExternalProject
# (vulkan-shaders-gen) kopiert der Install-Schritt nur die innere EXE, Bau-DLLs liegen zusätzlich neben den Tests,
# Tests kopieren DLLs unter anderem Namen – bliebe eine Kopie, käme dieselbe gesperrte Datei wieder. crispasr baut
# eine eigene, gleichnamige EXE, die nicht gesperrt ist; über den Namen wäre sie mitgelöscht worden. Alle EXE/DLL zu
# hashen kostet 0,7 s (99 Dateien, 402 MB, gemessen 2026-10-25) – nur im Fehlerfall.
function Remove-BlockedFiles([string[]]$Paths, [string[]]$Hashes) {
    $set = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($h in $Hashes) { if ($h) { [void]$set.Add($h) } }
    foreach ($p in $Paths) {
        if ($p -and (Test-Path -LiteralPath $p)) { [void]$set.Add((Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash) }
    }
    if ($set.Count -eq 0) { return }
    Get-ChildItem -LiteralPath $BuildDir -Recurse -File -Include *.exe, *.dll -ErrorAction SilentlyContinue |
        Where-Object { $set.Contains((Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash) } |
        ForEach-Object {
            Write-Info "Smart App Control sperrt $($_.FullName) – lösche, wird neu gebaut"
            Remove-Item -LiteralPath $_.FullName -Force
        }
}

function Stop-SacBuild([string]$What) {
    Stop-Build ("$What fehlgeschlagen: Smart App Control sperrt frisch gebaute Dateien auch nach " +
        "$SacRetries Neuversuchen. Erneut starten (jede neu gelinkte Datei wird neu bewertet) oder " +
        'Smart App Control in der Windows-Sicherheit ausschalten – das entscheidest du.')
}

# --- build ----------------------------------------------------------------------
function Invoke-BuildStage {
    Write-Step 'Bauen'
    for ($try = 0; ; ++$try) {
        $since = Get-Date
        & $cmake --build $BuildDir --config $Config 2>&1 | Tee-Object -Variable buildOut | Out-Host
        $code = Get-ExitCode
        if ($code -eq 0) { return }
        $paths = @(Get-BlockedFromOutput @($buildOut | ForEach-Object { "$_" }))
        $events = @(Get-BlockedFromEventLog $since)
        if ($paths.Count + $events.Count -eq 0) { Stop-Build "Bau fehlgeschlagen (Exit $code)." }
        if ($try -ge $SacRetries) { Stop-SacBuild 'Bau' }
        Remove-BlockedFiles ($paths + @($events | ForEach-Object { $_.File })) @($events | ForEach-Object { $_.Hash })
        Write-Step "Bauen (Neuversuch $($try + 1) von $SacRetries)"
    }
}

# --- test -----------------------------------------------------------------------
# Fehlgeschlagene Tests aus der Liste am Ende („ 20 - swa_test_funds (BAD_COMMAND)“) samt Pfad der Test-EXE.
function Get-FailedTests([string[]]$Lines) {
    return @(foreach ($l in $Lines) {
            if ($l -match '^\s*\d+ - (?<name>\S+) \((?<why>[^)]*)\)') {
                # FALLE: Jedes -match überschreibt $Matches – Gruppen vorher sichern.
                $name = $Matches.name
                $why = $Matches.why
                $exe = Get-ChildItem -LiteralPath $BuildDir -Recurse -File -Filter "$name.exe" -ErrorAction SilentlyContinue |
                    Select-Object -First 1
                [pscustomobject]@{
                    Name    = $name
                    Exe     = if ($exe) { $exe.FullName } else { '' }
                    # „Not Run“ mit „Process not started“: so meldete ctest eine gesperrte EXE in einem Lauf des
                    # Agents 2026-10-25 (swa_test_signals) statt BAD_COMMAND.
                    NoStart = $why -match 'BAD_COMMAND|Not Run'
                }
            }
        })
}

# Fehlermodus ohne Dialog (SEM_FAILCRITICALERRORS), erben ctest und jeder Test. WOZU: Importiert ein Test eine
# gesperrte DLL fest, zeigt der Loader sonst einen Dialog, den niemand wegklickt – gemessen 2026-10-24
# (C:\swb\sac-probe\dll): unter ctest „Timeout“ nach der vollen Wartezeit (hier TIMEOUT 300 je Test). Mit dem
# Modus endet der Test sofort mit 0xC0E90002, und das Protokoll nennt die DLL.
function Disable-ErrorDialogs {
    if (-not ('Swbh.ErrorMode' -as [type])) {
        Add-Type -Namespace Swbh -Name ErrorMode -MemberDefinition (
            '[System.Runtime.InteropServices.DllImport("kernel32.dll")] public static extern uint SetErrorMode(uint mode);')
    }
    [void][Swbh.ErrorMode]::SetErrorMode(0x0001)
}

function Invoke-TestStage {
    Write-Step 'Tests (unit)'
    Disable-ErrorDialogs
    $ctestArgs = @('--test-dir', $BuildDir, '-C', $Config, '-L', 'unit', '--output-on-failure')
    for ($try = 0; ; ++$try) {
        $since = Get-Date
        & $ctest @ctestArgs 2>&1 | Tee-Object -Variable testOut | Out-Host
        $code = Get-ExitCode
        if ($code -eq 0) { break }
        $failed = @(Get-FailedTests @($testOut | ForEach-Object { "$_" }))
        $events = @(Get-BlockedFromEventLog $since)
        # Gesperrt heißt: Die Test-EXE startete nicht (BAD_COMMAND), oder IHR Prozess wollte eine gesperrte Datei
        # laden. Ein roter Test ohne eigene Sperre ist ein echter Fehler und bricht sofort ab – auch wenn daneben
        # Sperren stehen.
        $unexplained = @($failed | Where-Object {
                $exe = $_.Exe
                -not $_.NoStart -and -not ($exe -and @($events | Where-Object { $_.Process -ieq $exe }).Count -gt 0)
            })
        if ($failed.Count -eq 0 -or $unexplained.Count -gt 0) { Stop-Build "Tests fehlgeschlagen (Exit $code)." }
        $paths = @($events | ForEach-Object { $_.File })
        # BAD_COMMAND ohne Protokolleintrag (Protokoll nicht lesbar): Die Datei heißt wie der Test. NUR dort –
        # sonst ist die Test-EXE selbst frei, und jede unnötig neu gelinkte Datei wird neu bewertet: In der
        # Gegenprobe 2026-10-24 wurden so 12 freie Test-EXEs mitgelöscht, eine davon kam gesperrt zurück.
        $paths += @($failed | Where-Object { $_.NoStart -and $_.Exe } | ForEach-Object { $_.Exe })
        if ($try -ge $SacRetries) { Stop-SacBuild 'Tests' }
        Remove-BlockedFiles $paths @($events | ForEach-Object { $_.Hash })
        # Ganzer Bau statt nur der Test-Ziele: Eine gesperrte DLL gehört meist zu einem anderen Ziel. Über die
        # Bau-Stage, damit auch eine erneute Sperre beim Neulinken abgefangen wird.
        Invoke-BuildStage
        Write-Step "Tests (Neuversuch $($try + 1) von $SacRetries, nur die fehlgeschlagenen)"
        $ctestArgs = @('--test-dir', $BuildDir, '-C', $Config, '-L', 'unit', '--output-on-failure', '--rerun-failed')
    }
    if ($GpuTestModel) {
        Write-Step "GPU-Probe mit $GpuTestModel"
        $env:SWBH_TEST_GGUF = $GpuTestModel
        Invoke-Checked $ctest @('--test-dir', $BuildDir, '-C', $Config, '-L', 'gpu', '--output-on-failure') 'GPU-Probe'
    }
}

# --- stage ----------------------------------------------------------------------
function Copy-LicenseBundle([string]$Dist) {
    $index = Get-Content (Join-Path $Root 'licenses\licenses.json') -Raw -Encoding utf8 | ConvertFrom-Json
    foreach ($f in $index.files) {
        $src = Join-Path $Root ('licenses\' + ($f.path -replace '/', '\'))
        if (-not (Test-Path -LiteralPath $src)) { Stop-Build "Lizenzdatei fehlt: $src" }
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $src).Hash.ToLowerInvariant()
        if ($hash -ne $f.sha256) { Stop-Build "Lizenzdatei weicht von licenses.json ab: $($f.path)" }
        $dst = Join-Path $Dist ('licenses\' + ($f.path -replace '/', '\'))
        New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
        Copy-Item -LiteralPath $src -Destination $dst -Force
    }
    Copy-Item -LiteralPath (Join-Path $Root 'THIRD-PARTY-NOTICES.md') -Destination $Dist -Force
    # WOZU: Die App selbst steht seit 2026-11-09 unter GPL-3.0-or-later (LICENSE im Repo-Wurzelordner). Wer Binärdateien
    # weitergibt, muss den Lizenztext mitgeben (GPL-3.0 Abschnitt 4 und 6) – das Setup prüft deshalb darauf.
    $appLicense = Join-Path (Split-Path $Root -Parent) 'LICENSE'
    if (-not (Test-Path -LiteralPath $appLicense)) { Stop-Build "Lizenz der App fehlt: $appLicense" }
    Copy-Item -LiteralPath $appLicense -Destination (Join-Path $Dist 'LICENSE.txt') -Force
    Write-Info "$(@($index.files).Count) Lizenzdateien + THIRD-PARTY-NOTICES.md + LICENSE.txt"
}

function Invoke-StageStage {
    Write-Step "Bereitstellen ($DistDir)"
    if (Test-Path $DistDir) { Remove-Item -Recurse -Force -LiteralPath $DistDir }
    foreach ($comp in 'app', 'runtime') {
        Invoke-Checked $cmake @('--install', $BuildDir, '--config', $Config, '--component', $comp, '--prefix', $DistDir) "Installation ($comp)"
    }
    Copy-LicenseBundle $DistDir
    foreach ($must in 'BaumeisterHost.exe', 'ggml.dll', 'ggml-base.dll', 'ggml-vulkan.dll', 'Qt6Core.dll', 'Qt6Gui.dll', 'Qt6Widgets.dll', 'platforms\qwindows.dll',
                      'translations\qtbase_de.qm', 'msvcp140.dll', 'vcruntime140.dll') {
        if (-not (Test-Path (Join-Path $DistDir $must))) { Stop-Build "Bereitstellung unvollständig: $must fehlt" }
    }
    # CUDA nie im Setup: cuBLAS ist NVIDIA-lizenziert und 600 MB groß; die App lädt es nach Zustimmung (components).
    foreach ($never in 'models', 'cuda', 'components', 'ggml-cuda.dll', 'cublas64_13.dll', 'cublasLt64_13.dll', 'cudart64_13.dll') {
        if (Test-Path (Join-Path $DistDir $never)) { Stop-Build "Bereitstellung enthält $never – gehört nicht ins Setup" }
    }
    # Symbole und Test-EXEs nie ins Setup.
    if (Get-ChildItem -LiteralPath $DistDir -Recurse -File -Filter '*.pdb') { Stop-Build 'Bereitstellung enthält PDB-Dateien' }
    if (Get-ChildItem -LiteralPath $DistDir -Recurse -File -Filter 'swbh_test_*') { Stop-Build 'Bereitstellung enthält Test-EXEs' }
    Write-Info 'PE-Abhängigkeiten gegen installer\windows-baseline-dlls.json'
    $deps = Test-PackageDependencies -Dist $DistDir -BaselineFile (Join-Path $Root 'installer\windows-baseline-dlls.json')
    $report = Join-Path (Split-Path $DistDir) 'abhängigkeiten.txt'
    Write-Utf8NoBom $report (($deps.Report -join "`n") + "`n")
    if ($deps.Problems.Count -gt 0) {
        Stop-Build ("Abhängigkeiten der Bereitstellung (Bericht: $report):`n  - " + ($deps.Problems -join "`n  - "))
    }
    Write-Info "$($deps.PeCount) PE-Dateien geprüft, keine Abweichung (Bericht: $report)"
}

# --- installer ------------------------------------------------------------------
function Invoke-InstallerStage {
    $version = Get-AppVersion
    Write-Step "Setup $version"
    $exe = Join-Path $DistDir 'BaumeisterHost.exe'
    $pv = (Get-Item -LiteralPath $exe).VersionInfo.ProductVersion
    if ($pv -ne $version) { Stop-Build "BaumeisterHost.exe trägt Version $pv statt $version" }
    New-Item -ItemType Directory -Force $InstallerDir | Out-Null
    Invoke-Checked $iscc @('/Q', "/DAppVer=$version", "/DSrcDir=$DistDir", "/O$InstallerDir",
        (Join-Path $Root 'installer\BaumeisterHost.iss')) 'Inno Setup'
    $setup = Join-Path $InstallerDir "StreamWizard-Baumeister-Host-Setup-$version.exe"
    if (-not (Test-Path $setup)) { Stop-Build "Setup fehlt nach ISCC: $setup" }
    # Dateiliste des Setups gegen die Bereitstellung: nichts fehlt.
    $manifest = Join-Path $InstallerDir "StreamWizard-Baumeister-Host-Setup-$version-files.txt"
    $listed = @(Import-Csv -LiteralPath $manifest -Delimiter "`t" | ForEach-Object { $_.SourceFilename })
    foreach ($f in @(Get-ChildItem -LiteralPath $DistDir -Recurse -File)) {
        if (-not ($listed | Where-Object { [string]::Equals($_, $f.FullName, [StringComparison]::OrdinalIgnoreCase) })) { Stop-Build "Datei der Bereitstellung fehlt im Setup: $($f.FullName)" }
    }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $setup).Hash.ToLowerInvariant()
    Write-Utf8NoBom "$setup.sha256" "$hash  $(Split-Path $setup -Leaf)`n"
    $size = [math]::Round((Get-Item $setup).Length / 1MB, 1)
    Write-Info "$setup ($size MB)"
    Write-Info "SHA-256 $hash"
    Write-Info 'Hinweis: Diese EXE ist nicht signiert (Signatur nur über den YubiKey-Weg, nach Freigabe).'
}

# Nur EIN Bau je Bauordner. Am 2026-10-04 liefen zwei gleichzeitig (Terminal + Sitzung): Beide holten
# llama.cpp per FetchContent/git, einer sah einen halben Stand und brach mit „llama.cpp steht auf ''“ ab.
# Ausdrücklich freigeben (finally): Mit „.\build.ps1“ läuft das Skript im Prozess des Terminals – eine nur
# fallengelassene Datei blieb dort offen und sperrte den nächsten Lauf im selben Terminal (gesehen).
New-Item -ItemType Directory -Force $BuildDir | Out-Null
$BuildLockPath = Join-Path $BuildDir '.build.lock'
try {
    $BuildLock = [System.IO.File]::Open($BuildLockPath, 'OpenOrCreate', 'ReadWrite', 'None')
} catch [System.IO.IOException] {
    Stop-Build "In $BuildDir läuft schon ein anderer Bau – erst den beenden (oder abwarten), dann erneut starten."
}
try {
    foreach ($s in $Stages) {
        switch ($s) {
            'configure' { Invoke-ConfigureStage }
            'build' { Invoke-BuildStage }
            'test' { Invoke-TestStage }
            'stage' { Invoke-StageStage }
            'installer' { Invoke-InstallerStage }
        }
    }
} finally {
    $BuildLock.Dispose()
}
Write-Step 'Fertig'
exit 0
