// Gemeinsame Fehlerklassen aller Module.
//
// Vertrag: Jede Ausnahme, deren Text beim Nutzer landet (Statuszeile, Dialog,
// stderr), ist eine UserError mit dem deutschen IST-Text aus der Spezifikation.
// Aufrufer kürzen solche Texte nur nach Codepoints (swbh::truncate_cp), nie nach
// Bytes – sonst entsteht ungültiges UTF-8, an dem PHP json_decode den ganzen
// Ingest-Batch verwirft (spec 03 Port-Risiken).
//
// Threads: Die Klassen sind unveränderlich und frei zwischen Threads übergebbar.

#pragma once

#include <stdexcept>
#include <string>
#include <string_view>
#include <system_error>

namespace swbh {

// Fehler, dessen what() wörtlich an den Nutzer geht.
struct UserError : std::runtime_error {
    using std::runtime_error::runtime_error;
};

// Text, den die Engine bei einem bewusst abgebrochenen Download meldet
// (spec 02 §1, engine.py:323-330).
inline constexpr std::string_view kDownloadCancelledText = "Modell-Download abgebrochen.";

// Bewusster Abbruch eines Modell-Downloads (Stop, Schließen, Knopf „Abbrechen“).
// Kein Fehler im eigentlichen Sinn: Die Engine meldet kDownloadCancelledText als
// Status und beendet run() mit RunEnd::DownloadCancelled.
struct DownloadCancelled : std::runtime_error {
    DownloadCancelled();
};

// Windows-Fehlertext (FormatMessageW, Sprache des Systems) als UTF-8, ohne
// abschließenden Zeilenumbruch, mit angehängtem Code: "Zugriff verweigert (Fehler 5)".
// Unbekannte Codes ergeben "Windows-Fehler 1234".
std::string system_error_text(unsigned long win32_error);

// Text zu einem std::error_code als gültiges UTF-8. Warum nicht ec.message(): Unter
// MSVC liefert system_category().message() FormatMessageA, also Bytes der
// ANSI-Codepage – „schreibgeschützt“ wurde in Meldungen und im Log zu
// „schreibgesch�tzt“ (Befund code-rand, Sonde R2-B, Codepage 1252). Fehler aus
// std::filesystem tragen unter Windows Win32-Codes (system_category) und gehen
// deshalb über system_error_text; andere Kategorien (generic) sind ASCII.
std::string error_code_text(const std::error_code& ec);

}  // namespace swbh
