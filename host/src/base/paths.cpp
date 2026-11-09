#include "base/paths.hpp"

#include "base/errors.hpp"
#include "base/fileio.hpp"
#include "base/utf8.hpp"

#include <windows.h>
#include <shlobj_core.h>

#include <system_error>

namespace swbh {

std::filesystem::path u8path(std::string_view u8) {
    // Über UTF-16 statt path(std::string): Letzteres läuft unter MSVC durch die
    // ANSI-Codepage und scheitert an „Jörg“ (gemessen, spec 07 §2.4.5).
    return std::filesystem::path(widen(u8));
}

std::string u8str(const std::filesystem::path& p) { return narrow(p.native()); }

std::filesystem::path exe_path() {
    std::wstring buffer(512, L'\0');
    for (;;) {
        const DWORD len = GetModuleFileNameW(nullptr, buffer.data(), static_cast<DWORD>(buffer.size()));
        if (len == 0) {
            throw UserError("Programmpfad nicht ermittelbar: " + system_error_text(GetLastError()));
        }
        if (len < buffer.size()) {
            buffer.resize(len);
            return std::filesystem::path(buffer);
        }
        // Puffer war zu klein (ERROR_INSUFFICIENT_BUFFER): verdoppeln, lange Pfade sind erlaubt.
        buffer.resize(buffer.size() * 2);
    }
}

std::filesystem::path exe_dir() { return exe_path().parent_path(); }

std::filesystem::path local_appdata_dir() {
    PWSTR raw = nullptr;
    const HRESULT hr = SHGetKnownFolderPath(FOLDERID_LocalAppData, KF_FLAG_DEFAULT, nullptr, &raw);
    std::filesystem::path result;
    if (SUCCEEDED(hr) && raw != nullptr) {
        result = std::filesystem::path(raw);
    }
    if (raw != nullptr) {
        CoTaskMemFree(raw);
    }
    if (result.empty()) {
        throw UserError("Ordner %LOCALAPPDATA% nicht ermittelbar: " +
                        system_error_text(static_cast<unsigned long>(hr)));
    }
    return result;
}

std::filesystem::path default_data_dir() { return local_appdata_dir() / L"StreamWizard" / L"Baumeister"; }

namespace {

std::filesystem::path absolute_normal(const std::filesystem::path& p) {
    std::error_code ec;
    std::filesystem::path abs = std::filesystem::absolute(p, ec);
    if (ec) {
        abs = p;
    }
    return abs.lexically_normal();
}

}  // namespace

AppPaths resolve_paths_from(const std::filesystem::path& exe_directory, const std::filesystem::path& default_data,
                            const std::optional<std::filesystem::path>& config,
                            const std::optional<std::filesystem::path>& data_dir) {
    AppPaths paths;
    paths.exe_dir = absolute_normal(exe_directory);
    paths.data_dir = data_dir ? absolute_normal(*data_dir) : absolute_normal(default_data);
    paths.config_file = config ? absolute_normal(*config) : paths.data_dir / widen(kConfigFileName);
    paths.config_dir = paths.config_file.parent_path();
    paths.models_dir = paths.data_dir / L"models";
    paths.logs_dir = paths.data_dir / L"logs";
    paths.components_dir = paths.data_dir / L"components";
    return paths;
}

AppPaths resolve_paths(const std::optional<std::filesystem::path>& config,
                       const std::optional<std::filesystem::path>& data_dir) {
    // Den Profilordner nur abfragen, wenn --data-dir fehlt: Tests und portable
    // Entwicklungsläufe sollen ohne %LOCALAPPDATA% auskommen.
    const std::filesystem::path fallback = data_dir ? std::filesystem::path{} : default_data_dir();
    return resolve_paths_from(exe_dir(), fallback, config, data_dir);
}

void ensure_data_dirs(const AppPaths& paths) {
    try {
        ensure_directory(paths.data_dir);
        ensure_directory(paths.logs_dir);
        ensure_directory(paths.models_dir);
    } catch (const FileError& e) {
        throw UserError(std::string("Datenordner kann nicht angelegt werden: ") + e.what());
    }
}

bool same_path(const std::filesystem::path& a, const std::filesystem::path& b) {
    std::wstring na = absolute_normal(a).native();
    std::wstring nb = absolute_normal(b).native();
    while (na.size() > 3 && (na.back() == L'\\' || na.back() == L'/')) {
        na.pop_back();
    }
    while (nb.size() > 3 && (nb.back() == L'\\' || nb.back() == L'/')) {
        nb.pop_back();
    }
    return CompareStringOrdinal(na.c_str(), -1, nb.c_str(), -1, TRUE) == CSTR_EQUAL;
}

}  // namespace swbh
