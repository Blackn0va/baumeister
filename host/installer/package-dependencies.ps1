#Requires -Version 7
<#
.SYNOPSIS
    Abhängigkeitsprüfung der Bereitstellung: Jede ausgelieferte PE-Datei (EXE, DLL,
    Qt-Plugins, cuda\) samt normalen UND verzögerten Importen wird gegen den Paketinhalt
    und die Positivliste installer\windows-baseline-dlls.json geprüft.

.DESCRIPTION
    Warum (Spur paket, 2026-09-24): Der Betreiber verlangt ein Setup, das auf einem
    fremden PC alles mitbringt. Die frühere Prüfung (dumpbin /dependents) nahm jede
    DLL als gegeben, die auf DIESEM Rechner in System32 lag – hier stehen aber
    Grafiktreiber, SDKs und Laufzeiten, die ein frischer Windows-10-1809-PC nicht hat.
    Jetzt gilt: im Paket vorhanden ODER in der Positivliste für Windows 10 1809 x64
    (API-Sets über das ApiSetSchema auf ihre Wirts-DLL aufgelöst), sonst Abbruch.

    Sonderregeln (driver_dlls in der Positivliste):
      vulkan-1.dll  nur als Import von ggml-vulkan.dll; ggml-vulkan.dll selbst darf
                    niemand statisch importieren (sonst startete die App ohne
                    Vulkan-Treiber gar nicht, statt auf die CPU zu fallen).
      nvcuda.dll    nur aus cuda\ (NVIDIA-Gate); NVIDIA-Bibliotheken importiert nichts
                    außerhalb von cuda\.
    Zusätzlich muss jede PE-Datei x64 sein (Maschine 0x8664).

    Auflösung je Importeur (fix5): Eine DLL im Paket zählt nur, wenn sie im Ordner des
    Importeurs oder im Programmordner (Wurzel der Bereitstellung) liegt. So sucht der
    Windows-Loader: Für EXEs und DLLs der Wurzel ist das der Anwendungsordner; Qt-Plugins
    (platforms\ …) und die DLLs in cuda\ finden ihre Nachbarn im eigenen Ordner und die
    Qt-/ggml-DLLs über LOAD_LIBRARY_SEARCH_APPLICATION_DIR in der Wurzel. Vorher reichte
    „irgendwo im Paketbaum“: Eine ggml-base.dll nur unter translations\ ging durch (0
    Befunde), StreamWizardAgent.exe endete dann mit Loader-Fehler 126 (Prüfung
    paket-sicherheit).

    Grenzen (ehrlich): Die ApiSetSchema-Auflösung nutzt das Schema des Bau-Rechners,
    nicht das von 1809; geprüft werden DLL-Namen, keine einzelnen Funktionen; DLLs, die
    erst zur Laufzeit per LoadLibrary nachgeladen werden (etwa von Qt oder vom
    Vulkan-Loader), sieht keine Importtabelle.

    Wird von build.ps1 per Dot-Sourcing geladen; Test: tests\installer\test_package_dependencies.ps1.
#>

Set-StrictMode -Version Latest

# Liest Maschine, Importe und verzögerte Importe einer PE-Datei. $null, wenn die Datei
# keine PE-Datei ist (Modelle, Texte). Wirft bei einer kaputten PE-Datei – eine solche
# Datei darf nicht still durchgehen.
function Read-PeImports([string]$Path) {
    $fs = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    try {
        $br = [System.IO.BinaryReader]::new($fs)
        if ($fs.Length -lt 0x40) { return $null }
        if ($br.ReadUInt16() -ne 0x5A4D) { return $null }
        $fs.Position = 0x3C
        $nt = $br.ReadUInt32()
        if ($nt + 24 -gt $fs.Length) { return $null }
        $fs.Position = $nt
        if ($br.ReadUInt32() -ne 0x00004550) { return $null }
        $machine = $br.ReadUInt16()
        $sectionCount = $br.ReadUInt16()
        $fs.Position = $nt + 20
        $optionalSize = $br.ReadUInt16()
        $optional = $nt + 24
        $fs.Position = $optional
        $magic = $br.ReadUInt16()
        switch ($magic) {
            0x10B { $dirBase = $optional + 96; $fs.Position = $optional + 28; $imageBase = [uint64]$br.ReadUInt32(); $countAt = $optional + 92 }
            0x20B { $dirBase = $optional + 112; $fs.Position = $optional + 24; $imageBase = $br.ReadUInt64(); $countAt = $optional + 108 }
            default { throw "Unbekannter Optional-Header 0x$('{0:x}' -f $magic): $Path" }
        }
        $fs.Position = $countAt
        $dirCount = $br.ReadUInt32()
        $sections = [System.Collections.Generic.List[object]]::new()
        $first = $optional + $optionalSize
        for ($i = 0; $i -lt $sectionCount; $i++) {
            $fs.Position = $first + $i * 40 + 8
            $vsize = $br.ReadUInt32(); $va = $br.ReadUInt32(); $rawSize = $br.ReadUInt32(); $rawPtr = $br.ReadUInt32()
            $sections.Add([pscustomobject]@{ Va = [uint64]$va; Size = [uint64][Math]::Max($vsize, $rawSize); Raw = [uint64]$rawPtr })
        }
        $toOffset = {
            param([uint64]$Rva)
            foreach ($s in $sections) {
                if ($Rva -ge $s.Va -and $Rva -lt ($s.Va + $s.Size)) { return [int64]($s.Raw + ($Rva - $s.Va)) }
            }
            return [int64]-1
        }
        $readName = {
            param([uint64]$Rva)
            $off = & $toOffset $Rva
            if ($off -lt 0) { throw "Importname außerhalb aller Abschnitte (RVA 0x$('{0:x}' -f $Rva)): $Path" }
            $fs.Position = $off
            $bytes = $br.ReadBytes(260)
            $end = [Array]::IndexOf($bytes, [byte]0)
            if ($end -lt 1) { throw "Importname ohne Ende: $Path" }
            return [System.Text.Encoding]::ASCII.GetString($bytes, 0, $end).ToLowerInvariant()
        }
        $imports = [System.Collections.Generic.List[string]]::new()
        $delay = [System.Collections.Generic.List[string]]::new()
        # Dateiposition jedes Importnamens – nur für Tests, die eine Abhängigkeit in einer
        # Kopie umbenennen (tests\installer\test_package_dependencies.ps1).
        $entries = [System.Collections.Generic.List[object]]::new()
        # Verzeichnis 1: Importe (20 Byte je Deskriptor, Name bei +12).
        if ($dirCount -gt 1) {
            $fs.Position = $dirBase + 1 * 8
            $rva = $br.ReadUInt32()
            if ($rva -ne 0) {
                $desc = & $toOffset $rva
                if ($desc -lt 0) { throw "Importverzeichnis außerhalb aller Abschnitte: $Path" }
                for ($n = 0; $n -lt 4096; $n++) {
                    $fs.Position = $desc + $n * 20
                    $oft = $br.ReadUInt32(); [void]$br.ReadUInt32(); [void]$br.ReadUInt32(); $nameRva = $br.ReadUInt32(); $ft = $br.ReadUInt32()
                    if ($nameRva -eq 0 -and $oft -eq 0 -and $ft -eq 0) { break }
                    $imports.Add((& $readName $nameRva))
                    $entries.Add([pscustomobject]@{ Name = $imports[$imports.Count - 1]; Offset = (& $toOffset $nameRva); Delay = $false })
                }
            }
        }
        # Verzeichnis 13: verzögerte Importe (32 Byte je Deskriptor, Name bei +4; ohne
        # Bit 0 in Attributes sind es VAs statt RVAs – alte Linker).
        if ($dirCount -gt 13) {
            $fs.Position = $dirBase + 13 * 8
            $rva = $br.ReadUInt32()
            if ($rva -ne 0) {
                $desc = & $toOffset $rva
                if ($desc -lt 0) { throw "Verzeichnis der verzögerten Importe außerhalb aller Abschnitte: $Path" }
                for ($n = 0; $n -lt 4096; $n++) {
                    $fs.Position = $desc + $n * 32
                    $attributes = $br.ReadUInt32(); $nameRva = [uint64]$br.ReadUInt32()
                    if ($nameRva -eq 0) { break }
                    if (($attributes -band 1) -eq 0) { $nameRva -= $imageBase }
                    $delay.Add((& $readName $nameRva))
                    $entries.Add([pscustomobject]@{ Name = $delay[$delay.Count - 1]; Offset = (& $toOffset $nameRva); Delay = $true })
                }
            }
        }
        return [pscustomobject]@{ Machine = $machine; MachineOffset = $nt + 4; Imports = @($imports); DelayImports = @($delay); Entries = @($entries) }
    } finally {
        $fs.Dispose()
    }
}

# API-Set-Namensraum (Version 6, Windows 10/11) aus apisetschema.dll: Schlüssel = Name
# bis zum letzten Bindestrich (so vergleicht auch der Loader), Wert = Wirts-DLL der
# Vorgabe ('' = auf diesem Rechner ohne Wirt).
function Get-ApiSetMap([string]$SchemaDll = (Join-Path $env:WINDIR 'System32\apisetschema.dll')) {
    $bytes = [System.IO.File]::ReadAllBytes($SchemaDll)
    $nt = [BitConverter]::ToUInt32($bytes, 0x3C)
    $sectionCount = [BitConverter]::ToUInt16($bytes, $nt + 6)
    $first = $nt + 24 + [BitConverter]::ToUInt16($bytes, $nt + 20)
    $base = -1
    for ($i = 0; $i -lt $sectionCount; $i++) {
        $s = $first + $i * 40
        $name = [System.Text.Encoding]::ASCII.GetString($bytes, $s, 8).TrimEnd([char]0)
        if ($name -eq '.apiset') { $base = [int][BitConverter]::ToUInt32($bytes, $s + 20); break }
    }
    if ($base -lt 0) { throw "Kein .apiset-Abschnitt in $SchemaDll" }
    $version = [BitConverter]::ToUInt32($bytes, $base)
    if ($version -ne 6) { throw "ApiSetSchema Version $version wird nicht unterstützt (erwartet 6): $SchemaDll" }
    $count = [BitConverter]::ToUInt32($bytes, $base + 12)
    $entryOffset = [BitConverter]::ToUInt32($bytes, $base + 16)
    $map = @{}
    for ($i = 0; $i -lt $count; $i++) {
        $e = $base + $entryOffset + $i * 24
        $nameOffset = [BitConverter]::ToUInt32($bytes, $e + 4)
        $hashedLength = [BitConverter]::ToUInt32($bytes, $e + 12)
        $valueOffset = [BitConverter]::ToUInt32($bytes, $e + 16)
        $valueCount = [BitConverter]::ToUInt32($bytes, $e + 20)
        $key = [System.Text.Encoding]::Unicode.GetString($bytes, $base + $nameOffset, $hashedLength).ToLowerInvariant()
        $hostName = ''
        for ($v = 0; $v -lt $valueCount; $v++) {
            $ve = $base + $valueOffset + $v * 20
            $importerLength = [BitConverter]::ToUInt32($bytes, $ve + 8)
            $hostOffset = [BitConverter]::ToUInt32($bytes, $ve + 12)
            $hostLength = [BitConverter]::ToUInt32($bytes, $ve + 16)
            # Die Vorgabe ist der Wert ohne einführendes Modul; Ausnahmen gelten nur für
            # bestimmte Aufrufer und sagen über die Grundausstattung nichts.
            if ($importerLength -eq 0 -and $hostLength -gt 0) {
                $hostName = [System.Text.Encoding]::Unicode.GetString($bytes, $base + $hostOffset, $hostLength).ToLowerInvariant()
                break
            }
        }
        $map[$key] = $hostName
    }
    return $map
}

# Wirts-DLL eines API-Sets (api-ms-win-*.dll, ext-ms-*.dll) oder $null, wenn das Schema
# den Namen nicht kennt bzw. keinen Wirt hat.
function Resolve-ApiSet([string]$Name, [hashtable]$Map) {
    $n = $Name.ToLowerInvariant()
    if ($n.EndsWith('.dll')) { $n = $n.Substring(0, $n.Length - 4) }
    $cut = $n.LastIndexOf('-')
    if ($cut -lt 1) { return $null }
    $key = $n.Substring(0, $cut)
    if (-not $Map.ContainsKey($key) -or -not $Map[$key]) { return $null }
    return $Map[$key]
}

# Prüft die Bereitstellung $Dist. Rückgabe: Probleme (leer = in Ordnung), Zahl der
# PE-Dateien und ein Bericht als Textzeilen (für <DistDir>\<variante>-dependencies.txt).
function Test-PackageDependencies {
    param(
        [Parameter(Mandatory)][string]$Dist,
        [Parameter(Mandatory)][string]$BaselineFile,
        [hashtable]$ApiSetMap = $null
    )
    $baseline = Get-Content -LiteralPath $BaselineFile -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
    $allowed = @{}
    foreach ($k in $baseline.dlls.Keys) { $allowed[$k.ToLowerInvariant()] = $baseline.dlls[$k] }
    if (-not $ApiSetMap) { $ApiSetMap = Get-ApiSetMap }
    $vulkanImporters = @($baseline.driver_dlls['vulkan-1.dll'].importers | ForEach-Object { $_.ToLowerInvariant() })
    $nvcudaDir = [string]$baseline.driver_dlls['nvcuda.dll'].dir
    $vulkanDelayOnly = [bool]$baseline.driver_dlls['vulkan-1.dll'].delay_load_only
    # Eigene Kindprozesse (crispasr.exe) dürfen statisch importieren – siehe reason in der Grundliste.
    $vulkanStatic = @($baseline.driver_dlls['vulkan-1.dll'].static_importers | Where-Object { $_ } | ForEach-Object { $_.ToLowerInvariant() })

    $distFull = [System.IO.Path]::GetFullPath($Dist).TrimEnd('\') + '\'
    $files = @(Get-ChildItem -LiteralPath $Dist -Recurse -File)
    $pe = [System.Collections.Generic.List[object]]::new()
    foreach ($f in $files) {
        $info = Read-PeImports $f.FullName
        if ($null -eq $info) { continue }
        $rel = $f.FullName.Substring($distFull.Length)
        $pe.Add([pscustomobject]@{ Rel = $rel; Name = $f.Name.ToLowerInvariant(); Info = $info
                                   InCuda = $rel.StartsWith("$nvcudaDir\", [StringComparison]::OrdinalIgnoreCase) })
    }
    # Name → Ordner (relativ, klein, '' = Wurzel), in denen die DLL liegt.
    $present = @{}
    foreach ($p in $pe) {
        $dir = [System.IO.Path]::GetDirectoryName($p.Rel)
        $dir = if ($dir) { $dir.ToLowerInvariant() } else { '' }
        if (-not $present.ContainsKey($p.Name)) { $present[$p.Name] = [System.Collections.Generic.List[string]]::new() }
        $present[$p.Name].Add($dir)
    }

    $problems = [System.Collections.Generic.List[string]]::new()
    $systemUse = [ordered]@{}
    $apiUse = [ordered]@{}
    foreach ($p in $pe) {
        if ($p.Info.Machine -ne 0x8664) {
            $problems.Add(('{0}: Maschine 0x{1:x} statt x64 (0x8664)' -f $p.Rel, $p.Info.Machine))
        }
        $all = @($p.Info.Imports | ForEach-Object { [pscustomobject]@{ Dll = $_; Delay = $false } }) +
               @($p.Info.DelayImports | ForEach-Object { [pscustomobject]@{ Dll = $_; Delay = $true } })
        $importerDir = [System.IO.Path]::GetDirectoryName($p.Rel)
        $importerDir = if ($importerDir) { $importerDir.ToLowerInvariant() } else { '' }
        foreach ($imp in $all) {
            $dll = $imp.Dll
            $how = if ($imp.Delay) { 'verzögert' } else { 'statisch' }
            if (-not $p.InCuda -and $dll -match '^(cublas|cublaslt|cudart|cudnn|nvrtc|nvcuda|ggml-cuda|swbh_ct2_cuda)') {
                $problems.Add("$($p.Rel) importiert $how die NVIDIA-Bibliothek $dll außerhalb von $nvcudaDir\ (NVIDIA-Gate)")
                continue
            }
            if ($dll -eq 'ggml-vulkan.dll' -and -not $imp.Delay) {
                $problems.Add("$($p.Rel) importiert ggml-vulkan.dll statisch – ohne Vulkan-Treiber startete das Programm dann nicht (accel lädt sie zur Laufzeit)")
                continue
            }
            if ($present.ContainsKey($dll)) {
                $dirs = $present[$dll]
                if ($dirs.Contains($importerDir) -or $dirs.Contains('')) { continue }
                $where = ($dirs | Sort-Object -Unique | ForEach-Object { "$_\" }) -join ', '
                $problems.Add("$($p.Rel) → $dll ($how): liegt nur in $where – der Loader sucht im Ordner des Importeurs und im Programmordner")
                continue
            }
            if ($dll -eq 'vulkan-1.dll') {
                if ($vulkanImporters -notcontains $p.Name) {
                    $problems.Add("$($p.Rel) importiert $how vulkan-1.dll – nur $($vulkanImporters -join ', ') darf den Treiber-Loader nutzen")
                } elseif ($vulkanDelayOnly -and -not $imp.Delay -and $vulkanStatic -notcontains $p.Name.ToLowerInvariant()) {
                    # StreamWizard Agent: statisch gebunden startet die EXE ohne Vulkan-Treiber gar nicht.
                    $problems.Add("$($p.Rel) importiert vulkan-1.dll statisch – nur verzögert erlaubt (/DELAYLOAD)")
                }
                continue
            }
            if ($dll -eq 'nvcuda.dll') {
                if (-not $p.InCuda) { $problems.Add("$($p.Rel) importiert $how nvcuda.dll außerhalb von $nvcudaDir\") }
                continue
            }
            if ($dll -match '^(msvcp140|vcruntime140|vcomp140|concrt140)') {
                $problems.Add("$($p.Rel) → $dll ($how): VC-Laufzeit fehlt im Paket (muss app-lokal liegen, /CURRENTUSER kann vc_redist nicht installieren)")
                continue
            }
            if ($dll.StartsWith('api-ms-') -or $dll.StartsWith('ext-ms-')) {
                $hostDll = Resolve-ApiSet $dll $ApiSetMap
                if (-not $hostDll) {
                    $problems.Add("$($p.Rel) → $dll ($how): API-Set im ApiSetSchema unbekannt oder ohne Wirt")
                } elseif (-not $allowed.ContainsKey($hostDll)) {
                    $problems.Add("$($p.Rel) → $dll ($how): Wirt $hostDll steht nicht in der Positivliste für Windows 10 1809")
                } else {
                    if (-not $apiUse.Contains($dll)) { $apiUse[$dll] = [System.Collections.Generic.List[string]]::new() }
                    $apiUse[$dll].Add($p.Rel)
                }
                continue
            }
            if ($allowed.ContainsKey($dll)) {
                if (-not $systemUse.Contains($dll)) { $systemUse[$dll] = [System.Collections.Generic.List[string]]::new() }
                $systemUse[$dll].Add("$($p.Rel)$(if ($imp.Delay) { ' (verzögert)' })")
                continue
            }
            $problems.Add("$($p.Rel) → $dll ($how): weder im Paket noch in der Windows-10-1809-Grundausstattung")
        }
    }

    $report = [System.Collections.Generic.List[string]]::new()
    $report.Add("Abhängigkeiten der Bereitstellung $Dist")
    $report.Add("PE-Dateien: $($pe.Count) von $($files.Count) Dateien; Positivliste: $BaselineFile")
    $report.Add('')
    $report.Add('== System-DLLs (Windows 10 1809 x64) und ihre Importeure')
    foreach ($k in ($systemUse.Keys | Sort-Object)) { $report.Add("$k <- $(($systemUse[$k] | Sort-Object -Unique) -join ', ')") }
    $report.Add('')
    $report.Add('== API-Sets (Wirt laut ApiSetSchema dieses Rechners)')
    foreach ($k in ($apiUse.Keys | Sort-Object)) { $report.Add("$k -> $(Resolve-ApiSet $k $ApiSetMap) <- $(($apiUse[$k] | Sort-Object -Unique) -join ', ')") }
    $report.Add('')
    $report.Add('== Je PE-Datei (statisch | verzögert)')
    foreach ($p in ($pe | Sort-Object Rel)) {
        $report.Add("$($p.Rel): $(($p.Info.Imports | Sort-Object) -join ', ') | $(($p.Info.DelayImports | Sort-Object) -join ', ')")
    }
    if ($problems.Count -gt 0) {
        $report.Add('')
        $report.Add('== PROBLEME')
        foreach ($x in $problems) { $report.Add($x) }
    }
    return [pscustomobject]@{ Problems = @($problems); PeCount = $pe.Count; Report = @($report) }
}
