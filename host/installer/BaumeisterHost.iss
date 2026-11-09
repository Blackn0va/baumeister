; Inno-Setup des StreamWizard Baumeister-Host (Inno Setup 6.7, Unicode).
;
; Grundentscheidungen
;  - Aufbau wie kraken\installer\StreamWizardAgent.iss: für alle Benutzer nach {autopf}\StreamWizard Baumeister-Host
;    (PrivilegesRequired=admin, /CURRENTUSER nur per Kommandozeile), x64compatible, feste AppId, Lizenzen und
;    THIRD-PARTY-NOTICES.md im Programmordner, de + en.
;  - Ohne Selbst-Update: kraken\include\code.iss (854 Zeilen, READYEVENT-Vertrag mit dem Updater) wurde NICHT
;    übernommen; include\code.iss hier prüft nur, ob PuTTY da ist.
;  - CUDA NIE im Setup: cuBLAS steht unter der NVIDIA-EULA und wiegt 600 MB. Die App lädt den CUDA-Kern nach
;    Zustimmung im eigenen Dialog nach; ohne Zustimmung rechnet Vulkan (kommt mit dem Grafiktreiber).
;  - Keine Modelle im Setup: Die App lädt das GGUF beim ersten Start nach %LOCALAPPDATA%\StreamWizard\Baumeister\models
;    (Größe und SHA-256 geprüft).
;  - Start nach der Installation mit runasoriginaluser: Die App verweigert erhöhte Rechte (main_gui.cpp).
;  - Kein Autostart aus dem Setup (2026-10-28). Vorher gab es die Aufgabe „autostart“ mit einer Verknüpfung in
;    {userstartup}. Das Setup läuft aber erhöht: Meldet sich dafür ein anderes Admin-Konto an, landete die Verknüpfung
;    in DESSEN Autostart, während der Tunnel den Pageant des bedienenden Nutzers braucht; außerdem startete sie ohne
;    --im-hintergrund, also jedes Mal mit offenem Fenster. Jetzt schaltet die App selbst unter Einstellungen → „Mit
;    Windows starten“ (HKCU\…\CurrentVersion\Run, src/gui/autostart.cpp), im Task-Manager abschaltbar.
;  - [InstallDelete] räumt die alte Verknüpfung bei jedem Update ab. Wer sie hatte, startet danach NICHT mehr mit
;    Windows, bis er das Häkchen in der App setzt – bewusst kein stilles Übertragen, weil das Setup nicht weiß, in
;    welchem Konto die Verknüpfung lag. Auch {userstartup} meint hier das Konto, unter dem das Setup läuft.
;  - Deinstallation entfernt den Run-Wert NICHT: Er gehört dem Benutzerkonto, das ihn in der App gesetzt hat, und das
;    erhöhte Deinstallationsprogramm sieht womöglich ein anderes HKCU. Bleibt er verwaist, versucht Windows bei der
;    Anmeldung eine fehlende Datei zu starten (wie Windows das anzeigt, ist nicht gemessen); entfernen über den
;    Task-Manager oder vorher das Häkchen in der App abnehmen. Wer das ändert, darf nur den eigenen Wertnamen „StreamWizard Baumeister-Host“ löschen.
;
; Aufruf (build.ps1 -Stage installer):
;   ISCC /Q /DAppVer=<x.y.z> /DSrcDir=<out\dist> /O<ausgabe> installer\BaumeisterHost.iss

#ifndef AppVer
  #error "AppVer fehlt: ISCC /DAppVer=<x.y.z> … (build.ps1 liest sie aus project() in CMakeLists.txt)"
#endif
#ifndef SrcDir
  #error "SrcDir fehlt: /DSrcDir=<Bereitstellungsordner out\dist>"
#endif

#define AppName "StreamWizard Baumeister-Host"
#define AppExe "BaumeisterHost.exe"
#define AppMutexName "Local\StreamWizardBaumeisterHost"
#define AppDirName "StreamWizard Baumeister-Host"
; Feste AppId – NIE ändern. Windows erkennt daran Updates und Deinstallation.
#define AppIdGuid "85B26F56-784C-48BD-9EC8-0BE21318F07B"

#if !FileExists(AddBackslash(SrcDir) + AppExe)
  #error "BaumeisterHost.exe fehlt in SrcDir"
#endif
#if !FileExists(AddBackslash(SrcDir) + "platforms\qwindows.dll")
  #error "platforms\qwindows.dll fehlt in SrcDir – ohne Plattform-Plugin startet die App nicht"
#endif
#if !FileExists(AddBackslash(SrcDir) + "THIRD-PARTY-NOTICES.md")
  #error "THIRD-PARTY-NOTICES.md fehlt in SrcDir – ohne Lizenzbeilage kein Setup"
#endif
#if !FileExists(AddBackslash(SrcDir) + "LICENSE.txt")
  #error "LICENSE.txt fehlt in SrcDir – die App steht unter GPL-3.0-or-later, der Lizenztext gehört ins Setup"
#endif
#if !FileExists(AddBackslash(SrcDir) + "ggml-vulkan.dll")
  #error "ggml-vulkan.dll fehlt in SrcDir – ohne Vulkan-Kern rechnet die App nicht auf der GPU"
#endif
#if DirExists(AddBackslash(SrcDir) + "models") || DirExists(AddBackslash(SrcDir) + "components") || FileExists(AddBackslash(SrcDir) + "ggml-cuda.dll") || FileExists(AddBackslash(SrcDir) + "cublas64_13.dll")
  #error "SrcDir enthält Modelle oder NVIDIA-Dateien – das Setup liefert weder Modelle noch CUDA"
#endif

#define OutputBase "StreamWizard-Baumeister-Host-Setup-" + AppVer

[Setup]
; Feste AppId – NIE ändern: Windows erkennt daran Updates und Deinstallation.
AppId={{{#AppIdGuid}}
AppName={#AppName}
AppVersion={#AppVer}
AppVerName={#AppName} {#AppVer}
AppPublisher=StreamWizard
AppPublisherURL=https://streamwizard.de
VersionInfoVersion={#AppVer}
VersionInfoProductName={#AppName}
VersionInfoDescription={#AppName} Setup
DefaultDirName={autopf}\{#AppDirName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
DisableDirPage=auto
DisableReadyPage=yes
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=commandline
UsedUserAreasWarning=no
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputBaseFilename={#OutputBase}
OutputDir=..\out\installer
Compression=lzma2/max
SolidCompression=yes
LZMAUseSeparateProcess=yes
OutputManifestFile={#OutputBase}-files.txt
WizardStyle=modern
SetupIconFile=..\resources\branding\BaumeisterHost.ico
WizardImageFile=..\resources\branding\wizard-image-100.png,..\resources\branding\wizard-image-125.png,..\resources\branding\wizard-image-150.png,..\resources\branding\wizard-image-175.png,..\resources\branding\wizard-image-200.png
WizardSmallImageFile=..\resources\branding\wizard-small-100.png,..\resources\branding\wizard-small-125.png,..\resources\branding\wizard-small-150.png,..\resources\branding\wizard-small-175.png,..\resources\branding\wizard-small-200.png
ShowLanguageDialog=auto
LanguageDetectionMethod=uilanguage
AppMutex={#AppMutexName}
CloseApplications=yes
RestartApplications=no
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
SetupLogging=yes

[Languages]
Name: "de"; MessagesFile: "compiler:Languages\German.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

#include "include\messages.iss"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; Verknüpfung der früheren Aufgabe „autostart“ (siehe Grundentscheidungen): Sonst startete die App nach dem Update
; doppelt – einmal still über den Run-Wert, einmal ohne Schalter über die Verknüpfung; kommt diese als zweite, zeigt
; sie die Fehlerbox „läuft bereits“.
Type: files; Name: "{userstartup}\{#AppName}.lnk"

[Files]
Source: "{#SrcDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchApp}"; Flags: nowait postinstall skipifsilent runasoriginaluser

#include "include\code.iss"
