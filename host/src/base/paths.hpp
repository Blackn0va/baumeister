// Pfade der Anwendung.
//
// Grundsatz: Pfade werden nur als std::filesystem::path aus UTF-8 (u8string)
// oder UTF-16 gebildet. Gemessen mit MSVC 19.44, /utf-8, ACP 1252: path(std::string)
// scheitert bei „Jörg“ und „Юрий“, path(std::u8string) gelingt (spec 07 §2.4.5).
//
// Layout:
//   {app}  = Ordner der laufenden EXE (nur lesend: EXE, Qt- und VC-DLLs)
//   {data} = %LOCALAPPDATA%\StreamWizard\Baumeister oder --data-dir
//            config.local.json, models/ (GGUF, auf Knopfdruck geladen),
//            components/ (CUDA-Kern nach Zustimmung), logs/
//   Benutzerdaten nie neben die EXE: {app} liegt unter Program Files.
//
// Threads: reentrant (keine globalen Zustände). Fehler: UserError mit deutschem
// Text, wenn Windows den Profilordner nicht liefert.

#pragma once

#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace swbh {

inline constexpr std::string_view kConfigFileName = "config.local.json";

// UTF-8 → path und zurück (verlustfrei für gültiges UTF-8).
std::filesystem::path u8path(std::string_view u8);
std::string u8str(const std::filesystem::path& p);

// Vollständiger Pfad der laufenden EXE bzw. ihr Ordner (GetModuleFileNameW,
// ohne MAX_PATH-Grenze).
std::filesystem::path exe_path();
std::filesystem::path exe_dir();

// %LOCALAPPDATA% über SHGetKnownFolderPath(FOLDERID_LocalAppData).
// Wirft UserError, wenn Windows keinen Ordner liefert.
std::filesystem::path local_appdata_dir();

// {data} ohne --data-dir: %LOCALAPPDATA%\StreamWizard\Baumeister.
std::filesystem::path default_data_dir();

struct AppPaths {
    std::filesystem::path exe_dir;      // {app}
    std::filesystem::path data_dir;     // {data}
    std::filesystem::path config_file;  // geladene bzw. zu schreibende Konfigdatei
    std::filesystem::path config_dir;   // Ordner von config_file
    std::filesystem::path models_dir;   // {data}\models
    std::filesystem::path logs_dir;     // {data}\logs
    std::filesystem::path components_dir;  // {data}\components
};

// Löst alle Pfade auf, ohne etwas anzulegen. config/data_dir entsprechen
// --config bzw. --data-dir; relative Angaben werden gegen das Arbeitsverzeichnis
// absolut gemacht. Ohne --config gilt {data}\config.local.json.
AppPaths resolve_paths(const std::optional<std::filesystem::path>& config,
                       const std::optional<std::filesystem::path>& data_dir);

// Wie resolve_paths, aber mit vorgegebenem EXE- und Standard-Datenordner (Tests).
AppPaths resolve_paths_from(const std::filesystem::path& exe_directory,
                            const std::filesystem::path& default_data,
                            const std::optional<std::filesystem::path>& config,
                            const std::optional<std::filesystem::path>& data_dir);

// Legt {data} und {data}\logs an. Wirft UserError mit Pfad und Windows-Text.
void ensure_data_dirs(const AppPaths& paths);

// Vergleich zweier Pfade wie Windows: normalisiert, ohne Groß-/Kleinschreibung.
bool same_path(const std::filesystem::path& a, const std::filesystem::path& b);

}  // namespace swbh
