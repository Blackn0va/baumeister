// Datei lesen und schreiben mit UTF-16-Pfaden.
//
// Warum eigene Funktionen: Die Python-Version schrieb die Konfig direkt und nicht
// atomar; ein Absturz konnte eine abgeschnittene Datei mit Klartext-Token
// hinterlassen (spec 06 Port-Risiken). write_file_atomic schreibt deshalb in eine
// Temp-Datei im selben Ordner, spült sie auf die Platte und ersetzt das Ziel per
// MoveFileExW(REPLACE_EXISTING | WRITE_THROUGH).
//
// Threads: reentrant; gleichzeitige Schreiber auf dieselbe Datei muss der
// Aufrufer ausschließen. Fehler: FileError mit Pfad und Windows-Text.

#pragma once

#include <cstdint>
#include <filesystem>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>

namespace swbh {

struct FileError : std::runtime_error {
    FileError(std::string message, unsigned long win32_error)
        : std::runtime_error(std::move(message)), win32(win32_error) {}
    unsigned long win32 = 0;
};

// Liest die ganze Datei. Wirft FileError ("Datei nicht lesbar: <pfad>: <text>").
// max_bytes schützt vor unerwartet großen Dateien (FileError bei Überschreitung).
std::string read_file(const std::filesystem::path& file, std::uint64_t max_bytes = 256ull * 1024 * 1024);

// Wie read_file, aber nullopt statt Ausnahme, wenn die Datei fehlt.
// Andere Fehler (Zugriff, Größe) werfen weiterhin.
std::optional<std::string> read_file_if_exists(const std::filesystem::path& file,
                                               std::uint64_t max_bytes = 256ull * 1024 * 1024);

// Schreibt atomar: Temp-Datei im Zielordner → FlushFileBuffers → MoveFileExW.
// Der Zielordner muss existieren. Bei Fehlern bleibt das alte Ziel unverändert
// und die Temp-Datei wird entfernt.
void write_file_atomic(const std::filesystem::path& target, std::string_view bytes);

// Legt einen Ordner samt Eltern an (bewusst nicht create_directories genannt:
// ADL fände sonst std::filesystem::create_directories und der Aufruf wäre mehrdeutig).
// Wirft FileError.
void ensure_directory(const std::filesystem::path& dir);

}  // namespace swbh
