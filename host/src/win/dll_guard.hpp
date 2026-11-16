// Gehärtete DLL-Suche (Architektur §3 Schritt 1, §7.2).
//
// Warum: ggml_backend_load_all() und das Standard-LoadLibrary-Verhalten laden
// DLLs aus dem Arbeitsverzeichnis, aus GGML_BACKEND_PATH und über PATH – auf
// einem Kundenrechner mit CUDA-Toolkit also cuBLAS OHNE NVIDIA-Zustimmung
// (gemessen). Nach harden_dll_search() findet der Loader nur noch den EXE-Ordner,
// System32 und per add_dll_directory freigegebene Ordner. Gemessen: ohne Freigabe
// von cuda/ scheitert ggml-cuda.dll mit Fehler 126 nach 16 ms, die Transkription
// läuft auf der CPU, keine NVIDIA-DLL im Prozess.
//
// Threads: harden_dll_search nur einmal, als erste Anweisung in wmain/wWinMain
// (vor jedem anderen Thread). Die übrigen Funktionen sind threadsicher.
// Fehler: Rückgabewerte, keine Ausnahmen.

#pragma once

#include <filesystem>
#include <optional>

namespace swbh::win {

// SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_DEFAULT_DIRS) + SetDllDirectoryW(L"").
// false = Härtung nicht möglich; der Aufrufer bricht fail-closed mit Exit 1 ab.
bool harden_dll_search();

// Ladesperre für Bibliotheken (ProcessImageLoadPolicy, 2026-11-16): keine DLL von einem Netzlaufwerk
// (NoRemoteImages) und keine mit niedriger Integritätsstufe (NoLowMandatoryLabelImages) – etwa eine, die ein Browser
// im Sandkasten abgelegt hat. Vorher regelte harden_dll_search nur, WO gesucht wird, nicht WAS geladen werden darf.
// Bestehende Bits (Exploit-Schutz von Windows) bleiben gesetzt, die Funktion schaltet nur zu. Keine
// PreferSystem32Images: Die App bringt ihre VC-Laufzeit mit, eine ältere aus System32 ginge sonst vor.
// false = Richtlinie nicht gesetzt (GetLastError); der Aufrufer bricht fail-closed ab.
bool harden_image_loads();

// Kennung eines per AddDllDirectory freigegebenen Ordners.
struct DllDirectoryCookie {
    void* cookie = nullptr;
};

// Gibt einen ABSOLUTEN Ordner für den Loader frei. Nur accel::Runtime ruft das auf, und nur für den Ordner einer
// Komponente nach components::pin_files (jede Datei gegen den Katalog geprüft und gesperrt; bei CUDA außerdem mit
// festgehaltener NVIDIA-Zustimmung) bzw. für bin\ des AMD HIP SDK unter Program Files. nullopt bei Fehler.
std::optional<DllDirectoryCookie> add_dll_directory(const std::filesystem::path& absolute_dir);

// Nimmt eine Freigabe zurück (RemoveDllDirectory). Ungültige Cookies werden ignoriert.
void remove_dll_directory(DllDirectoryCookie cookie);

struct LoadedModule {
    void* handle = nullptr;       // HMODULE, nullptr bei Fehler
    unsigned long win32_error = 0;  // GetLastError bei Fehler (126 = Abhängigkeit fehlt)
};

// LoadLibraryExW mit absolutem Pfad und LOAD_LIBRARY_SEARCH_DEFAULT_DIRS |
// LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR. Relative Pfade werden abgelehnt (Fehler 87),
// damit nie über Suchpfade geladen wird. Unterdrückt Fehlerdialoge des Loaders.
LoadedModule load_library_absolute(const std::filesystem::path& absolute_dll);

// Adresse einer exportierten Funktion (GetProcAddress) oder nullptr.
void* find_export(void* module, const char* name);

// FreeLibrary; nullptr wird ignoriert.
void free_library(void* module);

}  // namespace swbh::win
