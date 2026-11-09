// Live-Konsole als Textdatei sichern („Speichern …“ neben „Leeren“).
//
// Warum: Die Konsole zeigt Anfragen, Token-Strom, Nachbesserungen und Tunnel-Ereignisse – genau das, was man bei einem
// verworfenen Plan oder einem Tunnelabbruch weitergeben will. Vorher ging das nur über Markieren und Kopieren, und die
// Konsole hält höchstens 5000 Zeilen; was davor lag, war weg. Jetzt schreibt die App den Inhalt mit Kopfzeile (Version,
// Zeitpunkt) in eine UTF-8-Datei, atomar über write_file_atomic – ein Absturz beim Schreiben lässt keine halbe Datei
// zurück.
//
// Threads: reentrant (keine geteilten Daten). Fehler: console_file_name und console_export_text werfen nicht;
// save_console_text wirft FileError aus base/fileio mit deutschem Text.

#pragma once

#include <ctime>
#include <filesystem>
#include <string>
#include <string_view>

namespace swbh::gui {

// baumeister-konsole-JJJJMMTT-hhmmss.txt aus der lokalen Zeit – sortiert sich im Explorer nach Datum.
std::wstring console_file_name(const std::tm& local);

// Kopfzeile plus Konsoleninhalt. Zeilenenden werden zu CRLF, damit der Windows-Editor auch ältere Fassungen sauber
// zeigt; ein abschließender Zeilenumbruch kommt immer dazu.
std::string console_export_text(std::string_view version, const std::tm& local, std::string_view console_utf8);

// Schreibt console_export_text nach file (Zielordner muss existieren).
void save_console_text(const std::filesystem::path& file, std::string_view version, const std::tm& local,
                       std::string_view console_utf8);

}  // namespace swbh::gui
