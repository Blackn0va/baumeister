// Absturz-Protokoll: eine Logzeile und ein Minidump im Log-Ordner, wenn der Baumeister-Host abstürzt.
//
// Warum: Am 2026-11-01 riss der LLM-Tunnel ab, und die App lief danach nicht mehr – zwischen 11:20 und 11:26 stand keine
// einzige Zeile im Protokoll, es gab keinen Hinweis, ob sie geschlossen wurde oder abgestürzt ist. Vorher hatte die App
// keinen Absturz-Handler; ein Absturz endete ohne Zeile. Jetzt schreibt install_crash_guard für unbehandelte
// SEH-Ausnahmen (SetUnhandledExceptionFilter) und für std::terminate eine Abbilddatei
// baumeister-absturz-JJJJMMTT-hhmmss.dmp und eine Zeile „Absturz: …“ ins Tagesprotokoll.
//
// Grenzen (bewusst): __fastfail (/GS-Pufferüberlauf, std::abort mit _CALL_REPORTFAULT, beschädigter Heap mit
// HeapEnableTerminationOnCorruption) umgeht jeden Filter – dort bleibt nur der Windows-Fehlerbericht. Den gibt es weiter,
// weil der Filter EXCEPTION_CONTINUE_SEARCH zurückgibt: Wer auf EXCEPTION_EXECUTE_HANDLER umstellt, verliert den Eintrag
// „Anwendungsfehler“ (Ereignis 1000) in der Ereignisanzeige.
//
// Das Abbild entsteht in einem eigenen Thread: Bei einem Stapelüberlauf hat der abstürzende Thread keinen Platz mehr, und
// MiniDumpWriteDump soll laut Microsoft nicht aus dem fehlerhaften Thread selbst laufen. Der Thread bekommt 15 s; hängt
// er (etwa am Log-Mutex, den der abgestürzte Thread hielt), endet der Prozess trotzdem. Deshalb erst das Abbild, dann die
// Logzeile. dbghelp.dll wird beim Einrichten mit absolutem Pfad aus System32 geladen, nicht erst im Absturz.
//
// Threads: install_crash_guard einmal beim Start (nach log::open_dir). Filter und terminate-Handler schreiben je Prozess
// höchstens einen Bericht (der erste Absturz zählt, Folgefehler beim Sterben nicht); write_crash_report selbst ist
// reentrant.
// Fehler: keine Ausnahmen; install_crash_guard liefert false, wenn dbghelp.dll fehlt (dann nur noch die Logzeile).

#pragma once

#include <cstddef>
#include <cstdint>
#include <ctime>
#include <filesystem>
#include <optional>
#include <string>
#include <string_view>

struct _EXCEPTION_POINTERS;

namespace swbh::win {

// Wie viele Abbilder im Log-Ordner bleiben (die neuesten). Ein Abbild ohne Modellspeicher hat wenige MB.
inline constexpr std::size_t kKeepCrashDumps = 5;

// baumeister-absturz-JJJJMMTT-hhmmss.dmp aus der lokalen Zeit.
std::wstring crash_dump_name(const std::tm& local);

// „Ausnahme 0xC0000005 (Zugriffsverletzung) bei 0x00007FF6A1B2C3D4“.
std::string exception_cause(unsigned long code, std::uintptr_t address);

// Fertige Logzeile: mit dump_file der Name des Abbilds, sonst dump_error als Grund, warum es keins gibt.
std::string crash_log_line(std::string_view cause, std::optional<std::wstring> dump_file, std::string_view dump_error);

// Löscht alle baumeister-absturz-*.dmp in dir bis auf die keep neuesten (nach Name = nach Zeit). Rückgabe: gelöscht.
std::size_t prune_crash_dumps(const std::filesystem::path& dir, std::size_t keep);

// Richtet Filter und terminate-Handler ein; dump_dir muss absolut sein und existieren (der Log-Ordner).
bool install_crash_guard(const std::filesystem::path& dump_dir);

// Schreibt Abbild und Logzeile sofort (aus einem Hilfsthread). exception darf nullptr sein (std::terminate).
// Rückgabe: Pfad des Abbilds oder nullopt.
std::optional<std::filesystem::path> write_crash_report(const std::filesystem::path& dump_dir,
                                                        _EXCEPTION_POINTERS* exception, std::string_view cause);

}  // namespace swbh::win
