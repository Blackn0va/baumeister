#Requires -Version 7
<#
.SYNOPSIS
  Packt alles, was ein fremder Server-Betreiber braucht, in einen Ordner und ein ZIP: Plugin, Relay samt
  Einrichtungsskript, Baumeister-App (Setup), Client-Mod, Anleitung, Lizenzhinweise, Prüfsummen.

.DESCRIPTION
  Ziel (2026-11-08): Das Plugin soll sich auch auf fremden Servern leicht installieren lassen. Bisher lagen die Teile in vier Bauordnern, und die einzige Anleitung war die für dev01.
  Dieses Skript baut (wenn nicht -OhneBau) und legt ab:

    out\paket\Baumeister-<fassung>\
      INSTALLATION.md                     docs/installation.md
      plugin\Baumeister-<fassung>.jar     nach plugins/ des Servers
      relay\                              Stufe 3: einrichten.sh, baumeister-relay.jar, Unit, Beispiel, Schlüsselskript
      app\StreamWizard-Baumeister-Host-Setup-<fassung>.exe   Stufe 2 und 3 (nur, wenn gebaut)
      client\baumeister-client-<fassung>.jar                 freiwillig, für Spieler
      THIRD-PARTY-NOTICES.md, SHA256SUMS.txt
    out\paket\Baumeister-<fassung>.zip

  WICHTIG:
    - Veröffentlicht nichts. Ob und wo das Paket hinkommt, entscheidest du (CLAUDE.md: Code nie öffentlich machen).
    - Das App-Setup ist unsigniert; signieren nur mit deiner PIN (host\build.ps1, Stage sign).
    - .sh-Dateien bleiben LF – das Skript prüft das, ein CRLF bricht bash auf dem Server.

  Voraussetzungen: PowerShell 7, Maven und JDK 21 im PATH, für den Client JDK 25 (-Jdk25), Repo-Stand gebaut oder
  baubar.

  Warum diese Datei ein BOM hat: Windows PowerShell 5.1 liest eine .ps1 ohne Byte Order Mark in der ANSI-Codepage.
  Ein versehentlicher Start dort zeigt so die #Requires-Meldung statt Zeichensalat. Nicht entfernen.

  Exitcodes: 0 fertig · 1 Fehler · 2 fertig, aber ohne App-Setup (nicht gebaut).

.EXAMPLE
  pwsh -File tools\paket\Paket-Erstellen.ps1

.EXAMPLE
  pwsh -File tools\paket\Paket-Erstellen.ps1 -OhneBau
#>
[CmdletBinding()]
param(
    # Nichts bauen, nur die vorhandenen Bauergebnisse packen.
    [switch]$OhneBau,
    # Zielordner.
    [string]$Ausgabe = (Join-Path $PSScriptRoot '..\..\out\paket'),
    # JDK für den Client-Mod (Minecraft 26.2 braucht 25).
    [string]$Jdk25 = (Join-Path $env:USERPROFILE '.local\opt\jdk-25.0.5+8')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

function Schritt([string]$Text) { Write-Host "==> $Text" -ForegroundColor Cyan }
function Hinweis([string]$Text) { Write-Host "    $Text" }
function Warnen([string]$Text) { Write-Host "    WARNUNG: $Text" -ForegroundColor Yellow }
function Fehler([string]$Text) { Write-Host "FEHLER: $Text" -ForegroundColor Red }

# FALLE: $LASTEXITCODE gibt es in einer frischen Konsole nicht – unter StrictMode daher abgesichert lesen.
function Invoke-Native([string]$Was, [scriptblock]$Befehl) {
    & $Befehl
    $rc = (Get-Variable LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue)?.Value
    if ($rc -ne 0) {
        throw "$Was endete mit $rc"
    }
}

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$exit = 0
try {
    $pom = [xml](Get-Content (Join-Path $repo 'pom.xml') -Raw)
    $version = $pom.project.version
    $clientVersion = ((Get-Content (Join-Path $repo 'client\gradle.properties')) -match '^version=' |
        Select-Object -First 1) -replace '^version=', ''
    Schritt "Baumeister $version, Client $clientVersion"

    if (-not $OhneBau) {
        Push-Location $repo
        try {
            Schritt 'Plugin bauen und testen'
            Invoke-Native 'mvn package (Plugin)' { mvn -o -q package }
            Schritt 'Relay bauen und testen'
            Invoke-Native 'mvn package (Relay)' { mvn -o -q -f relay/pom.xml package }
        } finally {
            Pop-Location
        }
        Schritt 'Client-Mod bauen'
        Push-Location (Join-Path $repo 'client')
        $oldJava = $env:JAVA_HOME
        try {
            $env:JAVA_HOME = $Jdk25
            Invoke-Native 'gradlew build (Client)' { .\gradlew.bat --offline -q build }
        } finally {
            $env:JAVA_HOME = $oldJava
            Pop-Location
        }
    }

    $teile = [ordered]@{
        "plugin\Baumeister-$version.jar"                = "target\Baumeister-$version.jar"
        'relay\baumeister-relay.jar'                     = 'relay\target\baumeister-relay.jar'
        'relay\einrichten.sh'                            = 'relay\einrichten.sh'
        'relay\schluessel-erzeugen.sh'                   = 'relay\deploy\schluessel-erzeugen.sh'  # umlaut-ok: Dateiname
        'relay\baumeister-relay.service'                 = 'relay\deploy\baumeister-relay.service'
        'relay\relay.properties.beispiel'                = 'relay\deploy\relay.properties.beispiel'
        'relay\README.md'                                = 'relay\deploy\README.md'
        "client\baumeister-client-$clientVersion.jar"   = "client\build\libs\baumeister-client-$clientVersion.jar"
        'INSTALLATION.md'                                = 'docs\installation.md'
        'kanal-v1.md'                                    = 'docs\kanal-v1.md'
        'THIRD-PARTY-NOTICES.md'                         = 'THIRD-PARTY-NOTICES.md'
        # GPL-3.0-or-later (2026-11-09): Wer das Paket weitergibt, gibt den Lizenztext mit.
        'LICENSE.txt'                                    = 'LICENSE'
    }
    foreach ($quelle in $teile.Values) {
        if (-not (Test-Path (Join-Path $repo $quelle))) {
            Fehler "$quelle fehlt – erst bauen (ohne -OhneBau)"
            exit 1
        }
    }

    # FALLE: Ein CRLF in einem Shell-Skript lässt bash auf dem Server mit „$'\r': command not found“ abbrechen.
    foreach ($sh in @('relay\einrichten.sh', 'relay\deploy\schluessel-erzeugen.sh')) {  # umlaut-ok: Dateiname
        $bytes = [IO.File]::ReadAllBytes((Join-Path $repo $sh))
        if ($bytes -contains 13) {
            Fehler "$sh enthält CRLF"
            exit 1
        }
    }

    $ziel = Join-Path ([IO.Path]::GetFullPath($Ausgabe)) "Baumeister-$version"
    Schritt "Packen nach $ziel"
    if (Test-Path $ziel) {
        Remove-Item -Recurse -Force $ziel
    }
    foreach ($e in $teile.GetEnumerator()) {
        $nach = Join-Path $ziel $e.Key
        New-Item -ItemType Directory -Force (Split-Path $nach -Parent) | Out-Null
        Copy-Item (Join-Path $repo $e.Value) $nach
    }

    $setup = Get-ChildItem (Join-Path $repo 'host\out\installer') -Filter 'StreamWizard-Baumeister-Host-Setup-*.exe' `
        -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
    if ($null -eq $setup) {
        Warnen 'Kein App-Setup unter host\out\installer – Stufe 2 und 3 fehlen im Paket (host\build.ps1 bauen)'
        $exit = 2
    } else {
        New-Item -ItemType Directory -Force (Join-Path $ziel 'app') | Out-Null
        Copy-Item $setup.FullName (Join-Path $ziel 'app')
        Hinweis "App: $($setup.Name) (unsigniert)"
    }

    Schritt 'Prüfsummen'
    $zeilen = Get-ChildItem $ziel -Recurse -File | Where-Object Name -ne 'SHA256SUMS.txt' | Sort-Object FullName |
        ForEach-Object {
            $rel = [IO.Path]::GetRelativePath($ziel, $_.FullName).Replace('\', '/')
            '{0}  {1}' -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $rel
        }
    # LF und ohne BOM: „sha256sum -c SHA256SUMS.txt“ auf dem Server liest das so.
    [IO.File]::WriteAllText((Join-Path $ziel 'SHA256SUMS.txt'), ($zeilen -join "`n") + "`n",
        [Text.UTF8Encoding]::new($false))
    $zeilen | ForEach-Object { Hinweis $_ }

    $zip = "$ziel.zip"
    if (Test-Path $zip) {
        Remove-Item -Force $zip
    }
    Compress-Archive -Path $ziel -DestinationPath $zip
    Schritt "Fertig: $zip ($([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB)"
    Hinweis 'Nichts veröffentlicht. Weitergeben entscheidest du.'
    exit $exit
} catch {
    Fehler $_.Exception.Message
    exit 1
}
