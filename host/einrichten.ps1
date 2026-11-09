#Requires -Version 7
<#
.SYNOPSIS
  Ein Befehl für den GPU-PC: Werkzeuge einrichten, Baumeister-Host bauen, Setup starten.

.DESCRIPTION
  Übernommen aus kraken\scripts\einrichten.ps1 (ohne WebView2), dazu PuTTY und am Ende der Bau und das Setup.
  Ziel (2026-10-28): den Host auf der NVIDIA-Maschine mit einem Befehl bauen.

  Prüft jedes Werkzeug und installiert nur, was fehlt – ein zweiter Lauf ändert nichts. Alle Fassungen kommen aus
  deps.lock.json (toolchain, qt); keine steht doppelt hier.

    Git                    winget Git.Git
    Build Tools 2022       winget, MSVC 14.44 + Windows-SDK + C++-CMake-Werkzeuge (Ninja)
    CMake 4.4.3            winget Kitware.CMake
    Vulkan-SDK 1.4.357.0   winget KhronosGroup.VulkanSDK (setzt VULKAN_SDK)
    Inno Setup 6.7.3       winget JRSoftware.InnoSetup, pro Benutzer (nur für die Stage installer)
    Qt 6.11.1              obs-deps-Release nach C:\swb\deps\qt6, Archiv- und DLL-Hashes geprüft
    PuTTY                  winget PuTTY.PuTTY (plink + Pageant für den Tunnel zum Minecraft-Server)
  Danach: build.ps1 (alle Stages) und das fertige Setup starten (abschaltbar mit -OhneSetup).

  Nicht hier: llama.cpp holt build.ps1 beim ersten configure selbst (git, fester Commit). Das Sprachmodell lädt die
  App beim ersten Start, CUDA nach Zustimmung im Dialog.

  WICHTIG:
    - In einem normalen Terminal starten, nicht aus der Claude-Desktop-App: Die ist ein MSIX-Paket, neue Ordner unter
      %LOCALAPPDATA% landen dort unsichtbar in ihrem Paket-Cache – Inno Setup pro Benutzer gäbe es dann nur für sie.
    - Git, Build Tools, CMake und Vulkan-SDK installieren für alle Benutzer: Windows fragt per UAC nach.
    - Vorhandene Build Tools fasst das Skript nicht an, es prüft sie nur. Fehlt etwas, nennt das Skript die
      Komponente.
    - Liegt Qt schon da und weicht von deps.lock.json ab, bricht das Skript ab, statt zu überschreiben.
    - Ohne PowerShell 7: einrichten.cmd doppelklicken – installiert PowerShell 7 und ruft dieses Skript.

  Voraussetzungen: Windows 10/11 x64, winget (App Installer), PowerShell 7, Internet.

  Warum diese Datei ein BOM hat: Windows PowerShell 5.1 liest eine .ps1 ohne Byte Order Mark in der ANSI-Codepage.
  Ein versehentlicher Start dort zeigt so die #Requires-Meldung statt Zeichensalat. Nicht entfernen.

  Exitcodes: 0 alles da · 1 Fehler · 2 fertig, aber Handarbeit offen (im Probelauf: es fehlt noch etwas).

.EXAMPLE
  pwsh -File host\einrichten.ps1
  pwsh -File host\einrichten.ps1 -Probelauf
  pwsh -File host\einrichten.ps1 -OhneSetup
#>
[CmdletBinding()]
param(
    # Nichts installieren oder laden – nur prüfen und sagen, was geschähe.
    [switch]$Probelauf,
    # Nur zum Ausprobieren auf einem fertigen Rechner: Qt unter diesem Ordner statt unter dem Pfad aus deps.lock.json
    # ablegen. build.ps1 erwartet den Lock-Pfad.
    [string]$Ziel = '',
    # Nur für die Gegenprobe (verfälschter Hash muss abbrechen): andere Lock-Datei.
    [string]$LockDatei = '',
    # Nach dem Bau das Setup NICHT starten.
    [switch]$OhneSetup,
    # Nur einrichten, nicht bauen.
    [switch]$OhneBau
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
# WOZU: Mit Fortschrittsbalken lädt Invoke-WebRequest ein Vielfaches langsamer.
$ProgressPreference = 'SilentlyContinue'

$Root = $PSScriptRoot
if (-not $LockDatei) { $LockDatei = Join-Path $Root 'deps.lock.json' }
$Lock = Get-Content -LiteralPath $LockDatei -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
$Tc = $Lock.toolchain
$script:Fehlerzahl = 0
$script:Offen = [System.Collections.Generic.List[string]]::new()
$script:Installiert = $false

function Schritt([string]$Text) { Write-Host ''; Write-Host "==> $Text" -ForegroundColor Cyan }
function Hinweis([string]$Text) { Write-Host "    $Text" -ForegroundColor DarkGray }
function Gut([string]$Text) { Write-Host "    [ok]   $Text" -ForegroundColor Green }
function Warnen([string]$Text) { Write-Host "    [warn] $Text" -ForegroundColor Yellow; $script:Offen.Add($Text) }
function Fehler([string]$Text) { Write-Host "    [FEHL] $Text" -ForegroundColor Red; $script:Fehlerzahl++ }

# FALLE: $LASTEXITCODE existiert in einer frischen Sitzung unter StrictMode nicht.
function Get-Exitcode {
    $c = Get-Variable -Name LASTEXITCODE -Scope Global -ValueOnly -ErrorAction SilentlyContinue
    if ($null -eq $c) { return 0 }
    return [int]$c
}

# WOZU: Nach einer winget-Installation steht das neue Werkzeug nur im PATH der Registry, nicht in dieser Sitzung.
function Sync-Pfad {
    $env:PATH = [Environment]::GetEnvironmentVariable('PATH', 'Machine') + ';' +
        [Environment]::GetEnvironmentVariable('PATH', 'User')
}

# Installiert nur, wenn der Aufrufer vorher festgestellt hat, dass das Werkzeug fehlt (Prüfung über die Datei, nicht
# über „winget list“ – das meldet manches Vorhandene als fehlend, gemessen in claudesetup, Schritt 20).
function Install-Paket {
    param([string]$Id, [string]$Version = '', [string]$Bereich = '', [string]$Override = '')
    $a = @('install', '--id', $Id, '--exact', '--source', 'winget', '--accept-package-agreements',
        '--accept-source-agreements', '--disable-interactivity')
    if ($Version) { $a += @('--version', $Version) }
    if ($Bereich) { $a += @('--scope', $Bereich) }
    if ($Override) { $a += @('--override', $Override) }
    if ($Probelauf) {
        Hinweis "fehlt – würde ausführen: winget $($a -join ' ')"
        $script:Offen.Add("$Id fehlt")
        return $false
    }
    Hinweis "winget install $Id $Version"
    & winget @a
    $code = Get-Exitcode
    Sync-Pfad
    if ($code -ne 0) {
        Fehler "winget $Id endete mit Exitcode $code"
        return $false
    }
    $script:Installiert = $true
    return $true
}

function Get-Sha256([string]$Pfad) { (Get-FileHash -LiteralPath $Pfad -Algorithm SHA256).Hash.ToLowerInvariant() }

# Abweichungen eines Ordners vom Hashsatz aus deps.lock.json; leer = alles passt.
function Test-Hashsatz([string]$Ordner, [System.Collections.IDictionary]$Soll) {
    foreach ($rel in $Soll.Keys) {
        $p = Join-Path $Ordner ($rel -replace '/', '\')
        if (-not (Test-Path -LiteralPath $p)) { "$rel fehlt"; continue }
        $ist = Get-Sha256 $p
        if ($ist -ne $Soll[$rel]) { "$rel hat $ist" }
    }
}

# Archiv laden, Archiv-Hash prüfen, entpacken (alles oder nur $Auswahl: Lock-Pfad → Pfad im Archiv), Hashsatz
# prüfen – erst dann an den Zielort. Scheitert irgendein Schritt, bleibt der Zielordner unangetastet: keine
# ungeprüften Binärdateien im Bau (OWASP A08, ASVS V10.3.2). ExtractToDirectory weist Einträge außerhalb des Ziels
# ab (.NET-Prüfung gegen Zip-Slip).
function Install-Hashsatz {
    param([string]$Name, [string]$Ordner, [string]$Url, [string]$ArchivSha, [System.Collections.IDictionary]$Soll,
        [System.Collections.IDictionary]$Auswahl = $null)
    if (Test-Path -LiteralPath $Ordner) {
        $abw = @(Test-Hashsatz $Ordner $Soll)
        if ($abw.Count -eq 0) {
            Gut "$Name vorhanden, $($Soll.Count) Hashes stimmen ($Ordner)"
        } else {
            Fehler "$Name in $Ordner weicht von deps.lock.json ab: $($abw -join '; '). Das Skript überschreibt nichts."
        }
        return
    }
    if ($Probelauf) {
        Hinweis "fehlt – würde laden: $Url"
        Hinweis "  und nach $Ordner entpacken"
        $script:Offen.Add("$Name fehlt")
        return
    }
    $arbeit = Join-Path (Split-Path $Ordner) ('_einrichten-' + (Split-Path $Ordner -Leaf))
    if (Test-Path -LiteralPath $arbeit) { Remove-Item -LiteralPath $arbeit -Recurse -Force }
    New-Item -ItemType Directory -Force $arbeit | Out-Null
    try {
        $zip = Join-Path $arbeit 'archiv.zip'
        Hinweis "lade $Url"
        Invoke-WebRequest -Uri $Url -OutFile $zip -UseBasicParsing
        $ist = Get-Sha256 $zip
        if ($ist -ne $ArchivSha) { throw "Archiv-SHA-256 weicht ab: $ist statt $ArchivSha" }
        $neu = Join-Path $arbeit 'inhalt'
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        if ($Auswahl) {
            $z = [System.IO.Compression.ZipFile]::OpenRead($zip)
            try {
                foreach ($rel in $Auswahl.Keys) {
                    $eintrag = $z.GetEntry($Auswahl[$rel])
                    if (-not $eintrag) { throw "im Archiv fehlt $($Auswahl[$rel])" }
                    $dst = Join-Path $neu ($rel -replace '/', '\')
                    New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
                    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($eintrag, $dst)
                }
            } finally {
                $z.Dispose()
            }
        } else {
            [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $neu)
        }
        $abw = @(Test-Hashsatz $neu $Soll)
        if ($abw.Count -gt 0) { throw "Hashes weichen ab: $($abw -join '; ')" }
        New-Item -ItemType Directory -Force (Split-Path $Ordner) | Out-Null
        Move-Item -LiteralPath $neu -Destination $Ordner
        Gut "$Name abgelegt in $Ordner, $($Soll.Count) Hashes geprüft"
    } catch {
        Fehler "${Name}: $($_.Exception.Message)"
    } finally {
        Remove-Item -LiteralPath $arbeit -Recurse -Force
    }
}

# --- Voraussetzungen ----------------------------------------------------------------------------------------------
Schritt 'Voraussetzungen'
if (-not [Environment]::Is64BitOperatingSystem) {
    Fehler 'Windows x64 nötig – Qt, Vulkan und CUDA gibt es nur dafür.'
    exit 1
}
Sync-Pfad
if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    Fehler 'winget fehlt – „App Installer“ aus dem Microsoft Store installieren, dann erneut starten.'
    exit 1
}
Gut "PowerShell $($PSVersionTable.PSVersion), winget vorhanden"
if ($Probelauf) { Hinweis 'Probelauf: es wird nichts installiert und nichts geladen.' }

# --- Git ----------------------------------------------------------------------------------------------------------
Schritt 'Git'
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Install-Paket -Id 'Git.Git' | Out-Null }
if (Get-Command git -ErrorAction SilentlyContinue) { Gut ((& git --version) -join ' ') }

# --- Build Tools --------------------------------------------------------------------------------------------------
Schritt "Visual Studio Build Tools 2022 (MSVC $($Tc.msvc))"
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
function Find-VsInstanz {
    # Wie build.ps1: zuerst die Instanz aus deps.lock.json, sonst irgendeine mit C++-Werkzeugen.
    $lockVs = $Tc.vs_instance -replace '/', '\'
    if (Test-Path -LiteralPath (Join-Path $lockVs 'VC\Tools\MSVC')) { return $lockVs }
    if (-not (Test-Path -LiteralPath $vswhere)) { return '' }
    $frage = @('-products', '*', '-requires', 'Microsoft.VisualStudio.Component.VC.Tools.x86.x64',
        '-property', 'installationPath')
    $treffer = @(& $vswhere @frage)
    if ($treffer.Count -gt 0) { return [string]$treffer[0] }
    return ''
}
$vs = Find-VsInstanz
if (-not $vs) {
    # Komponenten: C++-Werkzeuge x64, C++-CMake-Werkzeuge (bringt Ninja an der Stelle aus toolchain.ninja),
    # Windows-11-SDK. Build Tools 17.14 bringt MSVC 14.44 (winget-Stand 2026-10-24).
    $override = '--wait --passive --norestart --add Microsoft.VisualStudio.Workload.VCTools ' +
        '--add Microsoft.VisualStudio.Component.VC.Tools.x86.x64 ' +
        '--add Microsoft.VisualStudio.Component.VC.CMake.Project ' +
        '--add Microsoft.VisualStudio.Component.Windows11SDK.26100'
    if (Install-Paket -Id 'Microsoft.VisualStudio.2022.BuildTools' -Override $override) { $vs = Find-VsInstanz }
}
if ($vs) {
    Gut "Instanz: $vs"
    $msvc = @(Get-ChildItem -LiteralPath (Join-Path $vs 'VC\Tools\MSVC') -Directory -ErrorAction SilentlyContinue |
            Where-Object Name -Like "$($Tc.msvc).*" | Select-Object -ExpandProperty Name)
    if ($msvc.Count -gt 0) {
        Gut "MSVC $($msvc -join ', ')"
    } else {
        # FALLE: PowerShell nimmt „ “ ” als Anführungszeichen – in "…" beenden sie den String. Deshalb '…'.
        Warnen ("MSVC $($Tc.msvc) fehlt in $vs – im Visual Studio Installer " +
            '„MSVC v143 – VS 2022 C++ x64/x86-Buildtools (v' + $Tc.msvc + ')“ ergänzen.')
    }
    $ninja = Join-Path $vs ($Tc.ninja -replace '/', '\')
    if (Test-Path -LiteralPath $ninja) {
        Gut 'Ninja (aus den C++-CMake-Werkzeugen)'
    } else {
        Warnen ("Ninja fehlt ($ninja) – Komponente " +
            '„C++-CMake-Tools für Windows“ (Microsoft.VisualStudio.Component.VC.CMake.Project) ergänzen.')
    }
    $kits = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\Include'
    $sdk = @(Get-ChildItem -LiteralPath $kits -Directory -Filter '10.0.*' -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty Name)
    if ($sdk.Count -gt 0) { Gut "Windows-SDK $($sdk -join ', ')" }
    else { Warnen 'Windows-SDK fehlt – Komponente „Windows 11 SDK“ ergänzen.' }
    $redistWurzel = Join-Path $vs 'VC\Redist\MSVC'
    $redist = @(Get-ChildItem -LiteralPath $redistWurzel -Directory -Filter "$($Tc.msvc).*" `
            -ErrorAction SilentlyContinue)
    if ($redist.Count -gt 0) { Gut "VC-Laufzeit zum Mitliefern $($redist.Name -join ', ')" }
    else { Warnen "VC-Laufzeit $($Tc.msvc).* fehlt unter $redistWurzel – nur die Stage stage braucht sie." }
} elseif (-not $Probelauf) {
    Fehler 'Keine Visual-Studio-Instanz mit C++-Werkzeugen gefunden.'
}

# --- CMake --------------------------------------------------------------------------------------------------------
Schritt "CMake $($Tc.cmake)"
function Find-Cmake4 {
    # Wie build.ps1: alle Fundstellen, nicht das erste cmake im PATH – die Developer PowerShell stellt CMake 3.31 vor.
    $imPfad = @(Get-Command cmake -All -CommandType Application -ErrorAction SilentlyContinue | ForEach-Object Source)
    $kandidaten = $imPfad + @(Join-Path $env:ProgramFiles 'CMake\bin\cmake.exe') |
        Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique
    $gefunden = foreach ($k in $kandidaten) {
        $erste = (& $k --version) | Select-Object -First 1
        [pscustomobject]@{ Pfad = $k; Fassung = ($erste -replace '^cmake version\s+', '') }
    }
    @($gefunden | Where-Object Fassung -EQ $Tc.cmake) + @($gefunden | Where-Object { $_.Fassung.StartsWith('4.') }) |
        Select-Object -First 1
}
$cm = Find-Cmake4
if (-not $cm) {
    if (Install-Paket -Id 'Kitware.CMake' -Version $Tc.cmake) { $cm = Find-Cmake4 }
}
if ($cm) {
    Gut "CMake $($cm.Fassung) ($($cm.Pfad))"
    if ($cm.Fassung -ne $Tc.cmake) { Hinweis "deps.lock.json nennt $($Tc.cmake); build.ps1 nimmt jede 4.x." }
}

# --- Vulkan-SDK ---------------------------------------------------------------------------------------------------
Schritt "Vulkan-SDK $($Tc.vulkan_sdk)"
function Get-VulkanSdk {
    $v = [Environment]::GetEnvironmentVariable('VULKAN_SDK', 'Machine')
    if (-not $v) { $v = $env:VULKAN_SDK }
    return $v
}
$vk = Get-VulkanSdk
if (-not $vk) {
    if (Install-Paket -Id 'KhronosGroup.VulkanSDK' -Version $Tc.vulkan_sdk) { $vk = Get-VulkanSdk }
}
if ($vk) {
    $env:VULKAN_SDK = $vk
    if (-not (Test-Path -LiteralPath (Join-Path $vk 'Bin\glslc.exe'))) {
        Fehler "VULKAN_SDK=$vk, aber Bin\glslc.exe fehlt – llama.cpp braucht es für die Vulkan-Shader."
    } elseif (-not $vk.TrimEnd('\').EndsWith($Tc.vulkan_sdk)) {
        # Bewusst kein Umstellen: VULKAN_SDK gilt maschinenweit, auch für localAI und CaptionBridgeCPP.
        Warnen ("VULKAN_SDK zeigt auf $vk, build.ps1 verlangt $($Tc.vulkan_sdk) – " +
            "winget install --id KhronosGroup.VulkanSDK --version $($Tc.vulkan_sdk)")
    } else {
        Gut "VULKAN_SDK=$vk"
    }
}

# --- Inno Setup ---------------------------------------------------------------------------------------------------
Schritt "Inno Setup $($Tc.innosetup) (nur für die Stage installer)"
$isccLock = [Environment]::ExpandEnvironmentVariables([string]$Tc.iscc) -replace '/', '\'
$isccBenutzer = Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'
if (-not (Test-Path -LiteralPath $isccLock) -and -not (Test-Path -LiteralPath $isccBenutzer)) {
    Install-Paket -Id 'JRSoftware.InnoSetup' -Version $Tc.innosetup -Bereich 'user' | Out-Null
}
if (Test-Path -LiteralPath $isccLock) {
    # ISCC.exe trägt keine Versionsressource (0.0.0.0, gemessen) – die Fassung steht im Deinstallationseintrag.
    $innoFassung = Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1',
        'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1' `
        -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty DisplayVersion
    Gut "Inno Setup $innoFassung ($isccLock)"
    if ($innoFassung -and $innoFassung -ne $Tc.innosetup) { Hinweis "deps.lock.json nennt $($Tc.innosetup)." }
} elseif (Test-Path -LiteralPath $isccBenutzer) {
    # deps.lock.json nennt den Pfad mit dem Benutzernamen des Entwicklungsrechners; build.ps1 sucht auch hier.
    Gut "Inno Setup ($isccBenutzer)"
}

# --- Qt -----------------------------------------------------------------------------------------------------------
$qt = $Lock.qt
Schritt "Qt $($qt.version) (obs-deps $($qt.obs_deps_commit))"
$qtOrdner = if ($Ziel) { Join-Path $Ziel 'qt6' } else { $qt.dir -replace '/', '\' }
Install-Hashsatz -Name 'Qt' -Ordner $qtOrdner -Url $qt.archive -ArchivSha $qt.archive_sha256 -Soll $qt.sha256

# --- PuTTY --------------------------------------------------------------------------------------------------------
Schritt 'PuTTY (plink + Pageant für den Tunnel)'
$plink = Join-Path $env:ProgramFiles 'PuTTY\plink.exe'
if (-not (Test-Path -LiteralPath $plink)) { Install-Paket -Id 'PuTTY.PuTTY' | Out-Null }
if (Test-Path -LiteralPath $plink) {
    Gut "plink ($plink)"
    Hinweis 'Pageant mit dem Schlüssel für deinen Server laden und einmal mit PuTTY dorthin verbinden (Hostschlüssel bestätigen).'
} elseif (-not $Probelauf) {
    Warnen "plink.exe fehlt unter $plink – Pfad später in den Einstellungen der App eintragen."
}

# --- Ergebnis -----------------------------------------------------------------------------------------------------
Schritt 'Ergebnis der Einrichtung'
if ($script:Installiert) { Sync-Pfad; $env:VULKAN_SDK = Get-VulkanSdk }
if ($script:Fehlerzahl -gt 0) {
    Fehler "$($script:Fehlerzahl) Fehler – siehe oben."
    exit 1
}
foreach ($o in $script:Offen) { Write-Host "    offen: $o" -ForegroundColor Yellow }
if ($Probelauf -or $OhneBau) {
    exit $(if ($script:Offen.Count -gt 0) { 2 } else { 0 })
}

# --- Bauen --------------------------------------------------------------------------------------------------------
Schritt 'Baumeister-Host bauen (build.ps1: configure, build, stage, installer – ohne Tests)'
Hinweis 'Der erste Bau holt llama.cpp und übersetzt die Vulkan-Shader – rund 10 bis 20 Minuten.'
# WOZU: eigener pwsh-Prozess mit frischem PATH aus der Registry – winget hat eben erst Werkzeuge eingetragen.
& (Get-Process -Id $PID).Path -NoProfile -File (Join-Path $Root 'build.ps1')
$bau = Get-Exitcode
if ($bau -ne 0) {
    Fehler "build.ps1 endete mit Exitcode $bau – Ausgabe oben."
    exit 1
}
$version = ((Get-Content (Join-Path $Root 'CMakeLists.txt') -Raw) -replace '(?s).*project\(BaumeisterHost\s+VERSION\s+(\d+\.\d+\.\d+).*', '$1')
$setup = Join-Path $Root "out\installer\StreamWizard-Baumeister-Host-Setup-$version.exe"
Gut "Setup: $setup"
if (-not $OhneSetup -and (Test-Path -LiteralPath $setup)) {
    Hinweis 'Starte das Setup – danach BaumeisterHost aus dem Startmenü öffnen. Es lädt beim ersten Start das Modell.'
    Start-Process -FilePath $setup
}
exit 0
