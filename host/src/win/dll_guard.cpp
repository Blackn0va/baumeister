#include "win/dll_guard.hpp"

#include <windows.h>

namespace swbh::win {

bool harden_dll_search() {
    if (!SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_DEFAULT_DIRS)) {
        return false;
    }
    // Leerer String entfernt auch das Arbeitsverzeichnis aus der klassischen Suche.
    return SetDllDirectoryW(L"") != FALSE;
}

bool harden_image_loads() {
    PROCESS_MITIGATION_IMAGE_LOAD_POLICY policy{};
    if (!GetProcessMitigationPolicy(GetCurrentProcess(), ProcessImageLoadPolicy, &policy, sizeof policy)) {
        return false;
    }
    policy.NoRemoteImages = 1;
    policy.NoLowMandatoryLabelImages = 1;
    return SetProcessMitigationPolicy(ProcessImageLoadPolicy, &policy, sizeof policy) != FALSE;
}

std::optional<DllDirectoryCookie> add_dll_directory(const std::filesystem::path& absolute_dir) {
    if (!absolute_dir.is_absolute()) {
        return std::nullopt;
    }
    DLL_DIRECTORY_COOKIE cookie = AddDllDirectory(absolute_dir.c_str());
    if (cookie == nullptr) {
        return std::nullopt;
    }
    return DllDirectoryCookie{cookie};
}

void remove_dll_directory(DllDirectoryCookie cookie) {
    if (cookie.cookie != nullptr) {
        RemoveDllDirectory(static_cast<DLL_DIRECTORY_COOKIE>(cookie.cookie));
    }
}

LoadedModule load_library_absolute(const std::filesystem::path& absolute_dll) {
    if (!absolute_dll.is_absolute()) {
        return LoadedModule{nullptr, ERROR_INVALID_PARAMETER};
    }
    // Keine Loader-Fehlerdialoge („DLL nicht gefunden“) – der Aufrufer meldet selbst.
    DWORD old_mode = 0;
    SetThreadErrorMode(SEM_FAILCRITICALERRORS | SEM_NOOPENFILEERRORBOX, &old_mode);
    HMODULE h = LoadLibraryExW(absolute_dll.c_str(), nullptr,
                               LOAD_LIBRARY_SEARCH_DEFAULT_DIRS | LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR);
    const DWORD err = h == nullptr ? GetLastError() : 0;
    SetThreadErrorMode(old_mode, nullptr);
    return LoadedModule{h, err};
}

void* find_export(void* module, const char* name) {
    if (module == nullptr || name == nullptr) {
        return nullptr;
    }
    return reinterpret_cast<void*>(GetProcAddress(static_cast<HMODULE>(module), name));
}

void free_library(void* module) {
    if (module != nullptr) {
        FreeLibrary(static_cast<HMODULE>(module));
    }
}

}  // namespace swbh::win
