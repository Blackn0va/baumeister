// „Mit Windows starten“: Run-Wert unter HKCU, geschaltet aus der App (Einstellungen), nicht vom Setup.
//
// Vorher legte das Setup eine Verknüpfung in {userstartup} an (Aufgabe „autostart“). Das hatte drei Fehler:
//  - Das Setup läuft erhöht; meldet sich dafür ein anderes Admin-Konto an, landete die Verknüpfung in DESSEN
//    Autostart – der Tunnel braucht aber den Pageant des Nutzers, der die App bedient.
//  - Ein- und Ausschalten ging nur über eine Neuinstallation.
//  - Die Verknüpfung startete ohne Schalter: Bei jeder Anmeldung ging das Fenster auf.
// Jetzt schreibt die App selbst HKCU\…\CurrentVersion\Run (kein Adminrecht nötig, immer das richtige Konto) mit
// --im-hintergrund, und sie liest Explorer\StartupApproved\Run mit: Dort vermerkt der Task-Manager (Reiter
// „Autostart“) und die Einstellungen-App, dass der Nutzer den Eintrag deaktiviert hat. Ohne diese Prüfung zeigte das
// Häkchen „an“, obwohl Windows den Eintrag gar nicht mehr startet.
//
// Der Pfad steht in Anführungszeichen: Ohne sie zerlegt Windows „C:\Program Files\…\BaumeisterHost.exe“ an der
// Leerstelle und versucht zuerst C:\Program.exe – der bekannte Fehler „Unquoted Search Path“ (CWE-428, OWASP A05).
// Ein Schreibrecht auf C:\ genügt dann, um bei jeder Anmeldung fremden Code zu starten.
//
// Die Schlüssel sind injizierbar, damit Tests unter HKCU\Software\StreamWizard\BaumeisterHostTest schreiben und den
// echten Run-Schlüssel nie berühren.
//
// Threads: reentrant (jeder Aufruf öffnet seine Schlüssel selbst). Fehler: autostart_enabled und autostart_command
// werfen nicht (unlesbar zählt als „aus“); set_autostart wirft UserError mit deutschem Text.

#pragma once

#include <filesystem>
#include <optional>
#include <string>

namespace swbh::gui {

// Unterschlüssel unter HKEY_CURRENT_USER und der Wertname. Vorgabe: die echten Windows-Schlüssel.
struct AutostartKeys {
    std::wstring run = L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    std::wstring approved = L"Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";
    std::wstring value_name = L"StreamWizard Baumeister-Host";
};

// Inhalt des Run-Werts: "<exe>" --im-hintergrund.
std::wstring run_value(const std::filesystem::path& exe);

// true, wenn der Run-Wert existiert und nicht in StartupApproved deaktiviert ist (erstes Byte 0x03, allgemein ungerade).
// Fehlt der StartupApproved-Eintrag oder steht dort 0x02, gilt der Eintrag als an.
bool autostart_enabled(const AutostartKeys& keys = {});

// Der eingetragene Run-Wert (unexpandiert), falls vorhanden.
std::optional<std::wstring> autostart_command(const AutostartKeys& keys = {});

// on = true: Run-Wert auf run_value(exe) setzen und einen Deaktiviert-Vermerk in StartupApproved entfernen (der Klick
// in der App ist der ausdrückliche Wunsch, auch gegen den früheren Klick im Task-Manager). on = false: Run-Wert löschen.
void set_autostart(bool on, const std::filesystem::path& exe, const AutostartKeys& keys = {});

}  // namespace swbh::gui
