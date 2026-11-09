// Befehlszeile von BaumeisterHost.exe – ohne Qt, testbar.
//
// Vorher lag parse_args() im anonymen Namespace von main_gui.cpp und las GetCommandLineW() selbst. Kein Test konnte
// prüfen, ob der Schalter, den der Autostart in den Run-Wert schreibt (autostart.hpp), beim Start überhaupt erkannt
// wird – ein Tippfehler auf einer der beiden Seiten hätte das Fenster bei jeder Anmeldung aufgehen lassen, ohne dass
// es jemand merkt. Jetzt kommen Schalter und Zerlegung aus EINER Stelle, und der Test geht den Weg Run-Wert →
// CommandLineToArgvW → parse_args ganz.
//
// Schalter: --data-dir <ordner>, --screenshot <png>, --im-hintergrund. Alles andere geht unverändert an Qt
// (z. B. -platform, -style).
//
// Threads: reentrant. Fehler: parse_args wirft nicht; split_command_line und parse_command_line werfen UserError, wenn
// Windows die Befehlszeile nicht zerlegt.

#pragma once

#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace swbh::gui {

// Stiller Start: Fenster bleibt im Infobereich, ein zweiter Start beendet sich ohne Fehlerbox. Steht so im Run-Wert
// (autostart.cpp) – wer ihn hier umbenennt, muss bestehende Run-Werte bedenken: Die alten starteten sonst sichtbar.
inline constexpr std::wstring_view kBackgroundSwitch = L"--im-hintergrund";

struct AppArgs {
    std::optional<std::filesystem::path> data_dir;
    std::optional<std::filesystem::path> screenshot;
    bool background = false;
    // argv[0] und alle unbekannten Argumente (UTF-8) für QApplication.
    std::vector<std::string> qt_args;
};

// argv wie CommandLineToArgvW es liefert (argv[0] = Programm). Ein Schalter ohne Wert am Ende (--data-dir) geht an Qt,
// wie schon vor der Herauslösung.
AppArgs parse_args(const std::vector<std::wstring>& argv);

// Zerlegt eine Befehlszeile nach den Windows-Regeln (CommandLineToArgvW). FALLE: Eine leere Zeichenkette liefert den
// Pfad der laufenden EXE, nicht eine leere Liste.
std::vector<std::wstring> split_command_line(std::wstring_view command_line);

// parse_args(split_command_line(GetCommandLineW())).
AppArgs parse_command_line();

}  // namespace swbh::gui
