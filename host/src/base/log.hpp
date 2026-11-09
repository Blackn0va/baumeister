// Anwendungs-Log mit Datei-Senke.
//
// Neu gegenüber IST (die Python-Version hatte keine Logdatei, Architektur §13):
// {data}\logs\baumeister-YYYY-MM-DD.log, UTF-8 ohne BOM, eine Zeile je Eintrag
// "YYYY-MM-DD HH:MM:SS.mmm [INFO|WARNUNG|FEHLER] text". Aufbewahrung 14 Tage:
// Beim Öffnen und bei jedem Tageswechsel werden baumeister-*.log gelöscht,
// deren Datum keep_days oder mehr Tage zurückliegt (heute + 13 Vortage bleiben).
// Fremde Dateien im Ordner (z. B. setup-<version>.log von Inno) bleiben unberührt.
//
// Datenschutz (Architektur §15): Das Log enthält keine Caption- oder
// Übersetzungstexte und keine Geheimnisse. Wer ein Geheimnis zuordnen muss,
// schreibt log::ref(geheimnis) (8 Hex-Zeichen des SHA-256), Freitext aus
// fremden Quellen geht durch log::safe.
//
// Threads: alle Funktionen sind threadsicher (ein Mutex). Die Senke wird unter
// diesem Mutex aufgerufen und darf deshalb selbst nicht loggen.
// Fehler: write() wirft nie. Scheitert das Schreiben der Datei, geht die Zeile
// weiter an die Senke, dazu einmalig ein Hinweis "Logdatei nicht schreibbar: …".

#pragma once

#include "base/clock.hpp"

#include <cstddef>
#include <filesystem>
#include <functional>
#include <optional>
#include <string>
#include <string_view>

namespace swbh::log {

enum class Level { Info, Warn, Error };

// Zusätzliche Ausgabe (CLI-Konsole, Diagnose). Ohne Senke und ohne Datei geht
// eine Zeile nur an OutputDebugStringW.
using Sink = std::function<void(Level, std::string_view line)>;

void set_sink(Sink sink);
void reset_sink();

// Öffnet die Datei-Senke im Ordner dir (legt ihn an). false, wenn weder Ordner
// noch Datei nutzbar sind; das Log läuft dann ohne Datei weiter.
bool open_dir(const std::filesystem::path& dir, int keep_days = 14);

// Schließt die Datei-Senke (Prozessende, Tests).
void close();

// Aktuell offene Logdatei, falls vorhanden.
std::optional<std::filesystem::path> current_file();

// Schreibt eine Zeile. Zeilenumbrüche und Steuerzeichen im Text werden zu
// Leerzeichen, sehr lange Texte auf 4000 Codepoints gekürzt.
void write(Level level, std::string_view message);

inline void info(std::string_view m) { write(Level::Info, m); }
inline void warn(std::string_view m) { write(Level::Warn, m); }
inline void error(std::string_view m) { write(Level::Error, m); }

// Nicht umkehrbarer Kurzverweis auf ein Geheimnis: die ersten 8 Hex-Zeichen des
// SHA-256 der UTF-8-Bytes. Leeres Geheimnis ergibt "leer".
std::string ref(std::string_view secret);

// Entschärft Freitext für das Log: Steuerzeichen (auch CR/LF) → Leerzeichen,
// danach auf max_codepoints gekürzt, Kürzung mit "…" markiert.
std::string safe(std::string_view value, std::size_t max_codepoints = 240);

// --- Teile der Rotation, einzeln testbar ---

// "baumeister-YYYY-MM-DD.log".
std::string file_name_for(const LocalDate& date);

// Datum aus einem Dateinamen der obigen Form, sonst nullopt.
std::optional<LocalDate> parse_file_name(std::string_view file_name);

// Löscht baumeister-*.log in dir mit (today − Datum) ≥ keep_days Tagen.
// Rückgabe: Anzahl gelöschter Dateien. Fehler beim Löschen einzelner Dateien
// werden übersprungen (die Datei kann gerade von einem Editor offen sein).
std::size_t cleanup_old(const std::filesystem::path& dir, const LocalDate& today, int keep_days);

// Ersetzt die Datumsquelle (nur Tests: Tageswechsel ohne Warten prüfen).
// Leere Funktion stellt die Ortszeit wieder her.
void set_date_source_for_tests(std::function<LocalDate()> source);

}  // namespace swbh::log
