#requires -Version 7.0
<#
.SYNOPSIS
  Messprobe winhttp_pin: gepinnte wss://-Verbindung mit Client-Zertifikat gegen eine JSSE-Gegenstelle.

.DESCRIPTION
  Zweck: misst, was WinHTTP bei Kanal v1 (docs/kanal-v1.md) wirklich tut – Pin-Prüfung vor dem ersten Byte der
  Anfrage, Client-Authentifizierung mit nicht exportierbarem CNG-Schlüssel (TPM oder Software), Rahmen, Keepalive,
  Empfangsfrist, falsche Rahmen, TLS 1.2. Ergebnis steht in README.md daneben.

  WICHTIG: Legt je Lauf einen Schlüssel „BaumeisterHost-Probe-<zufall>“ im Schlüsselspeicher des Benutzers und ein
  Zertifikat in CurrentUser\My an und löscht beides am Ende wieder (auch nach einem Fehler). Die Schlüssel der
  Gegenstelle (keytool, EC P-256) entstehen je Lauf neu im Arbeitsordner und werden mit ihm gelöscht.
  Aus der Claude-App heraus leitet MSIX neue Ordner unter %LOCALAPPDATA% um (PCPKSP des TPM!) – für (b) zusätzlich
  von außen starten: Invoke-CimMethod Win32_Process Create (siehe README.md).

  Voraussetzungen: Build Tools 2022 (vcvars64), JDK 21+ (JAVA_HOME), PowerShell 7.

  Die Datei hat ein BOM: Windows PowerShell 5.1 läse sie sonst als ANSI und zerlegte die Umlaute.

.EXAMPLE
  pwsh -File host\probes\winhttp_pin\run_probe.ps1
  pwsh -File host\probes\winhttp_pin\run_probe.ps1 -WorkDir C:\swb\probe-pin -Anbieter software
#>
[CmdletBinding()]
param(
    # Arbeitsordner für EXE, Schlüssel der Gegenstelle und Mitschrift. Vorgabe: host\out\probe-winhttp-pin.
    [string]$WorkDir = '',
    # tpm (Rückfall auf software, wenn kein TPM) oder software.
    [ValidateSet('tpm', 'software')] [string]$Anbieter = 'tpm',
    # Nur diese Szenarien (Namen aus $Szenarien unten), leer = alle.
    [string[]]$Nur = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
# FALLE: pwsh -File übergibt -Nur a,b als EINEN String – hier aufteilen.
$Nur = @($Nur | ForEach-Object { $_ -split ',' } | Where-Object { $_ })

function Schritt([string]$Text) { Write-Host "==> $Text" -ForegroundColor Cyan }
function Hinweis([string]$Text) { Write-Host "    $Text" }
function Warnen([string]$Text) { Write-Host "WARNUNG: $Text" -ForegroundColor Yellow }
function Fehler([string]$Text) { Write-Host "FEHLER: $Text" -ForegroundColor Red }

function Get-ExitCode {
    if (Test-Path variable:global:LASTEXITCODE) { return $global:LASTEXITCODE }
    return 0
}

$here = $PSScriptRoot
if (-not $WorkDir) { $WorkDir = Join-Path $here '..\..\out\probe-winhttp-pin' }
$WorkDir = [IO.Path]::GetFullPath($WorkDir)
New-Item -ItemType Directory -Force $WorkDir | Out-Null
$log = Join-Path $WorkDir ('ergebnis-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

function Protokoll([string]$Text) {
    Add-Content -LiteralPath $log -Value $Text -Encoding utf8
    Write-Host $Text
}

# --- Übersetzen -------------------------------------------------------------------------------------------------
Schritt 'Probe übersetzen (cl)'
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
$vsDir = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath |
    Select-Object -First 1
$vcvars = Join-Path $vsDir 'VC\Auxiliary\Build\vcvars64.bat'
$exe = Join-Path $WorkDir 'winhttp_pin_probe.exe'
$src = Join-Path $here 'winhttp_pin_probe.cpp'
$cmdFile = Join-Path $WorkDir 'cl.cmd'
# FALLE: cl schreibt .obj ins Arbeitsverzeichnis – deshalb /Fo in den Arbeitsordner.
$cl = "@call `"$vcvars`" >nul`r`n@cl /nologo /std:c++20 /W4 /WX /EHsc /utf-8 /O2 /DUNICODE /D_UNICODE " +
      "/DWIN32_LEAN_AND_MEAN /DNOMINMAX `"$src`" /Fo`"$WorkDir\\`" /Fe`"$exe`" /link /guard:cf`r`n"
[IO.File]::WriteAllText($cmdFile, $cl, [Text.UTF8Encoding]::new($false))
& $env:ComSpec /d /c $cmdFile
if ((Get-ExitCode) -ne 0) { Fehler 'Übersetzen fehlgeschlagen'; exit 1 }

# --- Schlüssel der Gegenstelle ------------------------------------------------------------------------------------
Schritt 'Schlüssel der Gegenstelle (keytool, EC P-256, je Lauf neu)'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
$pass = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(16))
foreach ($ks in 'server', 'fremd') {
    $p12 = Join-Path $WorkDir "$ks.p12"
    Remove-Item -LiteralPath $p12 -ErrorAction SilentlyContinue
    & $keytool -genkeypair -alias relay -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA `
        -dname "CN=relay-probe-$ks" -validity 2 -keystore $p12 -storetype PKCS12 -storepass $pass -keypass $pass 2>&1 |
        Out-Null
    if ((Get-ExitCode) -ne 0) { Fehler "keytool ($ks) fehlgeschlagen"; exit 1 }
}
# Abgelaufenes Serverzertifikat (vor 3 Tagen ausgestellt, 1 Tag gültig): Prüft Schannel das Datum trotz ignorierter CA?
$p12 = Join-Path $WorkDir 'abgelaufen.p12'
Remove-Item -LiteralPath $p12 -ErrorAction SilentlyContinue
& $keytool -genkeypair -alias relay -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA -dname 'CN=relay-probe-alt' `
    -startdate '-3d' -validity 1 -keystore $p12 -storetype PKCS12 -storepass $pass -keypass $pass 2>&1 | Out-Null
if ((Get-ExitCode) -ne 0) { Fehler 'keytool (abgelaufen) fehlgeschlagen'; exit 1 }
$der = Join-Path $WorkDir 'fremd.der'
& $keytool -exportcert -alias relay -keystore (Join-Path $WorkDir 'fremd.p12') -storepass $pass -file $der 2>&1 | Out-Null
$fremdPin = 'sha256:' + (Get-FileHash -LiteralPath $der -Algorithm SHA256).Hash.ToLowerInvariant()

# --- Client-Schlüssel ---------------------------------------------------------------------------------------------
$name = 'BaumeisterHost-Probe-' + [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(6)).ToLowerInvariant()
$nameSoft = $name + '-sw'
$nameOhne = $name + '-ohne'
Protokoll "# winhttp_pin $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') Windows $([Environment]::OSVersion.Version) Anbieter=$Anbieter"

function Invoke-Probe([string[]]$ArgList) {
    $lines = & $exe @ArgList 2>&1
    $code = Get-ExitCode
    foreach ($l in $lines) { Protokoll "  client: $l" }
    return $code
}

$exitCode = 0
try {
    Schritt "Client-Schlüssel anlegen ($Anbieter): $name"
    Protokoll '## anlegen'
    $code = Invoke-Probe @('anlegen', $name, $Anbieter)
    if ($code -ne 0 -and $Anbieter -eq 'tpm') {
        Warnen 'TPM-Schlüssel nicht anlegbar – Rückfall auf den Software-Schlüsselspeicher'
        $code = Invoke-Probe @('anlegen', $name, 'software')
    }
    if ($code -ne 0) { throw 'Client-Schlüssel nicht anlegbar' }
    Protokoll '## anlegen software'
    $null = Invoke-Probe @('anlegen', $nameSoft, 'software')
    Protokoll '## ohne CRYPT_KEY_PROV_INFO'
    $null = Invoke-Probe @('ohne-prov-info', $nameOhne, 'software')
    Protokoll '## prüfen (frischer Prozess)'
    $null = Invoke-Probe @('nachsehen', $name)

    # Name | TLS der Gegenstelle | Szenario der Gegenstelle | Client-Name | Pin (server|fremd) | Client-Szenario |
    # Optionen | Schlüssel der Gegenstelle (Vorgabe server)
    $Szenarien = @(
        @('tls13-echo', 'beide', 'echo', $name, 'server', 'echo', @()),
        @('software-echo', 'beide', 'echo', $nameSoft, 'server', 'echo', @()),
        @('ohne-prov-info', 'beide', 'echo', $nameOhne, 'server', 'echo', @()),
        @('falscher-pin-callback', 'beide', 'echo', $name, 'fremd', 'echo', @('--abbruch=callback')),
        @('falscher-pin-ohne-abbruch', 'beide', 'echo', $name, 'fremd', 'echo', @('--abbruch=keiner')),
        @('ohne-ignore', 'beide', 'echo', $name, 'server', 'echo', @('--ohne-ignore')),
        @('ohne-client-zertifikat', 'beide', 'echo', '-', 'server', 'echo', @()),
        @('server-nur-tls12', '12', 'echo', $name, 'server', 'echo', @()),
        @('client-nur-tls12', 'beide', 'echo', $name, 'server', 'echo', @('--nur-tls12')),
        @('ping', 'beide', 'ping', $name, 'server', 'ping', @()),
        @('voll', 'beide', 'voll', $name, 'server', 'voll', @()),
        @('fragment', 'beide', 'fragment', $name, 'server', 'fragment', @()),
        @('maskiert', 'beide', 'maskiert', $name, 'server', 'maskiert', @()),
        @('rsv', 'beide', 'rsv', $name, 'server', 'rsv', @()),
        @('text', 'beide', 'text', $name, 'server', 'text', @()),
        @('ping-lang', 'beide', 'ping-lang', $name, 'server', 'ping-lang', @()),
        @('frist-ohne-ping', 'beide', 'stille:15', $name, 'server', 'stille', @('--frist=5000')),
        @('frist-mit-ping', 'beide', 'ping-stille:20', $name, 'server', 'ping-stille', @('--frist=5000')),
        @('keepalive', 'beide', 'stille:50', $name, 'server', 'stille', @('--keepalive=20000')),
        @('frist-anfrage-ohne-ping', 'beide', 'stille:15', $name, 'server', 'stille', @('--frist-anfrage=5000')),
        @('frist-anfrage-mit-ping', 'beide', 'ping-stille:20', $name, 'server', 'ping-stille', @('--frist-anfrage=5000')),
        @('standardfrist', 'beide', 'stille:70', $name, 'server', 'stille', @()),
        @('client-abgelehnt', 'beide', 'ablehnen', $name, 'server', 'echo', @()),
        @('client-abgelehnt-tls12', '12', 'ablehnen', $name, 'server', 'echo', @()),
        @('abgelaufen', 'beide', 'echo', $name, 'server', 'echo', @(), 'abgelaufen')
    )
    foreach ($s in $Szenarien) {
        $sname, $tls, $sszen, $cname, $pinWahl, $cszen, $opts = $s[0..6]
        $ks = if ($s.Count -gt 7) { $s[7] } else { 'server' }
        if ($Nur.Count -gt 0 -and $sname -notin $Nur) { continue }
        Schritt "Szenario $sname"
        Protokoll "## $sname"
        $psi = [Diagnostics.ProcessStartInfo]::new($java)
        foreach ($a in @('-Dstdout.encoding=UTF-8', (Join-Path $here 'RelayProbe.java'), (Join-Path $WorkDir "$ks.p12"),
                $pass, $tls, $sszen)) { $psi.ArgumentList.Add($a) }
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
        $psi.UseShellExecute = $false
        $server = [Diagnostics.Process]::Start($psi)
        $first = $server.StandardOutput.ReadLine()
        if (-not $first -or $first -notmatch 'bereit port=(\d+) server_pin=(sha256:[0-9a-f]{64})') {
            Protokoll "  server: $first"
            Protokoll "  server-fehler: $($server.StandardError.ReadToEnd())"
            throw "Gegenstelle nicht bereit ($sname)"
        }
        $port = $Matches[1]
        $pin = if ($pinWahl -eq 'fremd') { $fremdPin } else { $Matches[2] }
        $rest = $server.StandardOutput.ReadToEndAsync()
        $null = Invoke-Probe (@('verbinden', $port, $pin, $cname, $cszen) + $opts)
        if (-not $server.WaitForExit(120000)) { $server.Kill(); Warnen "Gegenstelle hing ($sname)" }
        foreach ($l in ($rest.Result -split "`r?`n")) { if ($l) { Protokoll "  server: $l" } }
        $err = $server.StandardError.ReadToEnd()
        if ($err) { foreach ($l in ($err -split "`r?`n")) { if ($l) { Protokoll "  server-stderr: $l" } } }
    }
} catch {
    Fehler $_.Exception.Message
    $exitCode = 1
} finally {
    Schritt 'Aufräumen'
    foreach ($n in $name, $nameSoft, $nameOhne) { $null = Invoke-Probe @('entfernen', $n) }
    Remove-Item -LiteralPath (Join-Path $WorkDir 'server.p12'), (Join-Path $WorkDir 'fremd.p12'),
        (Join-Path $WorkDir 'abgelaufen.p12'), $der `
        -ErrorAction SilentlyContinue
}
Hinweis "Mitschrift: $log"
exit $exitCode
