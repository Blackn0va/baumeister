#Requires -Version 7
<#
.SYNOPSIS
  Macht aus deinem Minecraft-Mods-Ordner ein Modpaket für Mitspieler: .mrpack (Prism Launcher, Modrinth App) und ZIP.

.DESCRIPTION
  Ziel (2026-11-07): die Mods des Spiele-PCs zum Download anbieten. Ein Minecraft-Server kann keine Mods an Clients schicken (nur
  Ressourcenpakete). Darum dieses Skript auf dem Spiele-PC: Es erkennt jede Mod über ihre SHA-1 bei Modrinth
  (POST /v2/version_files, nur lesend) und schreibt

    Baumeisterland-<mc>.mrpack       Modrinth-Modpaket: Mods von Modrinth nur als Link mit SHA-1/SHA-512 (der
                                     Launcher lädt und prüft selbst), alles andere – z. B. Baumeister-Client – liegt
                                     im Paket unter overrides/mods. Import: Prism „Hinzufügen > Importieren“,
                                     Modrinth App „Datei importieren“.
    Baumeisterland-<mc>-mods.zip     dieselben Jars für den normalen Launcher (Fabric-Installer, dann in mods/).
    Baumeisterland-<mc>-mods.txt     Liste mit Name, Fassung und Quelle – zum Nachsehen und für den Server.

  Mit -Hochladen -Server root@<server> landen alle drei auf dem Server unter
  /srv/minecraft/plugins/Baumeister/modpaket/ (pscp, dein Schlüssel); /mods im Spiel zeigt dann Liste und Fassungen.

  WICHTIG:
    - Das Paket enthält fremde Mods, deren Lizenzen eine öffentliche Weitergabe oft nicht erlauben: nur an
      Mitspieler geben, nicht ins Netz stellen.
    - Nur Fabric-Mods für dieselbe Minecraft-Fassung in den Ordner legen; das Skript prüft die Fassung nicht – das tut
      der Launcher beim Start (Fehlermeldung wie bei Baumeister-Client 0.1.0).
    - Ohne Netz: Alles kommt als Datei ins Paket (overrides), das .mrpack wird größer, funktioniert aber.

  Voraussetzungen: PowerShell 7; für -Hochladen pscp (PuTTY) und Pageant mit deinem Schlüssel.

  Warum diese Datei ein BOM hat: Windows PowerShell 5.1 liest eine .ps1 ohne Byte Order Mark in der ANSI-Codepage.
  Ein versehentlicher Start dort zeigt so die #Requires-Meldung statt Zeichensalat. Nicht entfernen.

  Exitcodes: 0 fertig · 1 Fehler · 2 fertig, aber nicht rundum grün (eine Mod ohne Modrinth-Treffer ist kein Fehler).

.EXAMPLE
  pwsh -File tools\modpaket\Modpaket-Erstellen.ps1

.EXAMPLE
  pwsh -File tools\modpaket\Modpaket-Erstellen.ps1 -Hochladen
#>
[CmdletBinding()]
param(
    # Ordner mit den Mod-Jars (Vorgabe: der des normalen Launchers).
    [string]$ModsOrdner = (Join-Path $env:APPDATA '.minecraft\mods'),
    # Wohin die drei Dateien kommen.
    [string]$Ausgabe = (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Baumeisterland-Modpaket'),
    # Minecraft-Fassung des Servers.
    [string]$Minecraft = '26.2',
    # Fabric-Loader; leer = aus .minecraft\versions lesen, sonst 0.20.1.
    [string]$FabricLoader = '',
    # Fertige Dateien auf den Server laden.
    [switch]$Hochladen,
    # Ziel für -Hochladen, z. B. root@192.0.2.10. Ohne festen Vorgabewert, damit nichts an einen fremden Rechner geht.
    [string]$Server = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

function Schritt([string]$Text) { Write-Host "==> $Text" -ForegroundColor Cyan }
function Hinweis([string]$Text) { Write-Host "    $Text" }
function Warnen([string]$Text) { Write-Host "    WARNUNG: $Text" -ForegroundColor Yellow }
function Fehler([string]$Text) { Write-Host "FEHLER: $Text" -ForegroundColor Red }

try {
    if (-not (Test-Path $ModsOrdner)) {
        Fehler "Mods-Ordner fehlt: $ModsOrdner"
        exit 1
    }
    $jars = @(Get-ChildItem -Path $ModsOrdner -Filter '*.jar' -File | Sort-Object Name)
    if ($jars.Count -eq 0) {
        Fehler "Keine .jar in $ModsOrdner"
        exit 1
    }
    Schritt "$($jars.Count) Mods in $ModsOrdner"

    if ($FabricLoader -eq '') {
        $versions = Join-Path (Split-Path $ModsOrdner -Parent) 'versions'
        $found = @(Get-ChildItem -Path $versions -Directory -Filter "fabric-loader-*-$Minecraft" -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending)
        # WOZU: Name der Launcher-Fassung ist „fabric-loader-<loader>-<mc>“; die neueste gewinnt.
        $FabricLoader = if ($found.Count -gt 0) { $found[0].Name -replace "^fabric-loader-", '' -replace "-$([regex]::Escape($Minecraft))$", '' } else { '0.20.1' }
    }
    Hinweis "Minecraft $Minecraft, Fabric-Loader $FabricLoader"

    $info = @{}
    foreach ($j in $jars) {
        $info[$j.FullName] = [pscustomobject]@{
            Sha1   = (Get-FileHash -Path $j.FullName -Algorithm SHA1).Hash.ToLowerInvariant()
            Sha512 = (Get-FileHash -Path $j.FullName -Algorithm SHA512).Hash.ToLowerInvariant()
        }
    }

    Schritt 'Mods bei Modrinth nachschlagen (SHA-1)'
    $known = @{}
    try {
        $body = @{ hashes = @($info.Values.Sha1); algorithm = 'sha1' } | ConvertTo-Json -Compress
        # FALLE: -AsHashtable gibt es nur bei ConvertFrom-Json, nicht bei Invoke-RestMethod (gemessen 2026-11-07).
        $resp = Invoke-WebRequest -Method Post -Uri 'https://api.modrinth.com/v2/version_files' -Body $body `
            -ContentType 'application/json' -UserAgent 'baumeisterland-modpaket/1.0' -TimeoutSec 30
        $known = $resp.Content | ConvertFrom-Json -AsHashtable
    } catch {
        # FALLE: ohne Netz kein Abbruch – dann kommt jede Mod als Datei ins Paket (overrides).
        Warnen "Modrinth nicht erreichbar ($($_.Exception.Message)) – alle Mods kommen als Datei ins Paket"
        $known = @{}
    }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("modpaket-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path (Join-Path $tmp 'overrides\mods') -Force | Out-Null
    $files = [System.Collections.Generic.List[object]]::new()
    $lines = [System.Collections.Generic.List[string]]::new()
    $bundled = 0
    foreach ($j in $jars) {
        $h = $info[$j.FullName]
        $v = if ($known.ContainsKey($h.Sha1)) { $known[$h.Sha1] } else { $null }
        $file = if ($v) { @($v.files | Where-Object { $_.hashes.sha1 -eq $h.Sha1 })[0] } else { $null }
        if ($file) {
            $files.Add([ordered]@{
                path      = "mods/$($j.Name)"
                hashes    = [ordered]@{ sha1 = $h.Sha1; sha512 = $h.Sha512 }
                env       = [ordered]@{ client = 'required'; server = 'unsupported' }
                downloads = @($file.url)
                fileSize  = $j.Length
            })
            $lines.Add("$($j.Name)`tModrinth`t$($v.version_number)`t$($v.project_id)")
        } else {
            Copy-Item -Path $j.FullName -Destination (Join-Path $tmp "overrides\mods\$($j.Name)")
            $lines.Add("$($j.Name)`tim Paket`t-`t-")
            $bundled++
        }
    }
    $index = [ordered]@{
        formatVersion = 1
        game          = 'minecraft'
        versionId     = "$(Get-Date -Format 'yyyy.MM.dd')-$Minecraft"
        name          = "Baumeisterland $Minecraft"
        summary       = 'Mods für den Server Baumeisterland (Fabric, Simple Voice Chat, Baumeister-Client)'
        files         = $files
        dependencies  = [ordered]@{ minecraft = $Minecraft; 'fabric-loader' = $FabricLoader }
    }
    $json = $index | ConvertTo-Json -Depth 6
    [IO.File]::WriteAllText((Join-Path $tmp 'modrinth.index.json'), $json, [Text.UTF8Encoding]::new($false))

    New-Item -ItemType Directory -Path $Ausgabe -Force | Out-Null
    $pack = Join-Path $Ausgabe "Baumeisterland-$Minecraft.mrpack"
    $zip = Join-Path $Ausgabe "Baumeisterland-$Minecraft-mods.zip"
    $list = Join-Path $Ausgabe "Baumeisterland-$Minecraft-mods.txt"
    Remove-Item -Path $pack, $zip -ErrorAction SilentlyContinue
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::CreateFromDirectory($tmp, $pack)
    $zipTmp = Join-Path $tmp 'zip'
    New-Item -ItemType Directory -Path $zipTmp | Out-Null
    $jars | Copy-Item -Destination $zipTmp
    [IO.Compression.ZipFile]::CreateFromDirectory($zipTmp, $zip)
    $header = "Baumeisterland $Minecraft – Fabric-Loader $FabricLoader – erzeugt $(Get-Date -Format 'yyyy-MM-dd HH:mm')"
    [IO.File]::WriteAllLines($list, @($header) + $lines, [Text.UTF8Encoding]::new($false))
    Remove-Item -Path $tmp -Recurse -Force

    Schritt 'Fertig'
    Hinweis "$pack"
    Hinweis "$zip"
    Hinweis "$list"
    Hinweis "$($files.Count) Mods als Modrinth-Link, $bundled im Paket"

    if ($Hochladen) {
        if (-not $Server) {
            Fehler '-Hochladen braucht -Server root@<server>'
            exit 1
        }
        Schritt "Hochladen nach $Server"
        $pscp = (Get-Command pscp -ErrorAction SilentlyContinue)
        if (-not $pscp) {
            Fehler 'pscp fehlt (PuTTY installieren: winget install PuTTY.PuTTY)'
            exit 1
        }
        & plink -batch $Server 'mkdir -p /srv/minecraft/plugins/Baumeister/modpaket'
        if ((Get-Variable LASTEXITCODE -ValueOnly -ErrorAction SilentlyContinue) -ne 0) {
            Fehler 'plink: Verbindung oder Schlüssel fehlt (Pageant?)'
            exit 1
        }
        & pscp -batch -q $pack $zip $list "${Server}:/srv/minecraft/plugins/Baumeister/modpaket/"
        if ((Get-Variable LASTEXITCODE -ValueOnly -ErrorAction SilentlyContinue) -ne 0) {
            Fehler 'pscp gescheitert'
            exit 1
        }
        Hinweis 'Auf dem Server abgelegt – /mods im Spiel zeigt die Liste.'
    }
    exit ($bundled -gt 0 -and $files.Count -eq 0 ? 2 : 0)
} catch {
    Fehler $_.Exception.Message
    exit 1
}
