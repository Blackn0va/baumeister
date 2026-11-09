// Pfad- und Rechteprüfungen für das Selbst-Update in einer Installation für alle
// Benutzer (Program Files).
//
// Warum eigene Funktionen: Das Setup läuft dort erhöht, liest aber seine eigene EXE
// aus dem Datenordner des Benutzers ({data}\update\<version>\…). Könnte der Benutzer
// (bzw. Schadsoftware in seinem Konto) diesen Pfad zwischen Prüfung und Start per
// Junction oder Symlink umlenken, liefe eine fremde Datei mit Administratorrechten.
// Die Updater-Kette prüft deshalb, dass kein Pfadteil ein Reparse-Punkt ist, und
// vergleicht den Pfad mit dem, auf den das gesperrte Handle wirklich zeigt
// (gemessen: Solange eine Datei ohne FILE_SHARE_DELETE offen ist, lassen sich weder
// sie noch ihre Vorfahrenordner umbenennen – Probe machine/M1).
//
// Threads: reentrant. Fehler: Rückgabewerte mit Windows-Fehlercode, keine Ausnahmen.

#pragma once

#include <filesystem>
#include <optional>
#include <string>
#include <vector>

namespace swbh::win {

struct ReparseCheck {
    bool ok = false;                      // true: kein Pfadteil ist ein Reparse-Punkt
    std::filesystem::path offending;      // erster auffällige bzw. unlesbare Pfadteil
    unsigned long win32_error = 0;        // 0 = Reparse-Punkt gefunden, sonst Lesefehler
};

// Prüft jeden Teil eines absoluten Pfads unterhalb der Laufwerkswurzel (auch das
// letzte Element) auf FILE_ATTRIBUTE_REPARSE_POINT (Junction, Symlink,
// Bereitstellungspunkt, Cloud-Platzhalter). Fail-closed: Ein nicht lesbarer Teil,
// ein relativer Pfad oder ein Pfad ohne Laufwerksbuchstaben (UNC) gilt als Befund.
ReparseCheck check_no_reparse_points(const std::filesystem::path& absolute_path);

// Endgültiger Pfad hinter einem offenen Handle (GetFinalPathNameByHandleW,
// VOLUME_NAME_DOS) ohne das Präfix \\?\. nullopt bei Fehler (win32_error gesetzt).
std::optional<std::filesystem::path> final_path_of_handle(void* handle, unsigned long* win32_error = nullptr);

// Dasselbe für einen Pfad: öffnet ihn (auch Ordner) nur mit Attributrechten und
// folgt dabei Verknüpfungen. Normalisiert 8.3-Kurznamen und Groß-/Kleinschreibung.
std::optional<std::filesystem::path> final_path_of(const std::filesystem::path& path,
                                                   unsigned long* win32_error = nullptr);

// true, wenn der Pfad (nach Normalisierung, ohne Groß-/Kleinschreibung) UNTER
// root liegt (nicht gleich). Rein lexikalisch – für echte Pfade vorher
// final_path_of anwenden.
bool path_is_below(const std::filesystem::path& path, const std::filesystem::path& root);

// Darf der aktuelle Benutzer (Token des Threads bzw. Prozesses) im Ordner Dateien
// und Unterordner anlegen? AccessCheck gegen die DACL – schreibt NICHTS (die App
// darf nie in {app} schreiben, auch nicht zur Probe). nullopt bei Fehler.
std::optional<bool> directory_writable_by_current_user(const std::filesystem::path& dir,
                                                       unsigned long* win32_error = nullptr);

// Die Program-Files-Ordner dieses Systems (64 Bit, 32 Bit), endgültige Pfade,
// ohne Dubletten. Leer, wenn Windows keinen liefert.
std::vector<std::filesystem::path> program_files_roots();

// Läuft dieser Prozess mit erhöhtem Token (TokenElevation)? nullopt bei Fehler.
std::optional<bool> current_process_elevated();

// Genauer als current_process_elevated: Unterscheidet, OB es für dieses Konto einen
// unerhöhten Weg gibt. Warum: Bei abgeschalteter UAC (EnableLUA=0) und im
// eingebauten Administratorkonto (FilterAdministratorToken=0) ist JEDER Prozess des
// Kontos erhöht, das Token hat kein verknüpftes eingeschränktes Token
// (TokenElevationTypeDefault). Dort ist „bitte normal starten“ unerfüllbar (Befund
// code-rand). Nur bei TokenElevationTypeFull wurde ausdrücklich erhöht gestartet.
enum class ElevationKind {
    Unknown,              // Token nicht lesbar – Aufrufer entscheiden fail-closed
    NotElevated,          // normal gestartet (auch Standardbenutzer)
    ElevatedSplitToken,   // „Als Administrator ausführen“: es gibt einen unerhöhten Weg
    ElevatedNoSplitToken  // UAC aus bzw. eingebauter Administrator: alles läuft erhöht
};
ElevationKind current_process_elevation();
// Reine Abbildung (Tests): TokenElevation.TokenIsElevated und TOKEN_ELEVATION_TYPE (1–3).
ElevationKind elevation_kind_from(bool token_is_elevated, int token_elevation_type);
const char* elevation_kind_name(ElevationKind kind);

}  // namespace swbh::win
