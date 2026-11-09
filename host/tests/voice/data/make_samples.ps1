#requires -Version 5.1
<#
Zweck: Erzeugt die Testaufnahmen für tests/voice/test_voice.cpp mit der Windows-Sprachausgabe (System.Speech):
16 kHz, 16 bit, mono – das Format, in dem das Mikrofon der App aufnimmt.

WICHTIG: Braucht eine deutsche Stimme (Hedda/Katja, Windows-Sprachpaket Deutsch). Ohne sie spricht die englische
Standardstimme den deutschen Text – die Erkennung im Test schlägt dann zu Recht fehl.

Aufruf: pwsh -File tests\voice\data\make_samples.ps1

Warum mit BOM: Windows PowerShell 5 liest Skripte ohne BOM als ANSI – in kraken sprach Hedda deshalb „Ã¼“ statt „ü“
(Gedaechtnis kraken, Testaufnahme-Falle). umlaut-ok
#>
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Speech
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$samples = [ordered]@{
    'vera_fackeln.wav' = 'Hey Vera, gib mir sechzehn Fackeln.'
}
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $german = $synth.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -like 'de-*' } | Select-Object -First 1
    if ($null -eq $german) {
        throw 'Keine deutsche Stimme installiert (Windows-Einstellungen → Sprache → Deutsch mit Sprachausgabe).'
    }
    $synth.SelectVoice($german.VoiceInfo.Name)
    foreach ($name in $samples.Keys) {
        $path = Join-Path $PSScriptRoot $name
        $synth.SetOutputToWaveFile($path, $format)
        $synth.Speak($samples[$name])
        $synth.SetOutputToNull()
        Write-Host "geschrieben: $path ($($german.VoiceInfo.Name))"
    }
} finally {
    $synth.Dispose()
}
